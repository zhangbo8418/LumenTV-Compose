package com.corner.ui.player.frame

import androidx.compose.ui.graphics.ImageBitmap
import com.corner.ui.player.PlayerController

interface FramePlayerController : PlayerController, FrameRenderer {
    /** 最新视频帧（AtomicReference，供 Compose 在 withFrameNanos 中拉取，勿跨线程写 MutableState） */
    fun peekVideoFrame(): ImageBitmap?
    fun hasPlayer(): Boolean
    fun isReleased(): Boolean
    fun release()
}
