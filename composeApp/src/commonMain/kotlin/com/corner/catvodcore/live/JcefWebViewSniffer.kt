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
import java.awt.Toolkit
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * 内嵌 Chromium 拦截请求，嗅探媒体地址。
 * 对齐 TV CustomWebView：请求头、click/Sniffer 脚本、嵌套 player 嗅探、Cloudflare 人工验证。
 */
object JcefWebViewSniffer {
    private val log = LoggerFactory.getLogger("JcefWebViewSniffer")
    private val playerPattern = Regex("player.*https?://", RegexOption.IGNORE_CASE)
    private const val CHALLENGE_HINT = "/cdn-cgi/challenge-platform/"

    suspend fun parse(
        webUrl: String,
        headers: Map<String, String>,
        timeoutMs: Long = 15_000,
        parses: List<Parse>? = null,
        click: String = "",
        siteKey: String = "",
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
                val startUrl = item.url + webUrl
                val detectNested = !startUrl.contains("player/?url=")
                sniffOnce(
                    startUrl = startUrl,
                    headers = mergeHeaders(headers, item),
                    timeoutMs = timeoutMs,
                    detectNested = detectNested,
                    click = click,
                    siteKey = siteKey,
                )?.let { return@withContext it }
            }
            val jxs = items.joinToString(";") { it.url }
            val encodedJxs = URLEncoder.encode(jxs, StandardCharsets.UTF_8)
            val encodedUrl = URLEncoder.encode(webUrl, StandardCharsets.UTF_8)
            val parsePage = "http://127.0.0.1:${KtorD.getPort()}/parse?jxs=$encodedJxs&url=$encodedUrl"
            sniffOnce(
                startUrl = parsePage,
                headers = headers,
                timeoutMs = timeoutMs,
                detectNested = true,
                click = click,
                siteKey = siteKey,
            )
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
        click: String = "",
        siteKey: String = "",
        depth: Int = 0,
    ): String? {
        if (depth > 3) return null
        val found = CompletableDeferred<String?>()
        val stopped = AtomicBoolean(false)
        val challenge = AtomicBoolean(false)
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
                    applyHeaders(request, headers)
                    val host = runCatching { URI(url).host }.getOrNull().orEmpty()
                    if (host.isNotBlank() && VideoSniffer.isAdHost(host)) {
                        return true
                    }
                    if (url.contains(CHALLENGE_HINT)) {
                        challenge.set(true)
                        showChallengeFrame(hostFrame)
                    }
                    if (detectNested && playerPattern.containsMatchIn(url) && seenPlayer.add(url) && seenPlayer.size <= 5) {
                        if (!found.isCompleted) {
                            found.complete("nested:$url")
                        }
                        return false
                    }
                    if (isVideoFormat(url, startUrl, detectNested, siteKey) &&
                        !url.equals(startUrl, ignoreCase = true)
                    ) {
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
                    if (frame == null || !frame.isMain) return
                    val url = frame.url
                    if (url.isBlank() || url.equals("about:blank", ignoreCase = true)) return
                    val scripts = buildScripts(url, click)
                    if (scripts.isEmpty()) return
                    SwingUtilities.invokeLater {
                        executeScripts(frame, url, scripts, 0)
                    }
                }
            })

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
                val b = swingClient.createBrowser("about:blank", false, false)
                frame.contentPane.layout = BorderLayout()
                frame.contentPane.add(b.uiComponent, BorderLayout.CENTER)
                frame.isVisible = true
                b.createImmediately()
                created[0] = b
                frameHolder[0] = frame
            }
            browser = created[0]
            hostFrame = frameHolder[0]
            loadWithHeaders(browser, startUrl, headers)

            val effectiveTimeout = if (challenge.get()) timeoutMs + 60_000 else timeoutMs
            val result = withTimeoutOrNull(effectiveTimeout) { found.await() }
            if (result != null && result.startsWith("nested:")) {
                val nested = result.removePrefix("nested:")
                disposeBrowser(browser, client, hostFrame)
                browser = null
                client = null
                hostFrame = null
                return sniffOnce(
                    startUrl = nested,
                    headers = headers,
                    timeoutMs = timeoutMs,
                    detectNested = false,
                    click = click,
                    siteKey = siteKey,
                    depth = depth + 1,
                )
            }
            result
        } catch (e: Throwable) {
            log.warn("JCEF 嗅探失败: {} — {}", startUrl.take(120), e.message)
            null
        } finally {
            stopped.set(true)
            if (!found.isCompleted) found.complete(null)
            disposeBrowser(browser, client, hostFrame)
        }
    }

    private fun applyHeaders(request: CefRequest?, headers: Map<String, String>) {
        if (request == null || headers.isEmpty()) return
        headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                request.setHeaderByName(key, value, true)
            }
        }
    }

    private fun loadWithHeaders(browser: CefBrowser?, url: String, headers: Map<String, String>) {
        if (browser == null) return
        val request = CefRequest.create()
        request.setURL(url)
        request.setMethod("GET")
        applyHeaders(request, headers)
        browser.loadRequest(request)
    }

    private fun buildScripts(url: String, click: String): List<String> {
        val script = VideoSniffer.getScript(url).toMutableList()
        if (click.isNotBlank() && !script.contains(click)) {
            script.add(0, click)
        }
        return script.filter { it.isNotBlank() }
    }

    private fun executeScripts(frame: CefFrame?, url: String, scripts: List<String>, index: Int) {
        if (frame == null || index >= scripts.size) return
        val js = scripts[index]
        if (js.isBlank()) {
            executeScripts(frame, url, scripts, index + 1)
            return
        }
        frame.executeJavaScript(js, url, 0)
        SwingUtilities.invokeLater {
            executeScripts(frame, url, scripts, index + 1)
        }
    }

    private fun showChallengeFrame(frame: JFrame?) {
        if (frame == null) return
        SwingUtilities.invokeLater {
            if (frame.location.x < 0) {
                val screen = Toolkit.getDefaultToolkit().screenSize
                frame.setLocation(
                    (screen.width - frame.width) / 2,
                    (screen.height - frame.height) / 2,
                )
                frame.title = "请完成网页验证"
                frame.isAlwaysOnTop = true
                log.info("检测到 Cloudflare 验证，已显示内嵌浏览器窗口")
            }
        }
    }

    private fun isVideoFormat(url: String, startUrl: String, detect: Boolean, siteKey: String): Boolean {
        if (!detect && url.equals(startUrl, ignoreCase = true)) return false
        if (siteKey.isNotBlank()) {
            runCatching {
                val site = ApiConfig.getSite(siteKey) ?: return@runCatching
                val spider = ApiConfig.getSpider(site)
                if (spider.manualVideoCheck()) return spider.isVideoFormat(url)
            }
        }
        return VideoSniffer.isVideoFormat(url)
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
