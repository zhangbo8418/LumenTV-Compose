package com.corner.catvodcore.parse

import com.corner.catvodcore.bean.Parse
import com.corner.catvodcore.bean.Result
import com.corner.catvodcore.bean.Url
import com.corner.catvodcore.bean.add
import com.corner.catvodcore.bean.v
import com.corner.catvodcore.config.ApiConfig
import com.corner.catvodcore.config.ParseConfig
import com.corner.catvodcore.live.LiveJsonParser
import com.corner.catvodcore.live.LiveSuperParser
import com.corner.catvodcore.live.LiveWebParser
import com.corner.catvodcore.loader.BaseLoader
import com.corner.ui.scene.SnackBar
import com.corner.util.VideoSniffer
import com.corner.util.core.needParse
import com.corner.util.core.isUseParse
import com.corner.util.json.Jsons
import org.slf4j.LoggerFactory

object ParseHelper {
    private val log = LoggerFactory.getLogger("ParseHelper")

    suspend fun parseLive(webUrl: String, headers: Map<String, String>, timeoutMs: Long = 15_000): String? {
        if (webUrl.isBlank()) return null
        if (VideoSniffer.isVideoFormat(webUrl)) return webUrl

        LiveJsonParser.parse(webUrl, headers)?.let { return it }
        LiveWebParser.parse(webUrl, headers, timeoutMs)?.let { return it }

        if (ApiConfig.api.parses.any { it.type == 4 }) {
            LiveSuperParser.parse(webUrl, headers, timeoutMs)?.let { return it }
        }
        return null
    }

    suspend fun parseVod(
        result: Result,
        useParse: Boolean = false,
        forcedParse: Parse? = null,
        timeoutMs: Long = 15_000,
    ): Result? {
        if (forcedParse == null && !result.needParse() && !result.isUseParse()) return result

        val webUrl = result.url.v()
        if (webUrl.isBlank()) return null

        // 对齐 TV / parseLive：已是直链就不再解析（新浪等源常带 parse=1 但仍返回 m3u8）
        if (forcedParse == null && VideoSniffer.isVideoFormat(webUrl)) {
            log.info("播放地址已是直链，跳过解析: {}", webUrl.take(120))
            result.parse = 0
            return result
        }

        SnackBar.postMsg("正在解析播放地址...", type = SnackBar.MessageType.INFO, key = "vod_parse")

        val parse = forcedParse ?: resolveParse(result, useParse) ?: run {
            log.warn("未找到可用解析器")
            return null
        }
        val headers = buildHeaders(result, parse)
        val parsedUrl = executeParse(parse, webUrl, result.flag.orEmpty(), headers, timeoutMs)
            ?: return null

        if (parsedUrl.length <= 40) return null

        result.url = Url().add(parsedUrl)
        result.parse = 0
        return result
    }

    private fun resolveParse(result: Result, useParse: Boolean): Parse? {
        val playUrl = result.playUrl.orEmpty()
        when {
            playUrl.startsWith("json:") -> {
                return Parse(name = "inline", type = 1, url = playUrl.removePrefix("json:"))
            }
            playUrl.startsWith("parse:") -> {
                val name = playUrl.removePrefix("parse:")
                return ApiConfig.api.parses.find { it.name == name }
            }
            playUrl.isNotBlank() -> {
                return Parse(name = "inline", type = 0, url = playUrl)
            }
            useParse -> {
                return ParseConfig.getParse().takeUnless { it.isEmpty() }
            }
        }
        return ParseConfig.getParse().takeUnless { it.isEmpty() }
            ?: ApiConfig.api.parses.find { it.type == 4 }
            ?: ApiConfig.api.parses.firstOrNull()
    }

    private suspend fun executeParse(
        parse: Parse,
        webUrl: String,
        flag: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): String? {
        return when (parse.type) {
            0 -> LiveWebParser.parse(webUrl, headers, timeoutMs, listOf(parse))
            1 -> LiveJsonParser.parseWith(parse, webUrl, headers)
            2 -> jsonExtend(parse, webUrl, headers, timeoutMs)
            3 -> jsonExtMix(parse, flag, webUrl, headers, timeoutMs)
            4 -> LiveSuperParser.parse(webUrl, headers, timeoutMs)
            else -> null
        }
    }

    private suspend fun jsonExtend(
        parse: Parse,
        webUrl: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): String? {
        val jxs = linkedMapOf<String, String>()
        filterByFlag(ApiConfig.api.parses.filter { it.type == 1 }, null)
            .forEach { jxs[it.name] = it.extUrl() }
        val json = BaseLoader.jsonExt(parse.url, jxs, webUrl) ?: run {
            SnackBar.postMsg("JAR 扩展解析失败，请检查解析器 Jar 是否包含 Json${parse.url}", type = SnackBar.MessageType.WARNING, key = "vod_parse")
            return null
        }
        return extractFromJsonResult(json, headers, timeoutMs)
    }

    private suspend fun jsonExtMix(
        parse: Parse,
        flag: String,
        webUrl: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): String? {
        val jxs = linkedMapOf<String, HashMap<String, String>>()
        ApiConfig.api.parses.forEach { jxs[it.name] = it.mixMap() }
        val json = BaseLoader.jsonExtMix(flag, parse.url, parse.name, jxs, webUrl) ?: run {
            SnackBar.postMsg("JAR 混合解析失败，请检查解析器 Jar 是否包含 Mix${parse.url}", type = SnackBar.MessageType.WARNING, key = "vod_parse")
            return null
        }
        return extractFromJsonResult(json, headers, timeoutMs)
    }

    private suspend fun extractFromJsonResult(
        json: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): String? {
        return try {
            val parsed = Jsons.decodeFromString<Result>(json)
            if (parsed.header.isNullOrEmpty()) parsed.header = headers
            if (parsed.needParse() && parsed.url.v().isNotBlank()) {
                return parseVod(parsed, useParse = false, timeoutMs = timeoutMs)?.url?.v()
            }
            parsed.url.v().takeIf { it.length > 40 }
        } catch (e: Exception) {
            log.warn("解析 JAR 返回结果失败", e)
            null
        }
    }

    private fun buildHeaders(result: Result, parse: Parse): Map<String, String> {
        val map = linkedMapOf<String, String>()
        result.header?.let { map.putAll(it) }
        parse.ext?.header?.forEach { (key, value) -> map.putIfAbsent(key, value) }
        return map
    }

    private fun filterByFlag(items: List<Parse>, flag: String?): List<Parse> {
        if (flag.isNullOrBlank()) return items
        return items.filter { item ->
            val flags = item.ext?.flag.orEmpty()
            flags.isEmpty() || flags.contains(flag)
        }
    }
}
