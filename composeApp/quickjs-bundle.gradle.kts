import java.io.File
import org.gradle.process.ExecSpec

/**
 * Build QuickJS JVM native into src/desktopMain/appResources/<platform>/lib/
 *
 *   ./gradlew prepareBundledQuickjs
 *   ./gradlew prepareBundledQuickjs "-Plumen.quickjs.platform=macos-arm64"
 *
 * Windows uses scripts/build-quickjs-native.ps1 (Win7 SP1 MinGW target).
 * Others use scripts/build-quickjs-native.sh.
 */

fun detectHostQuickjsPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch.contains("aarch64") || arch.contains("arm64")
    return when {
        os.contains("win") -> "windows-x64"
        os.contains("mac") -> if (isArm) "macos-arm64" else "macos-x64"
        os.contains("linux") -> if (isArm) "linux-arm64" else "linux-x64"
        else -> error("Cannot detect host platform for QuickJS native")
    }
}

tasks.register("prepareBundledQuickjs") {
    group = "lumen"
    description = "Build quickjs-java-wrapper into appResources/<platform>/lib (Win7-targeted on Windows)"

    doLast {
        val platform = (project.findProperty("lumen.quickjs.platform")?.toString()
            ?.takeIf { it.isNotBlank() }) ?: detectHostQuickjsPlatform()

        val repoRoot = rootProject.projectDir
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val javaHome = System.getenv("JAVA_HOME")
            ?: System.getProperty("java.home")?.let { home ->
                // jre/ subdir often lacks include/; climb if needed
                val f = File(home)
                when {
                    File(f, "include/jni.h").isFile -> f.absolutePath
                    File(f.parentFile, "include/jni.h").isFile -> f.parentFile.absolutePath
                    else -> home
                }
            }
            ?: error("JAVA_HOME is required to build QuickJS native")

        fun ExecSpec.applyQuickjsEnv() {
            environment("JAVA_HOME", javaHome)
            environment("REPO_ROOT", repoRoot.absolutePath)
            environment(
                "QUICKJS_TAG",
                project.findProperty("lumen.quickjs.tag")?.toString() ?: "3.2.3",
            )
        }

        if (platform == "windows-x64") {
            val ps1 = File(repoRoot, "scripts/build-quickjs-native.ps1")
            require(ps1.isFile) { "Missing ${ps1.absolutePath}" }
            project.exec {
                applyQuickjsEnv()
                commandLine(
                    "pwsh",
                    "-NoProfile",
                    "-File",
                    ps1.absolutePath,
                    "-Platform",
                    "windows-x64",
                )
            }
        } else {
            val sh = File(repoRoot, "scripts/build-quickjs-native.sh")
            require(sh.isFile) { "Missing ${sh.absolutePath}" }
            if (!isWindows) {
                project.exec {
                    commandLine("chmod", "+x", sh.absolutePath)
                }
            }
            project.exec {
                applyQuickjsEnv()
                commandLine("bash", sh.absolutePath, platform)
            }
        }

        val libName = when {
            platform.startsWith("windows") -> "quickjs-java-wrapper.dll"
            platform.startsWith("macos") -> "libquickjs-java-wrapper.dylib"
            else -> "libquickjs-java-wrapper.so"
        }
        val out = project.file("src/desktopMain/appResources/$platform/lib/$libName")
        require(out.isFile) { "QuickJS native was not produced: ${out.absolutePath}" }
        logger.lifecycle("QuickJS native ready: ${out.absolutePath} (${out.length()} bytes)")
    }
}
