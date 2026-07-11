package com.corner.util.net

import org.slf4j.LoggerFactory
import java.text.DecimalFormat

/**
 * 对齐 TV [com.fongmi.android.tv.utils.Traffic]：按累计接收字节差分显示网速。
 * 桌面端用系统网卡 RX（VLC 走原生下载，不经 OkHttp）。
 */
object Traffic {
    private val log = LoggerFactory.getLogger("Traffic")
    private val format = DecimalFormat("#.0")
    private const val UNIT_KB = " KB/s"
    private const val UNIT_MB = " MB/s"

    @Volatile
    private var lastTotalRxBytes: Long = -1
    @Volatile
    private var lastTimeStamp: Long = 0

    /** 采样并格式化当前下行速度；首次采样返回空串。 */
    fun sampleSpeed(): String {
        val total = readSystemRxBytes()
        if (total < 0) return ""
        return getSpeed(total)
    }

    /** @param totalRxBytes 累计已接收字节 */
    fun getSpeed(totalRxBytes: Long): String {
        val now = System.currentTimeMillis()
        if (lastTimeStamp == 0L || lastTotalRxBytes < 0) {
            lastTotalRxBytes = totalRxBytes
            lastTimeStamp = now
            return ""
        }
        val deltaBytes = (totalRxBytes - lastTotalRxBytes).coerceAtLeast(0)
        val deltaMs = (now - lastTimeStamp).coerceAtLeast(1)
        lastTotalRxBytes = totalRxBytes
        lastTimeStamp = now
        val speedKb = deltaBytes * 1000 / deltaMs / 1024
        return if (speedKb < 1000) {
            "$speedKb$UNIT_KB"
        } else {
            format.format(speedKb / 1024f) + UNIT_MB
        }
    }

    fun reset() {
        lastTotalRxBytes = -1
        lastTimeStamp = 0
    }

    fun readSystemRxBytes(): Long {
        return try {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            when {
                os.contains("mac") || os.contains("darwin") -> readMacRxBytes()
                os.contains("linux") -> readLinuxRxBytes()
                else -> -1
            }
        } catch (e: Exception) {
            log.debug("读取网卡流量失败: {}", e.message)
            -1
        }
    }

    /** netstat -ib：只统计每个网卡的 &lt;Link#&gt; 行，避免多地址重复累加 */
    private fun readMacRxBytes(): Long {
        val process = ProcessBuilder("netstat", "-ib")
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        var total = 0L
        val seen = HashSet<String>()
        for (line in lines.drop(1)) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) continue
            val name = parts[0]
            if (!seen.add(name)) continue
            if (name == "lo0" || name.startsWith("awdl") || name.startsWith("llw") ||
                name.startsWith("utun") || name.startsWith("bridge") || name.endsWith("*")
            ) {
                continue
            }
            val linkIdx = parts.indexOfFirst { it.startsWith("<Link") }
            if (linkIdx < 0) continue
            var i = linkIdx + 1
            // 可选 MAC：xx:xx:xx:xx:xx:xx
            if (i < parts.size && parts[i].count { it == ':' } >= 2) i++
            // Ipkts Ierrs Ibytes
            val ibytes = parts.getOrNull(i + 2)?.toLongOrNull() ?: continue
            total += ibytes
        }
        return total
    }

    private fun readLinuxRxBytes(): Long {
        val file = java.io.File("/proc/net/dev")
        if (!file.isFile) return -1
        var total = 0L
        file.forEachLine { line ->
            if (!line.contains(":")) return@forEachLine
            val (iface, rest) = line.split(":", limit = 2).let { it[0].trim() to it[1].trim() }
            if (iface == "lo" || iface.startsWith("docker") || iface.startsWith("veth") ||
                iface.startsWith("br-")
            ) {
                return@forEachLine
            }
            val cols = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val rx = cols.firstOrNull()?.toLongOrNull() ?: return@forEachLine
            total += rx
        }
        return total
    }
}
