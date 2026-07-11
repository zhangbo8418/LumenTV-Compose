package com.corner.util.m3u8

import com.corner.catvodcore.bean.Result
import com.corner.catvodcore.bean.Url
import com.corner.catvodcore.bean.add
import com.corner.catvodcore.bean.isEmpty
import com.corner.catvodcore.bean.v
import com.corner.server.KtorD
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import com.corner.util.net.createDefaultOkHttpClient
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 播放阶段 M3U8 处理：在 playerContent 返回后、VLC 起播前执行，不阻塞换集 cancel。
 */
object M3u8PlayUrlResolver {
    private val log = LoggerFactory.getLogger("M3u8PlayUrlResolver")

    /** 复用连接池，避免每次起播新建 Client */
    private val sharedClient: OkHttpClient by lazy { createDefaultOkHttpClient() }

    private val probeClient: OkHttpClient by lazy {
        sharedClient.newBuilder()
            .followRedirects(false)
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .callTimeout(1, TimeUnit.SECONDS)
            .build()
    }

    fun resolveForPlayback(result: Result): Result {
        val urlStr = result.url.v()
        if (urlStr.isBlank()) return result

        val alreadyLocal = urlStr.contains("proxy/cached_m3u8", ignoreCase = true) ||
            urlStr.contains("lumen-m3u8", ignoreCase = true) ||
            (urlStr.startsWith("/") && urlStr.endsWith(".m3u8", ignoreCase = true))
        if (alreadyLocal) return result

        val looksLikeM3u8 = urlStr.contains("m3u8", ignoreCase = true)
        if (!looksLikeM3u8) return result

        val processed = processM3U8(result.url, result.header)
        if (processed.isEmpty()) {
            result.url = processed
            log.warn("M3U8 处理后无可用地址")
            return result
        }
        if (processed.v() != urlStr) {
            result.url = processed
            log.info("M3U8 已处理为本地播放地址: {}", processed.v())
        }
        return result
    }

    private fun processM3U8(url: Url, headers: Map<String, String>?): Url {
        val raw = url.v()
        if (!raw.contains("m3u8", ignoreCase = true)) return url
        if (raw.contains("proxy/cached_m3u8", ignoreCase = true)) return url

        return try {
            val requestHeaders = headers.orEmpty().toHeaders()
            val request = Request.Builder()
                .url(raw)
                .headers(requestHeaders)
                .build()

            // 主列表用无过滤客户端更快；媒体列表走广告过滤
            val content = sharedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.warn("下载 M3U8 失败，使用原始地址: code={}", response.code)
                    return url
                }
                response.body.string()
            }

            val isMaster = content.contains("#EXT-X-STREAM-INF")
            if (isMaster) {
                val variant = content.lines().firstOrNull { line ->
                    line.isNotBlank() && !line.startsWith("#") && line.contains("m3u8", ignoreCase = true)
                }
                if (variant != null) {
                    val nestedUrl = if (variant.startsWith("http")) variant
                    else "${raw.substringBeforeLast("/")}/$variant"
                    log.info("主列表取变体: {}", nestedUrl)
                    // 变体失败时回退原始主列表，交给播放器自己选流（对齐 TV/Exo）
                    return processMediaPlaylist(Url().add(nestedUrl), headers, fallback = url)
                }
            }

