package com.corner.catvodcore.live

import com.corner.catvodcore.bean.Catchup
import com.corner.catvodcore.bean.Live
import com.corner.catvodcore.bean.LiveChannel
import com.corner.catvodcore.bean.LiveGroup
import com.corner.catvodcore.config.ApiConfig
import com.corner.catvodcore.loader.BaseLoader
import com.corner.util.io.Urls
import com.corner.util.settings.SettingStore
import com.corner.util.settings.SettingType
import com.corner.util.net.Http
import com.corner.util.json.Jsons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import org.slf4j.LoggerFactory

object LiveConfig {
    private val log = LoggerFactory.getLogger("LiveConfig")
    private val _home = MutableStateFlow<Live?>(null)
    val home = _home.asStateFlow()
    private val _lives = MutableStateFlow<List<Live>>(emptyList())
    val lives = _lives.asStateFlow()

    fun syncFromApi() {
        val list = ApiConfig.api.lives.toMutableList()
        val liveUrl = SettingStore.getSettingItem(SettingType.LIVE)
        if (liveUrl.isNotBlank()) {
            list.add(
                Live(
                    name = "自定义直播",
                    type = 0,
                    url = liveUrl
                )
            )
        }
        _lives.value = list
        if (list.isNotEmpty() && _home.value == null) {
            _home.value = list.first()
        }
    }

    fun setHome(live: Live?) {
        _home.value = live
    }

    fun getHome(): Live? = _home.value

    fun isEmpty(): Boolean = _lives.value.isEmpty()

    /**
     * 仅拉取并解析频道列表。EPG 请调用 [loadEpg]，避免阻塞频道展示。
     */
    suspend fun loadChannels(live: Live): Live {
        if (live.groups.isNotEmpty()) return live
        val text = fetchText(live)
        if (text.isBlank()) {
            log.warn("直播源内容为空: {} ({})", live.name, live.url)
            return live
        }
        LiveParser.parse(live, text)
        val channelCount = live.groups.sumOf { it.channels.size }
        log.info("直播源 {} 解析完成: {} 分组 / {} 频道", live.name, live.groups.size, channelCount)
        return live
    }

    suspend fun loadEpg(live: Live) {
        val urls = live.getEpgXml()
        if (urls.isEmpty()) return
        urls.forEach { url ->
            try {
                EpgParser.start(live, url)
            } catch (e: Exception) {
                log.warn("加载 EPG 失败: $url", e)
            }
        }
    }

    private suspend fun fetchText(live: Live): String {
        if (live.api.isNotBlank()) {
            val spider = BaseLoader.getSpider(live.key, live.api, live.ext, live.jar)
            return spider.liveContent(live.url)
        }
        val requestUrl = resolveLiveUrl(live.url)
        val headers = live.getHeaders()
        val okHeaders = if (headers.isEmpty()) null else Headers.Builder().apply {
            headers.forEach { (k, v) -> add(k, v) }
        }.build()
        return Http.get(requestUrl, headers = okHeaders).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                log.warn("拉取直播源失败 HTTP {}: {} ({})", response.code, live.name, requestUrl)
            }
            body
        }
    }

    private fun resolveLiveUrl(url: String): String {
        if (url.isBlank()) return url
        if (url.startsWith("http", ignoreCase = true) || url.startsWith("file", ignoreCase = true)) {
            return url
        }
        val base = ApiConfig.api.url.orEmpty()
        if (base.isNotBlank()) {
            val resolved = Urls.convert(base, url)
            if (resolved.isNotBlank()) return resolved
        }
        return url
    }
}

object LiveParser {
    private val m3uPattern = Regex("^(?!.*#genre#).*#EXTM3U.*", RegexOption.MULTILINE)

    fun parse(live: Live, text: String) {
        if (live.groups.isNotEmpty()) return
        val normalized = text.trim()
        when {
            normalized.startsWith("[") -> parseJson(live, normalized)
            m3uPattern.containsMatchIn(normalized) -> parseM3u(live, normalized)
            else -> parseTxt(live, normalized)
        }
        apply(live)
    }

    private fun parseJson(live: Live, text: String) {
        live.groups.addAll(LiveGroup.parseJson(text))
    }

