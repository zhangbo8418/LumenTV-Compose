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
 * ImageBitmap 是对 Skia Bitmap 的薄包装：过早池化复用并 installPixels 会改写
 * Compose 仍可能绘制的帧，表现为「上一频道与当前频道画面闪烁」。
 */
class VlcjFrameRenderer(
    private val bitmapPool: BitmapPool = BitmapPool(3)
) : AutoCloseable {
    private val log = thisLogger()

    private var byteArray: ByteArray? = null
    private var info: ImageInfo? = null

    private class PublishedFrame(
        val imageBitmap: ImageBitmap,
        val bitmap: Bitmap,
        val epoch: Int,
    )

    private val latestFrame = AtomicReference<PublishedFrame?>(null)

    private var currentBitmap: Bitmap? = null

    @Volatile
    private var uiHeldBitmap: Bitmap? = null

    /** 最近 UI 拉过的帧，禁止入池，覆盖 GraphicsLayer 延迟绘制窗口 */
    private val recentlyShown = ArrayDeque<Bitmap>(8)
    private val recentlyShownCapacity = 8

    private val inFlightBitmaps = ArrayDeque<Bitmap>()
    private val maxInFlight = 24

    /** 换台代数：clear 后旧 display/peek 忽略，直到 resume 后重新发布 */
    @Volatile
    private var publishEpoch = 0

    @Volatile
    private var isReleased = false

    @Volatile
    private var renderEnabled = true

    private val bitmapSwapLock = Any()

    fun isReleased(): Boolean = isReleased

    /**
     * UI 线程每帧调用。旧帧 Bitmap 只在这里回收。
     */
    fun peekVideoFrame(): ImageBitmap? {
        if (latestFrame.get() == null) return null
        synchronized(bitmapSwapLock) {
            val frame = latestFrame.get() ?: return null
            if (frame.epoch != publishEpoch) return null
            rememberUiHeld(frame.bitmap)
            recycleInFlightSafe()
            return frame.imageBitmap
        }
    }

    private fun rememberUiHeld(bitmap: Bitmap) {
        uiHeldBitmap = bitmap
        recentlyShown.removeAll { it === bitmap }
        recentlyShown.addLast(bitmap)
        while (recentlyShown.size > recentlyShownCapacity) {
            recentlyShown.removeFirst()
        }
    }

    private fun recycleInFlightSafe() {
        if (inFlightBitmaps.isEmpty()) return
        val iterator = inFlightBitmaps.iterator()
        while (iterator.hasNext()) {
            val bitmap = iterator.next()
            if (bitmap === currentBitmap || bitmap === uiHeldBitmap) continue
            if (recentlyShown.any { it === bitmap }) continue
            iterator.remove()
            if (!bitmap.isClosed) {
                bitmapPool.release(bitmap)
            }
        }
    }

    fun createVideoSurface(): CallbackVideoSurface {
        return CallbackVideoSurface(
            object : BufferFormatCallback {
                private var lastPoolSize = -1
                private var lastWidth = -1
                private var lastHeight = -1

                private fun estimateFrameRate(width: Int, height: Int): Int {
                    val pixels = width * height
                    return when {
                        pixels >= 3_000_000 -> 60
                        pixels >= 1_000_000 -> 30
                        else -> 24
                    }
                }

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
                }

                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {
                    byteArray = ByteArray(buffers[0].limit())
                }
            },
            object : RenderCallback {
                override fun lock(mediaPlayer: MediaPlayer?) {
                }

                override fun display(
                    mediaPlayer: MediaPlayer,
                    nativeBuffers: Array<out ByteBuffer>,
                    bufferFormat: BufferFormat,
                    displayWidth: Int,
                    displayHeight: Int
                ) {
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
                            if (inFlightBitmaps.size >= maxInFlight) return
                            val epoch = publishEpoch
                            val bmp = bitmapPool.acquire(width, height)
                            bmp.installPixels(pixels)
                            currentBitmap?.let { inFlightBitmaps.addLast(it) }
                            currentBitmap = bmp
                            if (!bmp.isClosed) {
                                latestFrame.set(PublishedFrame(bmp.asComposeImageBitmap(), bmp, epoch))
                            }
                        }
                    } catch (e: Exception) {
                        log.error("渲染帧时发生错误", e)
                        latestFrame.set(null)
                        synchronized(bitmapSwapLock) {
                            currentBitmap = null
                        }
                    }
                }

                override fun unlock(mediaPlayer: MediaPlayer?) {
                }
            },
            true,
            VideoSurfaceAdapters.getVideoSurfaceAdapter()
        )
    }

    fun pauseRendering() {
        renderEnabled = false
    }

    fun resumeRendering() {
        renderEnabled = true
    }

    /**
     * 清空画面（换台/换集）：置空发布帧并提升代数，丢弃旧频道帧。
     * 不 close 底层 Bitmap，避免 Skiko SIGSEGV。
     */
    fun clearFrameSoft() {
        synchronized(bitmapSwapLock) {
            publishEpoch++
            latestFrame.set(null)
            currentBitmap?.let { inFlightBitmaps.addLast(it) }
            currentBitmap = null
            uiHeldBitmap = null
            recentlyShown.clear()
        }
    }

    fun cleanup() {
        renderEnabled = false
        clearFrameSoft()
        log.debug("帧渲染器资源已清理（软清理）")
    }

    override fun close() {
        release()
    }

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
                publishEpoch++
                latestFrame.set(null)
                currentBitmap?.let { inFlightBitmaps.addLast(it) }
                currentBitmap = null
                val held = uiHeldBitmap
                while (inFlightBitmaps.isNotEmpty()) {
                    val bitmap = inFlightBitmaps.removeFirst()
                    if (bitmap !== held && !bitmap.isClosed) bitmap.close()
                }
                recentlyShown.clear()
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
