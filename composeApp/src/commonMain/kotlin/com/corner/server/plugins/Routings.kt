package com.corner.server.plugins

import cn.hutool.core.io.file.FileNameUtil
import com.corner.catvodcore.viewmodel.GlobalAppState.hideProgress
import com.corner.catvodcore.viewmodel.GlobalAppState.showProgress
import com.corner.server.logic.proxy
import com.corner.server.ActionHandler
import com.corner.server.LocalFileHandler
import com.corner.server.PlaybackMediaState
import com.corner.server.RemoteDeviceInfo
import com.corner.util.net.createDefaultOkHttpClient
import com.corner.ui.scene.SnackBar
import com.corner.util.m3u8.M3U8Cache
import com.corner.util.toSingleValueMap
import com.corner.util.jcef.JcefBrowserManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import okhttp3.Request
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * 错误响应
 */
suspend fun errorResp(call: ApplicationCall) {
    call.respondText(
        text = HttpStatusCode.InternalServerError.description,
        contentType = ContentType.Application.OctetStream,
        status = HttpStatusCode.InternalServerError
    ) {}
}

/**
 * 错误响应
 */
suspend fun errorResp(call: ApplicationCall, msg: String) {
    call.respondText(
        text = msg,
        contentType = ContentType.Application.OctetStream,
        status = HttpStatusCode.InternalServerError
    ) {}
}

private fun escapeJs(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}

