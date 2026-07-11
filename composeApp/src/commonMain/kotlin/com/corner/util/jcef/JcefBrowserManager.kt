package com.corner.util.jcef

import com.corner.ui.scene.SnackBar
import com.corner.util.core.Constants
import com.corner.util.core.thisLogger
import com.corner.util.io.Paths
import com.corner.util.system.SysVerUtil
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
 * JCEF（内嵌 Chromium 109）管理。
 * 优先使用发行包内 `appResources/.../jcef-bundle`，复制到可写 userData 后再初始化。
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

    /** Cef 实际安装目录（可写 userData） */
    fun getInstallDir(): File = Paths.jcefBundle()

    fun findBundledDir(): File? =
        bundledCandidates().firstOrNull { isValidJcefDir(it) }

    fun isNativeInstalled(): Boolean =
        isValidJcefDir(getInstallDir()) || findBundledDir() != null

    fun isAvailable(): Boolean {
        availableCached?.let { return it }
        return isNativeInstalled().also { availableCached = it }
    }

    fun requestBrowserInstall(reason: String = "") {
        if (findBundledDir() != null) return
        if (!installPrompted.compareAndSet(false, true)) return
        log.info("请求安装 JCEF: {}", reason.ifBlank { "未说明" })
        _installRequested.tryEmit(reason.ifBlank { "网页解析需要内嵌浏览器" })
        SnackBar.postMsg(
            "网页解析需要内嵌浏览器，请确认下载",
            type = SnackBar.MessageType.WARNING,
        )
    }

    fun resetInstallPrompt() {
        installPrompted.set(false)
        availableCached = null
    }

    /**
     * 确保原生包就绪并完成 CefApp 初始化。
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
                    prepareInstallDirFromBundle(onProgress)
                    SnackBar.postMsg("正在准备内嵌浏览器…", type = SnackBar.MessageType.INFO)
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

    private fun prepareInstallDirFromBundle(onProgress: ((Double) -> Unit)?) {
        val installDir = getInstallDir()
        if (isValidJcefDir(installDir)) {
            onProgress?.invoke(0.3)
            return
        }
        val bundled = findBundledDir() ?: return
        log.info("从随包目录复制 JCEF: {} -> {}", bundled.absolutePath, installDir.absolutePath)
        onProgress?.invoke(0.05)
        if (installDir.exists()) installDir.deleteRecursively()
        installDir.mkdirs()
        bundled.copyRecursively(installDir, overwrite = true)
        onProgress?.invoke(0.35)
    }

    private fun isValidJcefDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val files = dir.listFiles() ?: return false
        // 忽略仅有标记文件的空壳
        return files.any { it.isFile && it.name != ".lumen-jcef-bundle" } ||
            files.any { it.isDirectory }
    }

    private fun bundledCandidates(): List<File> {
        val roots = mutableListOf<File>()
        System.getProperty(Constants.RES_PATH_KEY)?.takeIf { it.isNotBlank() }?.let { res ->
            roots += File(res, "jcef-bundle")
        }
        val platform = SysVerUtil.getAppResourcesPlatform()
        val userDir = System.getProperty("user.dir")
        roots += File(userDir, "src/desktopMain/appResources/$platform/jcef-bundle")
        roots += File(userDir, "composeApp/src/desktopMain/appResources/$platform/jcef-bundle")
        return roots.distinctBy { it.absolutePath }
    }
}
