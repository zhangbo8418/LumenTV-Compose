package com.corner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
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
import com.corner.service.player.PlayerType
import com.corner.ui.player.PlayState
import com.corner.ui.player.frame.FrameContainer
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
    onPlaybackError: () -> Unit = {},
    onPrevChannel: () -> Unit = {},
    onNextChannel: () -> Unit = {},
    /** null 表示由外层接管点击；默认点击切换播放/暂停 */
    onClick: (() -> Unit)? = null,
    togglePlayOnClick: Boolean = true,
) {
    val controller = remember { LiveFrameController() }
    val focus = remember { FocusRequester() }
    val playerState = controller.state.collectAsState()

    LaunchedEffect(playerState.value.state) {
        if (playerState.value.state == PlayState.ERROR) {
            onPlaybackError()
        }
    }

    DisposableEffect(Unit) {
        if (useInternalPlayer) {
            controller.vlcjFrameInit()
        }
        onDispose {
            controller.release()
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
            else -> false
        }
    }

    if (useInternalPlayer && playUrl.isNotBlank()) {
        LaunchedEffect(playUrl, playHeaders) {
            controller.load(playUrl, playHeaders)
            focus.requestFocus()
        }
        Box(modifier.background(Color.Black)) {
            FrameContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .focusable()
                    .focusRequester(focus)
                    .then(keyModifier),
                controller = controller,
                onClick = {
                    when {
                        onClick != null -> onClick()
                        togglePlayOnClick -> controller.togglePlayStatus()
                    }
                },
            )
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
