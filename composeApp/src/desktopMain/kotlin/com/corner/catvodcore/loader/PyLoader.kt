package com.corner.catvodcore.loader

import com.corner.server.KtorD
import com.corner.util.core.Constants
import com.corner.util.io.Paths
import com.corner.util.system.SysVerUtil
import com.github.catvod.crawler.Spider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PySpiderProcess(
    private val key: String,
    api: String,
    private val ext: String,
    jar: String,
) : Spider() {
    private val log = LoggerFactory.getLogger("PySpiderProcess")
    private val gson = Gson()
    private val requestId = AtomicInteger()
    private val cacheDir = Paths.py().apply { mkdirs() }
    private val runnerFile = File(cacheDir, "desktop_runner.py")
    private val scriptPath = PyScriptResolver.materialize(api, jar).absolutePath
    private val process: Process
    private val writer: BufferedWriter
    private val reader: BufferedReader
    private val callExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "py-call-$key").apply { isDaemon = true }
    }
    @Volatile
    private var destroyed = false

    companion object {
        private const val CALL_TIMEOUT_SECONDS = 45L
        @Volatile
        private var cachedCaBundle: String? = null
        @Volatile
        private var caResolved = false
    }

    init {
        extractRunner()
        val python = findPython()
        val processBuilder = ProcessBuilder(python.exe, runnerFile.absolutePath, cacheDir.absolutePath, scriptPath, key, api)
            .directory(cacheDir)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        processBuilder.environment().apply {
            put("LUMEN_PROXY_PORT", KtorD.getPort().toString())
            put("LUMEN_PY_CACHE", cacheDir.absolutePath)
            put("PYTHONUNBUFFERED", "1")
            put("PYTHONUTF8", "1")
            python.home?.let { home ->
                put("PYTHONHOME", home.absolutePath)
                // 捆绑 Python 目录 + JDK bin（Win10 兼容库，供 Win7 复用）优先于系统 PATH
                val pathKey = keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
                val oldPath = this[pathKey].orEmpty()
                val prepend = buildList {
                    add(home.absolutePath)
                    File(home, "Scripts").takeIf { it.isDirectory }?.absolutePath?.let(::add)
                    File(home, "bin").takeIf { it.isDirectory }?.absolutePath?.let(::add)
                    File(home, "DLLs").takeIf { it.isDirectory }?.absolutePath?.let(::add)
                    File(System.getProperty("java.home"), "bin").takeIf { it.isDirectory }?.absolutePath?.let(::add)
                }
                this[pathKey] = (prepend + oldPath).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
            }
            // 预置 CA，避免桌面 Python urllib SSL 失败（新浪资源等）
            resolveCaBundle(python.exe)?.let { ca ->
                put("SSL_CERT_FILE", ca)
                put("REQUESTS_CA_BUNDLE", ca)
                put("CURL_CA_BUNDLE", ca)
            }
        }
        process = processBuilder.start()
        writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
        startStderrDrainer()
        init(ext)
    }

    private fun extractRunner() {
        copyResource("py/desktop_runner.py", runnerFile)
        copyResource("py/app.py", File(cacheDir, "app.py"))
        val baseDir = File(cacheDir, "base").apply { mkdirs() }
        copyResource("py/base/spider.py", File(baseDir, "spider.py"))
    }

    private fun copyResource(resourcePath: String, target: File) {
        val resource = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("$resourcePath not found")
        resource.use { input -> target.writeBytes(input.readBytes()) }
    }

    private fun startStderrDrainer() {
        val stderr = process.errorStream ?: return
        Thread({
            BufferedReader(InputStreamReader(stderr, StandardCharsets.UTF_8)).use { err ->
                var line = err.readLine()
                while (line != null) {
                    log.debug("[py:{}] {}", key, line)
                    line = err.readLine()
                }
            }
        }, "py-stderr-$key").apply {
            isDaemon = true
            start()
        }
    }

    private data class PythonRuntime(val exe: String, val home: File?)

    private fun findPython(): PythonRuntime {
        for (candidate in bundledPythonCandidates()) {
            if (!candidate.isFile) continue
            ensureExecutable(candidate)
            if (probePython(candidate.absolutePath)) {
                log.info("使用捆绑 Python: {}", candidate.absolutePath)
                return PythonRuntime(candidate.absolutePath, candidate.parentFile.let { parent ->
                    // bin/python3 → home 为 python 根目录
                    if (parent?.name == "bin") parent.parentFile else parent
                })
            }
        }
        listOf("python3", "python").forEach { cmd ->
            if (probePython(cmd)) {
                log.info("使用系统 Python: {}", cmd)
                return PythonRuntime(cmd, null)
            }
        }
        throw IllegalStateException("未找到捆绑或系统 Python，请安装 Python 3 或执行 ./gradlew prepareBundledPython")
    }

    /** Compose 打包 appResources 时常丢掉 Unix 可执行位，启动前补回。 */
    private fun ensureExecutable(file: File) {
        if (SysVerUtil.currentOs == com.corner.util.system.OperatingSystem.Windows) return
        try {
            if (!file.canExecute()) {
                file.setExecutable(true, false)
            }
            // 同目录 python3.x 等也可能被剥权限
            file.parentFile?.listFiles()?.forEach { sibling ->
                val name = sibling.name
                if (sibling.isFile && (name == "python" || name.startsWith("python3"))) {
                    if (!sibling.canExecute()) sibling.setExecutable(true, false)
                }
            }
        } catch (e: Exception) {
            log.warn("无法设置 Python 可执行权限: {}", file, e)
        }
    }

    private fun bundledPythonCandidates(): List<File> {
        val roots = mutableListOf<File>()
        System.getProperty(Constants.RES_PATH_KEY)?.takeIf { it.isNotBlank() }?.let { res ->
            roots += File(res, "python")
        }
        // 开发态：直接读 appResources
        roots += File(
            System.getProperty("user.dir"),
            "src/desktopMain/appResources/${SysVerUtil.getAppResourcesPlatform()}/python",
        )
        // composeApp 子目录运行时
        roots += File(
            System.getProperty("user.dir"),
            "composeApp/src/desktopMain/appResources/${SysVerUtil.getAppResourcesPlatform()}/python",
        )
        val files = mutableListOf<File>()
        roots.distinctBy { it.absolutePath }.forEach { root ->
            files += listOf(
                File(root, "python.exe"),
                File(root, "python3.exe"),
                File(root, "bin/python3"),
                File(root, "bin/python"),
                File(root, "python3"),
                File(root, "python"),
            )
        }
        return files
    }

    private fun probePython(cmd: String): Boolean {
        return try {
            ProcessBuilder(cmd, "--version").start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveCaBundle(pythonExe: String): String? {
        if (caResolved) return cachedCaBundle
        synchronized(PySpiderProcess::class.java) {
            if (caResolved) return cachedCaBundle
            cachedCaBundle = try {
                val pb = ProcessBuilder(
                    pythonExe,
                    "-c",
                    "import certifi; print(certifi.where())",
                )
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText().trim()
                if (p.waitFor() == 0 && out.isNotBlank() && File(out).isFile) out else null
            } catch (_: Exception) {
                null
            }
            caResolved = true
            return cachedCaBundle
        }
    }

    private fun call(method: String, vararg args: Any?): String {
        if (destroyed) {
            throw IllegalStateException("python process destroyed")
        }
        log.info("[py:{}] call {} start", key, method)
        val future = CompletableFuture.supplyAsync({
            synchronized(this) {
                if (destroyed) {
                    throw IllegalStateException("python process destroyed")
                }
                doCall(method, *args)
            }
        }, callExecutor)
        return try {
            future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).also {
                log.info("[py:{}] call {} done", key, method)
            }
        } catch (e: TimeoutException) {
            log.error("[py:{}] {} timeout after {}s, recycling process", key, method, CALL_TIMEOUT_SECONDS)
            future.cancel(true)
            forceDestroy()
            throw IllegalStateException("python $method timeout")
        } catch (e: Exception) {
            if (e.cause is IllegalStateException) throw e.cause as IllegalStateException
            throw e
        }
    }

    private fun doCall(method: String, vararg args: Any?): String {
        val id = requestId.incrementAndGet()
        val payload = mapOf("id" to id, "method" to method, "args" to args.filterNotNull())
        writer.write(gson.toJson(payload))
        writer.newLine()
        writer.flush()
        val line = readJsonLine() ?: throw IllegalStateException("python process closed")
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val resp: Map<String, Any> = gson.fromJson(line, type)
        if (resp["ok"] != true) {
            val error = resp["error"]?.toString() ?: "python call failed"
            log.error("[py:{}] {} failed: {}", key, method, error)
            throw IllegalStateException(error)
        }
        return resp["result"]?.toString() ?: ""
    }

    private fun forceDestroy() {
        destroyed = true
        try {
            callExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            process.destroyForcibly()
        } catch (_: Exception) {
        }
        PyLoader.removeSpider(key)
    }

    internal fun recycleProcess() = forceDestroy()

    private fun readJsonLine(): String? {
        while (true) {
            val line = reader.readLine() ?: return null
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("{")) return trimmed
            log.warn("[py:{}] 忽略非 JSON 输出: {}", key, trimmed)
        }
    }

    override fun init(extend: String?) {
        call("init", extend ?: "")
    }

    override fun homeContent(filter: Boolean): String = call("homeContent", filter)

    override fun homeVideoContent(): String = call("homeVideoContent")

    override fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>
    ): String = call("categoryContent", tid, pg, filter, gson.toJson(extend))

    override fun detailContent(ids: List<String?>?): String =
        call("detailContent", gson.toJson(ids ?: emptyList<String>()))

    override fun searchContent(key: String?, quick: Boolean): String =
        call("searchContent", key ?: "", quick)

    override fun searchContent(key: String?, quick: Boolean, pg: String?): String =
        call("searchContent", key ?: "", quick, pg ?: "1")

    override fun playerContent(flag: String?, id: String?, vipFlags: List<String?>?): String =
        call("playerContent", flag ?: "", id ?: "", gson.toJson(vipFlags ?: emptyList<String>()))

    override fun liveContent(url: String?): String = call("liveContent", url ?: "")

    override fun proxy(params: Map<String, String>?): Array<Any>? {
        val json = gson.toJson(params ?: emptyMap<String, String>())
        val result = call("localProxy", json)
        if (StringUtils.isBlank(result)) return null
        val list: List<Any> = gson.fromJson(result, object : TypeToken<List<Any>>() {}.type)
        return list.toTypedArray()
    }

    override fun action(action: String?): String? = call("action", action ?: "")

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        try {
            doCall("destroy")
        } catch (e: Exception) {
            log.warn("destroy python spider failed", e)
        } finally {
            try {
                callExecutor.shutdownNow()
            } catch (_: Exception) {
            }
            process.destroyForcibly()
        }
    }
}

