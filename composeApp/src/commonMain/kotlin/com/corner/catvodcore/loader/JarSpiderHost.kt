package com.corner.catvodcore.loader

import com.github.catvod.crawler.Spider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Jar 爬虫 sidecar 入口：stdin/stdout NDJSON，进程内复用 [JarLoader]。
 * 启动：`java -Dlumen.jar.spider.host=true -cp ... com.corner.catvodcore.loader.JarSpiderHost`
 */
object JarSpiderHost {
    private val log = LoggerFactory.getLogger("JarSpiderHost")
    private val gson = Gson()
    private val spiders = ConcurrentHashMap<String, Spider>()

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty(HOST_PROP, "true")
        // 协议占用 stdout；把 System.out 指到 stderr，避免 log 打断 NDJSON
        val protocolOut = System.out
        System.setOut(System.err)
        val reader = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
        val writer = OutputStreamWriter(protocolOut, StandardCharsets.UTF_8)
        log.info("JarSpiderHost started, proxyPort={}", System.getenv("LUMEN_PROXY_PORT"))
        while (true) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue
            val resp = handleLine(trimmed)
            synchronized(writer) {
                writer.write(resp)
                writer.write("\n")
                writer.flush()
            }
        }
        log.info("JarSpiderHost stdin closed, exit")
    }

    private fun handleLine(line: String): String {
        return try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val req: Map<String, Any> = gson.fromJson(line, type)
            val id = (req["id"] as? Number)?.toInt() ?: 0
            val method = req["method"]?.toString() ?: ""
            @Suppress("UNCHECKED_CAST")
            val args = (req["args"] as? List<Any?>) ?: emptyList()
            val result = dispatch(method, args)
            gson.toJson(mapOf("id" to id, "ok" to true, "result" to result))
        } catch (e: Exception) {
            log.error("host handle failed: {}", e.message, e)
            val id = try {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val req: Map<String, Any> = gson.fromJson(line, type)
                (req["id"] as? Number)?.toInt() ?: 0
            } catch (_: Exception) {
                0
            }
            gson.toJson(
                mapOf(
                    "id" to id,
                    "ok" to false,
                    "error" to (e.message ?: e.javaClass.simpleName),
                )
            )
        }
    }

    private fun dispatch(method: String, args: List<Any?>): Any? {
        return when (method) {
            "ping" -> "pong"
            "loadJar" -> {
                val key = args.getString(0)
                val jar = args.getString(1)
                JarLoader.loadJar(key, jar)
                ""
            }
            "setRecentJar" -> {
                JarLoader.setRecentJar(args.getOrNull(0)?.toString())
                ""
            }
            "clear" -> {
                spiders.clear()
                JarLoader.clear()
                ""
            }
            "ensureSpider" -> {
                val key = args.getString(0)
                val api = args.getString(1)
                val ext = args.getString(2)
                val jar = args.getString(3)
                val spider = JarLoader.getSpider(key, api, ext, jar)
                spider.siteKey = key
                spiders[key] = spider
                ""
            }
            "homeContent" -> spider(args.getString(0)).homeContent(args.getBoolean(1))
            "homeVideoContent" -> spider(args.getString(0)).homeVideoContent()
            "categoryContent" -> {
                val extendType = object : TypeToken<HashMap<String, String>>() {}.type
                val extend: HashMap<String, String> =
                    gson.fromJson(args.getString(4), extendType) ?: hashMapOf()
                spider(args.getString(0)).categoryContent(
                    args.getString(1),
                    args.getString(2),
                    args.getBoolean(3),
                    extend,
                )
            }
            "detailContent" -> {
                val idsType = object : TypeToken<List<String?>>() {}.type
                val ids: List<String?> = gson.fromJson(args.getString(1), idsType) ?: emptyList()
                spider(args.getString(0)).detailContent(ids)
            }
            "searchContent" -> {
                val key = args.getString(0)
                val word = args.getString(1)
                val quick = args.getBoolean(2)
                if (args.size >= 4) {
                    spider(key).searchContent(word, quick, args.getString(3))
                } else {
                    spider(key).searchContent(word, quick)
                }
            }
            "playerContent" -> {
                val vipType = object : TypeToken<List<String?>>() {}.type
                val vip: List<String?> = gson.fromJson(args.getString(3), vipType) ?: emptyList()
                spider(args.getString(0)).playerContent(args.getString(1), args.getString(2), vip)
            }
            "liveContent" -> spider(args.getString(0)).liveContent(args.getString(1))
            "action" -> spider(args.getString(0)).action(args.getString(1)) ?: ""
            "destroy" -> {
                val key = args.getString(0)
                spiders.remove(key)?.destroy()
                ""
            }
            "localProxy" -> {
                val key = args.getString(0)
                val paramsType = object : TypeToken<Map<String, String>>() {}.type
                val params: Map<String, String> =
                    gson.fromJson(args.getString(1), paramsType) ?: emptyMap()
                encodeProxyResult(spider(key).proxy(params))
            }
            "proxyInvoke" -> {
                val paramsType = object : TypeToken<Map<String, String>>() {}.type
                val params: Map<String, String> =
                    gson.fromJson(args.getString(0), paramsType) ?: emptyMap()
                encodeProxyResult(JarLoader.proxyInvoke(params))
            }
            "jsonExt" -> {
                val jxsType = object : TypeToken<LinkedHashMap<String, String>>() {}.type
                val jxs: LinkedHashMap<String, String> =
                    gson.fromJson(args.getString(1), jxsType) ?: linkedMapOf()
                JarLoader.jsonExt(args.getString(0), jxs, args.getString(2)) ?: ""
            }
            "jsonExtMix" -> {
                val jxsType =
                    object : TypeToken<LinkedHashMap<String, HashMap<String, String>>>() {}.type
                val jxs: LinkedHashMap<String, HashMap<String, String>> =
                    gson.fromJson(args.getString(3), jxsType) ?: linkedMapOf()
                JarLoader.jsonExtMix(
                    args.getString(0),
                    args.getString(1),
                    args.getString(2),
                    jxs,
                    args.getString(4),
                ) ?: ""
            }
            else -> throw IllegalArgumentException("unknown method: $method")
        }
    }

    private fun spider(key: String): Spider =
        spiders[key] ?: throw IllegalStateException("spider not ensured: $key")

    private fun List<Any?>.getString(i: Int): String = getOrNull(i)?.toString() ?: ""

    private fun List<Any?>.getBoolean(i: Int): Boolean {
        val v = getOrNull(i) ?: return false
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> v.toString().equals("true", ignoreCase = true)
        }
    }

    /** 将 proxy 结果编码为可 JSON 传输的结构（字符串）。 */
    internal fun encodeProxyResult(result: Array<Any>?): String {
        if (result == null) return ""
        val encoded = result.map { encodeProxyElement(it) }
        return gson.toJson(encoded)
    }

    private fun encodeProxyElement(value: Any?): Any? {
        return when (value) {
            null -> null
            is Number, is Boolean, is String -> value
            is ByteArray -> mapOf("__type" to "bytes", "data" to Base64.getEncoder().encodeToString(value))
            is InputStream -> {
                val bytes = value.use { it.readBytes() }
                mapOf("__type" to "bytes", "data" to Base64.getEncoder().encodeToString(bytes))
            }
            is Map<*, *> -> value
            is okhttp3.Response -> {
                val bodyBytes = value.body?.bytes() ?: ByteArray(0)
                val headers = linkedMapOf<String, String>()
                value.headers.forEach { (n, v) -> headers[n] = v }
                mapOf(
                    "__type" to "response",
                    "code" to value.code,
                    "contentType" to (value.header("Content-Type") ?: ""),
                    "headers" to headers,
                    "body" to Base64.getEncoder().encodeToString(bodyBytes),
                )
            }
            else -> value.toString()
        }
    }

    const val HOST_PROP = "lumen.jar.spider.host"

    fun isHostProcess(): Boolean =
        System.getProperty(HOST_PROP) == "true"
}
