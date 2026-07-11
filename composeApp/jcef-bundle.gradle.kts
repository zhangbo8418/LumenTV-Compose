import java.io.File
import java.util.zip.ZipFile

/**
 * 下载并解压 JCEF natives（Chromium 109）到
 *   src/desktopMain/appResources/<platform>/jcef-bundle/
 *
 *   ./gradlew prepareBundledJcef
 *   ./gradlew prepareBundledJcef "-Plumen.jcef.platform=windows-x64"
 */

data class JcefNativeTarget(
    val platform: String,
    val artifact: String,
)

fun detectHostJcefPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch.contains("aarch64") || arch.contains("arm64")
    return when {
        os.contains("win") -> "windows-x64"
        os.contains("mac") -> if (isArm) "macos-arm64" else "macos-x64"
        os.contains("linux") -> if (isArm) "linux-arm64" else "linux-x64"
        else -> error("Cannot detect host platform for bundled JCEF")
    }
}

fun resolveJcefTarget(platformOverride: String?): JcefNativeTarget {
    val platform = platformOverride?.takeIf { it.isNotBlank() } ?: detectHostJcefPlatform()
    val artifact = when (platform) {
        "windows-x64" -> "jcef-natives-windows-amd64"
        "macos-arm64" -> "jcef-natives-macosx-arm64"
        "macos-x64" -> "jcef-natives-macosx-amd64"
        "linux-x64" -> "jcef-natives-linux-amd64"
        "linux-arm64" -> "jcef-natives-linux-arm64"
        else -> error("Unsupported lumen.jcef.platform=$platform")
    }
    return JcefNativeTarget(platform = platform, artifact = artifact)
}

fun extractZipEntryTo(zip: ZipFile, entryName: String, dest: File) {
    dest.parentFile?.mkdirs()
    zip.getInputStream(zip.getEntry(entryName)).use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    }
}

fun extractTarGzWithTar(archive: File, destDir: File) {
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

/** 与 gradle/libs.versions.toml 中 jcef-natives 保持一致 */
val jcefNativesVersion = "jcef-854af1f+cef-109.1.11+g6d4fdb2+chromium-109.0.5414.87"

tasks.register("prepareBundledJcef") {
    group = "distribution"
    description = "Download JCEF Chromium 109 natives into appResources/<platform>/jcef-bundle"

    doLast {
        val platformProp = project.findProperty("lumen.jcef.platform")?.toString()
            ?: project.findProperty("lumen.python.platform")?.toString()
            ?: project.findProperty("lumen.ffmpeg.platform")?.toString()
        val target = resolveJcefTarget(platformProp)

        val conf = configurations.detachedConfiguration(
            dependencies.create("me.friwi:${target.artifact}:$jcefNativesVersion"),
        )
        conf.isTransitive = false
        val jar = conf.resolve().single()
        logger.lifecycle("Using JCEF natives jar: ${jar.name} (${jar.length()} bytes)")

        val cacheDir = layout.buildDirectory.dir("jcef-bundle-cache/${target.platform}").get().asFile.apply {
            mkdirs()
        }
        val tarGz = File(cacheDir, "natives.tar.gz")
        ZipFile(jar).use { zip ->
            val entry = zip.entries().asSequence()
                .firstOrNull { !it.isDirectory && it.name.endsWith(".tar.gz", ignoreCase = true) }
                ?: error("No .tar.gz found inside ${jar.name}")
            logger.lifecycle("Extracting jar entry: ${entry.name}")
            extractZipEntryTo(zip, entry.name, tarGz)
        }

        val destDir = project.file("src/desktopMain/appResources/${target.platform}/jcef-bundle")
        logger.lifecycle("Extracting tar.gz -> ${destDir.absolutePath}")
        extractTarGzWithTar(tarGz, destDir)

        File(destDir, ".lumen-jcef-bundle").writeText(
            buildString {
                appendLine("platform=${target.platform}")
                appendLine("artifact=${target.artifact}")
                appendLine("version=$jcefNativesVersion")
                appendLine("chromium=109.0.5414.87")
            },
        )
        val count = destDir.walkTopDown().count { it.isFile }
        logger.lifecycle("Bundled JCEF ready: ${destDir.absolutePath} ($count files)")
    }
}

listOf(
    "createDistributable",
    "createReleaseDistributable",
    "packageDistributionForCurrentOS",
    "packageReleaseDistributionForCurrentOS",
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn("prepareBundledJcef")
    }
}
