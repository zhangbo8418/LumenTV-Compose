package com.corner.catvodcore.live

import com.corner.catvodcore.bean.Parse
import com.corner.util.VideoSniffer
import com.corner.util.json.Jsons
import com.corner.util.net.Http
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.slf4j.LoggerFactory

/**
 * JCEF 不可用时的轻量兜底：对 jx+url 做 HTTP 跟随重定向 / JSON / 正文嗅探。
 * 无法替代完整浏览器（v.qq 等强 JS 页仍需 JCEF），但能覆盖不少简单网关。
 */
object HttpJxSniffer {
    private val log = LoggerFactory.getLogger("HttpJxSniffer")
    private val urlInBody = Regex(
        """https?://[^\s"'<>]{12,}?\.(?:m3u8|mp4|mkv|flv|mpd)(?:\?[^\s"'<>]*)?""",
        RegexOption.IGNORE_CASE,
    )

    fun sniff(webUrl: String, headers: Map<String, String>, parses: List<Parse>): String? {
        if (webUrl.isBlank() || parses.isEmpty()) return null
        for (item in parses) {
            if (item.url.isBlank()) continue
            trySniff(item.url + webUrl, mergeHeaders(headers, item))?.let {
                log.info("HTTP 轻量嗅探成功: parse={}", item.name)
                return it
            }
        }
        return null
    }

    private fun mergeHeaders(base: Map<String, String>, parse: Parse): Map<String, String> {
        if (parse.ext?.header.isNullOrEmpty()) return base
        return linkedMapOf<String, String>().apply {
            putAll(base)
            parse.ext?.header?.forEach { (key, value) -> putIfAbsent(key, value) }
        }
    }

    private fun trySniff(requestUrl: String, headers: Map<String, String>): String? {
        if (requestUrl.toHttpUrlOrNull() == null) return null
        return try {
            val headerBuilder = Headers.Builder()
            headers.forEach { (k, v) -> headerBuilder.add(k, v) }
            Http.get(requestUrl, headers = headerBuilder.build()).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                if (VideoSniffer.isVideoFormat(finalUrl)) return finalUrl
                val body = resp.body.string()
                extractFromJson(body)?.takeIf { VideoSniffer.isVideoFormat(it) }?.let { return it }
                urlInBody.findAll(body).map { it.value }.firstOrNull { VideoSniffer.isVideoFormat(it) }
            }
        } catch (e: Exception) {
            log.debug("HTTP 嗅探失败: {}", requestUrl.take(120), e)
            null
        }
    }

    private fun extractFromJson(body: String): String? {
        if (!body.trimStart().startsWith("{")) return null
        return runCatching {
            val json = Jsons.parseToJsonElement(body).jsonObject
            val direct = json["url"]?.jsonPrimitive?.content.orEmpty()
            if (direct.length > 20) return@runCatching direct
            json["data"]?.jsonObject?.get("url")?.jsonPrimitive?.content.orEmpty()
                .takeIf { it.length > 20 }
        }.getOrNull()
    }
}
