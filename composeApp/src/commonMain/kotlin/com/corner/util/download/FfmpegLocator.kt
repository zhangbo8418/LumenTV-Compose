package com.corner.util.download

import com.corner.util.core.Constants
import com.corner.util.system.SysVerUtil
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 优先使用打包进 appResources 的 ffmpeg，其次系统 PATH。
 */
object FfmpegLocator {
    private val log = LoggerFactory.getLogger("FfmpegLocator")
    @Volatile private var cached: String? = null

    fun find(): String? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val found = bundledCandidates().firstOrNull { probe(it) }
                ?: systemCandidates().firstOrNull { probe(it) }
            if (found != null) {
                ensureExecutable(File(found))
                log.info("使用 ffmpeg: {}", found)
            } else {
                log.warn("未找到 ffmpeg（捆绑或系统）")
            }
            cached = found
            return found
        }
    }

    private fun bundledCandidates(): List<String> {
        val files = mutableListOf<File>()
        System.getProperty(Constants.RES_PATH_KEY)?.takeIf { it.isNotBlank() }?.let { res ->
            files += File(res, "ffmpeg/ffmpeg")
            files += File(res, "ffmpeg/ffmpeg.exe")
            files += File(res, "ffmpeg")
        }
        val platform = SysVerUtil.getAppResourcesPlatform()
        val userDir = System.getProperty("user.dir")
        listOf(
            "src/desktopMain/appResources/$platform/ffmpeg",
            "composeApp/src/desktopMain/appResources/$platform/ffmpeg",
        ).forEach { rel ->
            val root = File(userDir, rel)
            files += File(root, "ffmpeg")
            files += File(root, "ffmpeg.exe")
        }
        return files.map { it.absolutePath }
    }

    private fun systemCandidates(): List<String> = listOf(
        "ffmpeg",
        "/usr/local/bin/ffmpeg",
        "/opt/homebrew/bin/ffmpeg",
        "/usr/bin/ffmpeg",
    )

    private fun probe(cmd: String): Boolean {
        return try {
            val file = File(cmd)
            if (file.isAbsolute || cmd.contains('/') || cmd.contains('\\')) {
                if (!file.isFile) return false
            }
            val p = ProcessBuilder(cmd, "-version").redirectErrorStream(true).start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureExecutable(file: File) {
        if (!file.isFile) return
        try {
            if (!file.canExecute()) file.setExecutable(true, false)
        } catch (e: Exception) {
            log.warn("无法设置 ffmpeg 可执行权限: {}", file, e)
        }
    }
}
