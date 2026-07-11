import java.io.File
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * 下载静态 ffmpeg 到 src/desktopMain/appResources/<platform>/ffmpeg/
 *
 *   ./gradlew prepareBundledFfmpeg
 *   ./gradlew prepareBundledFfmpeg -Plumen.ffmpeg.platform=macos-arm64
 */

data class FfmpegTarget(
    val platform: String,
    val url: String,
    val archiveName: String,
    val binaryRelativeHints: List<String>,
)

fun detectHostFfmpegPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch.contains("aarch64") || arch.contains("arm64")
    return when {
        os.contains("win") -> "windows-x64"
        os.contains("mac") -> if (isArm) "macos-arm64" else "macos-x64"
        os.contains("linux") -> if (isArm) "linux-arm64" else "linux-x64"
        else -> error("Cannot detect host platform for bundled ffmpeg")
    }
}

fun resolveFfmpegTarget(platformOverride: String?): FfmpegTarget {
    val platform = platformOverride?.takeIf { it.isNotBlank() } ?: detectHostFfmpegPlatform()
    return when (platform) {
        "macos-arm64" -> FfmpegTarget(
            platform = platform,
            url = "https://www.osxexperts.net/ffmpeg71arm.zip",
            archiveName = "ffmpeg71arm.zip",
            binaryRelativeHints = listOf("ffmpeg", "bin/ffmpeg"),
        )
        "macos-x64" -> FfmpegTarget(
            platform = platform,
            url = "https://github.com/ffbinaries/ffbinaries-prebuilt/releases/download/v6.1/ffmpeg-6.1-macos-64.zip",
            archiveName = "ffmpeg-6.1-macos-64.zip",
            binaryRelativeHints = listOf("ffmpeg", "bin/ffmpeg"),
        )
        "windows-x64" -> FfmpegTarget(
            platform = platform,
            url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip",
            archiveName = "ffmpeg-master-latest-win64-gpl.zip",
            binaryRelativeHints = listOf("ffmpeg.exe", "bin/ffmpeg.exe"),
        )
        "linux-x64" -> FfmpegTarget(
            platform = platform,
            url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz",
            archiveName = "ffmpeg-master-latest-linux64-gpl.tar.xz",
            binaryRelativeHints = listOf("ffmpeg", "bin/ffmpeg"),
        )
        "linux-arm64" -> FfmpegTarget(
            platform = platform,
            url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linuxarm64-gpl.tar.xz",
            archiveName = "ffmpeg-master-latest-linuxarm64-gpl.tar.xz",
            binaryRelativeHints = listOf("ffmpeg", "bin/ffmpeg"),
        )
        else -> error("Unsupported lumen.ffmpeg.platform=$platform")
    }
}

fun downloadFfmpegFile(url: String, dest: File) {
    dest.parentFile?.mkdirs()
    URI.create(url).toURL().openStream().use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    }
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

fun extractTarXz(archive: File, destDir: File) {
    if (destDir.exists()) destDir.deleteRecursively()
    destDir.mkdirs()
    val pb = ProcessBuilder("tar", "-xJf", archive.absolutePath, "-C", destDir.absolutePath)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val out = proc.inputStream.bufferedReader().readText()
    if (proc.waitFor() != 0) {
        error("Failed to extract ${archive.name}: $out")
    }
}

fun findExtractedFfmpeg(root: File, hints: List<String>): File {
    hints.forEach { rel ->
        val direct = File(root, rel)
        if (direct.isFile) return direct
    }
    root.walkTopDown()
        .maxDepth(6)
        .firstOrNull { it.isFile && (it.name == "ffmpeg" || it.name.equals("ffmpeg.exe", true)) }
        ?.let { return it }
    error("ffmpeg binary not found under ${root.absolutePath}")
}

tasks.register("prepareBundledFfmpeg") {
    group = "distribution"
    description = "Download static ffmpeg into appResources/<platform>/ffmpeg"

    doLast {
        val platformProp = project.findProperty("lumen.ffmpeg.platform")?.toString()
            ?: project.findProperty("lumen.python.platform")?.toString()
        val target = resolveFfmpegTarget(platformProp)
        val cacheDir = layout.buildDirectory.dir("ffmpeg-bundle-cache").get().asFile.apply { mkdirs() }
        val archiveFile = File(cacheDir, target.archiveName)

        if (!archiveFile.isFile || archiveFile.length() < 1_000_000L) {
            logger.lifecycle("Downloading bundled ffmpeg: ${target.url}")
            downloadFfmpegFile(target.url, archiveFile)
        } else {
            logger.lifecycle("Using cached archive: ${archiveFile.name}")
        }

        val extractRoot = File(cacheDir, "extract-${target.platform}")
        logger.lifecycle("Extracting ${archiveFile.name}")
        when {
            target.archiveName.endsWith(".zip", ignoreCase = true) -> extractZipTo(archiveFile, extractRoot)
            target.archiveName.endsWith(".tar.xz", ignoreCase = true) -> extractTarXz(archiveFile, extractRoot)
            else -> error("Unsupported archive: ${target.archiveName}")
        }

        val binary = findExtractedFfmpeg(extractRoot, target.binaryRelativeHints)
        val destDir = project.file("src/desktopMain/appResources/${target.platform}/ffmpeg")
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()
        val destName = if (target.platform.startsWith("windows")) "ffmpeg.exe" else "ffmpeg"
        val destFile = File(destDir, destName)
        binary.copyTo(destFile, overwrite = true)
        if (!destFile.canExecute()) {
            destFile.setExecutable(true, false)
        }

        File(destDir, ".lumen-ffmpeg-bundle").writeText(
            "platform=${target.platform}\nsource=${target.url}\n",
        )
        logger.lifecycle("Bundled ffmpeg ready: ${destFile.absolutePath} (${destFile.length()} bytes)")
    }
}

listOf(
    "createDistributable",
    "createReleaseDistributable",
    "packageDistributionForCurrentOS",
    "packageReleaseDistributionForCurrentOS",
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn("prepareBundledFfmpeg")
    }
}
