package com.corner.util

import com.corner.util.net.Http
import okhttp3.Headers
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * lazy 失败且配置无解析器时，从播放页 HTML 里抠直链。
 * 覆盖 vfed 的 data-play、MacCMS player_aaaa、正文 m3u8/mp4。
 */
object PlayPageSniffer {
    private val log = LoggerFactory.getLogger("PlayPageSniffer")

    private val dataPlay = Regex("""data-play\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val playerConf = Regex("""player_[a-zA-Z0-9_]*\s*=\s*(\{.*?\})\s*<""", RegexOption.DOT_MATCHES_ALL)
    private val urlInBody = Regex(
        """https?://[^\s"'<>]{12,}?\.(?:m3u8|mp4|mkv|flv|mpd)(?:\?[^\s"'<>]*)?""",
        RegexOption.IGNORE_CASE,
    )

    fun sniff(pageUrl: String, headers: Map<String, String> = emptyMap()): String? {
        if (pageUrl.isBlank() || VideoSniffer.isVideoFormat(pageUrl)) return pageUrl.takeIf { VideoSniffer.isVideoFormat(it) }
        if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) return null
        return try {
            val headerBuilder = Headers.Builder()
            headers.forEach { (k, v) ->
                if (k.isBlank() || v.isBlank()) return@forEach
                runCatching { headerBuilder.add(k, v) }
            }
            if (headerBuilder["User-Agent"].isNullOrBlank()) {
                headerBuilder.add("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/90.0.4430.91 Mobile Safari/537.36")
            }
            if (headerBuilder["Referer"].isNullOrBlank()) {
                headerBuilder.add("Referer", pageUrl)
            }
            Http.get(pageUrl, headers = headerBuilder.build()).execute().use { resp ->
                val body = resp.body.string()
                if (body.isBlank()) {
                    log.warn("播放页嗅探响应为空: {}", pageUrl.take(120))
                    return null
                }
                extractDataPlay(body)?.let {
                    log.info("嗅探 data-play 成功: {}", it.take(120))
                    return it
                }
                extractPlayerUrl(body)?.let {
                    log.info("嗅探 player_ 成功: {}", it.take(120))
                    return it
                }
                urlInBody.findAll(body).map { it.value }.firstOrNull { VideoSniffer.isVideoFormat(it) }?.also {
                    log.info("嗅探正文直链成功: {}", it.take(120))
                } ?: run {
                    log.warn(
                        "播放页嗅探未找到直链: url={} bodyLen={} hasDataPlay={}",
                        pageUrl.take(120),
                        body.length,
                        body.contains("data-play", ignoreCase = true),
                    )
                    null
                }
            }
        } catch (e: Exception) {
            log.warn("播放页嗅探失败: {}", pageUrl.take(120), e)
            null
        }
    }

    private fun extractDataPlay(html: String): String? {
        val encoded = dataPlay.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (encoded.isBlank()) return null
        decodePlayPayload(encoded)?.takeIf { VideoSniffer.isVideoFormat(it) }?.let { return it }
        // 少数站点直接把 url 放进 data-play
        return encoded.takeIf { VideoSniffer.isVideoFormat(it) }
    }

    /**
     * vfed: data-play = 可变前缀 + base64(url)。前缀长度不固定，逐字节剥离尝试解码。
     */
    private fun decodePlayPayload(raw: String): String? {
        val maxPrefix = minOf(12, (raw.length - 4).coerceAtLeast(0))
        for (prefixLen in 0..maxPrefix) {
            val part = if (prefixLen == 0) raw else raw.substring(prefixLen)
            val bytes = runCatching {
                Base64.getDecoder().decode(padBase64(part))
            }.getOrNull() ?: continue
            val decoded = runCatching { String(bytes, Charsets.UTF_8) }
                .getOrElse { String(bytes, Charsets.ISO_8859_1) }
            val idx = decoded.indexOf("http")
            if (idx < 0) continue
            val clean = decoded.substring(idx).trim()
                .substringBefore(' ')
                .substringBefore('\n')
                .substringBefore('\r')
            if (VideoSniffer.isVideoFormat(clean)) return clean
        }
        return null
    }

    private fun extractPlayerUrl(html: String): String? {
        val json = playerConf.find(html)?.groupValues?.getOrNull(1) ?: return null
        val url = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1) ?: return null
        var result = url
        val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(json)?.groupValues?.getOrNull(1)
        if (encrypt == "1") {
            result = runCatching { java.net.URLDecoder.decode(result, Charsets.UTF_8) }.getOrDefault(result)
        } else if (encrypt == "2") {
            result = runCatching {
                String(Base64.getDecoder().decode(padBase64(result)), Charsets.UTF_8)
            }.getOrDefault(result)
            result = runCatching { java.net.URLDecoder.decode(result, Charsets.UTF_8) }.getOrDefault(result)
        }
        return result.takeIf { VideoSniffer.isVideoFormat(it) }
    }

    private fun padBase64(s: String): String {
        val pad = (4 - s.length % 4) % 4
        return s + "=".repeat(pad)
    }
}
