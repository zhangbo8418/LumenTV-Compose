package com.corner.catvodcore.bean

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

@Serializable
data class Live(
    val name: String = "",
    val type: Int = 0,
    val url: String = "",
    val playerType: Int = 0,
    var epg: String = "",
    val logo: String? = null,
    val api: String = "",
    val ext: String = "",
    val jar: String = "",
    val ua: String = "",
    val referer: String = "",
    val origin: String = "",
    val timeZone: String = "",
    val header: Map<String, String> = emptyMap(),
    var catchup: Catchup = Catchup(),
    @Transient
    val key: String = UtilsCompat.liveKey(name),
    @Transient
    val groups: MutableList<LiveGroup> = mutableListOf(),
) {
    fun findGroup(name: String): LiveGroup {
        val existing = groups.find { it.name == name }
        if (existing != null) return existing
        val group = LiveGroup(name)
        groups.add(group)
        return group
    }

    fun isEmpty(): Boolean = name.isBlank()

    fun getEpgXml(): List<String> {
        return epg.split(",").filter { it.isNotBlank() && !it.contains("{") && (it.contains("xml") || it.contains("gz")) }
    }

    fun getHeaders(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        map.putAll(header)
        if (ua.isNotBlank()) map["User-Agent"] = ua
        if (referer.isNotBlank()) map["Referer"] = referer
        if (origin.isNotBlank()) map["Origin"] = origin
        return map
    }

    fun getZoneId(): java.time.ZoneId {
        return try {
            if (timeZone.isBlank()) java.time.ZoneId.systemDefault() else java.time.ZoneId.of(timeZone)
        } catch (_: Exception) {
            java.time.ZoneId.systemDefault()
        }
    }

    /** 与 TV 一致：仅按名称区分，避免 MutableSet 因 groups 变化导致哈希损坏 */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Live) return false
        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()
}

