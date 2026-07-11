package com.corner.catvodcore.live

import com.corner.catvodcore.config.ParseConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

object LiveSuperParser {
    private val log = LoggerFactory.getLogger("LiveSuperParser")

    suspend fun parse(webUrl: String, headers: Map<String, String>, timeoutMs: Long = 15_000): String? =
        coroutineScope {
            val jsonParses = ParseConfig.getParses(1)
            val webParses = ParseConfig.getParses(0)
            if (jsonParses.isEmpty() && webParses.isEmpty()) return@coroutineScope null

            val result = CompletableDeferred<String?>()
            val jobs = buildList {
                jsonParses.forEach { item ->
                    add(
                        launch(Dispatchers.IO) {
                            if (result.isCompleted) return@launch
                            LiveJsonParser.parseWith(item, webUrl, headers)?.let { result.complete(it) }
                        }
                    )
                }
                if (webParses.isNotEmpty()) {
                    add(
                        launch(Dispatchers.IO) {
                            if (result.isCompleted) return@launch
                            LiveWebParser.parse(webUrl, headers, timeoutMs, webParses)?.let { result.complete(it) }
                        }
                    )
                }
            }
            try {
                withTimeoutOrNull(timeoutMs) { result.await() }
            } catch (e: Exception) {
                log.warn("超级解析失败: $webUrl", e)
                null
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
}
