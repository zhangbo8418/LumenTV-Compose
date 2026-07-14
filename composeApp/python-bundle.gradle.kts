import java.io.File
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * 下载并准备随包 Python 到
 * src/desktopMain/appResources/<platform>/python/
 *
 * - Windows：Alex313031/Python-Win7 embed（兼容 Win7 SP1）
 * - macOS / Linux：astral-sh/python-build-standalone
 *
 *   ./gradlew prepareBundledPython
 *   ./gradlew prepareBundledPython "-Plumen.python.platform=windows-x64"
 */

val bundledPythonVersion = "3.12.13"
val bundledPythonTag = "20260623" // python-build-standalone release tag（非 Windows）
// 对齐 TV/chaquo/requirements.txt，另加 certifi 供桌面 SSL
val bundledPythonPackages = listOf(
    "lxml",
    "ujson",
    "pyquery",
    "requests",
    "cachetools",
    "pycryptodome",
    "beautifulsoup4",
    "certifi",
)

/** Windows 用 Win7 兼容发行版；其它平台用 PBS */
data class PythonTarget(
    val platform: String,
    val archiveName: String,
    val downloadUrl: String,
    val kind: String, // "win7-embed" | "pbs-tar"
)

fun pythonAbiTag(version: String): String {
    val parts = version.split(".")
    require(parts.size >= 2) { "Bad python version: $version" }
    return "${parts[0]}${parts[1]}" // 3.12.13 -> 312
}

fun resolvePythonTarget(platformOverride: String?): PythonTarget {
    val platform = platformOverride?.takeIf { it.isNotBlank() } ?: detectHostPythonPlatform()
    return when (platform) {
        "windows-x64" -> {
            val name = "python-$bundledPythonVersion-embed-amd64.zip"
            PythonTarget(
                platform = platform,
                archiveName = name,
                downloadUrl =
                    "https://raw.githubusercontent.com/Alex313031/Python-Win7/master/" +
                        "$bundledPythonVersion/$name",
                kind = "win7-embed",
            )
        }
        "macos-x64" -> pbsTarget(platform, "x86_64-apple-darwin")
        "macos-arm64" -> pbsTarget(platform, "aarch64-apple-darwin")
        "linux-x64" -> pbsTarget(platform, "x86_64-unknown-linux-gnu")
        "linux-arm64" -> pbsTarget(platform, "aarch64-unknown-linux-gnu")
        else -> error("Unsupported lumen.python.platform=$platform")
    }
}

fun pbsTarget(platform: String, triple: String): PythonTarget {
    val name = "cpython-$bundledPythonVersion+$bundledPythonTag-$triple-install_only_stripped.tar.gz"
    return PythonTarget(
        platform = platform,
        archiveName = name,
        downloadUrl =
            "https://github.com/astral-sh/python-build-standalone/releases/download/$bundledPythonTag/$name",
        kind = "pbs-tar",
    )
}

fun detectHostPythonPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch.contains("aarch64") || arch.contains("arm64")
    return when {
        os.contains("win") -> "windows-x64"
        os.contains("mac") -> if (isArm) "macos-arm64" else "macos-x64"
        os.contains("linux") -> if (isArm) "linux-arm64" else "linux-x64"
        else -> error("Cannot detect host platform for bundled Python")
    }
}

