package com.corner.util.jcef

import com.corner.ui.scene.SnackBar
import com.corner.util.core.thisLogger
import com.corner.util.io.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JCEF（内嵌 Chromium）管理：对齐 TV 内嵌 WebView，替代原 Playwright 外挂浏览器。
 */
object JcefBrowserManager {
    private val log = thisLogger()
    private val initMutex = Mutex()
    private val installPrompted = AtomicBoolean(false)
    private val _installRequested = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val installRequested: SharedFlow<String> = _installRequested.asSharedFlow()

    @Volatile
    private var cefApp: CefApp? = null
    @Volatile
    private var availableCached: Boolean? = null

    fun getInstallDir(): File = Paths.jcefBundle()

    fun isNativeInstalled(): Boolean {
        val dir = getInstallDir()
        if (!dir.isDirectory) return false
        // jcefmaven 解压后会有 libcef / jcef 等 native；用目录非空近似判断
        return dir.listFiles()?.isNotEmpty() == true
    }

    fun isAvailable(): Boolean {
        availableCached?.let { return it }
        return isNativeInstalled().also { availableCached = it }
    }

    fun requestBrowserInstall(reason: String = "") {
        if (!installPrompted.compareAndSet(false, true)) return
        log.info("请求安装 JCEF: {}", reason.ifBlank { "未说明" })
        _installRequested.tryEmit(reason.ifBlank { "网页解析需要内嵌浏览器" })
        SnackBar.postMsg(
            "网页解析需要内嵌 Chromium（JCEF），请确认下载",
            type = SnackBar.MessageType.WARNING,
        )
    }

    fun resetInstallPrompt() {
        installPrompted.set(false)
        availableCached = null
    }

    /**
     * 确保原生包已下载并完成 CefApp 初始化。
     * @param onProgress 0.0–1.0
     */
    suspend fun ensureReady(onProgress: ((Double) -> Unit)? = null): Result<CefApp> =
        withContext(Dispatchers.IO) {
            initMutex.withLock {
                cefApp?.let {
                    onProgress?.invoke(1.0)
                    return@withContext Result.success(it)
                }
                try {
                    SnackBar.postMsg("正在准备内嵌浏览器（JCEF）…", type = SnackBar.MessageType.INFO)
                    val builder = CefAppBuilder()
                    builder.setInstallDir(getInstallDir())
                    builder.getCefSettings().windowless_rendering_enabled = true
                    builder.addJcefArgs("--disable-gpu")
                    builder.addJcefArgs("--disable-software-rasterizer")
                    builder.addJcefArgs("--ignore-certificate-errors")
                    builder.setAppHandler(object : MavenCefAppHandlerAdapter() {})
                    builder.setProgressHandler { state, percent ->
                        val p = percent.toDouble()
                        if (p in 0.0..100.0) {
                            onProgress?.invoke(p / 100.0)
                        }
                        log.debug("JCEF install: {} {}%", state, percent)
                    }
                    val app = builder.build()
                    cefApp = app
                    availableCached = true
                    onProgress?.invoke(1.0)
                    SnackBar.postMsg("内嵌浏览器已就绪", type = SnackBar.MessageType.SUCCESS)
                    Result.success(app)
                } catch (e: Exception) {
                    log.error("JCEF 初始化失败", e)
                    availableCached = false
                    SnackBar.postMsg("内嵌浏览器初始化失败: ${e.message}", type = SnackBar.MessageType.ERROR)
                    Result.failure(e)
                }
            }
        }

    fun createClient(): CefClient {
        val app = cefApp ?: throw IllegalStateException("JCEF 尚未初始化，请先 ensureReady()")
        return app.createClient()
    }

    fun clearInstall(): Boolean {
        return try {
            dispose()
            val dir = getInstallDir()
            if (dir.exists()) dir.deleteRecursively()
            availableCached = false
            resetInstallPrompt()
            true
        } catch (e: Exception) {
            log.warn("清理 JCEF 失败", e)
            false
        }
    }

    fun dispose() {
        runCatching {
            cefApp?.dispose()
        }
        cefApp = null
        availableCached = null
    }
}
