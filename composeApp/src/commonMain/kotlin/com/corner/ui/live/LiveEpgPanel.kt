package com.corner.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.corner.catvodcore.bean.EpgData
import com.corner.catvodcore.bean.LiveChannel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 直播叠层节目单：保留紧凑时间轴（固定高度），列表紧贴其下，避免大片空白。
 */
@Composable
fun LiveEpgOverlayPanel(
    channel: LiveChannel?,
    epgDayOffset: Int,
    onEpgDayChange: (Int) -> Unit,
    onPlayProgram: (EpgData) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoneId = channel?.live?.getZoneId() ?: ZoneId.systemDefault()
    val epg = channel?.epgForDay(epgDayOffset, zoneId)
    val canCatchup = channel?.hasCatchup() == true
    val programs = epg?.allPrograms().orEmpty()
    val now = System.currentTimeMillis()
    val dayLabels = remember(zoneId) {
        listOf(-1, 0, 1).map { offset ->
            offset to LocalDate.now(zoneId).plusDays(offset.toLong()).format(DateTimeFormatter.ofPattern("MM-dd"))
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(channel?.name, epgDayOffset, programs.size) {
        if (programs.isEmpty()) return@LaunchedEffect
        val index = programs.indexOfFirst { it.isInRange() }.takeIf { it >= 0 } ?: 0
        listState.scrollToItem(index.coerceAtLeast(0))
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("节目单", color = Color.White, style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            dayLabels.forEach { (offset, label) ->
                val title = when (offset) {
                    -1 -> "昨天"
                    0 -> "今天"
                    else -> "明天"
                }
                val selected = epgDayOffset == offset
                Text(
                    text = "$title $label",
                    color = if (selected) Color.Black else Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) Color(0xFF80CBC4) else Color.White.copy(alpha = 0.12f))
                        .clickable { onEpgDayChange(offset) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }

        if (programs.isEmpty()) {
            Text(
                "暂无节目信息",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        CompactEpgTimelineBar(
            programs = programs,
            currentTimeMs = if (epgDayOffset == 0) now else 0L,
            showNowMarker = epgDayOffset == 0,
            canCatchup = canCatchup && epgDayOffset <= 0,
            onProgramClick = onPlayProgram,
            modifier = Modifier.fillMaxWidth(),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f, fill = true).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(
                programs.size,
                key = { index ->
                    val item = programs[index]
                    "${item.start}-${item.end}-${item.title}-$index"
                },
            ) { index ->
                val item = programs[index]
                val highlight = item.isInRange()
                val enabled = canCatchup && epgDayOffset <= 0 && item.isPast()
                val color = when {
                    highlight -> Color(0xFF80CBC4)
                    enabled -> Color.White
                    else -> Color.White.copy(alpha = 0.55f)
                }
                Text(
                    text = item.format(),
                    color = color,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (highlight) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(enabled = enabled) { onPlayProgram(item) }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun CompactEpgTimelineBar(
    programs: List<EpgData>,
    currentTimeMs: Long,
    showNowMarker: Boolean,
    canCatchup: Boolean,
    onProgramClick: (EpgData) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayStart = programs.minOf { it.startTime }
    val dayEnd = programs.maxOf { it.endTime }
    val total = (dayEnd - dayStart).coerceAtLeast(1L)
    val current = if (showNowMarker) programs.firstOrNull { it.isInRange() } else null

    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.12f)),
        ) {
            Row(Modifier.fillMaxSize()) {
                programs.forEach { program ->
                    val duration = (program.endTime - program.startTime).coerceAtLeast(60_000L)
                    val weight = duration.toFloat() / total.toFloat()
                    val bg = when {
                        showNowMarker && program.isInRange() -> Color(0xFF80CBC4)
                        program.isPast() -> Color.White.copy(alpha = 0.28f)
                        else -> Color.White.copy(alpha = 0.1f)
                    }
                    Box(
                        Modifier
                            .weight(weight.coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(bg)
                            .clickable(enabled = canCatchup && program.isPast()) {
                                onProgramClick(program)
                            },
                    )
                }
            }
            if (showNowMarker) {
                val fraction = ((currentTimeMs - dayStart).toFloat() / total.toFloat()).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .offset(x = maxWidth * fraction - 1.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color(0xFFFF5252)),
                )
            }
        }
        Text(
            text = current?.title?.let { "正在播出：$it" }
                ?: if (showNowMarker) "当前时段无节目信息" else "历史 / 预告",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun LiveEpgPanel(
    channel: LiveChannel?,
    epgDayOffset: Int,
    onEpgDayChange: (Int) -> Unit,
    onPlayProgram: (EpgData) -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveEpgOverlayPanel(
        channel = channel,
        epgDayOffset = epgDayOffset,
        onEpgDayChange = onEpgDayChange,
        onPlayProgram = onPlayProgram,
        modifier = modifier,
    )
}
