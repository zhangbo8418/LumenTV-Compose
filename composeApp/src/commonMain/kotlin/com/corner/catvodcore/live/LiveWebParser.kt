package com.corner.catvodcore.live

import com.corner.catvodcore.bean.Parse
import com.corner.catvodcore.config.ApiConfig
import com.corner.server.KtorD
import com.corner.util.VideoSniffer
import com.corner.util.playwright.PlaywrightBrowserManager
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

object LiveWebParser {
    private val log = LoggerFactory.getLogger("LiveWebParser")

    suspend fun parse(
        webUrl: String,
        headers: Map<String, String>,
        timeoutMs: Long = 15_000,
        parses: List<Parse>? = null,
    ): String? {
        if (!PlaywrightBrowserManager.isBrowserAvailable()) {
            log.warn("Playwright 浏览器不可用，跳过 Web 解析")
            return null
        }
        val items = parses ?: ApiConfig.api.parses.filter { it.type == 0 }
        if (items.isEmpty()) return null

        return withContext(Dispatchers.IO) {
            var playwright: Playwright? = null
            var browser: Browser? = null
            try {
                playwright = Playwright.create()
                browser = playwright.chromium().launch(
                    BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setExecutablePath(Paths.get(PlaywrightBrowserManager.getBrowserExecutablePath()))
                )
                for (item in items) {
                    if (item.url.isBlank()) continue
                    sniffUrl(browser, item.url + webUrl, mergeHeaders(headers, item), timeoutMs)?.let { return@withContext it }
                }
                val jxs = items.joinToString(";") { it.url }
                val encodedJxs = URLEncoder.encode(jxs, StandardCharsets.UTF_8)
                val encodedUrl = URLEncoder.encode(webUrl, StandardCharsets.UTF_8)
                val parsePage = "http://127.0.0.1:${KtorD.getPort()}/parse?jxs=$encodedJxs&url=$encodedUrl"
                sniffUrl(browser, parsePage, headers, timeoutMs)
            } catch (e: Exception) {
                log.warn("Web 解析失败: $webUrl", e)
                null
            } finally {
                runCatching { browser?.close() }
                runCatching { playwright?.close() }
            }
        }
    }

    private fun mergeHeaders(base: Map<String, String>, parse: Parse): Map<String, String> {
        if (parse.ext?.header.isNullOrEmpty()) return base
        return linkedMapOf<String, String>().apply {
            putAll(base)
            parse.ext?.header?.forEach { (key, value) -> putIfAbsent(key, value) }
        }
    }

    private suspend fun sniffUrl(
        browser: Browser,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): String? {
        var context: BrowserContext? = null
        var page: Page? = null
        return try {
            context = browser.newContext(
                Browser.NewContextOptions().setExtraHTTPHeaders(headers)
            )
            page = context.newPage()
            val found = CompletableDeferred<String?>()
            page.onRequest { request ->
                val requestUrl = request.url()
                val host = runCatching { URI(requestUrl).host }.getOrNull().orEmpty()
                if (host.isNotBlank() && VideoSniffer.isAdHost(host)) return@onRequest
                if (!found.isCompleted && VideoSniffer.isVideoFormat(requestUrl)) {
                    found.complete(requestUrl)
                }
            }
            page.navigate(url, Page.NavigateOptions().setTimeout(timeoutMs.toDouble()))
            withTimeoutOrNull(timeoutMs) { found.await() }
        } catch (e: Exception) {
            log.debug("嗅探失败: $url", e)
            null
        } finally {
            runCatching { page?.close() }
            runCatching { context?.close() }
        }
    }
}
