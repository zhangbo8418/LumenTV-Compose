package com.corner.ui.player.frame

import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.ImageBitmap
import com.corner.ui.player.PlayerController

interface FramePlayerController : PlayerController, FrameRenderer {
    val imageBitmapState: MutableState<ImageBitmap?>
    fun hasPlayer(): Boolean
    fun isReleased(): Boolean
    fun release()
}
