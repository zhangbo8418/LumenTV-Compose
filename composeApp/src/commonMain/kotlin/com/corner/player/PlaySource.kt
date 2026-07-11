package com.corner.player

import com.corner.catvodcore.bean.Result
import com.corner.catvodcore.bean.Url
import com.corner.catvodcore.bean.add
import com.corner.catvodcore.bean.v
import com.corner.catvodcore.push.PushService
import com.corner.server.ServerEvent
import com.github.catvod.net.OkHttp
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI

/**
 * 播放地址提取器（移植 TV Source，桌面端支持 video://、.strm 等）
 */
object PlaySource {
    private val log = LoggerFactory.getLogger("PlaySource")

    fun fetch(result: Result): Result {
        val url = result.url.v()
        if (url.isBlank()) return result

        val scheme = runCatching { URI(url).scheme?.lowercase().orEmpty() }.getOrDefault("")
        val extracted = when {
            scheme == "push" -> {
                val target = url.substringAfter("push://", url.removePrefix("push:"))
                ServerEvent.push(target)
                ""
            }
            scheme == "video" -> fetchVideo(url)
            isStrm(url) -> fetchStrm(url)
            else -> url
        }

        if (extracted.isBlank()) return result
        if (extracted != url) {
            result.url = Url().add(extracted)
            if (scheme == "video") result.parse = 1
            log.debug("提取播放地址: {} -> {}", url, extracted)
        }
        return result
    }

    fun extractUrl(url: String): String {
        if (url.isBlank()) return url
        val result = Result().apply { this.url = Url().add(url) }
        return fetch(result).url.v().ifBlank { url }
    }

    private fun fetchVideo(url: String): String {
        return if (url.length > 8) url.substring(8) else url
    }

    private fun isStrm(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return path.endsWith(".strm", ignoreCase = true)
    }

    private fun fetchStrm(url: String): String {
        return try {
            when {
                url.startsWith("http", ignoreCase = true) -> fetchStrmHttp(url)
                url.startsWith("file://", ignoreCase = true) -> readFirstLine(File(URI(url)))
                url.startsWith("file:", ignoreCase = true) -> readFirstLine(File(url.removePrefix("file:")))
                else -> readFirstLine(File(url))
            }
        } catch (e: Exception) {
            log.warn("读取 strm 失败: $url", e)
            url
        }
    }

    private fun fetchStrmHttp(url: String): String {
        val client = OkHttp.noRedirect()
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            val disposition = it.header("Content-Disposition").orEmpty()
            val isText = disposition.contains(".strm", ignoreCase = true) ||
                disposition.contains(".txt", ignoreCase = true)
            return if (isText) {
                it.body.string().lineSequence().firstOrNull().orEmpty().ifBlank { url }
            } else {
                url
            }
        }
    }

    private fun readFirstLine(file: File): String {
        if (!file.exists()) return ""
        return file.readLines().firstOrNull().orEmpty().trim()
    }
}
