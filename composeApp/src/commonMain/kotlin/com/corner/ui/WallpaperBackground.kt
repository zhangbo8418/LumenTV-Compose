package com.corner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.corner.catvodcore.viewmodel.GlobalAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import javax.imageio.ImageIO

private val log = LoggerFactory.getLogger("WallpaperBackground")

@Composable
fun WallpaperBackground(modifier: Modifier = Modifier) {
    val path by GlobalAppState.wallpaperPath.collectAsState()
    if (path.isNullOrBlank()) return

    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path!!)
                if (!file.isFile || file.length() <= 0L) return@runCatching null
                ImageIO.read(file)?.toComposeImageBitmap()
            }.onFailure {
                log.warn("壁纸解码失败: {} ({})", path, it.message)
            }.getOrNull()
        }
    }

    val image = bitmap ?: return

    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = image,
            contentDescription = "壁纸",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // 轻微压暗，保证前景文字可读
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    }
}
