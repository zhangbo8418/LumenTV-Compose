package com.corner.ui.live

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("LiveChannelLogo")

/**
 * 直播频道 Logo：走 [LiveLogoCache]（内存 + 磁盘），避免每次重新拉取。
 */
@Composable
fun LiveChannelLogo(
    url: String,
    fallbackText: String,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(url) { mutableStateOf(LiveLogoCache.getCached(url)) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (!url.startsWith("http")) {
            failed = true
            return@LaunchedEffect
        }
        LiveLogoCache.getCached(url)?.let {
            bitmap = it
            failed = false
            return@LaunchedEffect
        }
        failed = false
        val loaded = withContext(Dispatchers.IO) {
            runCatching { LiveLogoCache.load(url) }
                .onFailure { log.warn("logo load failed: {}", url, it) }
                .getOrNull()
        }
        bitmap = loaded
        failed = loaded == null
    }

    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = fallbackText,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (failed || !url.startsWith("http")) {
            Text(
                text = fallbackText.take(1),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