    private fun parseM3u(live: Live, text: String) {
        // 不要预先创建空的「默认」分组，否则 UI 会选中空分组导致频道列表空白（与 TV 一致）
        var currentGroup: LiveGroup? = null
        var currentChannel: LiveChannel? = null
        var globalCatchup = Catchup()
        val setting = LiveLineSetting()
        text.replace("\r\n", "\n").replace("\r", "").lines().forEach { line ->
            when {
                setting.matches(line) -> setting.apply(line)
                line.startsWith("#EXTM3U") -> {
                    globalCatchup = readCatchup(line, globalCatchup)
                    Regex("""url-tvg="([^"]*)"""").find(line)?.groupValues?.getOrNull(1)?.let {
                        if (live.epg.isBlank()) live.epg = it
                    }
                    Regex("""tvg-url="([^"]*)"""").find(line)?.groupValues?.getOrNull(1)?.let {
                        if (live.epg.isBlank()) live.epg = it
                    }
                    live.catchup = Catchup.decide(readCatchup(line, Catchup()), globalCatchup)
                }
                line.startsWith("#EXTINF:") -> {
                    val group = Regex("""group-title="([^"]*)"""").find(line)?.groupValues?.getOrNull(1) ?: "默认"
                    val name = line.substringAfterLast(",").trim()
                    currentGroup = live.findGroup(group)
                    currentChannel = currentGroup!!.findChannel(name)
                    Regex("""tvg-logo="([^"]*)"""").find(line)?.groupValues?.getOrNull(1)?.let {
                        currentChannel?.logo = it
                    }
                    Regex("""tvg-id="([^"]*)"""").find(line)?.groupValues?.getOrNull(1)?.let {
                        currentChannel?.tvgId = it
                    }
                    Regex("""tvg-name="([^"]*)"""").find(line)?.groupValues?.getOrNull(1)?.let {
                        currentChannel?.tvgName = it
                    }
                    Regex("""tvg-chno="([^"]*)"""").find(line)?.groupValues?.getOrNull(1)?.let {
                        currentChannel?.number = it
                    }
                    // 对齐 TV：EXTINF 内嵌 http-user-agent="..."
                    Regex("""(?i)http-user-agent="([^"]*)"""").find(line)?.groupValues?.getOrNull(1)?.let {
                        if (it.isNotBlank()) currentChannel?.ua = it
                    }
                    val lineCatchup = readCatchup(line, Catchup())
                    currentChannel?.catchup = Catchup.decide(lineCatchup, globalCatchup)
                }
                !line.startsWith("#") && line.contains("://") -> {
                    val parts = line.split("|", limit = 2)
                    val streamUrl = parts[0].trim()
                    val channel = currentChannel ?: run {
                        val group = currentGroup ?: live.findGroup("默认").also { currentGroup = it }
                        group.findChannel("未命名").also { currentChannel = it }
                    }
                    channel.urls.add(streamUrl)
                    if (parts.size > 1) setting.applyPipeHeaders(parts[1])
                    setting.copyTo(channel)
                    setting.clear()
                }
            }
        }
        // 去掉解析过程中可能留下的空分组
        live.groups.removeAll { it.channels.isEmpty() }
        if (!globalCatchup.isEmpty()) live.catchup = globalCatchup
    }

    private fun parseTxt(live: Live, text: String) {
        var currentGroup: LiveGroup? = null
        val setting = LiveLineSetting()
        text.replace("\r\n", "\n").replace("\r", "").lines().forEach { line ->
            // 对齐 TV：setting 行与频道行可同时处理；仅 #genre# 时 clear
            if (setting.matches(line)) {
                setting.apply(line)
            }
            if (line.contains("#genre#")) {
                setting.clear()
                currentGroup = live.findGroup(line.substringBefore(",").trim())
                return@forEach
            }
            val split = line.split(",", limit = 2)
            if (split.size == 2 && split[1].contains("://")) {
                if (currentGroup == null) currentGroup = live.findGroup("默认")
                val channel = currentGroup!!.findChannel(split[0].trim())
                // 对齐 TV：多线 # 分隔；线内 | 后为 header（用 & 分隔 key=value）
                split[1].split("#").forEach { urlPart ->
                    val parts = urlPart.split("|", limit = 2)
                    val url = parts[0].trim()
                    if (url.contains("://")) {
                        if (parts.size > 1) setting.applyPipeHeaders(parts[1])
                        channel.urls.add(url)
                        setting.copyTo(channel)
                    }
                }
                // 注意：TV txt 换台后不 clear setting，分组内后续频道可继承 ua/referer
            }
        }
    }

    private fun readCatchup(line: String, fallback: Catchup): Catchup {
        fun attr(name: String): String {
            return Regex("""$name="([^"]*)"""").find(line)?.groupValues?.getOrNull(1).orEmpty()
        }
        val item = Catchup(
            type = attr("catchup"),
            source = attr("catchup-source"),
            replace = attr("catchup-replace"),
        )
        return Catchup.decide(item, fallback)
    }

    private fun apply(live: Live) {
        var number = 0
        live.groups.forEach { group ->
            group.channels.forEach { channel ->
                if (channel.number.isBlank()) channel.number = (++number).toString()
                channel.applyLive(live)
            }
        }
    }

    private class LiveLineSetting {
        private var ua: String? = null
        private var referer: String? = null
        private var origin: String? = null
        private var parse: Int? = null
        private val header = mutableMapOf<String, String>()

        fun matches(line: String): Boolean {
            return line.startsWith("ua") || line.startsWith("parse") || line.startsWith("click")
                || line.startsWith("referer") || line.startsWith("origin") || line.startsWith("header")
                || line.startsWith("format") || line.startsWith("forceKey")
                || line.startsWith("#EXTHTTP:") || line.startsWith("#EXTVLCOPT:") || line.startsWith("#KODIPROP:")
        }

        fun apply(line: String) {
            when {
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> applyExtVlcOpt(line)
                line.startsWith("#KODIPROP:", ignoreCase = true) &&
                    (line.contains("stream_headers", ignoreCase = true)
                        || line.contains("common_headers", ignoreCase = true)) -> {
                    val idx = line.indexOf("headers=", ignoreCase = true)
                    if (idx >= 0) parseAmpHeaders(line.substring(idx + "headers=".length).trim())
                }
                line.contains("#EXTHTTP:") -> readHeaderJson(line.substringAfter("#EXTHTTP:").trim())
                line.contains("header=") -> readHeaderJson(line.substringAfter("header=").trim())
                line.contains("ua=") -> ua = line.substringAfter("ua=").trim().trim('"')
                lowerContains(line, "user-agent=") ->
                    ua = afterIgnoreCase(line, "user-agent=").trim().trim('"')
                line.startsWith("parse") -> parse = line.substringAfter("parse=").trim().toIntOrNull()
                line.startsWith("referer") ->
                    referer = afterIgnoreCase(line, "referer=").trim().trim('"')
                line.startsWith("origin") ->
                    origin = afterIgnoreCase(line, "origin=").trim().trim('"')
            }
        }

        private fun applyExtVlcOpt(line: String) {
            when {
                lowerContains(line, "user-agent=") ->
                    ua = afterIgnoreCase(line, "user-agent=").trim().trim('"')
                lowerContains(line, "referrer=") ->
                    referer = afterIgnoreCase(line, "referrer=").trim().trim('"')
                lowerContains(line, "origin=") ->
                    origin = afterIgnoreCase(line, "origin=").trim().trim('"')
            }
        }

        fun applyPipeHeaders(pipe: String) {
            // 对齐 TV Setting.headers(String)：
            //   headers=a=1&b=2  |  a=1|b=2  |  a=1&b=2
            val trimmed = pipe.trim()
            when {
                trimmed.contains("headers=", ignoreCase = true) -> {
                    val idx = trimmed.indexOf("headers=", ignoreCase = true)
                    parseAmpHeaders(trimmed.substring(idx + "headers=".length).trim())
                }
                trimmed.contains("|") -> trimmed.split("|").forEach { applyPipeHeaders(it) }
                else -> parseAmpHeaders(trimmed)
            }
        }

        private fun parseAmpHeaders(payload: String) {
            payload.split("&").forEach { param ->
                if (!param.contains("=")) return@forEach
                val key = param.substringBefore("=").trim().trim('"')
                val value = param.substringAfter("=").trim().trim('"')
                if (key.isBlank() || value.isBlank()) return@forEach
                when {
                    key.equals("ua", ignoreCase = true) || key.equals("User-Agent", ignoreCase = true) -> ua = value
                    key.equals("referer", ignoreCase = true) || key.equals("referrer", ignoreCase = true) -> referer = value
                    key.equals("origin", ignoreCase = true) -> origin = value
                    key.equals("parse", ignoreCase = true) -> parse = value.toIntOrNull()
                    else -> header[key] = value
                }
            }
        }

        private fun lowerContains(text: String, token: String): Boolean =
            text.contains(token, ignoreCase = true)

        private fun afterIgnoreCase(text: String, token: String): String {
            val idx = text.indexOf(token, ignoreCase = true)
            return if (idx >= 0) text.substring(idx + token.length) else ""
        }

        fun copyTo(channel: LiveChannel) {
            ua?.let { channel.ua = it }
            referer?.let { channel.referer = it }
            origin?.let { channel.origin = it }
            parse?.let { channel.parse = it }
            if (header.isNotEmpty()) channel.header.putAll(header)
        }

        fun clear() {
            ua = null
            referer = null
            origin = null
            parse = null
            header.clear()
        }

        private fun readHeaderJson(raw: String) {
            runCatching {
                val json = Jsons.parseToJsonElement(raw).jsonObject
                json.entries.forEach { entry ->
                    header[entry.key] = entry.value.toString().trim('"')
                }
            }
        }
    }
}
