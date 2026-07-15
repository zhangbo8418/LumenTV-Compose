package com.corner.ui.player.vlcj

import com.corner.ui.player.BitmapPool
import com.corner.ui.player.PlayerController
import com.corner.ui.player.PlayerLifecycleManager
import com.corner.ui.player.frame.FramePlayerController
import com.corner.ui.player.frame.FrameRenderer
import com.corner.ui.scene.SnackBar
import com.corner.util.thisLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LiveFrameController(
    private val controller: LiveVlcjController = LiveVlcjController(),
    private val bitmapPool: BitmapPool = BitmapPool(3),
) : FramePlayerController, FrameRenderer, PlayerController by controller {
    private val log = thisLogger()
    private val frameRenderer = VlcjFrameRenderer(bitmapPool)

    override fun peekVideoFrame() = frameRenderer.peekVideoFrame()

    @Volatile
    private var released = false

    private val _size = MutableStateFlow(0 to 0)
    override val size = _size.asStateFlow()

    private val _bytes = MutableStateFlow<ByteArray?>(null)
    override val bytes = _bytes.asStateFlow()

    override fun load(url: String): PlayerController {
        beginChannelSwitch()
        controller.switchChannel(url, emptyMap()) { endChannelSwitch() }
        return controller
    }

    fun load(url: String, headers: Map<String, String>): PlayerController {
        beginChannelSwitch()
        controller.switchChannel(url, headers) { endChannelSwitch() }
        return controller
    }

    /** 换台：先停渲染+清画面，避免旧帧 Bitmap 被池复用后与新频道交替闪烁 */
    private fun beginChannelSwitch() {
        frameRenderer.pauseRendering()
        frameRenderer.clearFrameSoft()
    }

    private fun endChannelSwitch() {
        if (!released) frameRenderer.resumeRendering()
    }

    override fun vlcjFrameInit() {
        try {
            val lifecycleManager = PlayerLifecycleManager(controller)
            controller.setLifecycleManager(lifecycleManager)
            controller.init()
            val videoSurface = frameRenderer.createVideoSurface()
            controller.player?.videoSurface()?.set(videoSurface)
            frameRenderer.resumeRendering()
            released = false
        } catch (e: Exception) {
            log.error("直播视频表面初始化失败", e)
            SnackBar.postMsg("直播播放器初始化失败", type = SnackBar.MessageType.ERROR)
        }
    }

    override fun isReleased(): Boolean = released

    override fun hasPlayer(): Boolean = controller.player != null

    override fun release() {
        if (released) return
        synchronized(this) {
            if (released) return
            released = true
            try {
                frameRenderer.release()
                bitmapPool.close()
                controller.player?.controls()?.stop()
                controller.player?.videoSurface()?.set(null)
                Thread.sleep(100)
                controller.dispose()
                controller.player = null
            } catch (e: Throwable) {
                log.error("释放直播播放器失败", e)
            }
        }
    }
}
