package com.corner.catvodcore.loader

import com.corner.server.KtorD
import com.corner.util.core.Constants
import com.github.catvod.crawler.Spider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 主进程侧：管理唯一 JarSpiderHost 子进程，NDJSON RPC（对齐 Py）。
 */
object JarSpiderHostClient {
    private val log = LoggerFactory.getLogger("JarSpiderHostClient")
    private val gson = Gson()
    private val lock = ReentrantLock()
    private val requestId = AtomicInteger()
    private val proxies = ConcurrentHashMap<String, JarSpiderProxy>()

    private const val CALL_TIMEOUT_SECONDS = 45L

    @Volatile
    private var process: Process? = null
    @Volatile
    private var writer: BufferedWriter? = null
    @Volatile
    private var reader: BufferedReader? = null
    private val callExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jar-spider-rpc").apply { isDaemon = true }
    }

    fun getSpider(key: String, api: String, ext: String, jar: String): Spider {
        return proxies.computeIfAbsent(key) {
            JarSpiderProxy(key, api, ext, jar)
        }.also { proxy ->
            // 参数可能变化时仍复用 key；ensure 每次调用前再确认
            proxy.update(api, ext, jar)
        }
    }

    fun loadJar(key: String, jar: String) {
        call("loadJar", key, jar)
    }

    fun setRecentJar(jar: String?) {
        call("setRecentJar", jar ?: "")
    }

    fun clear() {
        lock.withLock {
            try {
                if (process?.isAlive == true) {
                    doCallUnlocked("clear")
                }
            } catch (e: Exception) {
                log.warn("jar host clear rpc failed: {}", e.message)
            }
            destroyProcessLocked()
            proxies.clear()
        }
    }

    fun proxyInvoke(params: Map<String, String>): Array<Any>? {
        val json = call("proxyInvoke", gson.toJson(params))
        return decodeProxyResult(json)
    }

    fun jsonExt(key: String, jxs: LinkedHashMap<String, String>, url: String): String? {
        val result = call("jsonExt", key, gson.toJson(jxs), url)
        return result.takeIf { it.isNotBlank() }
    }

    fun jsonExtMix(
        flag: String,
        key: String,
        name: String,
        jxs: LinkedHashMap<String, HashMap<String, String>>,
        url: String,
    ): String? {
        val result = call("jsonExtMix", flag, key, name, gson.toJson(jxs), url)
        return result.takeIf { it.isNotBlank() }
    }

    internal fun ensureSpider(key: String, api: String, ext: String, jar: String) {
        call("ensureSpider", key, api, ext, jar)
    }

    internal fun call(method: String, vararg args: Any?): String {
        val future = CompletableFuture.supplyAsync({
            lock.withLock {
                ensureProcessLocked()
                doCallUnlocked(method, *args)
            }
        }, callExecutor)
        return try {
            future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            log.error("jar host {} timeout after {}s, killing process", method, CALL_TIMEOUT_SECONDS)
            future.cancel(true)
            lock.withLock { destroyProcessLocked() }
            proxies.clear()
            throw IllegalStateException("jar spider $method timeout")
        } catch (e: Exception) {
            val cause = e.cause
            if (cause is IllegalStateException) throw cause
            throw e
        }
    }

    private fun ensureProcessLocked() {
        val alive = process?.isAlive == true
        if (alive && writer != null && reader != null) return
        destroyProcessLocked()
        startProcessLocked()
    }

    private fun startProcessLocked() {
        val javaBin = resolveJavaBinary()
        val cp = resolveClasspath()
        val cmd = mutableListOf(
            javaBin,
            "-Dlumen.jar.spider.host=true",
            "-Dfile.encoding=UTF-8",
        )
        System.getProperty(Constants.RES_PATH_KEY)?.takeIf { it.isNotBlank() }?.let {
            cmd += "-D${Constants.RES_PATH_KEY}=$it"
        }
        // 传递 user.home / 缓存相关，便于 Paths 与主进程一致
        System.getProperty("user.home")?.let { cmd += "-Duser.home=$it" }
        cmd += listOf("-cp", cp, "com.corner.catvodcore.loader.JarSpiderHost")

        val pb = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.PIPE)
        pb.environment()["LUMEN_PROXY_PORT"] = KtorD.getPort().toString()
        log.info("starting JarSpiderHost java={} cpEntries={}", javaBin, cp.split(File.pathSeparator).size)
        val p = pb.start()
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
        reader = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))
        startStderrDrainer(p)
        // 短暂等待；若秒退立刻失败
        Thread.sleep(200)
        if (!p.isAlive) {
            throw IllegalStateException("JarSpiderHost exited immediately")
        }
        doCallUnlocked("ping")
        log.info("JarSpiderHost ready")
    }

    /**
     * 必须与主进程同源 JVM（本工程 class 为 61 / Java 17）。
     * 打包后若退回 PATH 上的 `java`，Windows 常命中系统自带的 Java 8 → UnsupportedClassVersionError。
     */
    private fun resolveJavaBinary(): String {
        val isWin = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val javaName = if (isWin) "java.exe" else "java"
        val javawName = if (isWin) "javaw.exe" else "javaw"
        val candidates = LinkedHashSet<File>()

        fun addJava(dir: File?) {
            if (dir == null) return
            candidates += File(dir, javaName)
            candidates += File(dir, javawName)
        }

        fun addRuntimeBin(appRoot: File?) {
            if (appRoot == null) return
            addJava(File(appRoot, "runtime/bin"))
            addJava(File(appRoot, "bin"))
            appRoot.parentFile?.let { parent ->
                addJava(File(parent, "runtime/bin"))
            }
        }

        // 1) 当前进程命令行：gradle run 时是 java；jpackage 时是 LumenTV.exe
        ProcessHandle.current().info().command().orElse(null)?.let { cmd ->
            val exe = File(cmd)
            val name = exe.name.lowercase()
            when {
                name == "java" || name == "java.exe" || name == "javaw" || name == "javaw.exe" -> {
                    addJava(exe.parentFile)
                }
                else -> addRuntimeBin(exe.parentFile)
            }
        }

        // 2) java.home（正常 JVM / 部分打包布局）
        System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let { home ->
            addJava(File(home, "bin"))
            addJava(File(home, "jre/bin"))
        }

        // 3) jpackage 属性
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { appPath ->
            val app = File(appPath)
            addRuntimeBin(if (app.isFile) app.parentFile else app)
        }

        // 4) 从主 jar 位置推 runtime（…/app/*.jar → …/runtime/bin/java）
        runCatching {
            val loc = JarSpiderHostClient::class.java.protectionDomain?.codeSource?.location ?: return@runCatching
            val file = File(loc.toURI())
            val appDir = if (file.isFile) file.parentFile else file
            addRuntimeBin(appDir)
            addRuntimeBin(appDir?.parentFile)
        }

        val preferred = candidates.firstOrNull { f ->
            f.isFile && f.name.lowercase().let { it == "java" || it == "java.exe" }
        } ?: candidates.firstOrNull { it.isFile }

        if (preferred != null) {
            log.info(
                "JarSpiderHost java resolved: {} (java.home={}, version={})",
                preferred.absolutePath,
                System.getProperty("java.home"),
                System.getProperty("java.version"),
            )
            return preferred.absolutePath
        }

        log.warn(
            "JarSpiderHost 未找到捆绑 java，将使用 PATH 的 java（易命中 Java 8）。java.home={} java.version={} tried={}",
            System.getProperty("java.home"),
            System.getProperty("java.version"),
            candidates.map { it.path },
        )
        return "java"
    }

    private fun resolveClasspath(): String {
        val cp = System.getProperty("java.class.path").orEmpty()
        if (cp.isNotBlank() && cp != "\"\"") {
            // 开发态 / 常规 JVM：直接用当前 classpath
            if (cp.contains("composeApp") || cp.contains(".jar") || cp.contains(File.pathSeparator)) {
                return cp
            }
        }
        // jpackage / 模块路径兜底：用 code source 目录
        val codeSource = JarSpiderHostClient::class.java.protectionDomain?.codeSource?.location
        if (codeSource != null) {
            val file = File(codeSource.toURI())
            if (file.isFile) {
                // 单 jar：用该 jar；同目录下其它 jar 一并加入
                val dir = file.parentFile
                val jars = dir?.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.map { it.absolutePath }
                if (!jars.isNullOrEmpty()) {
                    return jars.joinToString(File.pathSeparator)
                }
                return file.absolutePath
            }
            if (file.isDirectory) {
                return file.absolutePath
            }
        }
        throw IllegalStateException(
            "无法解析 JarSpiderHost classpath（java.class.path 为空或无效）。开发态请用 gradle run；打包请确保 jar 可定位。"
        )
    }

    private fun startStderrDrainer(p: Process) {
        val err = p.errorStream ?: return
        Thread({
            BufferedReader(InputStreamReader(err, StandardCharsets.UTF_8)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    log.warn("[jar-host] {}", line)
                    line = br.readLine()
                }
            }
        }, "jar-host-stderr").apply { isDaemon = true; start() }
    }

    private fun doCallUnlocked(method: String, vararg args: Any?): String {
        val w = writer ?: throw IllegalStateException("jar host writer null")
        val r = reader ?: throw IllegalStateException("jar host reader null")
        if (process?.isAlive != true) {
            throw IllegalStateException("jar host process dead")
        }
        val id = requestId.incrementAndGet()
        val payload = mapOf("id" to id, "method" to method, "args" to args.filterNotNull())
        w.write(gson.toJson(payload))
        w.newLine()
        w.flush()
        val line = readJsonLine(r) ?: throw IllegalStateException("jar host closed")
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val resp: Map<String, Any> = gson.fromJson(line, type)
        if (resp["ok"] != true) {
            val error = resp["error"]?.toString() ?: "jar host call failed"
            throw IllegalStateException(error)
        }
        return resp["result"]?.toString() ?: ""
    }

    private fun readJsonLine(r: BufferedReader): String? {
        while (true) {
            val line = r.readLine() ?: return null
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("{")) return trimmed
            log.warn("ignore non-json from jar host: {}", trimmed)
        }
    }

    private fun destroyProcessLocked() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {
        }
        writer = null
        reader = null
        process = null
    }

    internal fun decodeProxyResult(json: String): Array<Any>? {
        if (StringUtils.isBlank(json)) return null
        val listType = object : TypeToken<List<Any?>>() {}.type
        val list: List<Any?> = gson.fromJson(json, listType) ?: return null
        if (list.isEmpty()) return null
        return Array(list.size) { i -> decodeProxyElement(list[i]) ?: "" }
    }

    private fun decodeProxyElement(value: Any?): Any? {
        if (value == null) return null
        if (value is Map<*, *>) {
            when (value["__type"]?.toString()) {
                "bytes" -> {
                    val data = value["data"]?.toString() ?: return ByteArrayInputStream(ByteArray(0))
                    val bytes = Base64.getDecoder().decode(data)
                    return ByteArrayInputStream(bytes)
                }
                "response" -> {
                    val code = (value["code"] as? Number)?.toInt() ?: 500
                    val bodyB64 = value["body"]?.toString() ?: ""
                    val bytes = if (bodyB64.isBlank()) ByteArray(0) else Base64.getDecoder().decode(bodyB64)
                    val contentType = value["contentType"]?.toString().orEmpty()
                    @Suppress("UNCHECKED_CAST")
                    val headers = (value["headers"] as? Map<String, String>) ?: emptyMap()
                    val builder = Response.Builder()
                        .request(Request.Builder().url("http://127.0.0.1/").build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("OK")
                        .body(bytes.toResponseBody(contentType.toMediaTypeOrNull()))
                    headers.forEach { (k, v) -> builder.header(k, v) }
                    return builder.build()
                }
            }
        }
        return when (value) {
            is Double -> if (value % 1.0 == 0.0) value.toInt() else value
            is Float -> if (value % 1f == 0f) value.toInt() else value
            else -> value
        }
    }

    fun removeProxy(key: String) {
        proxies.remove(key)
    }
}

