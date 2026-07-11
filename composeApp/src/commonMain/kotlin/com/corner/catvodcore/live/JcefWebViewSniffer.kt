package com.corner.catvodcore.live

import com.corner.catvodcore.bean.Parse
import com.corner.catvodcore.config.ApiConfig
import com.corner.server.KtorD
import com.corner.util.VideoSniffer
import com.corner.util.jcef.JcefBrowserManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 对齐 TV CustomWebView：内嵌 Chromium 拦请求嗅探媒体地址。
 */
object JcefWebViewSniffer {
    private val log = LoggerFactory.getLogger("JcefWebViewSniffer")
    private val playerPattern = Regex("player.*https?://", RegexOption.IGNORE_CASE)

    suspend fun parse(
        webUrl: String,
        headers: Map<String, String>,
        timeoutMs: Long = 15_000,
        parses: List<Parse>? = null,
    ): String? {
        val items = parses ?: ApiConfig.api.parses.filter { it.type == 0 }
        if (items.isEmpty() || webUrl.isBlank()) return null

        val ready = JcefBrowserManager.ensureReady().getOrElse {
            log.warn("JCEF 不可用: {}", it.message)
            return null
        }
        // ready 仅用于确认初始化；实际 createClient 用 manager
        ready.hashCode()

        return withContext(Dispatchers.IO) {
            for (item in items) {
                if (item.url.isBlank()) continue
                sniffOnce(item.url + webUrl, mergeHeaders(headers, item), timeoutMs, detectNested = true)
                    ?.let { return@withContext it }
            }
            val jxs = items.joinToString(";") { it.url }
            val encodedJxs = URLEncoder.encode(jxs, StandardCharsets.UTF_8)
            val encodedUrl = URLEncoder.encode(webUrl, StandardCharsets.UTF_8)
            val parsePage = "http://127.0.0.1:${KtorD.getPort()}/parse?jxs=$encodedJxs&url=$encodedUrl"
            sniffOnce(parsePage, headers, timeoutMs, detectNested = true)
        }
    }

    private fun mergeHeaders(base: Map<String, String>, parse: Parse): Map<String, String> {
        if (parse.ext?.header.isNullOrEmpty()) return base
        return linkedMapOf<String, String>().apply {
            putAll(base)
            parse.ext?.header?.forEach { (k, v) -> putIfAbsent(k, v) }
        }
    }

    private suspend fun sniffOnce(
        startUrl: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        detectNested: Boolean,
        depth: Int = 0,
    ): String? {
        if (depth > 3) return null
        val found = CompletableDeferred<String?>()
        val stopped = AtomicBoolean(false)
        val seenPlayer = LinkedHashSet<String>()
        var browser: CefBrowser? = null
        var client: org.cef.CefClient? = null

        fun complete(url: String) {
            if (stopped.compareAndSet(false, true) && !found.isCompleted) {
                found.complete(url)
            }
        }

        return try {
            client = JcefBrowserManager.createClient()
            val resourceHandler = object : CefResourceRequestHandlerAdapter() {
                override fun onBeforeResourceLoad(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                ): Boolean {
                    val url = request?.url.orEmpty()
                    if (url.isBlank()) return false
                    val host = runCatching { URI(url).host }.getOrNull().orEmpty()
                    if (host.isNotBlank() && VideoSniffer.isAdHost(host)) {
                        return true // cancel ad
                    }
                    if (detectNested && playerPattern.containsMatchIn(url) && seenPlayer.add(url) && seenPlayer.size <= 5) {
                        // 二次嗅探在外层 await 后处理；这里先记下来
                        if (!found.isCompleted) {
                            found.complete("nested:$url")
                        }
                        return false
                    }
                    if (VideoSniffer.isVideoFormat(url) && !url.equals(startUrl, ignoreCase = true)) {
                        log.info("JCEF 嗅探到媒体: {}", url.take(160))
                        complete(url)
                    }
                    return false
                }
            }
            client.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun getResourceRequestHandler(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    isNavigation: Boolean,
                    isDownload: Boolean,
                    requestInitiator: String?,
                    disableDefaultHandling: BoolRef?,
                ): CefResourceRequestHandler = resourceHandler

                override fun onCertificateError(
                    browser: CefBrowser?,
                    cert_error: org.cef.handler.CefLoadHandler.ErrorCode?,
                    request_url: String?,
                    callback: org.cef.callback.CefCallback?,
                ): Boolean {
                    callback?.Continue()
                    return true
                }
            })
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    // 页面完成：可扩展执行 click 脚本；当前仅依赖网络嗅探
                }
            })

            // OSR / windowless
            browser = client.createBrowser(startUrl, true, false)
            browser.createImmediately()
            // 额外 headers：CEF 对全局 header 支持有限，主要靠 URL 自身
            if (headers.isNotEmpty()) {
                log.debug("JCEF 请求 headers 数量={}", headers.size)
            }

            val result = withTimeoutOrNull(timeoutMs) { found.await() }
            if (result != null && result.startsWith("nested:")) {
                val nested = result.removePrefix("nested:")
                browser?.stopLoad()
                runCatching { browser?.close(true) }
                runCatching { client?.dispose() }
                return sniffOnce(nested, headers, timeoutMs, detectNested = false, depth = depth + 1)
            }
            result
        } catch (e: Exception) {
            log.warn("JCEF 嗅探失败: {}", startUrl.take(120), e)
            null
        } finally {
            stopped.set(true)
            if (!found.isCompleted) found.complete(null)
            runCatching { browser?.stopLoad() }
            runCatching { browser?.close(true) }
            runCatching { client?.dispose() }
        }
    }
}
