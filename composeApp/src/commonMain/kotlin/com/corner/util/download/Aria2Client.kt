package com.corner.util.download

import com.corner.util.io.Paths
import com.corner.util.settings.SettingStore
import com.corner.util.settings.SettingType
import com.github.catvod.net.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI

object DownloadSetting {
    fun isEnabled(): Boolean {
        return SettingStore.getSettingItem(SettingType.ARIA2_ENABLED).ifBlank { "false" }.toBoolean()
    }

    fun rpcUrl(): String {
        return SettingStore.getSettingItem(SettingType.ARIA2_RPC)
            .ifBlank { "http://127.0.0.1:6800/jsonrpc" }
            .trim()
    }

    fun secret(): String = SettingStore.getSettingItem(SettingType.ARIA2_SECRET).trim()

    /** aria2 JSON-RPC 要求密钥形如 token:xxx */
    fun rpcToken(): String? {
        val raw = secret()
        if (raw.isBlank()) return null
        return if (raw.startsWith("token:", ignoreCase = true)) raw else "token:$raw"
    }

    /** 仅当用户显式配置了目录时才传给 aria2（远程机不要传本机路径） */
    fun remoteDirOption(): String? {
        val dir = SettingStore.getSettingItem(SettingType.ARIA2_DIR).trim().takeIf { it.isNotBlank() }
            ?: return null
        if (isRemoteRpc() && looksLikeLocalClientPath(dir)) return null
        return dir
    }

    private fun isRemoteRpc(): Boolean {
        val host = runCatching { URI(rpcUrl()).host }.getOrNull()?.lowercase().orEmpty()
        return host.isNotBlank() && host !in setOf("127.0.0.1", "localhost", "::1")
    }

    private fun looksLikeLocalClientPath(dir: String): Boolean {
        return dir.startsWith("/Users/") ||
            dir.startsWith("/home/") ||
            dir.matches(Regex("""^[A-Za-z]:[\\/].*"""))
    }

    fun downloadDir(): File {
        val configured = SettingStore.getSettingItem(SettingType.ARIA2_DIR)
        val dir = if (configured.isBlank()) Paths.download() else File(configured)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}

object Aria2Client {
    private val log = LoggerFactory.getLogger("Aria2Client")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun addUri(uri: String, name: String = ""): Result<String> = withContext(Dispatchers.IO) {
        if (!DownloadSetting.isEnabled()) {
            return@withContext Result.failure(IllegalStateException("aria2 未启用"))
        }
        try {
            val options = buildJsonObject {
                DownloadSetting.remoteDirOption()?.let { put("dir", it) }
                if (name.isNotBlank()) put("out", sanitizeFileName(name))
            }
            val params = buildJsonArray {
                DownloadSetting.rpcToken()?.let { add(JsonPrimitive(it)) }
                add(buildJsonArray { add(JsonPrimitive(uri)) })
                add(options)
            }
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "lumen")
                put("method", "aria2.addUri")
                put("params", params)
            }.toString()
            val response = OkHttp.post(DownloadSetting.rpcUrl(), body)
            val parsed = json.decodeFromString<RpcResponse>(response)
            if (parsed.error != null) {
                Result.failure(IllegalStateException(parsed.error.message))
            } else {
                log.info("aria2 任务已添加: {} -> {}", uri.take(120), parsed.result)
                Result.success(parsed.result?.toString().orEmpty())
            }
        } catch (e: Exception) {
            log.warn("aria2 添加任务失败: {}", e.message)
            Result.failure(e)
        }
    }

    suspend fun getVersion(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonArray {
                DownloadSetting.rpcToken()?.let { add(JsonPrimitive(it)) }
            }
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "lumen")
                put("method", "aria2.getVersion")
                put("params", params)
            }.toString()
            val response = OkHttp.post(DownloadSetting.rpcUrl(), body)
            val parsed = json.decodeFromString<RpcResponse>(response)
            if (parsed.error != null) {
                Result.failure(IllegalStateException(parsed.error.message))
            } else {
                val version = when (val r = parsed.result) {
                    is JsonObject -> r["version"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: r.toString()
                    else -> r?.toString().orEmpty()
                }
                Result.success("aria2 $version")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
    }
}

object DownloadHelper {
    private val log = LoggerFactory.getLogger("DownloadHelper")

    suspend fun startDownload(url: String, name: String = ""): Boolean {
        val resolved = DownloadUrlResolver.resolve(url)
        val fileName = name.ifBlank { DownloadUrlResolver.displayName(url) }
        if (DownloadSetting.isEnabled()) {
            val result = Aria2Client.addUri(resolved, fileName)
            if (result.isSuccess) return true
            log.warn("aria2 下载失败，尝试系统默认方式: {}", result.exceptionOrNull()?.message)
        }
        return openWithSystem(resolved, fileName)
    }

    fun openWithSystem(url: String, name: String = ""): Boolean {
        val resolved = DownloadUrlResolver.resolve(url)
        return try {
            val desktop = java.awt.Desktop.getDesktop()
            when {
                resolved.startsWith("file://", ignoreCase = true) ||
                    (!resolved.contains("://") && java.io.File(resolved).exists()) -> {
                    val file = if (resolved.startsWith("file://", ignoreCase = true)) {
                        java.io.File(URI(resolved))
                    } else {
                        java.io.File(resolved)
                    }
                    desktop.open(file)
                    true
                }
                desktop.isSupported(java.awt.Desktop.Action.BROWSE) -> {
                    desktop.browse(java.net.URI(resolved))
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            log.error("系统打开下载链接失败: {}", name, e)
            false
        }
    }
}

@Serializable
private data class RpcResponse(
    val result: JsonElement? = null,
    val error: RpcError? = null,
)

@Serializable
private data class RpcError(
    val code: Int = 0,
    val message: String = "",
)