            // 已是媒体列表：本地过滤，避免再下一次
            val filtered = applyAdFilterLocally(raw, content)
            processMediaPlaylistContent(filtered, raw, url, headers)
        } catch (e: Exception) {
            log.warn("M3U8 处理失败，使用原始地址: {}", e.message)
            url
        }
    }

    /** 下载并过滤媒体播放列表；失败时返回 fallback（通常是原始主列表） */
    private fun processMediaPlaylist(
        url: Url,
        headers: Map<String, String>?,
        fallback: Url = url,
    ): Url {
        val raw = url.v()
        return try {
            val request = Request.Builder()
                .url(raw)
                .headers(headers.orEmpty().toHeaders())
                .build()
            // 媒体列表直接下原文，本地过滤（比 Interceptor 路径更可控）
            val content = sharedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.warn("下载媒体 M3U8 失败: code={}", response.code)
                    return fallback
                }
                response.body.string()
            }
            val filtered = applyAdFilterLocally(raw, content)
            processMediaPlaylistContent(filtered, raw, url, headers)
        } catch (e: Exception) {
            log.warn("媒体 M3U8 处理失败: {}", e.message)
            fallback
        }
    }

    private fun applyAdFilterLocally(url: String, content: String): String {
        val baseUrl = url.substringBeforeLast("/") + "/"
        val absolute = content.lines().joinToString("\n") { line ->
            when {
                line.startsWith("#") || line.isBlank() -> line
                line.startsWith("http") -> line
                line.startsWith("/") -> runCatching { URI(baseUrl).resolve(line).toString() }.getOrDefault(line)
                else -> "$baseUrl$line"
            }
        }
        if (!com.corner.util.settings.SettingStore.isAdFilterEnabled()) return absolute
        val filter = M3U8Filter(com.corner.util.settings.SettingStore.getM3U8FilterConfig())
        val out = filter.safelyProcessM3u8(url, absolute)
        val adCount = filter.getFilteredAdCount()
        if (adCount > 0) log.info("广告过滤完成，共过滤 {} 条广告", adCount)
        return out
    }

    private fun processMediaPlaylistContent(
        content: String,
        raw: String,
        url: Url,
        headers: Map<String, String>?,
    ): Url {
        val withKeys = if (content.contains("#EXT-X-KEY:")) {
            processEncryptionKeys(content, raw, headers)
        } else {
            content
        }

        // 若仍含嵌套 m3u8（少见），再展开一次
        val nestedExpanded = Regex("(?m)^(?!#).*\\.m3u8(?:\\?.*)?$").replace(withKeys) { match ->
            val nested = match.value.trim()
            val nestedUrl = when {
                nested.startsWith("http") -> nested
                else -> "${raw.substringBeforeLast("/")}/$nested"
            }
            log.info("展开嵌套 M3U8: {}", nestedUrl)
            processMediaPlaylist(Url().add(nestedUrl), headers).v()
        }
        if (nestedExpanded !== withKeys && nestedExpanded.contains("lumen-m3u8")) {
            val localLine = nestedExpanded.lines().firstOrNull {
                it.contains("lumen-m3u8") ||
                    it.endsWith(".m3u8", ignoreCase = true) &&
                    (it.startsWith("/") || (it.length >= 3 && it[1] == ':'))
            }
            if (localLine != null) return Url().add(localLine.trim())
        }

        val segmentCount = nestedExpanded.lines().count { line ->
            line.isNotBlank() && !line.startsWith("#")
        }
        if (segmentCount < 2) {
            log.warn("M3U8 过滤后分片过少 ({})，使用原始地址", segmentCount)
            return url
        }

        val cleaned = stripNonVideoSegments(nestedExpanded)
        val cleanedCount = cleaned.lines().count { line ->
            line.isNotBlank() && !line.startsWith("#")
        }
        if (cleanedCount < 2) {
            log.warn("去除广告图后分片过少 ({})，使用原始地址", cleanedCount)
            return url
        }
        if (cleanedCount < segmentCount) {
            log.info("已去除非视频分片: {} -> {}", segmentCount, cleanedCount)
        }
        // 广告网关：不要回退原始 getM3u8（仍会秒停），直接放弃让上层换线
        if (needsAdRedirectProbe(cleaned) && isAdRedirectPlaylist(cleaned)) {
            log.warn("分片重定向到广告图，放弃本地址（触发换线）")
            return Url()
        }
        // 去掉混入的少数异源分片（过滤后残留的尾部广告段）
        val dominant = keepDominantSegmentGroup(cleaned)
        log.info("M3U8 处理完成，分片数={}", dominant.lines().count { it.isNotBlank() && !it.startsWith("#") })

        val processedTsContent = convertRelativeTsToAbsolute(dominant, raw)
        return toLocalPlayUrl(processedTsContent)
    }

    private fun stripNonVideoSegments(content: String): String {
        val lines = content.lines()
        val out = ArrayList<String>(lines.size)
        var i = 0
        var removed = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF") && i + 1 < lines.size && isNonVideoMedia(lines[i + 1])) {
                removed++
                i += 2
                continue
            }
            if (line.isNotBlank() && !line.startsWith("#") && isNonVideoMedia(line)) {
                removed++
                i++
                continue
            }
            out.add(line)
            i++
        }
        if (removed > 0) log.info("移除非视频分片 {} 条", removed)
        return out.joinToString("\n")
    }

    /** 保留数量最多的分片目录组，去掉过滤后残留的异源/广告尾段 */
    private fun keepDominantSegmentGroup(content: String): String {
        val lines = content.lines()
        val dirs = lines.mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#") || !line.startsWith("http")) null
            else line.substringBeforeLast('/')
        }
        if (dirs.size < 10) return content
        val dominantDir = dirs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: return content
        val dominantCount = dirs.count { it == dominantDir }
        if (dominantCount == dirs.size || dominantCount < dirs.size * 2 / 3) return content

        val out = ArrayList<String>(lines.size)
        var i = 0
        var removed = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF") && i + 1 < lines.size) {
                val media = lines[i + 1]
                if (media.startsWith("http") && media.substringBeforeLast('/') != dominantDir) {
                    removed++
                    i += 2
                    continue
                }
            } else if (line.isNotBlank() && !line.startsWith("#") && line.startsWith("http") &&
                line.substringBeforeLast('/') != dominantDir
            ) {
                removed++
                i++
                continue
            }
            out.add(line)
            i++
        }
        if (removed > 0) log.info("去除异源分片 {} 条，保留主目录", removed)
        return out.joinToString("\n")
    }

    private fun isNonVideoMedia(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains(".png") || lower.contains(".jpg") || lower.contains(".jpeg") ||
            lower.contains(".gif") || lower.contains(".webp") || lower.contains(".bmp")
    }

    /** 正常 .ts/.m4s CDN 列表跳过探测；仅可疑网关才 HEAD */
    private fun needsAdRedirectProbe(content: String): Boolean {
        val media = content.lines().asSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(3)
            .toList()
        if (media.isEmpty()) return true
        return media.any { line ->
            val lower = line.lowercase()
            !lower.contains(".ts") && !lower.contains(".m4s") && !lower.contains(".mp4")
        }
    }

    /** 只抽查 1 个分片，短超时 */
    private fun isAdRedirectPlaylist(content: String): Boolean {
        val media = content.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return true
        return try {
            probeClient.newCall(Request.Builder().url(media).head().build()).execute().use { resp ->
                val loc = resp.header("Location").orEmpty().lowercase()
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                (resp.code in 300..399 && isNonVideoMedia(loc)) || ct.startsWith("image/")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun toLocalPlayUrl(content: String): Url {
        val dir = File(System.getProperty("java.io.tmpdir"), "lumen-m3u8").apply { mkdirs() }
        // 清理放到后台，不挡起播
        if (dir.listFiles()?.size ?: 0 > 32) {
            Thread({
                dir.listFiles()
                    ?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 1_800_000L }
                    ?.forEach { runCatching { it.delete() } }
            }, "m3u8-cleanup").apply { isDaemon = true }.start()
        }
        val file = File(dir, "${UUID.randomUUID()}.m3u8")
        file.writeText(content)
        val path = file.absolutePath
        log.info("M3U8 已写入本地文件: {}", path)
        return Url().add(path)
    }

    private fun processEncryptionKeys(
        content: String,
        baseUrl: String,
        headers: Map<String, String>?,
    ): String {
        val keyRegex = """#EXT-X-KEY:METHOD=([^,]+),URI="([^"]+)"(,IV=([^"]+))?""".toRegex()
        return keyRegex.replace(content) { match ->
            val (method, uri, _, iv) = match.destructured
            try {
                val keyUrl = when {
                    uri.startsWith("http") -> uri
                    uri.startsWith("/") -> {
                        val baseUri = URI(baseUrl)
                        "${baseUri.scheme}://${baseUri.host}$uri"
                    }
                    else -> {
                        val basePath = baseUrl.substringBeforeLast("/")
                        "$basePath/$uri".replace(Regex("(?<!:)//"), "/")
                    }
                }
                val cacheId = downloadAndStoreKey(keyUrl, headers)
                "#EXT-X-KEY:METHOD=$method,URI=\"$cacheId\"" +
                    (if (iv.isNotEmpty()) ",IV=$iv" else "")
            } catch (e: Exception) {
                log.warn("密钥处理失败，保留原始标签", e)
                match.value
            }
        }
    }

    private fun downloadAndStoreKey(keyUrl: String, headers: Map<String, String>?): String {
        val request = Request.Builder()
            .url(keyUrl)
            .headers(headers.orEmpty().toHeaders())
            .build()
        val keyData = sharedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("密钥下载失败")
            response.body.bytes()
        }
        val cacheId = M3U8Cache.put(String(keyData))
        return "http://127.0.0.1:${KtorD.getPort()}/proxy/cached_m3u8?id=$cacheId"
    }

    private fun convertRelativeTsToAbsolute(content: String, baseUrl: String): String {
        val baseUri = try {
            URI(baseUrl)
        } catch (e: Exception) {
            log.error("无效的基准 URL: $baseUrl", e)
            return content
        }
        val resolveBase = try {
            if (baseUrl.contains("getM3u8", ignoreCase = true)) {
                URI(
                    baseUri.scheme,
                    baseUri.authority,
                    baseUri.path.substringBeforeLast('/') + "/",
                    null,
                    null,
                )
            } else {
                baseUri
            }
        } catch (_: Exception) {
            baseUri
        }
        return content.lines().joinToString("\n") { line ->
            when {
                line.startsWith("#") || line.isBlank() -> line
                line.startsWith("http") -> line
                line.startsWith("/") -> {
                    "${resolveBase.scheme}://${resolveBase.authority}$line"
                }
                else -> {
                    runCatching { resolveBase.resolve(line).toString() }.getOrDefault(line)
                }
            }
        }
    }
}
