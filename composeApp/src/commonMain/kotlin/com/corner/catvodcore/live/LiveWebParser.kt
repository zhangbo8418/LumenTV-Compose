package com.corner.catvodcore.live

import com.corner.catvodcore.bean.Parse
import com.corner.catvodcore.config.ApiConfig
import com.corner.util.jcef.JcefBrowserManager
import org.slf4j.LoggerFactory

/**
 * Web 解析入口：优先内嵌浏览器嗅探，失败再 HTTP 轻量嗅探。
 */
object LiveWebParser {
    private val log = LoggerFactory.getLogger("LiveWebParser")

    suspend fun parse(
        webUrl: String,
        headers: Map<String, String>,
        timeoutMs: Long = 15_000,
        parses: List<Parse>? = null,
        click: String = "",
        siteKey: String = "",
    ): String? {
        val items = parses ?: ApiConfig.api.parses.filter { it.type == 0 }
        if (items.isEmpty()) return null

        JcefWebViewSniffer.parse(webUrl, headers, timeoutMs, items, click, siteKey)?.let { return it }

        log.warn("JCEF 未嗅探到地址，尝试 HTTP 轻量嗅探")
        HttpJxSniffer.sniff(webUrl, headers, items)?.let { return it }

        if (!JcefBrowserManager.isAvailable() && !JcefBrowserManager.isNativeInstalled()) {
            JcefBrowserManager.requestBrowserInstall("Web 解析需要内嵌浏览器")
        }
        log.warn("Web 解析失败: {}", webUrl.take(120))
        return null
    }
}
