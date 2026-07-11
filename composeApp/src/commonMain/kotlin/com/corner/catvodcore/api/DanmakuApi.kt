package com.corner.catvodcore.api

import com.corner.catvodcore.bean.Danmaku
import com.corner.catvodcore.setting.DanmakuSetting
import com.corner.util.json.Jsons
import com.corner.util.net.Http
import org.slf4j.LoggerFactory

object DanmakuApi {
    private val log = LoggerFactory.getLogger("DanmakuApi")

    fun search(name: String, episode: String): Danmaku? {
        val apiUrl = DanmakuSetting.effectiveApiUrl()
        if (apiUrl.isBlank()) return null
        return try {
            val url = if (apiUrl.contains("{name}") || apiUrl.contains("{episode}")) {
                apiUrl.replace("{name}", name).replace("{episode}", episode)
            } else {
                apiUrl
            }
            val body = if (url == apiUrl) {
                Http.newCall(
                    url,
                    okhttp3.Headers.Builder().build(),
                    Http.toBody(mapOf("name" to name, "episode" to episode)),
                ).execute().use { it.body.string() }
            } else {
                Http.get(url).execute().use { it.body.string() }
            }
            Jsons.decodeFromString<List<Danmaku>>(body).firstOrNull()
        } catch (e: Exception) {
            log.debug("弹幕搜索失败: {}", e.message)
            null
        }
    }
}