object PyLoader {
    private val log = LoggerFactory.getLogger("PyLoader")
    private val spiders = ConcurrentHashMap<String, Spider>()
    @Volatile
    private var recent: String? = null

    fun recycleRecent() {
        val key = recent ?: return
        val spider = spiders.remove(key) ?: return
        log.info("回收 Py 爬虫进程: {}", key)
        if (recent == key) {
            recent = null
        }
        try {
            if (spider is PySpiderProcess) {
                spider.recycleProcess()
            } else {
                spider.destroy()
            }
        } catch (e: Exception) {
            log.warn("回收 Py 爬虫失败", e)
        }
    }

    internal fun removeSpider(key: String) {
        spiders.remove(key)
        if (recent == key) {
            recent = null
        }
    }

    fun clear() {
        spiders.values.forEach {
            try {
                it.destroy()
            } catch (e: Exception) {
                log.warn("destroy py spider failed", e)
            }
        }
        spiders.clear()
        recent = null
    }

    fun setRecent(key: String) {
        recent = key
    }

    fun getSpider(key: String, api: String, ext: String, jar: String): Spider {
        return spiders.computeIfAbsent(key) {
            try {
                val spider = PySpiderProcess(key, api, ext, jar)
                spider.siteKey = key
                spider
            } catch (e: Throwable) {
                log.error("load py spider failed: key={} api={} jar={}", key, api, jar, e)
                Spider()
            }
        }
    }

    fun proxy(params: Map<String, String>): Array<Any>? {
        val key = recent ?: return null
        val spider = spiders[key] ?: return null
        return try {
            spider.proxy(params)
        } catch (e: Exception) {
            log.error("py proxy failed", e)
            null
        }
    }
}
