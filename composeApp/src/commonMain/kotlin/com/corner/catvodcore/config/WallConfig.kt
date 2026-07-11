package com.corner.catvodcore.config

import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.util.io.Paths
import com.corner.util.io.Urls
import com.corner.util.net.Http
import com.corner.util.scope.createCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

object WallConfig {
    private val log = LoggerFactory.getLogger("WallConfig")
    private val scope = createCoroutineScope()

    enum class WallType { IMAGE, GIF, VIDEO }

    /** 最近一次请求的壁纸 URL（可能是相对路径） */
    @Volatile
    var currentUrl: String? = null
        private set

    fun init(url: String?) {
        currentUrl = url?.trim()?.takeIf { it.isNotBlank() }
        if (currentUrl == null) {
            GlobalAppState.wallpaperPath.value = null
            GlobalAppState.wallpaperType.value = WallType.IMAGE
            return
        }
        val requestUrl = currentUrl!!
        scope.launch {
            try {
                val resolved = resolveWallUrl(requestUrl)
                if (resolved.isBlank()) {
                    log.warn("壁纸地址无效: {}", requestUrl)
                    GlobalAppState.wallpaperPath.value = null
                    return@launch
                }
                val file = Paths.wall()
                download(resolved, file)
                if (!file.isFile || file.length() <= 0L) {
                    log.warn("壁纸下载后文件为空: {}", resolved)
                    GlobalAppState.wallpaperPath.value = null
                    return@launch
                }
                val type = detectType(resolved, file)
                GlobalAppState.wallpaperType.value = type
                GlobalAppState.wallpaperPath.value = file.absolutePath
                log.info("壁纸已加载: {} type={} from={}", file.name, type, resolved)
            } catch (e: Exception) {
                log.warn("壁纸加载失败: {} ({})", requestUrl, e.message ?: e.javaClass.simpleName, e)
                GlobalAppState.wallpaperPath.value = null
            }
        }
    }

    fun refresh() {
        init(currentUrl)
    }

    /** 相对路径（如 ../bing）按点播配置基址解析，对齐 TV UrlUtil.convert */
    private fun resolveWallUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http", ignoreCase = true) || trimmed.startsWith("file", ignoreCase = true)) {
            return Urls.convert(trimmed).ifBlank { trimmed }
        }
        val base = ApiConfig.api.url?.takeIf { it.isNotBlank() }
            ?: ApiConfig.api.cfg?.url?.takeIf { it.isNotBlank() }
            ?: return trimmed
        return Urls.convert(base, trimmed).ifBlank { trimmed }
    }

    private suspend fun download(url: String, dest: File) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        when {
            url.startsWith("file", ignoreCase = true) -> {
                val source = File(Urls.convert(url))
                Files.copy(source.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            else -> {
                val body = Http.get(url).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body.bytes()
                }
                dest.writeBytes(body)
            }
        }
    }

    private fun detectType(url: String, file: File): WallType {
        val name = (url.substringAfterLast('/').ifBlank { file.name }).lowercase()
        return when {
            name.endsWith(".gif") || name.contains(".gif?") -> WallType.GIF
            name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".mkv") ||
                name.contains(".mp4?") || name.contains(".webm?") -> WallType.VIDEO
            else -> WallType.IMAGE
        }
    }
}
