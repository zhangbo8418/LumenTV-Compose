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
import java.awt.BorderLayout
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * 内嵌 Chromium 拦截请求，嗅探媒体地址。
 * 使用隐藏窗口模式（非 OSR），避免依赖 JOGL/gluegen_rt.dll。
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

        JcefBrowserManager.ensureReady().getOrElse {
            log.warn("JCEF 不可用: {}", it.message)
            return null
        }

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
        var hostFrame: JFrame? = null

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
                        return true
                    }
                    if (detectNested && playerPattern.containsMatchIn(url) && seenPlayer.add(url) && seenPlayer.size <= 5) {
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
                    // 依赖网络嗅探
                }
            })

            // 隐藏窗口模式，避免 OSR → JOGL → gluegen_rt.dll
            val created = arrayOfNulls<CefBrowser>(1)
            val frameHolder = arrayOfNulls<JFrame>(1)
            val swingClient = client
            SwingUtilities.invokeAndWait {
                val frame = JFrame("jcef-sniff").apply {
                    isUndecorated = true
                    setSize(960, 540)
                    setLocation(-20000, -20000)
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                }
                val b = swingClient.createBrowser(startUrl, false, false)
                frame.contentPane.layout = BorderLayout()
                frame.contentPane.add(b.uiComponent, BorderLayout.CENTER)
                frame.isVisible = true
                b.createImmediately()
                created[0] = b
                frameHolder[0] = frame
            }
            browser = created[0]
            hostFrame = frameHolder[0]
            if (headers.isNotEmpty()) {
                log.debug("JCEF 请求 headers 数量={}", headers.size)
            }

            val result = withTimeoutOrNull(timeoutMs) { found.await() }
            if (result != null && result.startsWith("nested:")) {
                val nested = result.removePrefix("nested:")
                disposeBrowser(browser, client, hostFrame)
                browser = null
                client = null
                hostFrame = null
                return sniffOnce(nested, headers, timeoutMs, detectNested = false, depth = depth + 1)
            }
            result
        } catch (e: Throwable) {
            // UnsatisfiedLinkError 等属于 Error，必须用 Throwable 才能落到 HTTP 兜底
            log.warn("JCEF 嗅探失败: {} — {}", startUrl.take(120), e.message)
            null
        } finally {
            stopped.set(true)
            if (!found.isCompleted) found.complete(null)
            disposeBrowser(browser, client, hostFrame)
        }
    }

    private fun disposeBrowser(browser: CefBrowser?, client: org.cef.CefClient?, hostFrame: JFrame?) {
        runCatching { browser?.stopLoad() }
        runCatching { browser?.close(true) }
        runCatching { client?.dispose() }
        runCatching {
            if (hostFrame != null) {
                SwingUtilities.invokeLater {
                    hostFrame.isVisible = false
                    hostFrame.dispose()
                }
            }
        }
    }
}
