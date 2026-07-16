package com.corner.ui.player.vlcj

import com.corner.ui.scene.SnackBar
import androidx.compose.ui.graphics.ImageBitmap
import com.corner.database.entity.History
import com.corner.ui.player.BitmapPool
import com.corner.ui.player.PlayerController
import com.corner.ui.player.PlayerLifecycleManager
import com.corner.ui.player.VodPlaybackHost
import com.corner.ui.player.frame.FramePlayerController
import com.corner.ui.player.frame.FrameRenderer
import com.corner.util.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.player.base.MediaPlayer
import kotlin.math.max

/**
 * VLCJ 帧控制器
 * 
 * 职责：
 * - 协调 VlcjController 和 VlcjFrameRenderer
 * - 管理历史记录收集
 * - 提供便捷的加载和释放方法
 * 
 * 实现 AutoCloseable 接口，确保资源能够被正确清理
 */
class VlcjFrameController(
    private val controller: VlcjController = VlcjController(),
    private val bitmapPool: BitmapPool = BitmapPool(3)
) : FramePlayerController, FrameRenderer, PlayerController by controller, AutoCloseable {
    private val log = thisLogger()
    
    // 委托给独立的帧渲染器
    private val frameRenderer: VlcjFrameRenderer = VlcjFrameRenderer(bitmapPool)
    private var videoSurface: uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface? = null
    
    // 视频帧由 AtomicReference 持有，Compose 侧 withFrameNanos 拉取
    override fun peekVideoFrame(): ImageBitmap? = frameRenderer.peekVideoFrame()
    
    @Volatile
    private var isReleased = false
    override fun isReleased(): Boolean = isReleased
    
    private var historyCollectJob: Job? = null
    private val _size = MutableStateFlow(0 to 0)
    override val size = _size.asStateFlow()
    
    private val _bytes = MutableStateFlow<ByteArray?>(null)
    override val bytes = _bytes.asStateFlow()

    init {
        controller.attachFrame(this)
    }

    fun bindHost(host: VodPlaybackHost) {
        controller.bindHost(host)
    }

    fun unbindHost(host: VodPlaybackHost) {
        controller.unbindHost(host)
    }

    fun vlcController(): VlcjController = controller

    /**
     * 加载视频URL。
     * 该方法会异步加载视频URL。
     * 若加载过程中发生异常，会记录错误日志。
     * 加载完成后，会自动播放视频并根据历史记录设置播放速度和位置。
     * 若历史记录中没有位置信息，会默认从视频开头开始播放。
     *
     * @param url 视频URL字符串。
     * @return 返回当前的FrameRenderer实例，用于链式调用。
     */

    override fun load(url: String): PlayerController {
        controller.scope.launch {
            log.info("load - 开始加载视频: {}", url)
            controller.loadURL(url, 15_000L)
            delay(300)
            speed(controller.history.value?.speed?.toFloat() ?: 1f)
            log.debug("load - 播放历史位置: {}", controller.history.value?.position)
        }
        return controller
    }

    fun clearVideoFrame() {
        frameRenderer.clearFrameSoft()
    }

    /** 换集/换源：对齐 TV stopPlaybackForRefresh（帧在主线程清，避免 snapshot 异常） */
    suspend fun stopPlaybackForRefresh() {
        withContext(Dispatchers.Swing) {
            frameRenderer.pauseRendering()
            frameRenderer.clearFrameSoft()
            detachVideoSurface()
        }
        controller.stopPlaybackForRefresh()
    }

    /**
     * Composition dispose 安全：只停渲染/清帧，不调 libvlc、不 detach surface。
     */
    fun beginLeavePlayback() {
        frameRenderer.pauseRendering()
        frameRenderer.clearFrameSoft()
    }

    /** 离开详情：对齐 TV Service.suspend，主线程清帧 + 异步 pause */
    suspend fun endPlayback() {
        withContext(Dispatchers.Swing) {
            frameRenderer.pauseRendering()
            frameRenderer.clearFrameSoft()
            detachVideoSurface()
        }
        controller.endPlaybackAndClearFrame()
    }

    fun endPlaybackSync() {
        beginLeavePlayback()
        // 同步路径也不再 detach（易与 Compose 退场动画竞态）；detach 交给异步 endPlayback
        controller.endPlayback()
    }

    fun resumeVideoRendering() {
        attachVideoSurface()
        frameRenderer.resumeRendering()
    }

    fun stopPlaybackForRefreshSync(): Int {
        return controller.stopPlaybackForRefresh()
    }

    fun invalidatePendingLoads(): Int = stopPlaybackForRefreshSync()

    fun currentLoadGeneration(): Int = controller.currentLoadGeneration()

    private fun detachVideoSurface() {
        runCatching { controller.player?.videoSurface()?.set(null) }
    }

    private fun attachVideoSurface() {
        if (isReleased) return
        val surface = videoSurface ?: return
        runCatching {
            controller.player?.videoSurface()?.set(surface)
        }.onFailure { log.warn("重新绑定视频表面失败: {}", it.message) }
    }

    fun prepareForEpisodeSwitch() {
        clearVideoFrame()
        // 保持暂停；由后续 loadURL → resumeVideoRendering 恢复
        controller.markBufferingForSwitch()
    }

    override fun vlcjFrameInit() {
        try {
            // 单例复用：已有 player 时只重绑 surface，不重建 factory
            if (controller.isPlayerInstanceReady() && !isReleased) {
                ensureVideoSurface()
                return
            }
            if (controller.lifecycleManagerOrNull() == null) {
                val lifecycleManager = PlayerLifecycleManager(controller)
                controller.setLifecycleManager(lifecycleManager)
            }
            controller.onPlayerRecreated = { _ ->
                attachVideoSurface()
            }
            controller.init()
            ensureVideoSurface()
            isReleased = false
        } catch (e: Exception) {
            log.error("视频表面初始化失败", e)
            SnackBar.postMsg("视频表面初始化失败,请尝试重启软件或去GITHUB反馈！", type = SnackBar.MessageType.ERROR)
        }
    }

    private fun ensureVideoSurface() {
        if (videoSurface == null) {
            videoSurface = frameRenderer.createVideoSurface()
        }
        controller.player?.videoSurface()?.set(videoSurface)
        frameRenderer.resumeRendering()
    }
    
    /**
     * 清理bitmap资源
     */
    fun cleanupBeforeQualityChange() {
        frameRenderer.cleanup()
        
        // 临时禁用渲染回调，防止在切换过程中访问旧资源
        val player = controller.player
        player?.videoSurface()?.set(null)
    }

    @Suppress("unused")
    fun isPlaying(): Boolean {
        return !isReleased && controller.player?.status()?.isPlaying == true
    }

    fun setStartEnd(opening: Long, ending: Long) {
        controller.setStartEnding(opening, ending)
    }

    fun setControllerHistory(history: History) {
        controller.scope.launch {
            controller.history.emit(history)
        }
        if (historyCollectJob != null) return
        historyCollectJob = controller.scope.launch {
            delay(10)
            controller.history.collect {
                if (it != null) {
                    controller.currentHost()?.updateHistory(it)
                }
            }
        }
    }

    fun getControllerHistory(): History? {
        return controller.history.value
    }

    fun doWithHistory(func: (History) -> History) {
        val current = controller.history.value ?: return
        controller.history.value = func(current)
    }

    @Suppress("unused")
    fun getPlayer(): MediaPlayer? {
        return controller.player
    }

    /**
     * 关闭控制器，释放所有资源
     * 实现 AutoCloseable 接口，支持 use 块自动清理
     */
    fun applySubtitle(url: String) {
        controller.applySubtitle(url)
    }

    fun setSubtitleUrl(url: String) {
        controller.setSubtitleUrl(url)
    }

    override fun close() {
        release()
    }

    override fun release() {
        if (isReleased) {
            log.debug("播放器已释放，跳过重复释放")
            return
        }
    
        synchronized(this) {
            if (isReleased) return
            isReleased = true
    
            try {
                log.debug("=====开始释放播放器资源=====")
                    
                // 先释放帧渲染器资源
                frameRenderer.release()
                    
                // 关闭 BitmapPool
                bitmapPool.close()
                    
                // 1. 检查播放器是否存在
                val player = controller.player
                if (player == null) {
                    log.debug("播放器对象为null，无需释放")
                    return
                }
    
                // 2. 安全停止播放
                try {
                    player.controls()?.stopAsync()
                    player.videoSurface()?.set(null)
                } catch (e: Exception) {
                    log.warn("停止播放器时出错：", e)
                }
    
                // 3. 延迟确保VLC内部清理
                Thread.sleep(100)
    
                // 4. 安全释放
                try {
                    player.release()
                } catch (e: Exception) {
                    log.warn("释放播放器时出错：", e)
                }
    
                controller.player = null
    
            } catch (e: Throwable) {
                log.error("释放播放器资源时出错：", e)
            } finally {
                // 清理所有引用
                historyCollectJob?.cancel()
            }
        }
    }

    override fun hasPlayer(): Boolean {
        return controller.player != null
    }
}