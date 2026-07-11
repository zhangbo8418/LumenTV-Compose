package com.corner.cast

import com.corner.database.Db
import com.corner.database.entity.History
import com.corner.server.KtorD
import com.corner.util.io.Paths
import com.corner.util.json.Jsons
import com.corner.util.net.NetworkUtil
import java.io.File

fun History.getSiteKey(): String = key.split(Db.SYMBOL).getOrElse(0) { "" }

fun History.getVodId(): String = key.split(Db.SYMBOL).getOrElse(1) { "" }

object CastHistoryUtil {

    fun resolvePlayUrl(raw: String): String {
        var fd = raw.trim()
        if (fd.isBlank()) return fd
        val root = Paths.root().canonicalFile
        if (fd.startsWith("/")) {
            val relative = fd.removePrefix(root.path).ifBlank { fd }
            val suffix = if (relative.startsWith("/")) relative else "/$relative"
            fd = "http://${NetworkUtil.getLanIp()}:${KtorD.getPort()}/file$suffix"
        }
        if (fd.startsWith("file", ignoreCase = true)) {
            val relative = fd
                .removePrefix("file://")
                .removePrefix("file:/")
            val file = File(relative).canonicalFile
            val withoutRoot = file.path.removePrefix(root.path)
            val suffix = if (withoutRoot.startsWith("/")) withoutRoot else "/$withoutRoot"
            fd = "http://${NetworkUtil.getLanIp()}:${KtorD.getPort()}/file$suffix"
        }
        if (fd.contains("127.0.0.1")) {
            fd = fd.replace("127.0.0.1", NetworkUtil.getLanIp())
        }
        return fd
    }

    fun buildCastJson(history: History, playUrl: String, positionMs: Long): String {
        val resolved = resolvePlayUrl(playUrl)
        val vodId = history.getVodId()
        val payload = history.copy(
            position = positionMs,
            episodeUrl = resolved,
        )
        val json = Jsons.encodeToString(payload)
        return if (vodId.isNotBlank()) json.replace(vodId, resolved) else json
    }

    fun parseHistory(json: String): History? {
        return runCatching { Jsons.decodeFromString<History>(json) }.getOrNull()
    }

    fun extractConfigUrl(configJson: String): String {
        return runCatching {
            Jsons.parseToJsonElement(configJson).let { element ->
                when {
                    element is kotlinx.serialization.json.JsonObject ->
                        element["url"]?.toString()?.trim('"').orEmpty()
                    else -> configJson.trim()
                }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: configJson.trim()
    }
}
