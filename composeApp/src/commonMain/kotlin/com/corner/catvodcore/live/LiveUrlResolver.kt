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

    private fun extractUrlDecorators(url: String, headers: MutableMap<String, String>): String {
        var result = url
        if (result.contains("@Headers=")) {
            val parts = result.split("@Headers=", limit = 2)
            result = parts[0]
            val jsonPart = parts.getOrNull(1)?.split("@")?.firstOrNull().orEmpty()
            runCatching {
                Jsons.parseToJsonElement(jsonPart).jsonObject.forEach { (key, value) ->
                    headers.putIfAbsent(key, value.toString().trim('"'))
                }
            }
        }
        if (result.contains("@Referer=")) {
            val parts = result.split("@Referer=", limit = 2)
            result = parts[0]
            parts.getOrNull(1)?.split("@")?.firstOrNull()?.let { headers.putIfAbsent("Referer", it) }
        }
        if (result.contains("@Cookie=")) {
            val parts = result.split("@Cookie=", limit = 2)
            result = parts[0]
            parts.getOrNull(1)?.split("@")?.firstOrNull()?.let { headers.putIfAbsent("Cookie", it) }
        }
        if (result.contains("@User-Agent=")) {
            val parts = result.split("@User-Agent=", limit = 2)
            result = parts[0]
            parts.getOrNull(1)?.split("@")?.firstOrNull()?.let { headers.putIfAbsent("User-Agent", it) }
        }
        return result.trim()
    }
}
