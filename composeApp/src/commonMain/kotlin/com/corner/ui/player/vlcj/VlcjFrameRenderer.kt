package com.corner.ui.player.vlcj

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.corner.ui.player.BitmapPool
import com.corner.util.thisLogger
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * VLCJ 帧渲染器
 * 
 * 职责：
 * - 管理视频帧的渲染回调
 * - 管理 Bitmap 池和帧缓冲
 * - 提供 Compose ImageBitmap 状态
 * 
 * 遵循单一职责原则（SRP），只负责帧渲染相关逻辑
 * 实现 AutoCloseable 接口，确保资源能够被正确清理
 */
class VlcjFrameRenderer(
    private val bitmapPool: BitmapPool = BitmapPool(3)
) : AutoCloseable {
    private val log = thisLogger()
    
    // 帧数据
    private var byteArray: ByteArray? = null
    private var info: ImageInfo? = null
    
    // Compose UI 状态
    val imageBitmapState: MutableState<ImageBitmap?> = mutableStateOf(null)
    
    // 当前和待释放的 Bitmap
    private var currentBitmap: Bitmap? = null
    /** Compose 可能仍在画这些帧，必须延迟回收，否则 Skiko RasterFromBitmap SIGSEGV */
    private val inFlightBitmaps = ArrayDeque<Bitmap>()
    private val maxInFlight = 6
    
    @Volatile
    private var isReleased = false

    @Volatile
    private var renderEnabled = true

    /** 仅保护 bitmap 交换；pause/resume 只用 volatile，避免与 VLC display 线程互锁 */
    private val bitmapSwapLock = Any()
    
    /**
     * 检查渲染器是否已释放
     */
    fun isReleased(): Boolean = isReleased
    
    /**
     * 创建 VLCJ 视频表面回调
     * 
     * @return CallbackVideoSurface 实例，需要设置到 MediaPlayer 的视频表面
     */
    fun createVideoSurface(): CallbackVideoSurface {
        return CallbackVideoSurface(
            object : BufferFormatCallback {
                private var lastPoolSize = -1
                private var lastWidth = -1
                private var lastHeight = -1
                
                /**
                 * 根据分辨率估算帧率
                 */
                private fun estimateFrameRate(width: Int, height: Int): Int {
                    val pixels = width * height
                    return when {
                        pixels >= 3_000_000 -> 60 // 高分辨率推高帧率（如2K/4K）
                        pixels >= 1_000_000 -> 30 // 主流1080p
                        else -> 24                // 标清或低码率
                    }
                }
                
                /**
                 * 根据分辨率和帧率动态调整 BitmapPool 大小
                 */
                private fun adjustBitmapPoolSize(width: Int, height: Int) {
                    if (width == lastWidth && height == lastHeight) return
                    
                    val resolutionFactor = (width * height) / 1_000_000f
                    val frameRate = estimateFrameRate(width, height)
                    val poolSize = (frameRate * resolutionFactor).roundToInt().coerceIn(2, 12)
                    
                    if (poolSize != lastPoolSize) {
                        bitmapPool.setMaxSize(poolSize)
                        log.info("根据 ${frameRate}fps @ ${width}x$height，调整 BitmapPool 大小为 $poolSize")
                        lastPoolSize = poolSize
                    }
                    
                    lastWidth = width
                    lastHeight = height
                }
                
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    info = ImageInfo.makeN32(sourceWidth, sourceHeight, ColorAlphaType.OPAQUE)
                    adjustBitmapPoolSize(width = sourceWidth, height = sourceHeight)
                    return RV32BufferFormat(sourceWidth, sourceHeight)
                }
                
                override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {
                    // 不需要处理
                }
                
                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {
                    byteArray = ByteArray(buffers[0].limit())
                }
            },
            object : RenderCallback {
                override fun lock(mediaPlayer: MediaPlayer?) {
                    // 不需要处理
                }
                
                override fun display(
                    mediaPlayer: MediaPlayer,
                    nativeBuffers: Array<out ByteBuffer>,
                    bufferFormat: BufferFormat,
                    displayWidth: Int,
                    displayHeight: Int
                ) {
                    // 先无锁快速退出，避免与 pauseRendering 死锁卡死 UI
                    if (isReleased || !renderEnabled) return
                    try {
                        val width = bufferFormat.width
                        val height = bufferFormat.height
                        val byteBuffer = nativeBuffers[0]
                        val pixels = byteArray
                        if (byteBuffer.limit() <= 0 || pixels == null) return

                        byteBuffer.get(pixels)
                        byteBuffer.rewind()

                        if (isReleased || !renderEnabled) return

                        val bmp = bitmapPool.acquire(width, height)
                        bmp.installPixels(pixels)

                        synchronized(bitmapSwapLock) {
                            if (isReleased || !renderEnabled) {
                                bitmapPool.release(bmp)
                                return
                            }
                            currentBitmap?.let { old ->
                                inFlightBitmaps.addLast(old)
                                drainInFlight(keep = maxInFlight)
                            }
                            currentBitmap = bmp
                            if (!bmp.isClosed) {
                                publishFrame(bmp.asComposeImageBitmap())
                            }
                        }
                    } catch (e: Exception) {
                        log.error("渲染帧时发生错误", e)
                        publishFrame(null)
                        synchronized(bitmapSwapLock) {
                            currentBitmap = null
                        }
                    }
                }
                
                override fun unlock(mediaPlayer: MediaPlayer?) {
                    // 不需要处理
                }
            },
            true,
            VideoSurfaceAdapters.getVideoSurfaceAdapter()
        )
    }
    
    /** 只回收超出保留窗口的旧帧 */
    private fun drainInFlight(keep: Int) {
        while (inFlightBitmaps.size > keep) {
            val bitmap = inFlightBitmaps.removeFirst()
            if (!bitmap.isClosed) {
                bitmapPool.release(bitmap)
            }
        }
    }

    /**
     * 换集/换线前暂停渲染。只关开关，不抢锁（避免与 display 死锁卡 UI）。
     */
    fun pauseRendering() {
        renderEnabled = false
    }

    fun resumeRendering() {
        renderEnabled = true
    }

    /**
     * VLC display 回调在非 Compose 线程写 MutableState，必须包进可变快照，
     * 否则离开页 NavHost 动画读帧时会抛 snapshot 未 apply 异常。
     */
    private fun publishFrame(bitmap: ImageBitmap?) {
        Snapshot.withMutableSnapshot {
            imageBitmapState.value = bitmap
        }
    }

    /** 清空画面，不销毁 Bitmap，避免 Skiko SIGSEGV */
    fun clearFrameSoft() {
        publishFrame(null)
    }

    /**
     * 清理帧渲染资源（用于画质切换等场景）
     */
    fun cleanup() {
        renderEnabled = false
        publishFrame(null)
        synchronized(bitmapSwapLock) {
            currentBitmap?.let { inFlightBitmaps.addLast(it) }
            currentBitmap = null
        }
        log.debug("帧渲染器资源已清理（软清理）")
    }
    
    /**
     * 释放渲染器所有资源
     * 实现 AutoCloseable 接口，支持 use 块自动清理
     */
    override fun close() {
        release()
    }
    
    /**
     * 释放渲染器所有资源
     */
    fun release() {
        if (isReleased) {
            log.debug("帧渲染器已释放，跳过重复释放")
            return
        }
        
        renderEnabled = false
        isReleased = true
        publishFrame(null)
        try {
            log.debug("=====开始释放帧渲染器资源=====")
            synchronized(bitmapSwapLock) {
                currentBitmap?.let { inFlightBitmaps.addLast(it) }
                currentBitmap = null
                while (inFlightBitmaps.isNotEmpty()) {
                    val bitmap = inFlightBitmaps.removeFirst()
                    if (!bitmap.isClosed) bitmap.close()
                }
            }
            bitmapPool.clear()
            byteArray = null
            info = null
            log.debug("=====帧渲染器资源释放成功=====")
        } catch (e: Throwable) {
            log.error("释放帧渲染器资源时出错：", e)
        }
    }
}
