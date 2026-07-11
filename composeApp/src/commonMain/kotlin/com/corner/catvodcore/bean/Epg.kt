package com.corner.catvodcore.bean

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class Epg(
    val key: String = "",
    val date: String = "",
    val list: MutableList<EpgData> = mutableListOf(),
) {
    fun selected(): Epg {
        list.forEach { it.selected = it.isInRange() }
        return this
    }

    fun currentProgram(): EpgData? = list.firstOrNull { it.isInRange() }

    fun upcomingPrograms(limit: Int = 5): List<EpgData> {
        val now = System.currentTimeMillis()
        return list.filter { it.startTime > now }.take(limit)
    }

    fun pastPrograms(limit: Int = 8): List<EpgData> {
        val now = System.currentTimeMillis()
        return list.filter { it.endTime in 1..now }.sortedByDescending { it.startTime }.take(limit)
    }

    fun allPrograms(): List<EpgData> = list.sortedBy { it.startTime }

    companion object {
        private val epgShortFmt = DateTimeFormatter.ofPattern("yyyy-MM-ddHH:mm")
        private val epgLongFmt = DateTimeFormatter.ofPattern("yyyy-MM-ddHH:mm:ss")
        private val epgCompactFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

        fun create(key: String, date: String) = Epg(key = key, date = date)

        fun objectFrom(text: String, key: String, zoneId: ZoneId = ZoneId.systemDefault()): Epg {
            return try {
                if (text.trimStart().startsWith("{")) {
                    val item = Json { ignoreUnknownKeys = true }.decodeFromString<EpgDto>(text)
                    Epg(
                        key = key.ifBlank { item.key },
                        date = item.date,
                        list = item.programs().toMutableList(),
                    ).apply {
                        setTime(zoneId)
                    }
                } else {
                    com.corner.catvodcore.live.EpgParser.parseXmlSnippet(text, key, zoneId)
                }
            } catch (_: Exception) {
                Epg()
            }
        }

        private fun Epg.setTime(zoneId: ZoneId) {
            val unique = list.distinctBy { "${it.start}-${it.end}-${it.title}" }
            list.clear()
            list.addAll(unique)
            list.forEach { data ->
                data.startTime = parseEpgTime(date + data.start, zoneId)
                data.endTime = parseEpgTime(date + data.end, zoneId)
                if (data.endTime in 1..<data.startTime) {
                    data.endTime = Instant.ofEpochMilli(data.endTime).atZone(zoneId).plusDays(1).toInstant().toEpochMilli()
                }
            }
        }

        private fun parseEpgTime(source: String, zoneId: ZoneId): Long {
            return try {
                val fmt = when {
                    source.length > 16 && source.contains("-") -> epgLongFmt
                    source.contains("-") -> epgShortFmt
                    else -> epgCompactFmt
                }
                val normalized = if (fmt === epgCompactFmt) source.take(12) else source
                LocalDateTime.parse(normalized, fmt).atZone(zoneId).toInstant().toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }
}

@Serializable
private data class EpgDto(
    val key: String = "",
    val date: String = "",
    val list: List<EpgData> = emptyList(),
    @SerialName("epg_data")
    val epgData: List<EpgData> = emptyList(),
) {
    fun programs(): List<EpgData> = if (list.isNotEmpty()) list else epgData
}

@Serializable
data class EpgData(
    val title: String = "",
    val start: String = "",
    val end: String = "",
    @Transient
    var selected: Boolean = false,
    @Transient
    var startTime: Long = 0,
    @Transient
    var endTime: Long = 0,
) {
    fun isInRange(): Boolean {
        val now = System.currentTimeMillis()
        return startTime <= now && now <= endTime
    }

    fun isPast(): Boolean {
        val now = System.currentTimeMillis()
        return endTime in 1..<now
    }

    fun isFuture(): Boolean = startTime > System.currentTimeMillis()

    fun format(): String {
        if (title.isBlank()) return ""
        if (start.isBlank() && end.isBlank()) return title
        return "$start ~ $end  $title"
    }
}
