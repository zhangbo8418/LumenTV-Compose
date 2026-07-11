package com.corner.ui.scene

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.corner.util.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("AnimatedNetworkImage")

/**
 * 网络图片；GIF 用 Skia Codec 逐帧播放，其它格式显示静态帧。
 * Codec 必须在主线程创建/使用（Skia native 非线程安全）。
 */
@Composable
fun AnimatedNetworkImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    errorContent: @Composable () -> Unit = {},
) {
    if (url.isBlank()) {
        errorContent()
        return
    }

    var codec by remember(url) { mutableStateOf<Codec?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        failed = false
        val previous = codec
        codec = null
        previous?.close()

        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                Http.get(url).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body.bytes()
                }.also { if (it.isEmpty()) error("empty body") }
            }.onFailure {
                log.warn("下载失败: {} ({})", url, it.message)
            }.getOrNull()
        }
        if (bytes == null) {
            failed = true
            return@LaunchedEffect
        }
        // 主线程创建 Codec，避免跨线程 SIGSEGV
        val loaded = runCatching {
            Codec.makeFromData(Data.makeFromBytes(bytes))
        }.onFailure {
            log.warn("解码失败: {} ({})", url, it.message)
        }.getOrNull()
        if (loaded == null) failed = true else codec = loaded
    }

    DisposableEffect(url) {
        onDispose {
            codec?.close()
            codec = null
        }
    }

    when {
        failed -> errorContent()
        codec == null -> Box(modifier)
        else -> GifOrStillImage(
            codec = codec!!,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            alignment = alignment,
        )
    }
}

@Composable
private fun GifOrStillImage(
    codec: Codec,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    alignment: Alignment,
) {
    val bitmap = remember(codec) {
        Bitmap().apply { allocPixels(codec.imageInfo) }
    }
    val frameCount = remember(codec) { codec.frameCount.coerceAtLeast(1) }
    val durationsNs = remember(codec) {
        LongArray(frameCount) { i ->
            codec.getFrameInfo(i).duration.coerceAtLeast(40) * 1_000_000L
        }
    }
    val totalDurationNs = remember(durationsNs) { durationsNs.sum().coerceAtLeast(1L) }

    var frameIndex by remember(codec) { mutableIntStateOf(0) }

    LaunchedEffect(codec) {
        if (frameCount <= 1) {
            frameIndex = 0
            return@LaunchedEffect
        }
        var start = -1L
        while (true) {
            withFrameNanos { now ->
                if (start < 0L) start = now
                val time = (now - start) % totalDurationNs
                var acc = 0L
                var idx = 0
                while (idx < frameCount) {
                    acc += durationsNs[idx]
                    if (acc > time) break
                    idx++
                }
                frameIndex = idx.coerceIn(0, frameCount - 1)
            }
        }
    }

    val imageBitmap = remember(codec, frameIndex) {
        runCatching {
            codec.readPixels(bitmap, frameIndex)
            bitmap.asComposeImageBitmap()
        }.getOrNull()
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = contentDescription,
            modifier = modifier.fillMaxSize(),
            contentScale = contentScale,
            alignment = alignment,
        )
    } else {
        Box(modifier)
    }
}
