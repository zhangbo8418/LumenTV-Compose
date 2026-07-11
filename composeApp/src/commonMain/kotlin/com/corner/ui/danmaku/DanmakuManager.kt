package com.corner.ui.danmaku

import com.corner.catvodcore.api.DanmakuApi
import com.corner.catvodcore.setting.DanmakuSetting
import com.corner.util.net.Http
import com.corner.util.scope.createCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.regex.Pattern

data class DanmakuItem(
    val text: String,
    val timeMs: Long,
    val mode: Int = 1,
    val size: Int = 25,
    val color: Int = 0xFFFFFF,
    val id: Long = System.nanoTime(),
    val spawnedAt: Long = 0L,
)

object DanmakuManager {
    private val log = LoggerFactory.getLogger("DanmakuManager")
    private val scope = createCoroutineScope()
    val items = MutableStateFlow<List<DanmakuItem>>(emptyList())
    val active = MutableStateFlow<List<DanmakuItem>>(emptyList())
    private var scheduled = listOf<DanmakuItem>()
    private var lastPositionMs = 0L
    private val shownIds = mutableSetOf<Long>()

    fun clear() {
        scheduled = emptyList()
        shownIds.clear()
        lastPositionMs = 0L
        items.value = emptyList()
        active.value = emptyList()
    }

    fun onPlayStart(danmakuUrl: String?, vodName: String, episodeName: String) {
        clear()
        if (!DanmakuSetting.isShow()) return
        scope.launch {
            val url = resolveDanmakuUrl(danmakuUrl, vodName, episodeName)
            if (url.isNullOrBlank()) return@launch
            loadFromUrl(url)
        }
    }

    private suspend fun resolveDanmakuUrl(
        danmakuUrl: String?,
        vodName: String,
        episodeName: String,
    ): String? {
        if (DanmakuSetting.isSpiderFirst() && !danmakuUrl.isNullOrBlank()) return danmakuUrl
        if (DanmakuSetting.canSearch()) {
            DanmakuApi.search(vodName, episodeName)?.url?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return danmakuUrl?.takeIf { it.isNotBlank() }
    }

    fun onSeek() {
        shownIds.clear()
        lastPositionMs = 0L
    }

    fun send(raw: String) {
        parseLine(raw)?.let { item ->
            val stamped = item.copy(spawnedAt = System.currentTimeMillis())
            active.update { current ->
                (current + stamped).takeLast(DanmakuSetting.getMaxOnScreen())
            }
        }
    }

    fun tick(positionMs: Long) {
        if (scheduled.isEmpty()) return
        if (positionMs < lastPositionMs - 3000) {
            shownIds.clear()
        }
        lastPositionMs = positionMs
        val windowStart = positionMs - 500
        val windowEnd = positionMs + 500
        val due = scheduled.filter { it.id !in shownIds && it.timeMs in windowStart..windowEnd }
        if (due.isEmpty()) return
        due.forEach { shownIds.add(it.id) }
        val now = System.currentTimeMillis()
        active.update { current ->
            (current + due.map { it.copy(spawnedAt = now) }).takeLast(DanmakuSetting.getMaxOnScreen())
        }
    }

    fun pruneActive(now: Long = System.currentTimeMillis()) {
        val duration = DanmakuSetting.getDurationMs()
        active.update { current ->
            current.filter { item ->
                val spawned = item.spawnedAt.takeIf { it > 0 } ?: now
                now - spawned < duration
            }
        }
    }

    private suspend fun loadFromUrl(url: String) = withContext(Dispatchers.IO) {
        try {
            val body = Http.get(url).execute().use { it.body.string() }
            val parsed = parseXml(body).ifEmpty { parseLines(body) }
            scheduled = parsed.sortedBy { it.timeMs }
            items.value = scheduled
            log.info("弹幕已加载 {} 条", parsed.size)
        } catch (e: Exception) {
            log.warn("弹幕加载失败: {}", e.message)
        }
    }

    private fun parseLine(raw: String): DanmakuItem? {
        val pattern = Pattern.compile("""^\[([^\]]+)](.+)$""")
        val matcher = pattern.matcher(raw.trim())
        if (!matcher.find()) return DanmakuItem(text = raw.trim(), timeMs = 0)
        val meta = matcher.group(1)?.split(",") ?: return null
        if (meta.size < 4) return null
        val timeSec = meta[0].toFloatOrNull() ?: 0f
        return DanmakuItem(
            text = matcher.group(2).orEmpty(),
            timeMs = (timeSec * 1000).toLong(),
            mode = meta[1].toIntOrNull() ?: 1,
            size = meta[2].toIntOrNull() ?: 25,
            color = meta[3].toIntOrNull() ?: 0xFFFFFF,
        )
    }

    private fun parseXml(xml: String): List<DanmakuItem> {
        val result = mutableListOf<DanmakuItem>()
        val itemPattern = Pattern.compile("""<d[^>]*p="([^"]+)"[^>]*>([^<]*)</d>""")
        val matcher = itemPattern.matcher(xml)
        while (matcher.find()) {
            val p = matcher.group(1)?.split(",") ?: continue
            if (p.size < 4) continue
            result.add(
                DanmakuItem(
                    text = matcher.group(2).orEmpty(),
                    timeMs = ((p[0].toFloatOrNull() ?: 0f) * 1000).toLong(),
                    mode = p[1].toIntOrNull() ?: 1,
                    size = p[2].toIntOrNull() ?: 25,
                    color = p[3].toIntOrNull() ?: 0xFFFFFF,
                )
            )
        }
        return result
    }

    private fun parseLines(body: String): List<DanmakuItem> {
        return body.lineSequence()
            .mapNotNull { line -> line.trim().takeIf { it.isNotEmpty() }?.let { parseLine(it) } }
            .toList()
    }
}
