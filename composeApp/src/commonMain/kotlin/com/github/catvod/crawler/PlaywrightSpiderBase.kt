package com.github.catvod.crawler

import com.corner.util.jcef.JcefBrowserManager
import kotlinx.coroutines.runBlocking

/**
 * 需要内嵌浏览器的爬虫基类。
 */
abstract class JcefSpiderBase : Spider() {

    protected var isBrowserReady: Boolean = false

    override fun init() {
        super.init()
        checkAndInitializeBrowser()
    }

    override fun init(extend: String?) {
        super.init(extend)
        checkAndInitializeBrowser()
    }

    private fun checkAndInitializeBrowser() {
        runBlocking {
            val result = JcefBrowserManager.ensureReady()
            if (result.isFailure) {
                throw IllegalStateException("内嵌浏览器（JCEF）未就绪，请先在设置中下载")
            }
        }
        isBrowserReady = true
    }

    override fun homeContent(filter: Boolean): String = "{}"
    override fun homeVideoContent(): String = "{}"
    override fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>,
    ): String = "{}"
    override fun detailContent(ids: List<String?>?): String = "{}"
    override fun searchContent(key: String?, quick: Boolean): String = "{}"
    override fun searchContent(key: String?, quick: Boolean, pg: String?): String = "{}"
    override fun playerContent(flag: String?, id: String?, vipFlags: List<String?>?): String = "{}"
    override fun manualVideoCheck(): Boolean = false
    override fun isVideoFormat(url: String?): Boolean = false
    override fun proxyLocal(params: Map<String?, String?>?): Array<Any>? = null

    protected fun getBrowserInstallDir(): String = JcefBrowserManager.getInstallDir().absolutePath

    protected fun isBrowserAvailable(): Boolean = JcefBrowserManager.isAvailable()

    protected fun ensureBrowserReady() {
        when (checkBrowserStatus()) {
            BrowserStatus.NOT_INSTALLED ->
                throw IllegalStateException("内嵌浏览器（JCEF）未安装，请先在设置中下载")
            BrowserStatus.NOT_INITIALIZED ->
                throw IllegalStateException("内嵌浏览器未初始化，请稍后重试或重启应用")
            BrowserStatus.READY -> {}
        }
    }

    protected fun checkBrowserStatus(): BrowserStatus = when {
        !JcefBrowserManager.isNativeInstalled() -> BrowserStatus.NOT_INSTALLED
        !isBrowserReady -> BrowserStatus.NOT_INITIALIZED
        else -> BrowserStatus.READY
    }

    enum class BrowserStatus {
        NOT_INSTALLED,
        NOT_INITIALIZED,
        READY,
    }
}

/** @deprecated 使用 [JcefSpiderBase] */
@Deprecated("Renamed to JcefSpiderBase", ReplaceWith("JcefSpiderBase"))
typealias PlaywrightSpiderBase = JcefSpiderBase
