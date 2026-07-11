package com.corner.ui.live

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.corner.util.io.Paths
import com.corner.util.net.Http
import com.corner.util.net.Utils
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 直播频道 Logo 缓存：内存 LRU + 磁盘文件，避免每次打开换台菜单都重新拉取。
 */
object LiveLogoCache {
    private val log = LoggerFactory.getLogger("LiveLogoCache")
    private const val MEMORY_MAX = 128
    private const val DISK_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MEMORY_MAX
        }
    }
    private val memoryLock = Any()
    private val inFlight = ConcurrentHashMap<String, Any>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private const val FAIL_COOLDOWN_MS = 60_000L

    private fun diskDir(): File = Paths.picCache().resolve("live-logo").also { it.mkdirs() }

    private fun diskFile(cacheKey: String): File = diskDir().resolve("$cacheKey.bin")

    fun getCached(url: String): ImageBitmap? {
        if (!url.startsWith("http")) return null
        val key = cacheKey(url)
        synchronized(memoryLock) {
            memory[key]?.let { return it }
        }
        return null
    }

    fun load(url: String): ImageBitmap? {
        if (!url.startsWith("http")) return null
        val key = cacheKey(url)
        synchronized(memoryLock) {
            memory[key]?.let { return it }
        }
        val failAt = failedUntil[key]
        if (failAt != null && System.currentTimeMillis() < failAt) return null

        // 简单串行化同一 URL 的并发请求
        synchronized(inFlight.computeIfAbsent(key) { Any() }) {
            try {
                synchronized(memoryLock) {
                    memory[key]?.let { return it }
                }
                readDisk(key)?.let { bytes ->
                    decode(bytes)?.also { putMemory(key, it) }?.let { return it }
                }
                val bytes = download(url) ?: run {
                    failedUntil[key] = System.currentTimeMillis() + FAIL_COOLDOWN_MS
                    return null
                }
                writeDisk(key, bytes)
                val bitmap = decode(bytes) ?: run {
                    failedUntil[key] = System.currentTimeMillis() + FAIL_COOLDOWN_MS
                    return null
                }
                failedUntil.remove(key)
                putMemory(key, bitmap)
                return bitmap
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun putMemory(key: String, bitmap: ImageBitmap) {
        synchronized(memoryLock) {
            memory[key] = bitmap
        }
    }

    private fun cacheKey(url: String): String = Utils.md5(url)

    private fun readDisk(key: String): ByteArray? {
        val file = diskFile(key)
        if (!file.isFile) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age > DISK_TTL_MS) {
            file.delete()
            return null
        }
        return runCatching { file.readBytes() }
            .onFailure { log.debug("read logo disk cache failed: {}", key, it) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun writeDisk(key: String, bytes: ByteArray) {
        runCatching {
            diskFile(key).writeBytes(bytes)
        }.onFailure { log.debug("write logo disk cache failed: {}", key, it) }
    }

    private fun download(url: String): ByteArray? {
        val httpUrl = buildLogoHttpUrl(url) ?: run {
            log.warn("logo invalid url: {}", url)
            return null
        }
        return Http.client().newCall(Request.Builder().url(httpUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                log.warn("logo HTTP {}: {}", resp.code, httpUrl)
                return@use null
            }
            resp.body?.bytes()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        return ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
            ?: run {
                log.warn("logo decode failed ({} bytes)", bytes.size)
                null
            }
    }
}

internal fun buildLogoHttpUrl(raw: String): HttpUrl? {
    val rebuilt = runCatching {
        val u = URL(raw)
        val builder = HttpUrl.Builder()
            .scheme(u.protocol)
            .host(u.host)
        if (u.port > 0) builder.port(u.port)
        u.path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            builder.addPathSegment(decodeQuiet(segment))
        }
        u.query?.split('&')?.forEach { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) {
                if (part.isNotEmpty()) builder.addEncodedQueryParameter(part, null)
            } else {
                builder.addQueryParameter(
                    decodeQuiet(part.substring(0, idx)),
                    decodeQuiet(part.substring(idx + 1)),
                )
            }
        }
        builder.build()
    }.getOrNull()
    return rebuilt ?: raw.toHttpUrlOrNull()
}

private fun decodeQuiet(value: String): String =
    runCatching { URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(value)
