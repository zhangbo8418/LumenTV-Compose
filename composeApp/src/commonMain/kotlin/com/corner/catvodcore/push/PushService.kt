package com.corner.catvodcore.push

import com.corner.catvodcore.bean.Site
import com.corner.catvodcore.bean.Vod
import com.corner.util.download.DownloadUrlResolver
import java.io.File

object PushService {
    const val SITE_KEY = "push_agent"
    private val URL_PATTERN = Regex("""(?i)(https?|thunder|magnet|ed2k|video|push|file)://\S+""")

    fun extractUrl(text: String): String {
        val trimmed = text.trim()
        if (trimmed.contains("$")) return trimmed
        val match = URL_PATTERN.find(trimmed)
        return match?.value ?: trimmed
    }

    fun buildVod(text: String): Vod {
        val raw = extractUrl(text)
        val url = when {
            raw.startsWith("file://", ignoreCase = true) -> raw
            else -> DownloadUrlResolver.resolve(raw)
        }
        val name = displayName(url)
        val vod = Vod()
        vod.vodId = url
        vod.vodName = name
        vod.vodPic = "https://pic.rmb.bdstatic.com/bjh/1d0b02d0f57f0a42201f92caba5107ed.jpeg"
        vod.site = Site.get(SITE_KEY, "推送")
        return vod
    }

    fun displayName(url: String): String {
        return when {
            url.startsWith("file://", ignoreCase = true) -> {
                val path = url.removePrefix("file://")
                File(path).name.ifBlank { "本地文件" }
            }
            url.startsWith("magnet:", ignoreCase = true) -> "磁力链接"
            url.startsWith("ed2k:", ignoreCase = true) -> "电驴链接"
            else -> {
                val path = url.substringBefore('?').substringBefore('#')
                path.substringAfterLast('/').ifBlank { url.take(48) }
            }
        }
    }

    fun isPushSite(siteKey: String?): Boolean = siteKey == SITE_KEY
}
