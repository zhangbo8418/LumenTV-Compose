package com.corner.catvodcore.live

import com.corner.catvodcore.bean.Parse
import com.corner.catvodcore.config.ApiConfig
import com.corner.util.json.Jsons
import com.corner.util.net.Http
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.slf4j.LoggerFactory

object LiveJsonParser {
    private val log = LoggerFactory.getLogger("LiveJsonParser")

    suspend fun parse(webUrl: String, headers: Map<String, String>): String? {
        if (webUrl.startsWith("json:")) {
            return parseWith(Parse(name = "inline", type = 1, url = webUrl.removePrefix("json:")), webUrl, headers)
        }
        if (webUrl.startsWith("parse:")) return null
        val parses = ApiConfig.api.parses.filter { it.type == 1 }
        for (item in parses) {
            parseWith(item, webUrl, headers)?.let { return it }
        }
        return null
    }

    suspend fun parseWith(item: Parse, webUrl: String, headers: Map<String, String>): String? {
        return try {
            val requestUrl = item.url + webUrl
            if (requestUrl.toHttpUrlOrNull() == null) {
                log.warn("JSON 解析地址非法: {} + {}", item.name, webUrl.take(80))
                return null
            }
            val headerBuilder = Headers.Builder()
            headers.forEach { (key, value) -> headerBuilder.add(key, value) }
            item.ext?.header?.forEach { (key, value) -> headerBuilder.add(key, value) }
            val body = Http.get(requestUrl, headers = headerBuilder.build()).execute().use { it.body.string() }
            extractUrl(body)
        } catch (e: Exception) {
            log.warn("JSON 解析失败: ${item.name}", e)
            null
        }
    }

    private fun extractUrl(body: String): String? {
        val json = Jsons.parseToJsonElement(body).jsonObject
        val direct = json["url"]?.jsonPrimitive?.content.orEmpty()
        if (direct.length > 40) return direct
        val nested = json["data"]?.jsonObject?.get("url")?.jsonPrimitive?.content.orEmpty()
        return nested.takeIf { it.length > 40 }
    }
}