/**
 * 主进程 Spider 代理：所有内容调用转发到 JarSpiderHost。
 */
class JarSpiderProxy(
    private val key: String,
    private var api: String,
    private var ext: String,
    private var jar: String,
) : Spider() {
    private val gson = Gson()

    init {
        siteKey = key
        JarSpiderHostClient.ensureSpider(key, api, ext, jar)
    }

    fun update(api: String, ext: String, jar: String) {
        if (this.api == api && this.ext == ext && this.jar == jar) return
        this.api = api
        this.ext = ext
        this.jar = jar
        JarSpiderHostClient.ensureSpider(key, api, ext, jar)
    }

    private fun ensure() = JarSpiderHostClient.ensureSpider(key, api, ext, jar)

    private fun call(method: String, vararg args: Any?): String {
        ensure()
        return JarSpiderHostClient.call(method, *args)
    }

    override fun init(extend: String?) {
        ext = extend ?: ""
        ensure()
    }

    override fun homeContent(filter: Boolean): String = call("homeContent", key, filter)

    override fun homeVideoContent(): String = call("homeVideoContent", key)

    override fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>,
    ): String = call("categoryContent", key, tid, pg, filter, gson.toJson(extend))

    override fun detailContent(ids: List<String?>?): String =
        call("detailContent", key, gson.toJson(ids ?: emptyList<String>()))

    override fun searchContent(key: String?, quick: Boolean): String =
        call("searchContent", this.key, key ?: "", quick)

    override fun searchContent(key: String?, quick: Boolean, pg: String?): String =
        call("searchContent", this.key, key ?: "", quick, pg ?: "1")

    override fun playerContent(flag: String?, id: String?, vipFlags: List<String?>?): String =
        call("playerContent", this.key, flag ?: "", id ?: "", gson.toJson(vipFlags ?: emptyList<String>()))

    override fun liveContent(url: String?): String = call("liveContent", key, url ?: "")

    override fun proxy(params: Map<String, String>?): Array<Any>? {
        val json = call("localProxy", key, gson.toJson(params ?: emptyMap<String, String>()))
        return JarSpiderHostClient.decodeProxyResult(json)
    }

    override fun action(action: String?): String? = call("action", key, action ?: "")

    override fun destroy() {
        try {
            JarSpiderHostClient.call("destroy", key)
        } catch (_: Exception) {
        }
        JarSpiderHostClient.removeProxy(key)
    }
}
