import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * 从 VideoLAN nightly 拉取 LibVLC 4 并写入 appResources（对齐 ffmpeg/jcef 流程）。
 *
 *   ./gradlew prepareBundledVlc
 *   ./gradlew prepareBundledVlc -Plumen.vlc.platform=macos-arm64
 *   ./gradlew prepareBundledVlc -Plumen.vlc.buildId=20260715-0413   # 固定某日构建
 *
 * 源：https://artifacts.videolan.org/vlc/nightly-*
 * 说明：nightly 不稳定；本任务在 CI 打包前自动跑，勿把百兆级 VLC 提交进 git。
 */

data class VlcNightlyTarget(
    val platform: String,
    val channel: String,
    /** 在 build 目录页里匹配下载文件名 */
    val artifactRegex: Regex,
    val kind: String, // sdk-tar | win-zip
)

fun detectHostVlcPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch.contains("aarch64") || arch.contains("arm64")
    return when {
        os.contains("win") -> "windows-x64"
        os.contains("mac") -> if (isArm) "macos-arm64" else "macos-x64"
        os.contains("linux") -> if (isArm) "linux-arm64" else "linux-x64"
        else -> error("Cannot detect host platform for bundled VLC")
    }
}

fun resolveVlcTarget(platformOverride: String?): VlcNightlyTarget? {
    val platform = platformOverride?.takeIf { it.isNotBlank() } ?: detectHostVlcPlatform()
    return when (platform) {
        "macos-arm64" -> VlcNightlyTarget(
            platform = platform,
            channel = "nightly-macos-arm64",
            artifactRegex = Regex("""href="(vlc-macos-sdk-[^"]+\.tar\.gz)""""),
            kind = "sdk-tar",
        )
        "macos-x64" -> VlcNightlyTarget(
            platform = platform,
            channel = "nightly-macos-x86_64",
            artifactRegex = Regex("""href="(vlc-macos-sdk-[^"]+\.tar\.gz)""""),
            kind = "sdk-tar",
        )
        "windows-x64" -> VlcNightlyTarget(
            platform = platform,
            channel = "nightly-win64",
            artifactRegex = Regex("""href="(vlc-4[^"]+-win64-[^"]+\.zip)""""),
            kind = "win-zip",
        )
        // Linux nightly 暂无稳定 zip/sdk；打包任务仍会 dependsOn 本任务，故跳过而非失败
        else -> null
    }
}

fun downloadVlcFile(url: String, dest: File) {
    dest.parentFile?.mkdirs()
    URI.create(url).toURL().openStream().use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    }
}

fun readUrlText(url: String): String =
    URI.create(url).toURL().openStream().bufferedReader().use { it.readText() }

fun latestVlcBuildId(channel: String, pinned: String?): String {
    if (!pinned.isNullOrBlank()) return pinned.trim()
    val index = readUrlText("https://artifacts.videolan.org/vlc/$channel/")
    val builds = Regex("""href="(\d{8}-\d{4})/"""")
        .findAll(index)
        .map { it.groupValues[1] }
        .toList()
    return builds.firstOrNull()
        ?: error("No nightly builds listed for $channel")
}

fun resolveVlcArtifact(target: VlcNightlyTarget, buildId: String): Pair<String, String> {
    val buildUrl = "https://artifacts.videolan.org/vlc/${target.channel}/$buildId/"
    val page = readUrlText(buildUrl)
    val name = target.artifactRegex.find(page)?.groupValues?.get(1)
        ?: error("Artifact not found in $buildUrl (regex=${target.artifactRegex})")
    return "$buildUrl$name" to name
}

fun extractZipTo(archive: File, destDir: File) {
    if (destDir.exists()) destDir.deleteRecursively()
    destDir.mkdirs()
    ZipInputStream(archive.inputStream().buffered()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val out = File(destDir, entry.name)
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                out.outputStream().use { zis.copyTo(it) }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
}

fun extractTarGz(archive: File, destDir: File) {
    if (destDir.exists()) destDir.deleteRecursively()
    destDir.mkdirs()
    val pb = ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", destDir.absolutePath)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val out = proc.inputStream.bufferedReader().readText()
    if (proc.waitFor() != 0) {
        error("Failed to extract ${archive.name}: $out")
    }
}

fun copyDirRecursive(from: File, to: File) {
    if (!from.exists()) error("Missing source dir: ${from.absolutePath}")
    if (to.exists()) to.deleteRecursively()
    from.copyRecursively(to, overwrite = true)
}

fun copyFileReplace(src: File, dest: File) {
    if (dest.exists()) {
        Files.deleteIfExists(dest.toPath())
    }
    dest.parentFile?.mkdirs()
    val srcPath = src.toPath()
    if (Files.isSymbolicLink(srcPath)) {
        Files.copy(
            srcPath,
            dest.toPath(),
            LinkOption.NOFOLLOW_LINKS,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } else {
        Files.copy(srcPath, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/** 只清 VLC 相关文件，保留 quickjs 等其它 native */
fun cleanBundledVlcFiles(platformRoot: File, windows: Boolean) {
    val libDir = File(platformRoot, "lib")
    if (libDir.isDirectory) {
        val doomed = libDir.listFiles()?.filter { f ->
            val n = f.name.lowercase()
            n.startsWith("libvlc") ||
                n.startsWith("libvlccore") ||
                n == "axvlc.dll" ||
                n == "npvlc.dll" ||
                n == "hrtfs" ||
                (windows && (n == "plugins" || n == "lua"))
        }.orEmpty()
        doomed.forEach { f ->
            // 先删符号链接本身，避免 copyTo 覆盖到旧 target
            if (Files.isSymbolicLink(f.toPath())) {
                Files.deleteIfExists(f.toPath())
            } else if (!f.deleteRecursively()) {
                error("Failed to remove old VLC file: ${f.absolutePath}")
            }
        }
    }
    if (!windows) {
        File(platformRoot, "plugins").takeIf { it.exists() }?.let { plugins ->
            if (!plugins.deleteRecursively()) {
                error("Failed to remove old plugins: ${plugins.absolutePath}")
            }
        }
    }
}

fun installMacOsSdk(extractRoot: File, platformRoot: File) {
    val libSrc = File(extractRoot, "lib").takeIf { File(it, "libvlc.dylib").exists() || Files.isSymbolicLink(File(it, "libvlc.dylib").toPath()) }
        ?: extractRoot.walkTopDown().maxDepth(4)
            .firstOrNull { it.isDirectory && File(it, "libvlc.dylib").exists() }
        ?: error("lib/ with libvlc.dylib not found in SDK")

    val pluginsSrc = File(libSrc, "vlc/plugins").takeIf { it.isDirectory }
        ?: extractRoot.walkTopDown().maxDepth(6)
            .firstOrNull { it.isDirectory && it.name == "plugins" && File(it, "plugins.dat").isFile }
        ?: error("plugins/ not found in SDK")

    val libDir = File(platformRoot, "lib").apply { mkdirs() }
    libSrc.listFiles()
        ?.filter { it.name.startsWith("libvlc") && (it.extension == "dylib" || Files.isSymbolicLink(it.toPath())) }
        ?.forEach { copyFileReplace(it, File(libDir, it.name)) }

    copyDirRecursive(pluginsSrc, File(platformRoot, "plugins"))
}

fun installWindowsZip(extractRoot: File, platformRoot: File) {
    val root = extractRoot.walkTopDown().maxDepth(3)
        .firstOrNull { it.isFile && it.name.equals("libvlc.dll", true) }
        ?.parentFile
        ?: error("libvlc.dll not found in Windows zip")

    val libDir = File(platformRoot, "lib").apply { mkdirs() }
    listOf("libvlc.dll", "libvlccore.dll").forEach { name ->
        val src = File(root, name)
        if (!src.isFile) error("Missing $name under ${root.absolutePath}")
        copyFileReplace(src, File(libDir, name))
    }
    File(root, "hrtfs").takeIf { it.isDirectory }?.let { copyDirRecursive(it, File(libDir, "hrtfs")) }
    val plugins = File(root, "plugins")
    if (!plugins.isDirectory) error("plugins/ missing under ${root.absolutePath}")
    copyDirRecursive(plugins, File(libDir, "plugins"))
}

tasks.register("prepareBundledVlc") {
    group = "distribution"
    description = "Download LibVLC 4 nightly into appResources/<platform>/{lib,plugins}"

    doLast {
        val platformProp = project.findProperty("lumen.vlc.platform")?.toString()
        val buildPinned = project.findProperty("lumen.vlc.buildId")?.toString()
        val force = project.findProperty("lumen.vlc.force")?.toString()?.toBoolean() == true
        val platform = platformProp?.takeIf { it.isNotBlank() } ?: detectHostVlcPlatform()
        val target = resolveVlcTarget(platform)
        if (target == null) {
            logger.lifecycle(
                "Skip prepareBundledVlc: platform=$platform 暂无 LibVLC 4 nightly 自动捆绑（仅 windows-x64 / macos-arm64 / macos-x64）",
            )
            return@doLast
        }
        val buildId = latestVlcBuildId(target.channel, buildPinned)
        val (url, archiveName) = resolveVlcArtifact(target, buildId)

        val platformRoot = project.file("src/desktopMain/appResources/${target.platform}")
        val marker = File(platformRoot, ".lumen-vlc-bundle")
        if (!force && marker.isFile && marker.readText().contains("buildId=$buildId") && marker.readText().contains("source=$url")) {
            logger.lifecycle("Bundled VLC already up to date: $buildId (${target.platform})")
            return@doLast
        }

        val cacheDir = layout.buildDirectory.dir("vlc-bundle-cache/${target.platform}").get().asFile.apply { mkdirs() }
        val archiveFile = File(cacheDir, archiveName)
        if (!archiveFile.isFile || archiveFile.length() < 1_000_000L) {
            logger.lifecycle("Downloading LibVLC 4 nightly: $url")
            downloadVlcFile(url, archiveFile)
        } else {
            logger.lifecycle("Using cached archive: ${archiveFile.name}")
        }

        val extractRoot = File(cacheDir, "extract-$buildId")
        logger.lifecycle("Extracting ${archiveFile.name}")
        when (target.kind) {
            "sdk-tar" -> extractTarGz(archiveFile, extractRoot)
            "win-zip" -> extractZipTo(archiveFile, extractRoot)
            else -> error("Unknown kind ${target.kind}")
        }

        platformRoot.mkdirs()
        cleanBundledVlcFiles(platformRoot, windows = target.platform.startsWith("windows"))
        when (target.kind) {
            "sdk-tar" -> installMacOsSdk(extractRoot, platformRoot)
            "win-zip" -> installWindowsZip(extractRoot, platformRoot)
        }

        marker.writeText(
            buildString {
                appendLine("platform=${target.platform}")
                appendLine("channel=${target.channel}")
                appendLine("buildId=$buildId")
                appendLine("source=$url")
                appendLine("major=4")
            },
        )
        logger.lifecycle("Bundled LibVLC 4 ready: ${platformRoot.absolutePath} (build $buildId)")
    }
}

listOf(
    "createDistributable",
    "createReleaseDistributable",
    "packageDistributionForCurrentOS",
    "packageReleaseDistributionForCurrentOS",
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn("prepareBundledVlc")
    }
}
