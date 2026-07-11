package com.corner.ui.scene

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import com.seiko.imageloader.ImageLoader
import com.seiko.imageloader.LocalImageLoader
import com.seiko.imageloader.model.ImageAction
import com.seiko.imageloader.model.ImageEvent
import com.seiko.imageloader.model.ImageResult
import com.seiko.imageloader.toPainter
import com.seiko.imageloader.ui.AutoSizeBox

/**
 * 支持自定义加载指示器的 AutoSizeImage 组件
 */
@Composable
fun AutoSizeImageWithLoading(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    imageLoader: ImageLoader = LocalImageLoader.current,
    placeholderPainter: (@Composable () -> Painter)? = null,
    errorPainter: (@Composable () -> Painter)? = null,
    loadingIndicator: @Composable (() -> Unit)? = null,
    isOnlyPostFirstEvent: Boolean = true,
) {
    if (url.isBlank()) return
    var isLoading by remember(url) { mutableStateOf(true) }

    Box(modifier = modifier) {
        AutoSizeBox(
            url = url,
            imageLoader = imageLoader,
            contentAlignment = alignment,
            isOnlyPostFirstEvent = isOnlyPostFirstEvent
        ) { action ->
            when (action) {
                is ImageEvent -> {
                    isLoading = true
                    placeholderPainter?.invoke()?.let { painter ->
                        Image(
                            painter = painter,
                            contentDescription = contentDescription,
                            modifier = Modifier.fillMaxSize(),
                            alignment = alignment,
                            contentScale = contentScale,
                            alpha = alpha,
                            colorFilter = colorFilter
                        )
                    }
                }
                is ImageAction.Success -> {
                    isLoading = false
                    val painter = successPainter(action) ?: return@AutoSizeBox
                    Image(
                        painter = painter,
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        alignment = alignment,
                        contentScale = contentScale,
                        alpha = alpha,
                        colorFilter = colorFilter
                    )
                }
                is ImageResult.OfError, is ImageResult.OfSource -> {
                    isLoading = false
                    errorPainter?.invoke()?.let { painter ->
                        Image(
                            painter = painter,
                            contentDescription = contentDescription,
                            modifier = Modifier.fillMaxSize(),
                            alignment = alignment,
                            contentScale = contentScale,
                            alpha = alpha,
                            colorFilter = colorFilter
                        )
                    }
                }
                else -> Unit
            }
        }

        if (isLoading && loadingIndicator != null) {
            loadingIndicator()
        }
    }
}

@Composable
fun AutoSizeImageWithLoading(
    resId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    imageLoader: ImageLoader = LocalImageLoader.current,
    placeholderPainter: (@Composable () -> Painter)? = null,
    errorPainter: (@Composable () -> Painter)? = null,
    loadingIndicator: @Composable (() -> Unit)? = null,
    isOnlyPostFirstEvent: Boolean = true,
) {
    var isLoading by remember(resId) { mutableStateOf(true) }

    Box(modifier = modifier) {
        AutoSizeBox(
            resId = resId,
            imageLoader = imageLoader,
            contentAlignment = alignment,
            isOnlyPostFirstEvent = isOnlyPostFirstEvent
        ) { action ->
            when (action) {
                is ImageEvent -> {
                    isLoading = true
                    placeholderPainter?.invoke()?.let { painter ->
                        Image(
                            painter = painter,
                            contentDescription = contentDescription,
                            modifier = Modifier.fillMaxSize(),
                            alignment = alignment,
                            contentScale = contentScale,
                            alpha = alpha,
                            colorFilter = colorFilter
                        )
                    }
                }
                is ImageAction.Success -> {
                    isLoading = false
                    val painter = successPainter(action) ?: return@AutoSizeBox
                    Image(
                        painter = painter,
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        alignment = alignment,
                        contentScale = contentScale,
                        alpha = alpha,
                        colorFilter = colorFilter
                    )
                }
                is ImageResult.OfError, is ImageResult.OfSource -> {
                    isLoading = false
                    errorPainter?.invoke()?.let { painter ->
                        Image(
                            painter = painter,
                            contentDescription = contentDescription,
                            modifier = Modifier.fillMaxSize(),
                            alignment = alignment,
                            contentScale = contentScale,
                            alpha = alpha,
                            colorFilter = colorFilter
                        )
                    }
                }
                else -> Unit
            }
        }

        if (isLoading && loadingIndicator != null) {
            loadingIndicator()
        }
    }
}

private fun successPainter(action: ImageAction.Success): Painter? {
    return when (action) {
        is ImageResult.OfPainter -> action.painter
        is ImageResult.OfBitmap -> action.bitmap.toPainter()
        is ImageResult.OfImage -> {
            // Skia Image → Bitmap → Painter
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(action.image)
            bitmap.toPainter()
        }
        else -> null
    }
}
