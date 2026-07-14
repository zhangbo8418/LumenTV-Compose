package com.corner.catvodcore.loader

import com.corner.util.core.Constants
import com.corner.util.system.OperatingSystem
import com.corner.util.system.SysVerUtil
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM 版 QuickJS（wang.harlon.quickjs:wrapper-java）不含 Android 的 QuickJSLoader；
 * 使用前必须手动 System.load 原生库 `quickjs-java-wrapper`。
 *
 * 原生库放在：`composeApp/src/desktopMain/appResources/<platform>/lib/`
 */
object QuickJsNative {
    private val log = LoggerFactory.getLogger("QuickJsNative")
    private val loaded = AtomicBoolean(false)

    fun ensureLoaded() {
        if (loaded.get()) return
        synchronized(this) {
            if (loaded.get()) return
            val libFile = resolveLibraryFile()
                ?: error(
                    "未找到 QuickJS 原生库（${libraryFileNames().joinToString(" / ")}）。" +
                        "请将 DLL 放到 appResources/${SysVerUtil.getAppResourcesPlatform()}/lib/"
                )
            val loadError = runCatching { System.load(libFile.absolutePath) }
                .exceptionOrNull()
            if (loadError != null) {
                val hint = if (SysVerUtil.currentOs == OperatingSystem.Windows &&
                    loadError.message.orEmpty().contains("dependent libraries", ignoreCase = true)
                ) {
                    "（Win7 常见原因：DLL 未静态链接 MinGW 运行时，需用 scripts/build-quickjs-native.ps1 重新编译并随包分发）"
                } else {
                    ""
                }
                error("加载 QuickJS 原生库失败: ${libFile.absolutePath} — ${loadError.message}$hint")
            }
            loaded.set(true)
            log.info("QuickJS native loaded: {}", libFile.absolutePath)
            verifyRuntime()
        }
    }

    /** Release 若 ProGuard 误删 JNI 回调方法，此处提前失败并给出明确提示 */
    private fun verifyRuntime() {
        runCatching {
            com.whl.quickjs.wrapper.QuickJSContext.create().use { }
        }.onFailure { e ->
            val msg = e.message.orEmpty()
            if (msg.contains("newFunction", ignoreCase = true) ||
                msg.contains("getCreator", ignoreCase = true)
            ) {
                error(
                    "QuickJS 原生库与 Java 层不匹配（常见原因：Release ProGuard 裁剪了 com.whl.quickjs）。" +
                        "请更新 rules.pro 保留 com.whl.quickjs.** 后重新打包。原始错误: $msg"
                )
            }
            throw e
        }
    }

    private fun libraryFileNames(): List<String> = when (SysVerUtil.currentOs) {
        OperatingSystem.Windows -> listOf("quickjs-java-wrapper.dll", "libquickjs-java-wrapper.dll")
        OperatingSystem.MacOS -> listOf("libquickjs-java-wrapper.dylib")
        OperatingSystem.Linux -> listOf("libquickjs-java-wrapper.so")
        OperatingSystem.Unknown -> listOf("libquickjs-java-wrapper.so")
    }

    private fun resolveLibraryFile(): File? {
        val platform = SysVerUtil.getAppResourcesPlatform()
        val candidates = mutableListOf<File>()

        for (name in libraryFileNames()) {
            System.getProperty(Constants.RES_PATH_KEY)?.takeIf { it.isNotBlank() }?.let { res ->
                candidates += File(res, "lib/$name")
                candidates += File(res, name)
            }

            val userDir = System.getProperty("user.dir") ?: "."
            candidates += File(userDir, "src/desktopMain/appResources/$platform/lib/$name")
            candidates += File(userDir, "composeApp/src/desktopMain/appResources/$platform/lib/$name")
            candidates += File(userDir, "appResources/$platform/lib/$name")

            System.getProperty("java.library.path")
                ?.split(File.pathSeparator)
                ?.filter { it.isNotBlank() }
                ?.forEach { dir ->
                    candidates += File(dir, name)
                }
        }

        return candidates.firstOrNull { it.isFile }
    }
}
