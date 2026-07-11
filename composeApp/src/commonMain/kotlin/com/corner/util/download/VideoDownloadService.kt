package com.corner.util.download

import com.corner.catvodcore.bean.Episode
import com.corner.catvodcore.bean.v
import com.corner.catvodcore.viewmodel.SiteViewModel
import com.corner.util.io.Paths
import com.corner.util.m3u8.M3u8PlayUrlResolver
import com.github.catvod.net.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 通用视频下载：
 * - magnet/ed2k/thunder 等 → aria2（若启用）
 * - HTTP 直链文件 → aria2 或本机下载
 * - m3u8/HLS → 拉切片后用 ffmpeg 合并为 mp4
 */
object VideoDownloadService {
    private val log = LoggerFactory.getLogger("VideoDownloadService")
    private val http by lazy {
        OkHttp.client().newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class Progress(
        val message: String,
        val percent: Float? = null,
    )

    suspend fun downloadEpisode(
        siteKey: String,
        flag: String,
        episode: Episode,
        titleHint: String = "",
        onProgress: (Progress) -> Unit = {},
    ): kotlin.Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(Progress("解析播放地址…"))
            val rawUrl = episode.url.trim()
            if (DownloadUrlResolver.isDownloadLink(rawUrl) && !looksLikeHttpMedia(rawUrl)) {
                onProgress(Progress("提交到 aria2…"))
                val ok = DownloadHelper.startDownload(rawUrl, episode.name.ifBlank { titleHint })
                if (!ok) error("提交下载失败")
                return@runCatching File("") // aria2 远程无本地文件
            }

            val play = SiteViewModel.playerContent(siteKey, flag, rawUrl)
                ?: error("获取播放地址失败")
            val processed = M3u8PlayUrlResolver.resolveForPlayback(play)
            val url = processed.url.v().ifBlank { play.url.v() }
            if (url.isBlank()) error("播放地址为空")
            val headers = processed.header ?: play.header

            val outName = sanitize(
                listOf(titleHint, episode.name).filter { it.isNotBlank() }.joinToString("_")
                    .ifBlank { "video_${System.currentTimeMillis()}" }
            )
            val destDir = Paths.download()

            when {
                isM3u8(url) -> {
                    onProgress(Progress("开始下载 HLS 切片…"))
                    downloadHls(url, headers, destDir, outName, onProgress)
                }
                DownloadSetting.isEnabled() -> {
                    onProgress(Progress("提交到 aria2…"))
                    val ok = Aria2Client.addUri(url, "$outName${guessExt(url)}").isSuccess
                    if (!ok) {
                        onProgress(Progress("aria2 失败，改本机下载…"))
                        downloadDirect(url, headers, File(destDir, "$outName${guessExt(url)}"), onProgress)
                    } else {
                        File("")
                    }
                }
                else -> {
                    onProgress(Progress("本机下载中…"))
                    downloadDirect(url, headers, File(destDir, "$outName${guessExt(url)}"), onProgress)
                }
            }
        }
    }

    private fun looksLikeHttpMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun isM3u8(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains("lumen-m3u8") || lower.contains("cached_m3u8") ||
            lower.contains("mpegurl")
    }

    private suspend fun downloadHls(
        url: String,
        headers: Map<String, String>?,
        destDir: File,
        outName: String,
        onProgress: (Progress) -> Unit,
    ): File {
        val out = File(destDir, "$outName.mp4")
        val ffmpeg = findFfmpeg()
        // 优先让 ffmpeg 直接读 m3u8（可处理 AES 加密切片）
        if (ffmpeg != null && url.startsWith("http", ignoreCase = true)) {
            onProgress(Progress("ffmpeg 拉取并合并…", 0.1f))
            val ok = runFfmpegFromUrl(ffmpeg, url, headers, out)
            if (ok) {
                onProgress(Progress("完成", 1f))
                return out
            }
            log.warn("ffmpeg 直拉失败，改切片下载")
        }

        val work = File(System.getProperty("java.io.tmpdir"), "lumen-dl-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val playlistUrl = resolveMediaPlaylist(url, headers)
            val content = fetchText(playlistUrl, headers)
            val base = playlistUrl.substringBeforeLast('/') + "/"
            val segments = content.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line -> if (line.startsWith("http")) line else URI(base).resolve(line).toString() }

            if (segments.isEmpty()) error("播放列表无切片")

            onProgress(Progress("共 ${segments.size} 个切片，下载中…", 0f))
            val segFiles = downloadSegments(segments, headers, work, onProgress)

            val listFile = File(work, "concat.txt")
            listFile.writeText(segFiles.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" })

            onProgress(Progress("正在合并…", 0.95f))
            runFfmpegMerge(listFile, out)
            onProgress(Progress("完成", 1f))
            return out
        } finally {
            work.deleteRecursively()
        }
    }

    private fun runFfmpegFromUrl(
        ffmpeg: String,
        url: String,
        headers: Map<String, String>?,
        out: File,
    ): Boolean {
        if (out.exists()) out.delete()
        val cmd = mutableListOf(ffmpeg, "-y", "-allowed_extensions", "ALL")
        if (!headers.isNullOrEmpty()) {
            val headerStr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
            cmd += listOf("-headers", headerStr)
        }
        cmd += listOf("-i", url, "-c", "copy", out.absolutePath)
        return try {
            log.info("ffmpeg url: {}", url.take(120))
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            val ok = code == 0 && out.isFile && out.length() > 0L
            if (!ok) log.warn("ffmpeg url 失败 code={} {}", code, output.take(500))
            ok
        } catch (e: Exception) {
            log.warn("ffmpeg url 异常: {}", e.message)
            false
        }
    }

    private fun resolveMediaPlaylist(url: String, headers: Map<String, String>?): String {
        // 本地 lumen-m3u8 文件直接读
        if (url.startsWith("/") && url.endsWith(".m3u8", ignoreCase = true)) {
            val text = File(url).takeIf { it.isFile }?.readText().orEmpty()
            if (text.contains("#EXT-X-STREAM-INF")) {
                val variant = text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return url
                return if (variant.startsWith("http")) variant else URI("file:$url").resolve(variant).toString()
            }
            return url
        }
        val text = fetchText(url, headers)
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = text.lines().firstOrNull {
                it.isNotBlank() && !it.startsWith("#") && it.contains("m3u8", ignoreCase = true)
            } ?: return url
            return if (variant.startsWith("http")) variant
            else URI(url.substringBeforeLast('/') + "/").resolve(variant).toString()
        }
        return url
    }

    private fun fetchText(url: String, headers: Map<String, String>?): String {
        if (url.startsWith("/") && File(url).isFile) return File(url).readText()
        val httpUrl = when {
            url.startsWith("http", ignoreCase = true) -> url
            url.startsWith("file:") -> return File(URI(url)).readText()
            else -> url
        }
        val req = Request.Builder().url(httpUrl).headers(headers.orEmpty().toHeaders()).get().build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("拉取播放列表失败 HTTP ${resp.code}")
            resp.body.string()
        }
    }

    private suspend fun downloadSegments(
        urls: List<String>,
        headers: Map<String, String>?,
        work: File,
        onProgress: (Progress) -> Unit,
    ): List<File> = coroutineScope {
        val semaphore = Semaphore(6)
        val total = urls.size
        var done = 0
        urls.mapIndexed { index, segUrl ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val ext = when {
                        segUrl.contains(".m4s", ignoreCase = true) -> ".m4s"
                        segUrl.contains(".mp4", ignoreCase = true) -> ".mp4"
                        else -> ".ts"
                    }
                    val file = File(work, String.format("%05d%s", index, ext))
                    val req = Request.Builder().url(segUrl).headers(headers.orEmpty().toHeaders()).get().build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("切片下载失败 #$index HTTP ${resp.code}")
                        file.writeBytes(resp.body.bytes())
                    }
                    synchronized(onProgress) {
                        done++
                        onProgress(Progress("切片 $done/$total", done.toFloat() / total * 0.9f))
                    }
                    index to file
                }
            }
        }.awaitAll()
            .sortedBy { it.first }
            .map { it.second }
    }

    private fun runFfmpegMerge(listFile: File, out: File) {
        val ffmpeg = findFfmpeg() ?: error("未找到 ffmpeg（发行包应已内置；开发环境可执行 ./gradlew prepareBundledFfmpeg）")
        if (out.exists()) out.delete()
        val cmd = listOf(
            ffmpeg, "-y",
            "-f", "concat", "-safe", "0",
            "-i", listFile.absolutePath,
            "-c", "copy",
            out.absolutePath,
        )
        log.info("ffmpeg: {}", cmd.joinToString(" "))
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0 || !out.isFile || out.length() <= 0L) {
            log.warn("ffmpeg 失败 code={} out={}", code, output.take(800))
            // TS 二进制拼接兜底
            fallbackBinaryConcat(listFile, out)
        }
    }

    private fun fallbackBinaryConcat(listFile: File, out: File) {
        log.info("使用二进制拼接兜底")
        out.outputStream().use { os ->
            listFile.readLines()
                .mapNotNull { line ->
                    Regex("""file '(.+)'""").find(line)?.groupValues?.getOrNull(1)
                }
                .forEach { path -> File(path).inputStream().use { it.copyTo(os) } }
        }
        if (!out.isFile || out.length() <= 0L) error("合并失败")
    }

    private fun downloadDirect(
        url: String,
        headers: Map<String, String>?,
        dest: File,
        onProgress: (Progress) -> Unit,
    ): File {
        val req = Request.Builder().url(url).headers(headers.orEmpty().toHeaders()).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("下载失败 HTTP ${resp.code}")
            val total = resp.body.contentLength()
            var read = 0L
            dest.outputStream().use { os ->
                resp.body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        os.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(Progress("下载中…", read.toFloat() / total))
                    }
                }
            }
        }
        onProgress(Progress("完成", 1f))
        return dest
    }

    private fun findFfmpeg(): String? = FfmpegLocator.find()

    private fun guessExt(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".mkv") -> ".mkv"
            path.endsWith(".flv") -> ".flv"
            path.endsWith(".webm") -> ".webm"
            path.endsWith(".ts") -> ".ts"
            else -> ".mp4"
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(80)
}