@Serializable
data class LiveGroup(
    val name: String = "",
    @Transient
    val isKeep: Boolean = false,
    @Transient
    val channels: MutableList<LiveChannel> = mutableListOf(),
) {
    fun findChannel(name: String): LiveChannel {
        val existing = channels.find { it.name == name }
        if (existing != null) return existing
        val channel = LiveChannel(name)
        channels.add(channel)
        return channel
    }

    companion object {
        fun parseJson(text: String): List<LiveGroup> {
            return try {
                Json { ignoreUnknownKeys = true }.decodeFromString<List<LiveGroupDto>>(text)
                    .map { dto ->
                        LiveGroup(dto.name).apply {
                            channels.addAll(dto.channel.map {
                                LiveChannel(
                                    name = it.name,
                                    logo = it.logo ?: "",
                                    urls = mutableListOf<String>().apply { addAll(it.streamUrls()) }
                                )
                            })
                        }
                    }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

@Serializable
private data class LiveGroupDto(
    val name: String = "",
    val channel: List<LiveChannelDto> = emptyList(),
)

@Serializable
private data class LiveChannelDto(
    val name: String = "",
    val logo: String? = null,
    val url: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
) {
    fun streamUrls(): List<String> = if (url.isNotEmpty()) url else urls
}

data class LiveChannel(
    val name: String = "",
    var logo: String = "",
    var number: String = "",
    var tvgId: String = "",
    var tvgName: String = "",
    var epg: String = "",
    var parse: Int = 0,
    var ua: String = "",
    var referer: String = "",
    var origin: String = "",
    val urls: MutableList<String> = mutableListOf(),
    @Transient
    var urlIndex: Int = 0,
    @Transient
    var proxyKey: String? = null,
    @Transient
    var header: MutableMap<String, String> = mutableMapOf(),
    @Transient
    var catchup: Catchup? = null,
    @Transient
    var live: Live? = null,
    @Transient
    var epgList: MutableList<Epg> = mutableListOf(),
) {
    fun currentUrl(): String {
        if (urls.isEmpty()) return ""
        val raw = urls[urlIndex.coerceIn(0, urls.lastIndex)]
        return raw.split("$").first().trim()
    }

    fun hasMultipleLines(): Boolean = urls.size > 1

    fun isLastLine(): Boolean = urls.isEmpty() || urlIndex >= urls.lastIndex

    fun lineLabel(): String {
        if (!hasMultipleLines()) return ""
        val parts = urls[urlIndex.coerceIn(0, urls.lastIndex)].split("$")
        return if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else "线路 ${urlIndex + 1}"
    }

    fun switchLine(next: Boolean) {
        if (urls.isEmpty()) return
        val step = if (next) 1 else -1
        urlIndex = (urlIndex + step + urls.size) % urls.size
    }

    fun applyLive(live: Live) {
        this.live = live
        if (ua.isBlank() && live.ua.isNotBlank()) ua = live.ua
        if (referer.isBlank() && live.referer.isNotBlank()) referer = live.referer
        if (origin.isBlank() && live.origin.isNotBlank()) origin = live.origin
        if (epg.isBlank() && live.epg.contains("{")) epg = live.epg
        if (header.isEmpty() && live.header.isNotEmpty()) header.putAll(live.header)
        if (catchup == null && !live.catchup.isEmpty()) catchup = live.catchup
        val logoTemplate = live.logo.orEmpty()
        if (logoTemplate.contains("{") && !logo.startsWith("http")) {
            val nameToken = tvgName.ifBlank { name }
            val idToken = tvgId.ifBlank { nameToken }
            logo = logoTemplate
                .replace("{id}", idToken)
                .replace("{name}", nameToken)
                .replace("{logo}", logo)
        }
    }

    fun resolvedLogo(): String {
        val raw = when {
            logo.startsWith("http") -> logo
            else -> {
                val template = live?.logo.orEmpty()
                if (!template.contains("{")) return logo
                val nameToken = tvgName.ifBlank { name }
                val idToken = tvgId.ifBlank { nameToken }
                template
                    .replace("{id}", idToken)
                    .replace("{name}", nameToken)
                    .replace("{logo}", logo)
            }
        }
        return encodeLogoUrl(raw)
    }

    private fun encodeLogoUrl(url: String): String {
        if (!url.startsWith("http")) return url
        return try {
            val parsed = java.net.URL(url)
            val encodedPath = parsed.path.split("/").joinToString("/") { segment ->
                if (segment.isEmpty() || segment.all { it.code < 128 && it != '+' }) segment
                else encodeLogoComponent(segment)
            }
            val encodedQuery = parsed.query?.split("&")?.joinToString("&") { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) part
                else {
                    val key = part.substring(0, idx)
                    val value = part.substring(idx + 1)
                    // 保留字面量 '+'（如 CCTV5+），URLDecoder 会把 '+' 当成空格
                    "$key=${encodeLogoComponent(value)}"
                }
            }
            buildString {
                append(parsed.protocol).append("://").append(parsed.authority).append(encodedPath)
                if (!encodedQuery.isNullOrEmpty()) append('?').append(encodedQuery)
                if (!parsed.ref.isNullOrEmpty()) append('#').append(parsed.ref)
            }
        } catch (_: Exception) {
            url
        }
    }

    /** 解码 %XX，但保留字面量 '+'，避免 CCTV5+ 被解成 CCTV5 。 */
    private fun decodeLogoComponent(value: String): String {
        return runCatching {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
        }.getOrDefault(value)
    }

    private fun encodeLogoComponent(value: String): String {
        val decoded = decodeLogoComponent(value)
        return java.net.URLEncoder.encode(decoded, Charsets.UTF_8.name()).replace("+", "%20")
    }

    fun hasCatchup(): Boolean {
        if (catchup?.isEmpty() == false) return true
        if (live?.catchup?.isEmpty() == false) return true
        return currentUrl().contains("/PLTV/")
    }

    fun buildCatchupUrl(data: EpgData): String? {
        val rule = when {
            catchup?.isEmpty() == false -> catchup!!
            live?.catchup?.isEmpty() == false -> live!!.catchup
            currentUrl().contains("/PLTV/") -> Catchup.pltv()
            else -> return null
        }
        return rule.format(currentUrl(), data)
    }

    fun buildHeaders(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        live?.header?.let { map.putAll(it) }
        map.putAll(header)
        val userAgent = ua.ifBlank { live?.ua.orEmpty() }
        if (userAgent.isNotBlank()) map["User-Agent"] = userAgent
        val refererValue = referer.ifBlank { live?.referer.orEmpty() }
        if (refererValue.isNotBlank()) map["Referer"] = refererValue
        val originValue = origin.ifBlank { live?.origin.orEmpty() }
        if (originValue.isNotBlank()) map["Origin"] = originValue
        return map
    }

    fun epgForDay(offset: Int = 0, zoneId: java.time.ZoneId = live?.getZoneId() ?: java.time.ZoneId.systemDefault()): Epg? {
        val date = java.time.LocalDate.now(zoneId).plusDays(offset.toLong()).toString()
        return epgList.find { it.date == date }?.selected()
            ?: if (offset == 0) epgList.firstOrNull()?.selected() else null
    }

    fun todayEpg(): Epg? = epgForDay(0)

    fun currentProgram(): EpgData? = todayEpg()?.currentProgram()

    fun availableEpgDates(): List<String> = epgList.map { it.date }.distinct().sorted()
}

private object UtilsCompat {
    fun liveKey(name: String): String = name.ifBlank { "live" }
}
