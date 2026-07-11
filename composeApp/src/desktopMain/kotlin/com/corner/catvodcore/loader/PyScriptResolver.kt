package com.corner.catvodcore.loader

import com.corner.catvodcore.config.ApiConfig
import com.corner.util.io.Paths
import com.corner.util.io.Urls
import com.corner.util.net.Http
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.ZipFile

object PyScriptResolver {
    private val log = LoggerFactory.getLogger("PyScriptResolver")

    fun materialize(api: String, jar: String): File {
        val resolved = resolveUrl(api, jar)
        val cacheDir = Paths.py().apply { mkdirs() }
        val fileName = resolved.substringAfterLast('/').substringBefore('?').ifBlank { "spider.py" }
        val target = File(cacheDir, fileName)

        when {
            resolved.startsWith("http") -> {
                runCatching { downloadHttp(resolved, target) }
                    .onFailure { log.warn("HTTP 下载 Py 脚本失败: {}, 尝试从 jar 解压", resolved, it) }
                    .onSuccess { return target }
            }

            resolved.startsWith("file") -> {
                val source = Paths.local(resolved)
                if (source.exists()) {
                    source.copyTo(target, overwrite = true)
                    return target
                }
            }

            File(resolved).exists() -> {
                File(resolved).copyTo(target, overwrite = true)
                return target
            }
        }

        extractFromJar(api, jar, target)?.let { return it }
        if (target.exists() && target.length() > 0) return target

        throw IllegalStateException("无法加载 Python 脚本: $api (resolved=$resolved)")
    }

    fun resolveUrl(api: String, jar: String): String {
        if (StringUtils.isBlank(api)) return api
        if (api.startsWith("http") || api.startsWith("file")) return api
        if (File(api).exists()) return File(api).absolutePath

        val bases = listOfNotNull(
            jar.takeIf { it.isNotBlank() },
            ApiConfig.api.spider.takeIf { it.isNotBlank() },
            ApiConfig.api.url?.takeIf { it.isNotBlank() },
        )
        for (base in bases) {
            val resolved = Urls.convert(base, api)
            if (resolved.isNotBlank() && resolved != api) return resolved
        }
        return api
    }

    private fun downloadHttp(url: String, target: File) {
        Http.get(url, connectTimeout = 30, readTimeout = 60)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: $url")
                }
                val bytes = response.body.bytes()
                if (bytes.isEmpty()) throw IllegalStateException("脚本内容为空: $url")
                target.writeBytes(bytes)
            }
    }

    private fun extractFromJar(api: String, jar: String, target: File): File? {
        val jarFile = locateJarFile(jar) ?: return null
        if (!jarFile.exists()) return null

        val entryName = normalizeJarEntry(api)
        val candidates = linkedSetOf(entryName, "py/$entryName", "./$entryName")
        ZipFile(jarFile).use { zip ->
            for (name in candidates) {
                val entry = zip.getEntry(name) ?: continue
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                log.info("从 jar 解压 Py 脚本: {} -> {}", name, target.absolutePath)
                return target
            }
        }
        return null
    }

    private fun locateJarFile(jar: String): File? {
        val candidates = listOfNotNull(
            jar.takeIf { it.isNotBlank() },
            ApiConfig.api.spider.takeIf { it.isNotBlank() },
        )
        for (candidate in candidates) {
            val url = when {
                candidate.startsWith("http") || candidate.startsWith("file") -> candidate
                else -> Urls.convert(ApiConfig.api.url.orEmpty(), candidate)
            }
            when {
                url.startsWith("file") -> {
                    val file = Paths.local(url)
                    if (file.exists()) return file
                }

                url.startsWith("http") -> {
                    val file = Paths.jar(url)
                    if (file.exists()) return file
                }
            }
        }
        return null
    }

    private fun normalizeJarEntry(api: String): String {
        return api.removePrefix("./").removePrefix("/").replace("\\", "/")
    }
}
