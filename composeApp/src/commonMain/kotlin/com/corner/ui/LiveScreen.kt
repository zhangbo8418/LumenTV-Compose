package com.corner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import com.corner.catvodcore.bean.EpgData
import com.corner.catvodcore.bean.Live
import com.corner.catvodcore.bean.LiveChannel
import com.corner.catvodcore.bean.LiveGroup
import com.corner.catvodcore.live.LiveKeep
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.ui.live.LiveChannelLogo
import com.corner.ui.live.LiveEpgOverlayPanel
import com.corner.ui.nav.vm.LiveViewModel
import kotlinx.coroutines.delay

private enum class LiveOverlay {
    None,
    ChannelMenu,
    ControlPanel,
}

@Composable
fun WindowScope.LiveScene(
    vm: LiveViewModel,
    onClickBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val useInternalPlayer = rememberUseInternalPlayer()
    val isFullScreen by GlobalAppState.videoFullScreen.collectAsState()
    var overlay by remember { mutableStateOf(LiveOverlay.None) }
    var showInfo by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(overlay) {
        if (overlay == LiveOverlay.None) return@LaunchedEffect
        delay(8_000)
        overlay = LiveOverlay.None
    }

    LaunchedEffect(state.selectedChannel?.name, state.playUrl) {
        if (state.selectedChannel == null) return@LaunchedEffect
        showInfo = true
        delay(5_000)
        if (overlay == LiveOverlay.None) showInfo = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.F11 -> {
                        GlobalAppState.toggleVideoFullScreen()
                        true
                    }
                    Key.Escape -> {
                        if (isFullScreen) {
                            GlobalAppState.toggleVideoFullScreen()
                            true
                        } else if (overlay != LiveOverlay.None) {
                            overlay = LiveOverlay.None
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
    ) {
        val empty = state.lives.isEmpty()
        val loading = state.isLoading && state.playUrl.isBlank()
        val resolving = state.isResolving && state.playUrl.isBlank()

        when {
            empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("当前配置未包含直播源", color = Color.White.copy(alpha = 0.7f))
            }

            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }

            else -> {
                when {
                    resolving -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }

                    state.playUrl.isNotBlank() -> LivePlayerView(
                        playUrl = state.playUrl,
                        playHeaders = state.playHeaders,
                        channelName = state.selectedChannel?.name.orEmpty(),
                        useInternalPlayer = useInternalPlayer,
                        onPlaybackError = vm::onPlaybackError,
                        onPrevChannel = {
                            showInfo = true
                            vm.prevChannel()
                        },
                        onNextChannel = {
                            showInfo = true
                            vm.nextChannel()
                        },
                        togglePlayOnClick = false,
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("选择频道开始播放", color = Color.White.copy(alpha = 0.7f))
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(overlay) {
                            detectTapGestures(
                                onDoubleTap = {
                                    // 手势层盖在播放器之上，双击需在此切换全屏（含恢复）
                                    GlobalAppState.toggleVideoFullScreen()
                                    overlay = LiveOverlay.None
                                    showInfo = false
                                },
                                onTap = { offset ->
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    when {
                                        overlay != LiveOverlay.None -> overlay = LiveOverlay.None
                                        offset.x < w * 0.28f -> {
                                            overlay = LiveOverlay.ChannelMenu
                                            showInfo = true
                                        }
                                        offset.x > w * 0.72f -> {
                                            overlay = LiveOverlay.ControlPanel
                                            showInfo = true
                                        }
                                        else -> showInfo = !showInfo
                                    }
                                },
                            )
                        }
                )
            }
        }

        Box(Modifier.align(Alignment.TopStart)) {
            WindowDraggableArea {
                IconButton(
                    onClick = onClickBack,
                    modifier = Modifier
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = overlay == LiveOverlay.ChannelMenu,
            enter = fadeIn() + slideInHorizontally { -it / 3 },
            exit = fadeOut() + slideOutHorizontally { -it / 3 },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            LiveChannelOverlay(
                groups = state.groups,
                selectedGroup = state.selectedGroup,
                channels = state.channels,
                selectedChannel = state.selectedChannel,
                keptKeys = state.keptKeys,
                live = state.currentLive,
                epgDayOffset = state.epgDayOffset,
                epgTick = state.epgTick,
                onSelectGroup = {
                    vm.selectGroup(it)
                    showInfo = true
                },
                onSelectChannel = {
                    vm.selectChannel(it)
                    showInfo = true
                    overlay = LiveOverlay.None
                },
                onLongPressChannel = vm::toggleKeep,
                onEpgDayChange = vm::setEpgDayOffset,
                onPlayProgram = {
                    vm.playEpgProgram(it)
                    overlay = LiveOverlay.None
                },
            )
        }

        AnimatedVisibility(
            visible = overlay == LiveOverlay.ControlPanel,
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            LiveControlOverlay(
                lives = state.lives,
                currentLive = state.currentLive,
                channel = state.selectedChannel,
                lineLabel = state.lineLabel,
                isFullScreen = isFullScreen,
                onSelectLive = {
                    vm.selectLive(it)
                    showInfo = true
                },
                onPrevLine = {
                    vm.prevLine()
                    showInfo = true
                },
                onNextLine = {
                    vm.nextLine()
                    showInfo = true
                },
                onPrevChannel = {
                    vm.prevChannel()
                    showInfo = true
                },
                onNextChannel = {
                    vm.nextChannel()
                    showInfo = true
                },
                onToggleFullscreen = { GlobalAppState.toggleVideoFullScreen() },
                onClose = { overlay = LiveOverlay.None },
            )
        }

        AnimatedVisibility(
            visible = showInfo && state.selectedChannel != null && overlay == LiveOverlay.None,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            LiveInfoBar(
                channel = state.selectedChannel,
                lineLabel = state.lineLabel,
                epgTick = state.epgTick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            )
        }

        if (overlay == LiveOverlay.None && state.selectedChannel == null && state.lives.isNotEmpty() && !state.isLoading) {
            Text(
                "点击左侧换台 · 点击右侧换源/设置 · F11 全屏",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveChannelOverlay(
    groups: List<LiveGroup>,
    selectedGroup: LiveGroup?,
    channels: List<LiveChannel>,
    selectedChannel: LiveChannel?,
    keptKeys: Set<String>,
    live: Live?,
    epgDayOffset: Int,
    epgTick: Long,
    onSelectGroup: (LiveGroup) -> Unit,
    onSelectChannel: (LiveChannel) -> Unit,
    onLongPressChannel: (LiveChannel) -> Unit,
    onEpgDayChange: (Int) -> Unit,
    onPlayProgram: (EpgData) -> Unit,
) {
    Row(
        Modifier
            .fillMaxHeight()
            .widthIn(max = 640.dp)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.82f),
                    0.75f to Color.Black.copy(alpha = 0.45f),
                    1f to Color.Transparent,
                )
            )
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(top = 56.dp, bottom = 16.dp),
    ) {
        LazyColumn(
            Modifier
                .width(132.dp)
                .fillMaxHeight()
                .padding(start = 8.dp),
        ) {
            items(groups, key = { it.name }) { group ->
                val selected = group.name == selectedGroup?.name
                Text(
                    text = group.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                    style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { onSelectGroup(group) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                )
            }
        }

        Column(
            Modifier
                .width(240.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(0.dp)),
        ) {
            Text(
                "长按收藏",
                color = Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            val listState = rememberLazyListState()
            LaunchedEffect(selectedChannel?.name, channels) {
                val index = channels.indexOfFirst { it.name == selectedChannel?.name }
                if (index >= 0) listState.animateScrollToItem(index)
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(channels, key = { "${it.name}-$epgTick" }) { channel ->
                    ChannelRow(
                        channel = channel,
                        selected = channel.name == selectedChannel?.name,
                        kept = live != null && keptKeys.contains(LiveKeep.keepKey(live, channel)),
                        onClick = { onSelectChannel(channel) },
                        onLongClick = { onLongPressChannel(channel) },
                    )
                }
            }
        }

        Column(
            Modifier
                .width(240.dp)
                .fillMaxHeight()
                .padding(end = 8.dp),
        ) {
            key(epgTick, selectedChannel?.name, epgDayOffset) {
                LiveEpgOverlayPanel(
                    channel = selectedChannel,
                    epgDayOffset = epgDayOffset,
                    onEpgDayChange = onEpgDayChange,
                    onPlayProgram = onPlayProgram,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.25f)),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: LiveChannel,
    selected: Boolean,
    kept: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val logoUrl = channel.resolvedLogo()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = channel.number.ifBlank { "" },
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(22.dp),
        )
        LiveChannelLogo(
            url = logoUrl,
            fallbackText = channel.name,
            modifier = Modifier.size(width = 40.dp, height = 30.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = channel.name,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                softWrap = true,
            )
            val program = channel.currentProgram()?.title
            if (!program.isNullOrBlank()) {
                Text(
                    text = program,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            animationMode = MarqueeAnimationMode.Immediately,
                            initialDelayMillis = 800,
                            velocity = 30.dp,
                        ),
                )
            }
        }
        if (kept) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = Color(0xFFFFC107),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun LiveControlOverlay(
    lives: List<Live>,
    currentLive: Live?,
    channel: LiveChannel?,
    lineLabel: String,
    isFullScreen: Boolean,
    onSelectLive: (Live) -> Unit,
    onPrevLine: () -> Unit,
    onNextLine: () -> Unit,
    onPrevChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(260.dp)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.25f to Color.Black.copy(alpha = 0.45f),
                    1f to Color.Black.copy(alpha = 0.85f),
                )
            )
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("直播设置", color = Color.White, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

        Text("换台", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPrevChannel) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Color.White)
                Text("上一频道", color = Color.White)
            }
            TextButton(onClick = onNextChannel) {
                Text("下一频道", color = Color.White)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
            }
        }

        if (channel?.hasMultipleLines() == true) {
            Text("线路", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
            Text(
                lineLabel.ifBlank { "线路 ${channel.urlIndex + 1}" },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPrevLine) { Text("上一线路", color = Color.White) }
                TextButton(onClick = onNextLine) { Text("下一线路", color = Color.White) }
            }
        }

        Text("画面", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = onToggleFullscreen) {
            Icon(
                if (isFullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = null,
                tint = Color.White,
            )
            Text(
                if (isFullScreen) "退出全屏 (ESC)" else "全屏 (F11)",
                color = Color.White,
            )
        }

        Text("直播源", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        LazyColumn(
            Modifier
                .weight(1f, fill = false)
                .heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(lives, key = { it.name }) { liveItem ->
                val selected = liveItem.name == currentLive?.name
                Text(
                    text = liveItem.name,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { onSelectLive(liveItem) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
            Text("关闭", color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun LiveInfoBar(
    channel: LiveChannel?,
    lineLabel: String,
    epgTick: Long = 0,
    modifier: Modifier = Modifier,
) {
    if (channel == null) return
    key(epgTick, channel.name) {
        val program = channel.currentProgram()
        val logoUrl = channel.resolvedLogo()

        Row(
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                channel.number.ifBlank { "--" },
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.titleMedium,
            )
            LiveChannelLogo(
                url = logoUrl,
                fallbackText = channel.name,
                modifier = Modifier.size(width = 48.dp, height = 36.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(channel.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    text = program?.let { "正在播出：${it.title}" } ?: "暂无节目信息",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (lineLabel.isNotBlank()) {
                Text(lineLabel, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