fun downloadFile(url: String, dest: File) {
    dest.parentFile?.mkdirs()
    URI.create(url).toURL().openStream().use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
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

fun extractZipFlat(archive: File, destDir: File) {
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

fun findBundledPythonExecutable(pythonRoot: File): File {
    val candidates = listOf(
        File(pythonRoot, "python.exe"),
        File(pythonRoot, "python3.exe"),
        File(pythonRoot, "bin/python3"),
        File(pythonRoot, "bin/python"),
        File(pythonRoot, "python3"),
        File(pythonRoot, "python"),
    )
    return candidates.firstOrNull { it.isFile }
        ?: error("Python executable not found under ${pythonRoot.absolutePath}")
}

/**
 * Embed 发行版默认隔离 site-packages；打开 import site 并加入 Lib/site-packages。
 */
fun enableWinEmbedSitePackages(pythonRoot: File, version: String, logger: org.gradle.api.logging.Logger) {
    val abi = pythonAbiTag(version)
    val pth = File(pythonRoot, "python$abi._pth")
    File(pythonRoot, "Lib/site-packages").mkdirs()
    val content = buildString {
        appendLine("python$abi.zip")
        appendLine(".")
        appendLine("Lib\\site-packages")
        appendLine("import site")
    }
    pth.writeText(content)
    logger.lifecycle("Enabled Win7 embed site-packages via ${pth.name}")
}

fun installPythonPackages(
    pythonExe: File,
    pythonRoot: File,
    platform: String,
    logger: org.gradle.api.logging.Logger,
    forceHostTarget: Boolean = false,
) {
    val env = HashMap(System.getenv())
    env["PYTHONHOME"] = pythonRoot.absolutePath
    env["PYTHONUTF8"] = "1"
    env["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
    val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
    val oldPath = env[pathKey].orEmpty()
    env[pathKey] = listOf(
        pythonRoot.absolutePath,
        File(pythonRoot, "Scripts").absolutePath,
        File(pythonRoot, "bin").absolutePath,
        oldPath,
    ).filter { it.isNotBlank() }.joinToString(File.pathSeparator)

    fun run(envMap: Map<String, String>, vararg args: String) {
        val pb = ProcessBuilder(*args).directory(pythonRoot).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(envMap)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (output.isNotBlank()) logger.lifecycle(output.trimEnd())
        if (code != 0) error("Command failed ($code): ${args.joinToString(" ")}")
    }

    val canExec = !forceHostTarget && try {
        val pb = ProcessBuilder(pythonExe.absolutePath, "--version").redirectErrorStream(true)
        pb.environment().putAll(env)
        pb.start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

    if (canExec) {
        try {
            run(env, pythonExe.absolutePath, "-m", "pip", "--version")
        } catch (_: Exception) {
            run(env, pythonExe.absolutePath, "-m", "ensurepip", "--upgrade")
        }
        run(
            env,
            pythonExe.absolutePath, "-m", "pip", "install", "--upgrade",
            "--no-warn-script-location",
            *bundledPythonPackages.toTypedArray(),
        )
        return
    }

    logger.lifecycle("Installing wheels via host pip --target (platform=$platform)")
    val minor = bundledPythonVersion.substringBeforeLast('.') // 3.12
    val sitePackages = when {
        platform.startsWith("windows") -> File(pythonRoot, "Lib/site-packages")
        else -> File(pythonRoot, "lib/python$minor/site-packages")
    }
    sitePackages.mkdirs()

    val pipPlatform = when (platform) {
        "windows-x64" -> "win_amd64"
        "macos-x64" -> "macosx_10_13_x86_64"
        "macos-arm64" -> "macosx_11_0_arm64"
        "linux-x64" -> "manylinux_2_17_x86_64.manylinux2014_x86_64"
        "linux-arm64" -> "manylinux_2_17_aarch64.manylinux2014_aarch64"
        else -> error("No pip platform tag for $platform")
    }
    val abi = "cp" + minor.replace(".", "")
    val hostPy = listOf("python3", "python").firstOrNull { cmd ->
        try {
            ProcessBuilder(cmd, "--version").start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    } ?: error("Need host python3 to cross-install packages for $platform")

    run(
        HashMap(System.getenv()),
        hostPy, "-m", "pip", "install", "--upgrade",
        "--target", sitePackages.absolutePath,
        "--platform", pipPlatform,
        "--implementation", "cp",
        "--python-version", minor,
        "--abi", abi,
        "--only-binary=:all:",
        "--no-warn-script-location",
        *bundledPythonPackages.toTypedArray(),
    )
}

tasks.register("prepareBundledPython") {
    group = "distribution"
    description = "Download relocatable CPython into appResources/<platform>/python"

    doLast {
        val platformProp = project.findProperty("lumen.python.platform")?.toString()
        val target = resolvePythonTarget(platformProp)
        val cacheDir = layout.buildDirectory.dir("python-bundle-cache").get().asFile.apply { mkdirs() }
        val archiveFile = File(cacheDir, target.archiveName)

        if (!archiveFile.isFile || archiveFile.length() < 1_000_000L) {
            logger.lifecycle("Downloading bundled Python (${target.kind}): ${target.downloadUrl}")
            downloadFile(target.downloadUrl, archiveFile)
        } else {
            logger.lifecycle("Using cached archive: ${archiveFile.name}")
        }

        val dest = project.file("src/desktopMain/appResources/${target.platform}/python")
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile.mkdirs()

        when (target.kind) {
            "win7-embed" -> {
                logger.lifecycle("Extracting Win7 embed Python: ${archiveFile.name}")
                extractZipFlat(archiveFile, dest)
                enableWinEmbedSitePackages(dest, bundledPythonVersion, logger)
            }
            "pbs-tar" -> {
                val extractRoot = File(cacheDir, "extract-${target.platform}")
                logger.lifecycle("Extracting ${archiveFile.name}")
                extractTarGz(archiveFile, extractRoot)
                val extractedPython = File(extractRoot, "python").takeIf { it.isDirectory }
                    ?: extractRoot.listFiles()?.firstOrNull {
                        it.isDirectory && (File(it, "bin").exists() || File(it, "python.exe").exists())
                    }
                    ?: error("Unexpected archive layout in $extractRoot")
                extractedPython.copyRecursively(dest, overwrite = true)
            }
            else -> error("Unknown python bundle kind: ${target.kind}")
        }

        val pythonExe = findBundledPythonExecutable(dest)
        if (!pythonExe.canExecute()) {
            pythonExe.setExecutable(true, false)
        }

        logger.lifecycle("Installing packages: $bundledPythonPackages")
        // Win7 embed 无 pip/ensurepip；统一用 host pip --target 装纯 wheel
        installPythonPackages(
            pythonExe,
            dest,
            target.platform,
            logger,
            forceHostTarget = target.kind == "win7-embed",
        )

        File(dest, ".lumen-python-bundle").writeText(
            buildString {
                appendLine("version=$bundledPythonVersion")
                appendLine("platform=${target.platform}")
                appendLine("kind=${target.kind}")
                if (target.kind == "win7-embed") {
                    appendLine("source=Alex313031/Python-Win7")
                } else {
                    appendLine("tag=$bundledPythonTag")
                    appendLine("source=astral-sh/python-build-standalone")
                }
            },
        )
        logger.lifecycle("Bundled Python ready: ${dest.absolutePath}")
    }
}

listOf(
    "createDistributable",
    "createReleaseDistributable",
    "packageDistributionForCurrentOS",
    "packageReleaseDistributionForCurrentOS",
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn("prepareBundledPython")
    }
}