fun Application.configureRouting() {
    routing {
        get("/action") {
            ActionHandler.handle(call.request.queryParameters.toSingleValueMap())
            call.respondText("OK")
        }
        post("/action") {
            ActionHandler.handle(call.receiveParameters().toSingleValueMap())
            call.respondText("OK")
        }
        get("/media") {
            call.respondText(PlaybackMediaState.toJson(), ContentType.Application.Json)
        }
        get("/device") {
            call.respondText(RemoteDeviceInfo.toJson(), ContentType.Application.Json)
        }

        get("/file") {
            LocalFileHandler.handleFile(call, "/")
        }
        get("/file/{path...}") {
            val subPath = call.parameters.getAll("path")?.joinToString("/").orEmpty()
            LocalFileHandler.handleFile(call, "/$subPath")
        }
        post("/upload") {
            LocalFileHandler.handleUpload(call)
        }
        post("/newFolder") {
            LocalFileHandler.handleNewFolder(call)
        }
        post("/delFolder") {
            LocalFileHandler.handleDelete(call)
        }
        post("/delFile") {
            LocalFileHandler.handleDelete(call)
        }

        staticResources("/css", "remote/css") {
            contentType { ContentType.Text.CSS }
        }
        staticResources("/js", "remote/js") {
            contentType {
                if (it.path.endsWith(".js")) ContentType.Text.JavaScript else null
            }
        }

        get("/openapi/documentation.yaml") {
            val resource = this::class.java.classLoader.getResourceAsStream("openapi/documentation.yaml")
            if (resource != null) {
                val content = resource.bufferedReader().use { it.readText() }
                call.respondText(content, ContentType.parse("application/yaml"))
            } else {
                call.respondText("OpenAPI 文档未找到", status = HttpStatusCode.NotFound)
            }
        }

        get("/swagger") {
            val html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>LumenTV API Documentation</title>
                    <link rel="stylesheet" type="text/css" href="https://unpkg.com/swagger-ui-dist@5.10.0/swagger-ui.css" />
                    <style>
                        html { box-sizing: border-box; overflow: -moz-scrollbars-vertical; overflow-y: scroll; }
                        *, *:before, *:after { box-sizing: inherit; }
                        body { margin:0; background: #fafafa; }
                    </style>
                </head>
                <body>
                    <div id="swagger-ui"></div>
                    <script src="https://unpkg.com/swagger-ui-dist@5.10.0/swagger-ui-bundle.js"></script>
                    <script src="https://unpkg.com/swagger-ui-dist@5.10.0/swagger-ui-standalone-preset.js"></script>
                    <script>
                        window.onload = function() {
                            const ui = SwaggerUIBundle({
                                url: '/openapi/documentation.yaml',
                                dom_id: '#swagger-ui',
                                deepLinking: true,
                                presets: [
                                    SwaggerUIBundle.presets.apis,
                                    SwaggerUIStandalonePreset
                                ],
                                plugins: [
                                    SwaggerUIBundle.plugins.DownloadUrl
                                ],
                                layout: "StandaloneLayout"
                            });
                            window.ui = ui;
                        };
                    </script>
                </body>
                </html>
            """.trimIndent()
            call.respondText(html, ContentType.Text.Html)
        }
        
        // 处理 CORS 预检请求
        options("/video/proxy") {
            call.response.header("Access-Control-Allow-Origin", call.request.headers["Origin"] ?: "*")
            call.response.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            call.response.header("Access-Control-Allow-Headers", "*")
            call.response.header("Access-Control-Allow-Credentials", "true")
            call.respond(HttpStatusCode.OK)
        }

        /**
         * 健康检查
         */
        get("/health") {
            call.respondText("OK", ContentType.Text.Plain)
        }

        /**
         * Web 解析页面（与 TV /parse 兼容）
         */
        get("/parse") {
            val jxs = call.request.queryParameters["jxs"].orEmpty()
            val url = call.request.queryParameters["url"].orEmpty()
            val template = this::class.java.classLoader
                .getResourceAsStream("parse.html")
                ?.bufferedReader()
                ?.use { it.readText() }
            if (template == null) {
                call.respondText("parse.html not found", status = HttpStatusCode.NotFound)
            } else {
                val html = String.format(template, escapeJs(jxs), escapeJs(url))
                call.respondText(html, ContentType.Text.Html)
            }
        }

        /**
         * 获取浏览器状态
         */
        get("/api/jcef/status") {
            call.respond(
                mapOf(
                    "available" to JcefBrowserManager.isAvailable().toString(),
                    "installed" to JcefBrowserManager.isNativeInstalled().toString(),
                    "path" to JcefBrowserManager.getInstallDir().absolutePath,
                )
            )
        }

        /**
         * 显示全局加载指示器
         * GET /api/progress/show?message=可选的提示消息
         */
        get("/api/progress/show") {
            val message = call.request.queryParameters["message"]
            showProgress()
                    
            // 如果提供了消息，同时显示 SnackBar 提示
            if (!message.isNullOrBlank()) {
                SnackBar.postMsg(message, type = SnackBar.MessageType.INFO, key = "api_progress")
            }
                    
            call.respond(mapOf<String, String>(
                "success" to "true",
                "message" to "加载指示器已显示"
            ))
        }
        
        /**
         * 隐藏全局加载指示器
         * GET /api/progress/hide
         */
        get("/api/progress/hide") {
            hideProgress()
                    
            call.respond(mapOf<String, String>(
                "success" to "true",
                "message" to "加载指示器已隐藏"
            ))
        }
                
        // 保留旧 API 路径以保持兼容性（标记为废弃）
        get("/postShowProgress") { 
            showProgress()
            call.respondText("已显示加载指示器（请使用 /api/progress/show）")
        }
        
        get("/postHideProgress") {
            hideProgress()
            call.respondText("已隐藏加载指示器（请使用 /api/progress/hide）")
        }

        /**
         * 静态资源
         */
        staticResources("/static", "assets") {
            contentType {
                val suffix = FileNameUtil.getSuffix(it.path)
                when (suffix) {
                    "js" -> ContentType.Text.JavaScript
                    "jsx" -> ContentType.Application.JavaScript
                    "html" -> ContentType.Text.Html
                    "txt" -> ContentType.Text.Plain
                    "htm" -> ContentType.Text.Html
                    else -> null
                }
            }
        }

        /**
         * 根目录
         */
        get("/") {
            try {
                val resource = this::class.java.classLoader.getResourceAsStream("remote/index.html")
                if (resource != null) {
                    val content = resource.bufferedReader().use { it.readText() }
                    call.respondText(content, ContentType.Text.Html)
                } else {
                    call.respondText("LumenTV 遥控面板未找到", status = HttpStatusCode.NotFound)
                }
            } catch (e: Exception) {
                log.error("根路径访问失败", e)
                call.respondText(
                    "服务器内部错误: ${e.message}",
                    status = HttpStatusCode.InternalServerError
                )
            }
        }

        /**
         * 弹窗消息
         * 支持 GET 和 POST 请求
         * GET: /postMsg?msg=消息内容&type=INFO&priority=0
         * POST: {"msg": "消息内容", "type": "INFO", "priority": 0, "key": "unique_key"}
         */
        get("/postMsg") {
            val msg = call.request.queryParameters["msg"]
            if (msg?.isBlank() == true) {
                call.respond(HttpStatusCode.MultiStatus, "消息不可为空")
                return@get
            }
            
            // 解析可选参数
            val typeStr = call.request.queryParameters["type"] ?: "INFO"
            val priorityStr = call.request.queryParameters["priority"] ?: "0"
            val key = call.request.queryParameters["key"]
            
            // 转换消息类型
            val messageType = try {
                com.corner.ui.scene.SnackBar.MessageType.valueOf(typeStr.uppercase())
            } catch (e: Exception) {
                com.corner.ui.scene.SnackBar.MessageType.INFO
            }
            
            // 转换优先级
            val priority = priorityStr.toIntOrNull() ?: 0
            
            SnackBar.postMsg(msg!!, priority, messageType, key)
            call.respondText("消息已发送: $msg (类型: $messageType, 优先级: $priority)")
        }
        
        post("/postMsg") {
            try {
                val requestBody = call.receive<Map<String, String>>()
                val msg = requestBody["msg"]
                
                if (msg.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf<String, Any>(
                        "error" to "消息内容不能为空",
                        "required_fields" to listOf("msg"),
                        "optional_fields" to listOf("type", "priority", "key")
                    ))
                    return@post
                }
                
                // 解析可选参数
                val typeStr = requestBody["type"] ?: "INFO"
                val priorityStr = requestBody["priority"] ?: "0"
                val key = requestBody["key"]
                
                // 转换消息类型
                val messageType = try {
                    com.corner.ui.scene.SnackBar.MessageType.valueOf(typeStr.uppercase())
                } catch (e: Exception) {
                    com.corner.ui.scene.SnackBar.MessageType.INFO
                }
                
                // 转换优先级
                val priority = priorityStr.toIntOrNull() ?: 0
                
                SnackBar.postMsg(msg, priority, messageType, key)
                
                call.respondText(
                    """{"success":true,"message":"消息已发送","data":{"content":"${msg.replace("\"", "\\\"")}","type":"${messageType}","priority":$priority,"key":"${key ?: ""}"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK
                )
            } catch (e: Exception) {
                log.error("处理 POST /postMsg 请求失败", e)
                call.respond(HttpStatusCode.BadRequest, mapOf<String, String>(
                    "error" to "请求格式错误: ${e.message}"
                ))
            }
        }

        /**
         * 代理
         * 播放器必须支持range请求 否则会返回完整资源 导致拨动进度条加载缓慢
         */
        get("/proxy") {
            val parameters = call.request.queryParameters
            val paramMap = parameters.toSingleValueMap().toMutableMap()
            paramMap.putAll(call.request.headers.toSingleValueMap())
            try {
                val objects: Array<Any> = proxy(paramMap) ?: arrayOf()
                if (objects.isEmpty()) {
                    errorResp(call)
                } else when {
                    objects[0] is Response -> (objects[0] as Response).use { response ->
                        response.headers.forEach { (name, value) ->
                            if (!HttpHeaders.isUnsafe(name)) {
                                call.response.headers.append(name, value)
                            }
                        }
                        // 设置 m3u8 的 Content-Type
                        val contentType = if (response.header("Content-Type")?.contains("m3u8") == true) {
                            ContentType.parse("application/vnd.apple.mpegurl")
                        } else {
                            response.header("Content-Type")?.let { ContentType.parse(it) }
                        }
                        call.respondOutputStream(
                            status = HttpStatusCode.fromValue(response.code),
                            contentType = contentType
                        ) {
                            response.body.byteStream().use { it.transferTo(this) }
                        }
                    }

                    objects[0] == HttpStatusCode.Found.value -> {
                        val redirectUrl = objects[2] as? String ?: run {
                            errorResp(call)
                            return@get
                        }
                        call.respondRedirect(Url(redirectUrl), false)
                    }

                    else -> {
                        (objects.getOrNull(3) as? Map<*, *>)?.forEach { (t, u) ->
                            if (t is String && u is String) call.response.headers.append(t, u)
                        }
                        (objects[2] as? InputStream)?.use { inputStream ->
                            val contentType = if (objects[1].toString().contains("m3u8")) {
                                ContentType.parse("application/vnd.apple.mpegurl")
                            } else {
                                ContentType.parse(objects[1].toString())
                            }
                            call.respondOutputStream(
                                contentType = contentType,
                                status = HttpStatusCode.fromValue(
                                    objects[0] as? Int ?: HttpStatusCode.InternalServerError.value
                                )
                            ) {
                                inputStream.transferTo(this)
                            }
                        } ?: errorResp(call)
                    }
                }
            } catch (_: IOException) {
            } catch (e: Exception) {
                log.error("proxy处理失败", e)
            }
        }

        /**
         * web播放器视频代理
         */
        get("/video/proxy") {
            val url = call.request.queryParameters["url"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "缺少URL参数")
                return@get
            }

            // 添加基本的安全检查
            if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                call.respond(HttpStatusCode.BadRequest, "无效的URL格式")
                return@get
            }

            // 使用带代理配置的HTTP客户端
            val client = createDefaultOkHttpClient()

            try {
                fun buildUpstreamRequest(withRange: Boolean): Request {
                    val requestBuilder = Request.Builder()
                        .url(url)
                        .header("Accept", "*/*")
                        .header("Connection", "keep-alive")
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )

                    // Cookie/Referer/UA 等由 OkHttp 注入（内嵌 VLC 不可靠）
                    PlaybackMediaState.headers.forEach { (key, value) ->
                        if (key.isBlank() || value.isBlank()) return@forEach
                        if (key.equals("Accept-Encoding", ignoreCase = true)) return@forEach
                        if (key.equals("Range", ignoreCase = true)) return@forEach
                        requestBuilder.header(key, value)
                    }
                    // 站点头之后再固定：避免 gzip 导致长度与实体不一致
                    requestBuilder.header("Accept-Encoding", "identity")

                    if (withRange) {
                        call.request.headers[HttpHeaders.Range]?.takeIf { it.isNotBlank() }?.let {
                            requestBuilder.header(HttpHeaders.Range, it)
                        }
                    }
                    return requestBuilder.build()
                }

                val clientRange = call.request.headers[HttpHeaders.Range]
                var response = client.newCall(buildUpstreamRequest(withRange = true)).execute()
                // VLC 探长/拖动偶发 Range 不被 CDN 接受 → 416；去掉 Range 再拉整段
                if (response.code == HttpStatusCode.RequestedRangeNotSatisfiable.value && !clientRange.isNullOrBlank()) {
                    log.debug("上游 416，去掉 Range 重试 url={}", url.take(120))
                    response.close()
                    response = client.newCall(buildUpstreamRequest(withRange = false)).execute()
                }

                if (!response.isSuccessful) {
                    val errorMsg = "上游服务器错误: ${response.code} ${response.message}"
                    log.warn("视频代理失败: {} -> {}", response.code, url.take(120))
                    response.close()
                    call.respond(HttpStatusCode.BadGateway, errorMsg)
                    return@get
                }

                val skipHeaders = setOf(
                    "transfer-encoding", "connection", "content-encoding",
                    "access-control-allow-origin", "access-control-allow-methods",
                    "access-control-allow-headers",
                )
                response.headers.names().forEach { name ->
                    if (HttpHeaders.isUnsafe(name) || name.lowercase() in skipHeaders) return@forEach
                    val values = response.headers(name)
                    val value = if (values.size == 1) values[0] else values.joinToString(", ")
                    call.response.headers.append(name, value)
                }
                if (call.response.headers[HttpHeaders.AcceptRanges].isNullOrBlank()) {
                    call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
                }

                val contentType = when {
                    response.header("Content-Type")?.contains("m3u8") == true ->
                        ContentType.parse("application/vnd.apple.mpegurl")
                    response.header("Content-Type")?.contains("mpegurl") == true ->
                        ContentType.parse("application/vnd.apple.mpegurl")
                    response.header("Content-Type")?.contains("video") == true ->
                        response.header("Content-Type")?.let { ContentType.parse(it) }
                            ?: ContentType.Video.MPEG
                    else ->
                        response.header("Content-Type")?.let { ContentType.parse(it) }
                            ?: ContentType.Application.OctetStream
                }

                val statusCode = when {
                    response.code == HttpStatusCode.PartialContent.value -> HttpStatusCode.PartialContent
                    else -> HttpStatusCode.OK
                }

                call.respondOutputStream(
                    status = statusCode,
                    contentType = contentType
                ) {
                    response.body.byteStream().use { input ->
                        try {
                            input.transferTo(this)
                        } catch (e: IOException) {
                            log.warn("视频流传输中断: ${e.message}")
                        }
                    }
                }

            } catch (e: SocketTimeoutException) {
                log.error("视频代理请求超时: URL=$url", e)
                call.respond(HttpStatusCode.GatewayTimeout, "请求超时")
            } catch (e: ConnectException) {
                log.error("视频代理连接失败: URL=$url", e)
                call.respond(HttpStatusCode.BadGateway, "无法连接到目标服务器")
            } catch (e: Exception) {
                log.error("视频代理请求失败: URL=$url", e)
                call.respond(HttpStatusCode.BadGateway, "代理请求失败: ${e.message ?: "未知错误"}")
            }
        }

        /**
         * 代理m3u8文件
         */
        get("/proxy/m3u8") {
            val encodedUrl = call.request.queryParameters["url"] ?: run {
                errorResp(call, "URL参数缺失")
                return@get
            }
            val decodedUrl = try {
                URLDecoder.decode(encodedUrl, "UTF-8").also { url ->
                    if (url.contains("proxy/m3u8") || !url.startsWith("https://")) {
                        errorResp(call, "非法的目标URL")
                        return@get
                    }
                }
            } catch (_: Exception) {
                errorResp(call, "URL解码失败")
                return@get
            }
            // 使用带代理配置的HTTP客户端
            val client = createDefaultOkHttpClient()
            try {
                val content = client.newCall(Request.Builder().url(decodedUrl).build())
                    .execute().use { response ->
                        if (!response.isSuccessful) {
                            errorResp(call, "上游服务器返回错误: ${response.code}")
                            return@get
                        }
                        response.body.string()
                    }
                call.respondText(content, ContentType.Application.OctetStream)
            } catch (e: Exception) {
                log.error("代理请求失败: URL=$decodedUrl", e)
                errorResp(call, "代理请求失败: ${e.message}")
            }
        }

        /**
         * 代理已缓存的m3u8文件
         */
        get("/proxy/cached_m3u8") {
            val id = call.request.queryParameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Missing cache ID")
                return@get
            }

            val content = M3U8Cache.get(id) ?: run {
                call.respond(HttpStatusCode.NotFound, "Cache expired or invalid")
                return@get
            }

            call.respondText(
                content,
                ContentType.parse("application/vnd.apple.mpegurl"),
            )
        }
    }
}