package com.corner.ui.danmaku

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import com.corner.catvodcore.setting.DanmakuSetting
import com.corner.ui.player.frame.FramePlayerController
import kotlinx.coroutines.delay

@Composable
fun DanmakuOverlay(
    controller: FramePlayerController,
    modifier: Modifier = Modifier,
) {
    if (!DanmakuSetting.isShow()) return
    val playerState by controller.state.collectAsState()
    val activeItems by DanmakuManager.active.collectAsState()
    val textMeasurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val engine = remember { DanmakuEngine(textMeasurer, density) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val durationMs = DanmakuSetting.getDurationMs()
    val textScale = DanmakuSetting.getTextScale()
    val maxOnScreen = DanmakuSetting.getMaxOnScreen()
    val scrollAreaRatio = 0.75f
    val alpha = 0.92f

    LaunchedEffect(playerState.timestamp) {
        DanmakuManager.tick(playerState.timestamp)
    }

    var lastPosition by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playerState.timestamp) {
        if (playerState.timestamp < lastPosition - 3000) {
            engine.onSeek()
            DanmakuManager.onSeek()
        }
        lastPosition = playerState.timestamp
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            clock = System.currentTimeMillis()
            DanmakuManager.pruneActive(clock)
        }
    }

    LaunchedEffect(activeItems) {
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        engine.syncItems(
            items = activeItems,
            canvasWidth = boxSize.width.toFloat(),
            canvasHeight = boxSize.height.toFloat(),
            now = clock,
            durationMs = durationMs,
            textScale = textScale,
            scrollAreaRatio = scrollAreaRatio,
            maxOnScreen = maxOnScreen,
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it },
    ) {
        if (boxSize.width <= 0 || boxSize.height <= 0) return@Canvas
        engine.syncItems(
            items = activeItems,
            canvasWidth = size.width,
            canvasHeight = size.height,
            now = clock,
            durationMs = durationMs,
            textScale = textScale,
            scrollAreaRatio = scrollAreaRatio,
            maxOnScreen = maxOnScreen,
        )
        engine.draw(this, clock, durationMs, alpha)
    }
}
