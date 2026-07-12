package com.corner.ui.player.frame

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.corner.ui.player.PlayState
import com.corner.ui.player.frame.FramePlayerController
import com.corner.ui.scene.emptyShow
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun FrameContainer(
    modifier: Modifier = Modifier,
    controller: FramePlayerController,
    onClick: () -> Unit
) {
    val playerState = controller.state.collectAsState()
    // 在帧时钟内拉取 AtomicReference，避免跨线程写 MutableState 触发 snapshot 卡死
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(controller) {
        while (true) {
            withFrameNanos {
                val next = controller.peekVideoFrame()
                if (next !== bitmap) {
                    bitmap = next
                }
            }
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isControllerReady by derivedStateOf { // 播放器就绪
        controller.hasPlayer() && !controller.isReleased()
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                enabled = true,
                onDoubleClick = {
                    controller.toggleFullscreen()
                },
                interactionSource = interactionSource,
                indication = null
            ) {
                onClick()
            }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val y = e.changes.first().scrollDelta.y
                if (y < 0) {
                    controller.volumeUp()
                } else {
                    controller.volumeDown()
                }
            }, contentAlignment = Alignment.Center
    ) {
        val frameSizeCalculator = remember { FrameContainerSizeCalculator() }

        LaunchedEffect(playerState.value.aspectRatio) {
            frameSizeCalculator.aspectRatio = playerState.value.aspectRatio
        }

        val imageSize by derivedStateOf {
            val mediaInfo = playerState.value.mediaInfo
            if (mediaInfo != null) {
                IntSize(mediaInfo.width, mediaInfo.height)
            } else {
                IntSize(1920, 1080)
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            bitmap?.let {
                Canvas(modifier = Modifier.matchParentSize()) {
                    frameSizeCalculator.calculate(imageSize, size)
                    drawImage(
                        it,
                        dstOffset = frameSizeCalculator.dstOffset,
                        dstSize = frameSizeCalculator.dstSize,
                        filterQuality = FilterQuality.High,
                    )
                }
            }
            val trafficSpeed = playerState.value.trafficSpeed
            val showBufferOverlay = playerState.value.state == PlayState.BUFFERING ||
                (trafficSpeed.isNotBlank() && bitmap == null)

            when {
                playerState.value.state == PlayState.ERROR -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = "error icon",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(playerState.value.msg, color = MaterialTheme.colorScheme.primary)
                    }
                }

                showBufferOverlay -> {
                    ProgressIndicator(
                        Modifier.align(Alignment.Center),
                        progression = playerState.value.bufferProgression,
                        trafficSpeed = trafficSpeed,
                    )
                }

                bitmap == null -> {
                    if (!isControllerReady) {
                        ProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            text = "播放器正在加载",
                            trafficSpeed = trafficSpeed,
                        )
                    } else {
                        emptyShow(
                            modifier = Modifier.align(Alignment.Center),
                            title = "未加载到视频",
                            subtitle = "请检查网络连接",
                            showRefresh = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProgressIndicator(
    modifier: Modifier,
    text: String = "加载中...",
    progression: Float = -1f,
    trafficSpeed: String = "",
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator()
        // 对齐 TV：网速为主文案；没有速度时再显示百分比/加载中
        val label = when {
            trafficSpeed.isNotBlank() -> trafficSpeed
            progression in 0f..99.9f -> "%.0f%%".format(progression)
            else -> text
        }
        Text(
            label,
            style = TextStyle(
                color = Color.White,
                shadow = Shadow(
                    color = Color.Black,
                    offset = Offset(2f, 2f),
                    blurRadius = 6f
                ),
                fontWeight = FontWeight.Bold,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
            )
        )
    }
}


class FrameContainerSizeCalculator {
    var srcWidth = 0
    var srcHeight = 0
    var dstWidth = 0f
    var dstHeight = 0f
    var offsetX = 0f
    var offsetY = 0f

    var aspectRatio: String = ""
    val dstOffset: IntOffset get() = IntOffset(offsetX.roundToInt(), offsetY.roundToInt())
    val dstSize: IntSize get() = IntSize(dstWidth.roundToInt(), dstHeight.roundToInt())

    fun calculate(imageSize: IntSize, containerSize: Size) {
        srcWidth = if (imageSize.width > 0) imageSize.width else 1280
        srcHeight = if (imageSize.height > 0) imageSize.height else 720

        val containerWidth = containerSize.width
        val containerHeight = containerSize.height
        val containerRatio = if (containerHeight > 0) containerWidth / containerHeight else 16f / 9f

        // 根据选择的视频比例调整显示
        if (aspectRatio.isNotBlank() && aspectRatio.contains(":")) {
            val parts = aspectRatio.split(":")
            if (parts.size == 2) {
                val targetRatio = parts[0].toFloat() / parts[1].toFloat()
                // 使用目标比例重新计算
                if (targetRatio > containerRatio) {
                    dstWidth = containerWidth
                    dstHeight = containerWidth / targetRatio
                } else {
                    dstHeight = containerHeight
                    dstWidth = containerHeight * targetRatio
                }
                offsetX = (containerWidth - dstWidth) / 2
                offsetY = (containerHeight - dstHeight) / 2
                return
            }
        }

        // 计算原始宽高比（添加安全检查避免除零）
        val imageRatio = if (srcHeight > 0) srcWidth.toFloat() / srcHeight.toFloat() else 16f / 9f // 默认16:9

        // 根据比例决定缩放方向，避免拉伸
        if (imageRatio > containerRatio) {
            dstWidth = containerWidth
            dstHeight = containerWidth / imageRatio
            offsetY = (containerHeight - dstHeight) / 2
            offsetX = 0f
        } else {
            dstHeight = containerHeight
            dstWidth = containerHeight * imageRatio
            offsetX = (containerWidth - dstWidth) / 2
            offsetY = 0f
        }
    }
}



