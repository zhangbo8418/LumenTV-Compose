package com.corner.catvodcore.live

import com.corner.catvodcore.bean.Epg
import com.corner.catvodcore.bean.EpgData
import com.corner.catvodcore.bean.Live
import com.corner.catvodcore.bean.LiveChannel
import com.corner.util.io.Paths
import com.corner.util.net.Http
import com.corner.util.net.Utils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object EpgParser {
    private val log = LoggerFactory.getLogger("EpgParser")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val fullFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    private val fullColonFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss ZZ")

    fun start(live: Live, url: String) {
        val file = Paths.epg(Utils.md5(url))
        val refresh = refreshReason(file)
        if (refresh != null) {
            log.info("下载 EPG: $url (${refresh})")
            Http.get(url).execute().use { response ->
                Paths.write(file, response.body.bytes())
            }
        }
        if (isGzip(file)) readGzip(live, file, refresh != null)
        else readXml(live, file, live.getZoneId())
    }

    fun parseXmlSnippet(xml: String, key: String, zoneId: ZoneId): Epg {
        val date = LocalDate.now(zoneId).format(dateFmt)
        val epg = Epg.create(key, date)
        programmePattern.findAll(xml).forEach { match ->
            val startRaw = attr(match.value, "start") ?: return@forEach
            val stopRaw = attr(match.value, "stop") ?: return@forEach
            val channel = attr(match.value, "channel") ?: return@forEach
            if (channel != key && key.isNotBlank()) return@forEach
            val start = parseFull(startRaw, zoneId)
            val end = parseFull(stopRaw, zoneId)
            val title = titlePattern.find(match.value)?.groupValues?.getOrNull(1).orEmpty()
            epg.list.add(buildEpgData(title, start, end, zoneId))
        }
        return epg
    }

    private fun readGzip(live: Live, file: File, refresh: Boolean) {
        val xml = Paths.epg(file.name + ".xml")
        if (!xml.exists() || refresh) {
            GZIPInputStream(FileInputStream(file)).use { input ->
                xml.writeBytes(input.readBytes())
            }
        }
        readXml(live, xml, live.getZoneId())
    }

    private fun readXml(live: Live, file: File, zoneId: ZoneId) {
        if (!file.exists()) return
        val text = file.readText()
        val channelMap = buildChannelMap(live)
        val epgByChannel = mutableMapOf<String, MutableMap<String, Epg>>()
        val logoByChannel = mutableMapOf<String, String>()

        channelLogoPattern.findAll(text).forEach { match ->
            val id = match.groupValues[1]
            val logo = iconPattern.find(match.value)?.groupValues?.getOrNull(1)
            if (!logo.isNullOrBlank()) logoByChannel[id] = logo
        }

        programmePattern.findAll(text).forEach { match ->
            val xmlChannelId = attr(match.value, "channel") ?: return@forEach
            val target = channelMap[xmlChannelId] ?: findByDisplayName(text, xmlChannelId, channelMap) ?: return@forEach
            val tvgId = target.tvgId.ifBlank { target.name }
            val start = parseFull(attr(match.value, "start") ?: return@forEach, zoneId)
            val end = parseFull(attr(match.value, "stop") ?: return@forEach, zoneId)
            val title = titlePattern.find(match.value)?.groupValues?.getOrNull(1).orEmpty()
            val programmeDate = start.atZoneSameInstant(zoneId).format(dateFmt)
            epgByChannel
                .getOrPut(tvgId) { mutableMapOf() }
                .getOrPut(programmeDate) { Epg.create(tvgId, programmeDate) }
                .list
                .add(buildEpgData(title, start, end, zoneId))
            if (target.logo.isBlank()) {
                logoByChannel[xmlChannelId]?.let { target.logo = it }
            }
        }

        live.groups.forEach { group ->
            group.channels.forEach { channel ->
                val tvgId = channel.tvgId.ifBlank { channel.name }
                val dateMap = epgByChannel[tvgId]
                if (dateMap != null) {
                    channel.epgList = dateMap.values.toMutableList()
                }
            }
        }
        log.info("EPG 绑定完成，匹配频道数: ${epgByChannel.size}")
    }

    private fun buildChannelMap(live: Live): Map<String, LiveChannel> {
        val map = mutableMapOf<String, LiveChannel>()
        live.groups.flatMap { it.channels }.forEach { channel ->
            if (channel.tvgId.isNotBlank()) map.putIfAbsent(channel.tvgId, channel)
            if (channel.tvgName.isNotBlank()) map.putIfAbsent(channel.tvgName, channel)
            if (channel.name.isNotBlank()) map.putIfAbsent(channel.name, channel)
        }
        return map
    }

    private fun findByDisplayName(xml: String, channelId: String, channelMap: Map<String, LiveChannel>): LiveChannel? {
        val block = Regex(
            """<channel\s+id="${Regex.escape(channelId)}"[^>]*>.*?</channel>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(xml)?.value ?: return null
        displayNamePattern.findAll(block).forEach { match ->
            val name = match.groupValues[1].trim()
            channelMap[name]?.let { return it }
        }
        return null
    }

    private fun attr(tag: String, name: String): String? {
        return Regex("""$name="([^"]*)"""").find(tag)?.groupValues?.getOrNull(1)
    }

    private fun buildEpgData(title: String, start: OffsetDateTime, end: OffsetDateTime, zoneId: ZoneId): EpgData {
        return EpgData(
            title = title.trim(),
            start = start.atZoneSameInstant(zoneId).format(timeFmt),
            end = end.atZoneSameInstant(zoneId).format(timeFmt),
            startTime = start.toInstant().toEpochMilli(),
            endTime = end.toInstant().toEpochMilli(),
        )
    }

    private fun parseFull(source: String, zoneId: ZoneId): OffsetDateTime {
        val s = source.trim()
        return try {
            val time = if (s.length > 14) s.substring(0, 14) else s
            val offset = if (s.length > 14) s.substring(14).trim() else ""
            if (offset.isNotEmpty()) {
                try {
                    OffsetDateTime.parse("$time $offset", fullFmt)
                } catch (_: Exception) {
                    OffsetDateTime.parse("$time $offset", fullColonFmt)
                }
            } else {
                LocalDateTime.parse(time, DateTimeFormatter.ofPattern("yyyyMMddHHmm")).atZone(zoneId).toOffsetDateTime()
            }
        } catch (e: Exception) {
            log.warn("解析 EPG 时间失败: $source")
            OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC)
        }
    }

    private fun refreshReason(file: File): String? {
        if (!file.exists()) return "file-missing"
        if (!isToday(file.lastModified())) return "not-today"
        if (System.currentTimeMillis() - file.lastModified() > TimeUnit.HOURS.toMillis(6)) return "older-than-6h"
        return null
    }

    private fun isGzip(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                (fis.read() or (fis.read() shl 8)) == 0x8B1F
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isToday(millis: Long): Boolean {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()) == LocalDate.now()
    }

    private val programmePattern = Regex("""<programme\b[^>]*>.*?</programme>""", RegexOption.DOT_MATCHES_ALL)
    private val titlePattern = Regex("""<title[^>]*>([^<]*)</title>""")
    private val channelLogoPattern = Regex("""<channel\s+id="([^"]+)"[^>]*>(.*?)</channel>""", RegexOption.DOT_MATCHES_ALL)
    private val iconPattern = Regex("""<icon\s+src="([^"]+)"""")
    private val displayNamePattern = Regex("""<display-name[^>]*>([^<]*)</display-name>""")
}
