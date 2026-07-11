import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 下载并准备随包 Python（python-build-standalone）到
 * src/desktopMain/appResources/<platform>/python/
 *
 *   ./gradlew prepareBundledPython
 *   ./gradlew prepareBundledPython -Plumen.python.platform=macos-x64
 */

val bundledPythonVersion = "3.12.13"
val bundledPythonTag = "20260623"
val bundledPythonPackages = listOf("requests", "lxml", "pycryptodome", "certifi")

data class PythonTarget(val platform: String, val archiveName: String)

fun resolvePythonTarget(platformOverride: String?): PythonTarget {
    val platform = platformOverride?.takeIf { it.isNotBlank() } ?: detectHostPythonPlatform()
    val triple = when (platform) {
        "windows-x64" -> "x86_64-pc-windows-msvc"
        "macos-x64" -> "x86_64-apple-darwin"
        "macos-arm64" -> "aarch64-apple-darwin"
        "linux-x64" -> "x86_64-unknown-linux-gnu"
        "linux-arm64" -> "aarch64-unknown-linux-gnu"
        else -> error("Unsupported lumen.python.platform=$platform")
    }
    return PythonTarget(
        platform,
        "cpython-$bundledPythonVersion+$bundledPythonTag-$triple-install_only_stripped.tar.gz",
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

fun copyWin10CompatDlls(pythonRoot: File, logger: org.gradle.api.logging.Logger) {
    val javaBin = File(System.getProperty("java.home"), "bin")
    if (!javaBin.isDirectory) {
        logger.warn("java.home/bin missing, skip Win10 compat DLL copy")
        return
    }
    val names = listOf(
        "ucrtbase.dll",
        "vcruntime140.dll",
        "vcruntime140_1.dll",
        "msvcp140.dll",
        "msvcp140_1.dll",
        "msvcp140_2.dll",
        "concrt140.dll",
    )
    var copied = 0
    names.forEach { name ->
        val src = File(javaBin, name)
        if (src.isFile) {
            Files.copy(src.toPath(), File(pythonRoot, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            copied++
        }
    }
    javaBin.listFiles { f -> f.isFile && f.name.startsWith("api-ms-win-") && f.name.endsWith(".dll") }
        ?.forEach { src ->
            Files.copy(src.toPath(), File(pythonRoot, src.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            copied++
        }
    logger.lifecycle("Copied $copied Win10 compat DLL(s) from JDK into bundled Python")
}

fun installPythonPackages(
    pythonExe: File,
    pythonRoot: File,
    platform: String,
    logger: org.gradle.api.logging.Logger,
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

    // 优先用捆绑解释器本机安装；跨架构（如在 x64 上准备 arm64）则回退 host pip --target
    val canExec = try {
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

    logger.lifecycle("Bundled python cannot execute on this host; installing wheels via host pip --target")
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
        val url =
            "https://github.com/astral-sh/python-build-standalone/releases/download/$bundledPythonTag/${target.archiveName}"

        if (!archiveFile.isFile || archiveFile.length() < 1_000_000L) {
            logger.lifecycle("Downloading bundled Python: $url")
            downloadFile(url, archiveFile)
        } else {
            logger.lifecycle("Using cached archive: ${archiveFile.name}")
        }

        val extractRoot = File(cacheDir, "extract-${target.platform}")
        logger.lifecycle("Extracting ${archiveFile.name}")
        extractTarGz(archiveFile, extractRoot)

        val extractedPython = File(extractRoot, "python").takeIf { it.isDirectory }
            ?: extractRoot.listFiles()?.firstOrNull {
                it.isDirectory && (File(it, "bin").exists() || File(it, "python.exe").exists())
            }
            ?: error("Unexpected archive layout in $extractRoot")

        val dest = project.file("src/desktopMain/appResources/${target.platform}/python")
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile.mkdirs()
        extractedPython.copyRecursively(dest, overwrite = true)

        val pythonExe = findBundledPythonExecutable(dest)
        if (!pythonExe.canExecute()) {
            pythonExe.setExecutable(true, false)
        }

        if (target.platform.startsWith("windows")) {
            copyWin10CompatDlls(dest, logger)
        }

        logger.lifecycle("Installing packages: $bundledPythonPackages")
        installPythonPackages(pythonExe, dest, target.platform, logger)

        File(dest, ".lumen-python-bundle").writeText(
            "version=$bundledPythonVersion\ntag=$bundledPythonTag\nplatform=${target.platform}\n",
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
