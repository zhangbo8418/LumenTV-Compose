package com.corner.catvodcore.live

import com.corner.catvodcore.bean.Epg
import com.corner.catvodcore.bean.LiveChannel
import com.corner.util.net.Http
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object LiveEpg {
    private val log = LoggerFactory.getLogger("LiveEpg")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    suspend fun loadChannelEpg(
        channel: LiveChannel,
        zoneId: ZoneId = channel.live?.getZoneId() ?: ZoneId.systemDefault(),
        offsets: List<Int> = listOf(-1, 0, 1),
    ) {
        val template = channel.epg.ifBlank { channel.live?.epg.orEmpty() }
        if (template.isBlank() || !template.contains("{")) {
            log.debug("频道无 EPG 模板: {}", channel.name)
            return
        }
        offsets.forEach { offset ->
            val date = LocalDate.now(zoneId).plusDays(offset.toLong()).format(dateFmt)
            if (channel.epgList.any { it.date == date }) return@forEach
            val nameToken = channel.tvgName.ifBlank { channel.name }
            val idToken = channel.tvgId.ifBlank { nameToken }
            val url = template
                .replace("{date}", date)
                .replace("{id}", encodeEpgToken(idToken))
                .replace("{name}", encodeEpgToken(nameToken))
                .replace("{epg}", channel.epg)
                .replace("{logo}", channel.logo)
            if (!url.startsWith("http")) return@forEach
            try {
                val text = Http.get(url).execute().use { it.body?.string().orEmpty() }
                val parsed = Epg.objectFrom(text, idToken, zoneId)
                if (parsed.list.isNotEmpty()) {
                    channel.epgList.removeAll { it.date == date }
                    channel.epgList.add(parsed.copy(date = date.ifBlank { parsed.date }))
                    log.info("频道 EPG 加载成功: {} {} ({} 条)", channel.name, date, parsed.list.size)
                } else {
                    log.warn("频道 EPG 解析为空: {} -> {}", channel.name, url.take(120))
                }
            } catch (e: Exception) {
                log.warn("加载频道 EPG 失败: $url", e)
            }
        }
    }

    /** 保留字面量 '+'（如 CCTV5+），避免被当成空格。 */
    private fun encodeEpgToken(value: String): String {
        return java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
