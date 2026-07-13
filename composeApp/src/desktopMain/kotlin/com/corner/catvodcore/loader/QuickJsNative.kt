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
                    "未找到 QuickJS 原生库（${libraryFileName()}）。" +
                        "请将 ${libraryFileName()} 放到 appResources/${SysVerUtil.getAppResourcesPlatform()}/lib/"
                )
            System.load(libFile.absolutePath)
            loaded.set(true)
            log.info("QuickJS native loaded: {}", libFile.absolutePath)
        }
    }

    private fun libraryFileName(): String = when (SysVerUtil.currentOs) {
        OperatingSystem.Windows -> "quickjs-java-wrapper.dll"
        OperatingSystem.MacOS -> "libquickjs-java-wrapper.dylib"
        OperatingSystem.Linux -> "libquickjs-java-wrapper.so"
        OperatingSystem.Unknown -> "libquickjs-java-wrapper.so"
    }

    private fun resolveLibraryFile(): File? {
        val name = libraryFileName()
        val platform = SysVerUtil.getAppResourcesPlatform()
        val candidates = mutableListOf<File>()

        System.getProperty(Constants.RES_PATH_KEY)?.takeIf { it.isNotBlank() }?.let { res ->
            candidates += File(res, "lib/$name")
            candidates += File(res, name)
        }

        val userDir = System.getProperty("user.dir") ?: "."
        candidates += File(userDir, "src/desktopMain/appResources/$platform/lib/$name")
        candidates += File(userDir, "composeApp/src/desktopMain/appResources/$platform/lib/$name")
        candidates += File(userDir, "appResources/$platform/lib/$name")

        // java.library.path 兜底
        System.getProperty("java.library.path")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.forEach { dir ->
                candidates += File(dir, name)
            }

        return candidates.firstOrNull { it.isFile }
    }
}
