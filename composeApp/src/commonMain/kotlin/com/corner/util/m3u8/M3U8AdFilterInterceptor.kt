package com.corner.util.m3u8

import com.corner.util.settings.SettingStore
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.slf4j.LoggerFactory
import java.net.URI


private val log = LoggerFactory.getLogger("M3U8Interceptor")

class M3U8AdFilterInterceptor {
    class Interceptor() : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()

            // 只拦截 m3u8（含 getM3u8?url=xxx.m3u8）
            if (!url.contains("m3u8", ignoreCase = true)) {
                return chain.proceed(request)
            }
            if (url.contains("proxy/cached_m3u8", ignoreCase = true)) {
                return chain.proceed(request)
            }
            log.info("拦截请求，URL: $url")

            val response = chain.proceed(request)
            if (!response.isSuccessful) return response

            val originalContent = response.body.string()

            // 主播放列表无分片广告，只做相对路径展开，避免无意义过滤拖慢起播
            val isMaster = originalContent.contains("#EXT-X-STREAM-INF")

            val baseUrl = url.substringBeforeLast("/") + "/"
            val absolutePathContent = originalContent.lines().joinToString("\n") { line ->
                when {
                    line.startsWith("#") || line.isBlank() -> line
                    line.startsWith("http") -> line
                    line.startsWith("/") -> URI(baseUrl).resolve(line).toString()
                    else -> "$baseUrl$line"
                }
            }

            val filteredContent = if (!isMaster && SettingStore.isAdFilterEnabled()) {
                val filter = M3U8Filter(SettingStore.getM3U8FilterConfig())
                val out = filter.safelyProcessM3u8(url, absolutePathContent)
                val adCount = filter.getFilteredAdCount()
                if (adCount > 0) {
                    log.info("广告过滤完成，共过滤 {} 条广告", adCount)
                }
                out
            } else {
                absolutePathContent
            }

            return response.newBuilder()
                .body(filteredContent.toResponseBody(response.body.contentType()))
                .build()
        }
    }
}