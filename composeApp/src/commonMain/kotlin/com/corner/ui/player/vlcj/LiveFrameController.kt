package com.corner.ui.player.vlcj

import com.corner.ui.player.BitmapPool
import com.corner.ui.player.PlayerController
import com.corner.ui.player.PlayerLifecycleManager
import com.corner.ui.player.frame.FramePlayerController
import com.corner.ui.player.frame.FrameRenderer
import com.corner.ui.scene.SnackBar
import com.corner.util.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import javax.swing.SwingUtilities

class LiveFrameController(
    private val controller: LiveVlcjController = LiveVlcjController(),
    private val bitmapPool: BitmapPool = BitmapPool(3),
) : FramePlayerController, FrameRenderer, PlayerController by controller {
    private val log = thisLogger()
    private val frameRenderer = VlcjFrameRenderer(bitmapPool)

    /** 与点播一致：强引用 CallbackVideoSurface，避免 JNA 回调被 GC */
    private var videoSurface: CallbackVideoSurface? = null

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
        if (!released) {
            bindVideoSurfaceOnUi("endChannelSwitch")
            frameRenderer.resumeRendering()
        }
    }

    override fun vlcjFrameInit() {
        try {
            if (controller.isPlayerInstanceReady() && !released) {
                bindVideoSurfaceOnUi("rebind")
                return
            }
            val lifecycleManager = PlayerLifecycleManager(controller)
            controller.setLifecycleManager(lifecycleManager)
            // 必须在 Swing 线程绑表面：后台线程 set(null)/set(surface) 会导致后续「有声无画」
            controller.onBeforePrepare = {
                if (!released) {
                    bindVideoSurfaceOnUi("beforePrepare")
                    frameRenderer.resumeRendering()
                }
            }
            controller.init()
            bindVideoSurfaceOnUi("init")
            released = false
        } catch (e: Exception) {
            log.error("直播视频表面初始化失败", e)
            SnackBar.postMsg("直播播放器初始化失败", type = SnackBar.MessageType.ERROR)
        }
    }

    private fun bindVideoSurfaceOnUi(reason: String) {
        val bind = {
            if (videoSurface == null) {
                videoSurface = frameRenderer.createVideoSurface()
            }
            val surface = videoSurface
            val player = controller.player
            if (surface != null && player != null) {
                // 不要 set(null)：在非主线程摘表面会弄断 callback，后续 HEVC 只剩音频
                runCatching { player.videoSurface()?.set(surface) }
                    .onFailure { log.warn("绑定视频表面失败 ({}): {}", reason, it.message) }
                frameRenderer.resumeRendering()
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            bind()
        } else {
            runBlocking(Dispatchers.Swing) { bind() }
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
                videoSurface = null
                Thread.sleep(100)
                controller.dispose()
                controller.player = null
            } catch (e: Throwable) {
                log.error("释放直播播放器失败", e)
            }
        }
    }
}
