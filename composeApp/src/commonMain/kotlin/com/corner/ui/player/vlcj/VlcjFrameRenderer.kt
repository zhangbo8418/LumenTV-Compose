package com.corner.ui.player.vlcj

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
import java.util.concurrent.atomic.AtomicReference
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

    /** 发布给 Compose 的帧及其底层 Bitmap（回收时需要对应关系） */
    private class PublishedFrame(val imageBitmap: ImageBitmap, val bitmap: Bitmap)

    // 最新帧：只放 AtomicReference，由 Compose withFrameNanos 拉取后再写入 MutableState
    private val latestFrame = AtomicReference<PublishedFrame?>(null)

    /**
     * UI 线程每帧调用。旧帧 Bitmap 只在这里回收：UI 拉取到新帧后，
     * 更旧的帧在同线程串行保证下不可能再被绘制，此时回收才安全。
     * 固定帧数窗口的延迟回收在慢机器（Win7/OpenGL）上会赶上正在绘制的帧，
     * 导致 Skiko makeFromBitmap 读已释放内存而 SIGSEGV。
     */
    fun peekVideoFrame(): ImageBitmap? {
        if (latestFrame.get() == null) return null
        synchronized(bitmapSwapLock) {
            // 必须在锁内重读：release/cleanup 在锁内置空并关闭旧帧，锁外读到的引用可能已失效
            val frame = latestFrame.get() ?: return null
            uiHeldBitmap = frame.bitmap
            recycleInFlight(exclude1 = frame.bitmap, exclude2 = currentBitmap)
            return frame.imageBitmap
        }
    }

    // 当前和待释放的 Bitmap
    private var currentBitmap: Bitmap? = null

    /** UI 线程当前持有（可能仍在绘制）的帧，任何路径都不得同步关闭它 */
    @Volatile
    private var uiHeldBitmap: Bitmap? = null

    /** 等待 UI 拉帧后回收的旧帧；VLC display 线程只入队，绝不在此队列上做回收 */
    private val inFlightBitmaps = ArrayDeque<Bitmap>()

    /** UI 长时间不拉帧（最小化/卡顿）时的丢帧阈值，防止 Bitmap 无限累积 */
    private val maxInFlight = 24
    
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

                        synchronized(bitmapSwapLock) {
                            if (isReleased || !renderEnabled) return
                            // UI 长时间不拉帧时丢弃新帧（不发布即安全），绝不在本线程回收旧帧
                            if (inFlightBitmaps.size >= maxInFlight) return
                            val bmp = bitmapPool.acquire(width, height)
                            bmp.installPixels(pixels)
                            currentBitmap?.let { inFlightBitmaps.addLast(it) }
                            currentBitmap = bmp
                            if (!bmp.isClosed) {
                                latestFrame.set(PublishedFrame(bmp.asComposeImageBitmap(), bmp))
                            }
                        }
                    } catch (e: Exception) {
                        log.error("渲染帧时发生错误", e)
                        latestFrame.set(null)
                        synchronized(bitmapSwapLock) {
                            // 只丢引用不 close：UI 可能仍在绘制，交给 Skiko cleaner 回收
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
    
    /** 回收所有待回收帧（排除 UI 正持有的与最新发布的）。必须在 bitmapSwapLock 内调用 */
    private fun recycleInFlight(exclude1: Bitmap?, exclude2: Bitmap?) {
        if (inFlightBitmaps.isEmpty()) return
        val iterator = inFlightBitmaps.iterator()
        while (iterator.hasNext()) {
            val bitmap = iterator.next()
            if (bitmap === exclude1 || bitmap === exclude2) continue
            iterator.remove()
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

    /** 清空画面，不销毁 Bitmap，避免 Skiko SIGSEGV */
    fun clearFrameSoft() {
        latestFrame.set(null)
    }

    /**
     * 清理帧渲染资源（用于画质切换等场景）
     */
    fun cleanup() {
        renderEnabled = false
        latestFrame.set(null)
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
        try {
            log.debug("=====开始释放帧渲染器资源=====")
            synchronized(bitmapSwapLock) {
                latestFrame.set(null)
                currentBitmap?.let { inFlightBitmaps.addLast(it) }
                currentBitmap = null
                // UI 可能仍持有最后画的一帧（FrameContainer 的 bitmap state），
                // 同步 close 会让退出瞬间的最后一次绘制读到已释放内存。
                // 这里只丢引用，交给 Skiko cleaner 在 GC 后回收（每次至多泄压一帧）。
                val held = uiHeldBitmap
                while (inFlightBitmaps.isNotEmpty()) {
                    val bitmap = inFlightBitmaps.removeFirst()
                    if (bitmap !== held && !bitmap.isClosed) bitmap.close()
                }
                uiHeldBitmap = null
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
