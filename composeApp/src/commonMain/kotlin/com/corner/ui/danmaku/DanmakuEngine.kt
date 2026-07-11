package com.corner.ui.danmaku

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

internal data class ActiveDanmaku(
    val item: DanmakuItem,
    val width: Float,
    val height: Float,
    val track: Int,
    val startMs: Long,
    val expireAt: Long,
    val y: Float,
    val fontSizePx: Float,
)

private data class ScrollTrackState(
    var occupiedUntil: Long = 0L,
)

internal class DanmakuEngine(
    private val textMeasurer: TextMeasurer,
    private val density: Density,
) {
    private val scrollTracks = mutableListOf<ScrollTrackState>()
    private val active = mutableListOf<ActiveDanmaku>()
    private val spawnedIds = mutableSetOf<Long>()

    fun clear() {
        active.clear()
        scrollTracks.clear()
        spawnedIds.clear()
    }

    fun onSeek() {
        active.clear()
        scrollTracks.clear()
        spawnedIds.clear()
    }

    fun syncItems(
        items: List<DanmakuItem>,
        canvasWidth: Float,
        canvasHeight: Float,
        now: Long,
        durationMs: Long,
        textScale: Float,
        scrollAreaRatio: Float,
        maxOnScreen: Int,
    ) {
        items.forEach { item ->
            if (item.id in spawnedIds) return@forEach
            if (active.size >= maxOnScreen) return@forEach
            spawn(item, canvasWidth, canvasHeight, now, durationMs, textScale, scrollAreaRatio)
            spawnedIds.add(item.id)
        }
        active.removeAll { now >= it.expireAt }
    }

    private fun spawn(
        item: DanmakuItem,
        canvasWidth: Float,
        canvasHeight: Float,
        now: Long,
        durationMs: Long,
        textScale: Float,
        scrollAreaRatio: Float,
    ) {
        val fontSizePx = item.size.coerceIn(12, 36) * textScale * density.density
        val style = TextStyle(
            fontSize = (fontSizePx / density.density).sp,
            fontWeight = FontWeight.Bold,
        )
        val layout = textMeasurer.measure(item.text, style)
        val width = layout.size.width.toFloat()
        val height = layout.size.height.toFloat()
        val lineHeight = height * 1.25f
        val areaHeight = canvasHeight * scrollAreaRatio.coerceIn(0.35f, 1f)
        val maxTracks = max(1, (areaHeight / lineHeight).toInt())

        when (item.mode) {
            5 -> {
                active.add(
                    ActiveDanmaku(
                        item = item,
                        width = width,
                        height = height,
                        track = 0,
                        startMs = now,
                        expireAt = now + durationMs,
                        y = lineHeight,
                        fontSizePx = fontSizePx,
                    )
                )
            }
            4 -> {
                active.add(
                    ActiveDanmaku(
                        item = item,
                        width = width,
                        height = height,
                        track = 0,
                        startMs = now,
                        expireAt = now + durationMs,
                        y = canvasHeight - lineHeight * 1.5f,
                        fontSizePx = fontSizePx,
                    )
                )
            }
            6 -> {
                val track = assignScrollTrack(now, width, canvasWidth, durationMs, maxTracks)
                active.add(
                    ActiveDanmaku(
                        item = item,
                        width = width,
                        height = height,
                        track = track,
                        startMs = now,
                        expireAt = now + durationMs,
                        y = (track + 1) * lineHeight,
                        fontSizePx = fontSizePx,
                    )
                )
            }
            else -> {
                val track = assignScrollTrack(now, width, canvasWidth, durationMs, maxTracks)
                active.add(
                    ActiveDanmaku(
                        item = item,
                        width = width,
                        height = height,
                        track = track,
                        startMs = now,
                        expireAt = now + durationMs,
                        y = (track + 1) * lineHeight,
                        fontSizePx = fontSizePx,
                    )
                )
            }
        }
    }

    private fun assignScrollTrack(
        now: Long,
        width: Float,
        canvasWidth: Float,
        durationMs: Long,
        maxTracks: Int,
    ): Int {
        while (scrollTracks.size < maxTracks) {
            scrollTracks.add(ScrollTrackState())
        }
        val travelMs = durationMs.toFloat()
        val gapMs = ((width + 48f) / (canvasWidth + width) * travelMs).toLong().coerceAtLeast(120L)
        for (i in 0 until maxTracks) {
            val track = scrollTracks[i]
            if (now >= track.occupiedUntil) {
                track.occupiedUntil = now + gapMs
                return i
            }
        }
        val fallback = Random.nextInt(maxTracks)
        scrollTracks[fallback].occupiedUntil = now + gapMs
        return fallback
    }

    fun draw(
        drawScope: DrawScope,
        now: Long,
        durationMs: Long,
        alpha: Float,
    ) {
        val canvasWidth = drawScope.size.width
        active.forEach { danmaku ->
            val elapsed = (now - danmaku.startMs).coerceAtLeast(0L).toFloat()
            val progress = (elapsed / durationMs.toFloat()).coerceIn(0f, 1f)
            val x = when (danmaku.item.mode) {
                6 -> -danmaku.width + (canvasWidth + danmaku.width) * progress
                5, 4 -> (canvasWidth - danmaku.width) / 2f
                else -> canvasWidth - (canvasWidth + danmaku.width) * progress
            }
            val color = Color(danmaku.item.color and 0xFFFFFF or 0xFF000000.toInt())
            val style = TextStyle(
                fontSize = (danmaku.fontSizePx / density.density).sp,
                fontWeight = FontWeight.Bold,
                color = color.copy(alpha = alpha),
            )
            drawScope.drawOutlinedText(
                textMeasurer = textMeasurer,
                text = danmaku.item.text,
                style = style,
                topLeft = Offset(x, danmaku.y),
                fillColor = color.copy(alpha = alpha),
                outlineColor = Color.Black.copy(alpha = min(1f, alpha + 0.1f)),
            )
        }
    }
}

private fun DrawScope.drawOutlinedText(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    topLeft: Offset,
    fillColor: Color,
    outlineColor: Color,
) {
    val layout = textMeasurer.measure(text, style.copy(color = fillColor))
    val stroke = 1.5f * density
    val offsets = arrayOf(
        Offset(-stroke, 0f), Offset(stroke, 0f),
        Offset(0f, -stroke), Offset(0f, stroke),
        Offset(-stroke, -stroke), Offset(stroke, stroke),
        Offset(-stroke, stroke), Offset(stroke, -stroke),
    )
    offsets.forEach { off ->
        drawText(
            textLayoutResult = textMeasurer.measure(text, style.copy(color = outlineColor)),
            topLeft = topLeft + off,
        )
    }
    drawText(textLayoutResult = layout, topLeft = topLeft)
}
