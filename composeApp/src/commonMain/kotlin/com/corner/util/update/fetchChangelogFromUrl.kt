package com.corner.util.update

import com.corner.util.net.KtorClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

suspend fun fetchChangelogFromUrl(url: String): String {
    val client = KtorClient.createHttpClient()
    return try {
        client.get(url).bodyAsText()
    } catch (e: Exception) {
        "无法获取更新日志: ${e.message}"
    } finally {
        runCatching { client.close() }
    }
}
