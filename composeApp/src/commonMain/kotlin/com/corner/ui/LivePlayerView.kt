package com.corner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.zIndex
import com.corner.service.player.PlayerType
import com.corner.ui.player.CenterPlayOverlay
import com.corner.ui.player.PlayState
import com.corner.ui.player.frame.FrameContainer
import com.corner.ui.player.shouldShowCenterPlay
import com.corner.ui.player.vlcj.LiveFrameController
import com.corner.util.play.Play
import com.corner.util.settings.SettingStore
import com.corner.util.settings.SettingType
import com.corner.util.settings.getPlayerSetting

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LivePlayerView(
    playUrl: String,
    playHeaders: Map<String, String> = emptyMap(),
    channelName: String,
    useInternalPlayer: Boolean,
    modifier: Modifier = Modifier,
    /** 外层持有时可在手势层之上画暂停按钮；为 null 时组件内部自建并释放 */
    controller: LiveFrameController? = null,
    onPlaybackError: () -> Unit = {},
    onPrevChannel: () -> Unit = {},
    onNextChannel: () -> Unit = {},
    /** null 表示由外层接管点击；默认点击切换播放/暂停 */
    onClick: (() -> Unit)? = null,
    togglePlayOnClick: Boolean = true,
    /** 由外层在手势层之上绘制暂停按钮时设为 false */
    showCenterPlay: Boolean = true,
) {
    val ownedController = remember { LiveFrameController() }
    val activeController = controller ?: ownedController
    val focus = remember { FocusRequester() }
    val playerState by activeController.state.collectAsState()

    LaunchedEffect(playerState.state) {
        if (playerState.state == PlayState.ERROR) {
            onPlaybackError()
        }
    }

    DisposableEffect(activeController, useInternalPlayer) {
        if (useInternalPlayer) {
            activeController.vlcjFrameInit()
        }
        onDispose {
            // 仅释放内部自建的控制器；外层传入时由外层负责释放
            if (controller == null) {
                activeController.release()
            }
        }
    }

    val keyModifier = Modifier.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
            Key.DirectionUp, Key.ChannelUp -> {
                onPrevChannel()
                true
            }
            Key.DirectionDown, Key.ChannelDown -> {
                onNextChannel()
                true
            }
            Key.Spacebar, Key.Enter -> {
                activeController.togglePlayStatus()
                true
            }
            else -> false
        }
    }

    if (useInternalPlayer && playUrl.isNotBlank()) {
        LaunchedEffect(playUrl, playHeaders) {
            activeController.load(playUrl, playHeaders)
            focus.requestFocus()
        }
        Box(modifier.background(Color.Black)) {
            FrameContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .focusable()
                    .focusRequester(focus)
                    .then(keyModifier),
                controller = activeController,
                onClick = {
                    when {
                        onClick != null -> onClick()
                        togglePlayOnClick -> activeController.togglePlayStatus()
                    }
                },
            )
            if (showCenterPlay) {
                CenterPlayOverlay(
                    visible = playerState.shouldShowCenterPlay(),
                    onPlay = { activeController.togglePlayStatus() },
                    modifier = Modifier.align(Alignment.Center).zIndex(2f),
                )
            }
        }
    } else if (playUrl.isNotBlank()) {
        LaunchedEffect(playUrl) {
            Play.start(playUrl, channelName)
        }
        Box(modifier.background(Color.Black))
    }
}

@Composable
fun rememberUseInternalPlayer(): Boolean {
    return SettingStore.getSettingItem(SettingType.PLAYER).getPlayerSetting().first() == PlayerType.Innie.id
}
