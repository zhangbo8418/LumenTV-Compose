package com.corner.util.io

import com.corner.util.core.thisLogger
import java.net.URI
import java.nio.file.Paths

object Urls {
    private val log = thisLogger()

    /**
     * file URI → 本地绝对路径。
     * 兼容 file:///Users/...、file:/Users/...，以及错误的 file://Users/...（缺第三斜杠）。
     */
    fun convert(url: String): String {
        if (!url.startsWith("file:", ignoreCase = true)) return url
        return try {
            Paths.get(toFileUri(url)).toAbsolutePath().toString()
        } catch (e: Exception) {
            var path = url.substringAfter("file:", "")
            while (path.startsWith("//")) path = path.substring(1)
            if (path.isNotEmpty() && !path.startsWith("/")) path = "/$path"
            path
        }
    }

    fun convert(baseUrl: String, refUrl: String): String {
        try {
            val url = if (baseUrl.startsWith("file:", ignoreCase = true)) {
                toFileUri(baseUrl).resolve(refUrl.replace("\\", "/")).toString()
            } else {
                URI(baseUrl.replace("\\", "/")).resolve(refUrl.replace("\\", "/")).toString()
            }
            log.info("解析url：$url，baseUrl $baseUrl，refUrl $refUrl")
            return url
        } catch (e: Exception) {
            log.error("解析url失败 返回空值", e)
            return ""
        }
    }

    /** 规范为 file:///abs/path，避免 replace("file://","file:/") 把三斜杠打成两斜杠。 */
    private fun toFileUri(url: String): URI {
        val normalized = url.replace("\\", "/")
        val fixed = when {
            normalized.startsWith("file:///") -> normalized
            // file://Users/... → file:///Users/...
            normalized.startsWith("file://") && !normalized.startsWith("file:///") ->
                "file:///" + normalized.removePrefix("file://")
            normalized.startsWith("file:/") -> "file://" + normalized.removePrefix("file:")
            else -> normalized
        }
        return URI(fixed)
    }
}
