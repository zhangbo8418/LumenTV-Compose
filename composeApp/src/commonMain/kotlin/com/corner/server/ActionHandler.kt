package com.corner.server

import com.corner.ui.danmaku.DanmakuManager
import com.corner.ui.scene.SnackBar
import org.slf4j.LoggerFactory

object ActionHandler {
    private val log = LoggerFactory.getLogger("ActionHandler")

    fun handle(params: Map<String, String>) {
        val action = params["do"] ?: return
        log.info("收到 action: {} params={}", action, params.keys)
        when (action) {
            "push" -> params["url"]?.takeIf { it.isNotBlank() }?.let { url ->
                ServerEvent.push(url)
                SnackBar.postMsg("收到推送: ${url.take(60)}", type = SnackBar.MessageType.INFO)
            }
            "search" -> params["word"]?.takeIf { it.isNotBlank() }?.let { word ->
                ServerEvent.search(word)
                SnackBar.postMsg("收到搜索: $word", type = SnackBar.MessageType.INFO)
            }
            "control" -> handleControl(params)
            "danmaku" -> params["text"]?.takeIf { it.isNotBlank() }?.let { text ->
                DanmakuManager.send(text)
                ServerEvent.danmaku(text)
            }
            "setting" -> params["text"]?.takeIf { it.isNotBlank() }?.let { text ->
                ServerEvent.setting(text, params["name"].orEmpty())
                SnackBar.postMsg("收到配置更新", type = SnackBar.MessageType.INFO)
            }
            "file" -> handleFile(params)
            "sync" -> SyncService.handle(params)
            "cast" -> handleCast(params)
            "refresh" -> log.debug("refresh action: {}", params["type"])
            else -> log.warn("未知 action: {}", action)
        }
    }

    private fun handleFile(params: Map<String, String>) {
        val path = params["path"]?.takeIf { it.isNotBlank() } ?: return
        when {
            path.endsWith(".json", true) || path.endsWith(".txt", true) -> {
                val url = normalizeFilePath(path)
                ServerEvent.setting(url, params["name"].orEmpty())
            }
            path.endsWith(".srt", true) || path.endsWith(".ass", true) || path.endsWith(".ssa", true) -> {
                ServerEvent.subtitle(normalizeFilePath(path))
            }
            else -> {
                val playUrl = LocalFileHandler.playUrl(path)
                ServerEvent.push(playUrl)
                SnackBar.postMsg("推送本地文件", type = SnackBar.MessageType.INFO)
            }
        }
    }

    private fun normalizeFilePath(path: String): String {
        if (path.startsWith("http", ignoreCase = true)) return path
        val relative = path.removePrefix("file:/").removePrefix("file://")
        val file = LocalFileHandler.localFile(relative)
        return file.toURI().toString()
    }

    private fun handleCast(params: Map<String, String>) {
        val device = params["device"]?.takeIf { it.isNotBlank() } ?: return
        val config = params["config"]?.takeIf { it.isNotBlank() } ?: return
        val history = params["history"]?.takeIf { it.isNotBlank() } ?: return
        ServerEvent.cast(device, config, history)
        SnackBar.postMsg("收到投屏请求", type = SnackBar.MessageType.INFO)
    }

    private fun handleControl(params: Map<String, String>) {
        when (params["type"]) {
            "play" -> PlaybackControl.play?.invoke()
            "pause" -> PlaybackControl.pause?.invoke()
            "stop" -> PlaybackControl.stop?.invoke()
            "prev" -> PlaybackControl.prev?.invoke()
            "next" -> PlaybackControl.next?.invoke()
            else -> log.warn("未知 control: {}", params["type"])
        }
    }
}

object PlaybackControl {
    var play: (() -> Unit)? = null
    var pause: (() -> Unit)? = null
    var stop: (() -> Unit)? = null
    var prev: (() -> Unit)? = null
    var next: (() -> Unit)? = null
}

object PlaybackMediaState {
    @Volatile var title: String = ""

    @Volatile var url: String = ""

    @Volatile var position: Long = 0L

    @Volatile var duration: Long = 0L

    @Volatile var speed: Float = 1f

    @Volatile var playing: Boolean = false

    @Volatile var subtitleUrl: String = ""

    @Volatile var headers: Map<String, String> = emptyMap()

    fun stateCode(): Int = when {
        playing -> 3
        duration > 0 && position > 0 -> 2
        else -> 1
    }

    fun toJson(): String {
        return """
            {
              "state": ${stateCode()},
              "speed": $speed,
              "duration": $duration,
              "position": $position,
              "url": "${escape(url)}",
              "title": "${escape(title)}"
            }
        """.trimIndent()
    }

    private fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
