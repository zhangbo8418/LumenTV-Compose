package com.corner.catvodcore.live

import com.corner.catvodcore.bean.LiveChannel
import com.corner.player.PlaySource
import com.corner.server.KtorD
import com.corner.util.json.Jsons
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ResolvedLiveUrl(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

object LiveUrlResolver {
    suspend fun resolve(channel: LiveChannel): ResolvedLiveUrl {
        val headers = channel.buildHeaders().toMutableMap()
        val raw = channel.currentUrl()
        if (raw.isBlank()) return ResolvedLiveUrl("")

        var url = PlaySource.extractUrl(raw)

        url = extractUrlDecorators(url, headers)

        if (channel.parse == 1 || raw.startsWith("video://", ignoreCase = true) || url.startsWith("json:") || url.startsWith("parse:")) {
            LiveParseHelper.parse(url, headers)?.let { parsed ->
                url = parsed
            }
        }

        if (channel.parse == 1 && !channel.proxyKey.isNullOrBlank()) {
            val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8)
            url = "http://127.0.0.1:${KtorD.getPort()}/proxy?do=${channel.proxyKey}&url=$encoded"
        }

        return ResolvedLiveUrl(url.trim(), headers)
    }

    suspend fun resolveUrl(url: String, channel: LiveChannel): ResolvedLiveUrl {
        val headers = channel.buildHeaders().toMutableMap()
        var resolved = url
        resolved = PlaySource.extractUrl(resolved)
        resolved = extractUrlDecorators(resolved, headers)
        if (channel.parse == 1 || resolved.startsWith("json:") || resolved.startsWith("parse:")) {
            LiveParseHelper.parse(resolved, headers)?.let { parsed -> resolved = parsed }
        }
        return ResolvedLiveUrl(resolved.trim(), headers)
    }

    /**
     * 从播放地址尾部剥离常见头注解：
     * - TV/社区 TXT：`url|Referer=x&User-Agent=y`（管道在解析阶段处理）
     * - 内嵌：`url$referer=x$user-agent=y` 或 `url@Referer=x@User-Agent=y`
     */
    private fun extractUrlDecorators(url: String, headers: MutableMap<String, String>): String {
        var result = url
        for (sep in listOf("$", "@")) {
            while (true) {
                val markers = listOf(
                    "Headers=", "Referer=", "referer=", "Cookie=",
                    "User-Agent=", "user-agent=", "ua=", "Origin=", "origin=",
                )
                val hit = markers.mapNotNull { name ->
                    val mark = "$sep$name"
                    val idx = result.indexOf(mark, ignoreCase = true)
                    if (idx >= 0) idx to (mark to name) else null
                }.minByOrNull { it.first } ?: break

                val (idx, markAndName) = hit
                val (mark, name) = markAndName
                val before = result.substring(0, idx)
                val rest = result.substring(idx + mark.length)
                val cut = rest.indexOf(sep).let { if (it < 0) rest.length else it }
                val value = rest.substring(0, cut).trim().trim('"')
                val after = if (cut < rest.length) rest.substring(cut) else ""
                result = before + after

                if (value.isBlank()) continue
                when {
                    name.startsWith("Headers", ignoreCase = true) -> {
                        runCatching {
                            Jsons.parseToJsonElement(value).jsonObject.forEach { (k, v) ->
                                headers.putIfAbsent(k, v.toString().trim('"'))
                            }
                        }
                    }
                    name.equals("Referer=", ignoreCase = true) || name.equals("referer=", ignoreCase = true) ->
                        headers.putIfAbsent("Referer", value)
                    name.equals("Cookie=", ignoreCase = true) ->
                        headers.putIfAbsent("Cookie", value)
                    name.equals("User-Agent=", ignoreCase = true) || name.equals("user-agent=", ignoreCase = true)
                        || name.equals("ua=", ignoreCase = true) ->
                        headers.putIfAbsent("User-Agent", value)
                    name.equals("Origin=", ignoreCase = true) || name.equals("origin=", ignoreCase = true) ->
                        headers.putIfAbsent("Origin", value)
                }
            }
        }
        return result.trim()
    }
}
