package com.corner.util.download

import java.net.URI
import java.util.Base64

object DownloadUrlResolver {
    private val schemeLinks = setOf("magnet", "ed2k", "thunder", "flashget", "ftp")
    // 仅归档/安装包等；流媒体扩展留给播放器，勿当「下载链」弹窗
    private val httpExtensions = setOf(
        ".torrent", ".zip", ".rar", ".7z", ".apk", ".iso",
    )

    fun isDownloadLink(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false
        val lower = trimmed.lowercase()
        if (lower.startsWith("magnet:?")) return true

        val scheme = runCatching { URI(trimmed).scheme?.lowercase() }.getOrNull()
        if (scheme != null) {
            if (scheme in schemeLinks) return true
            if (scheme == "http" || scheme == "https" || scheme == "file") {
                return hasDownloadExtension(lower)
            }
        }

        return schemeLinks.any { lower.startsWith("$it:") }
    }

    fun resolve(url: String): String {
        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("thunder://") -> decodeThunder(trimmed)
            lower.startsWith("flashget://") -> decodeFlashget(trimmed)
            else -> trimmed
        }
    }

    fun displayName(url: String, fallback: String = ""): String {
        val resolved = resolve(url)
        return when {
            resolved.startsWith("magnet:", ignoreCase = true) -> fallback.ifBlank { "磁力链接" }
            resolved.startsWith("ed2k:", ignoreCase = true) -> fallback.ifBlank { "电驴链接" }
            else -> {
                val path = runCatching { URI(resolved).path }.getOrNull().orEmpty()
                path.substringAfterLast('/').ifBlank { fallback }
            }
        }
    }

    private fun hasDownloadExtension(lower: String): Boolean {
        val path = lower.substringBefore('?').substringBefore('#')
        return httpExtensions.any { path.endsWith(it) }
    }

    private fun decodeThunder(url: String): String {
        return try {
            val encoded = url.substringAfter("thunder://", "")
            if (encoded.isBlank()) return url
            val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            when {
                decoded.length > 4 && decoded.startsWith("AA") && decoded.endsWith("ZZ") ->
                    decoded.substring(2, decoded.length - 2)
                else -> decoded
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun decodeFlashget(url: String): String {
        return try {
            val encoded = url.substringAfter("flashget://", "")
            if (encoded.isBlank()) return url
            val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            if (decoded.length > 20) decoded.substring(10, decoded.length - 10) else decoded
        } catch (_: Exception) {
            url
        }
    }
}
