package com.corner.cast

import com.corner.server.KtorD
import com.corner.server.RemoteDeviceInfo
import com.corner.util.net.Http
import com.corner.util.net.NetworkUtil
import com.corner.util.scope.createCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Headers
import org.slf4j.LoggerFactory

object LanDeviceScanner {
    private val log = LoggerFactory.getLogger("LanDeviceScanner")
    private val scope = createCoroutineScope()
    private var scanJob: Job? = null

    fun start(onFound: (RemoteDeviceInfo) -> Unit) {
        stop()
        val self = RemoteDeviceInfo.current()
        val lanIp = NetworkUtil.getLanIp()
        val port = KtorD.getPort()
        val base = lanIp.substring(0, lanIp.lastIndexOf('.') + 1)
        scanJob = scope.launch(Dispatchers.IO) {
            for (host in 1..255) {
                if (!isActive) break
                val targetHost = "$base$host"
                if (targetHost == lanIp) continue
                val url = "http://$targetHost:$port/device"
                try {
                    Http.newCall(url, Headers.Builder().build()).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val body = response.body.string()
                        val device = runCatching {
                            com.corner.util.json.Jsons.decodeFromString<RemoteDeviceInfo>(body)
                        }.getOrNull() ?: return@use
                        if (device.uuid == self.uuid) return@use
                        DeviceRegistry.add(device)
                        onFound(device)
                    }
                } catch (_: Exception) {
                }
            }
            log.debug("局域网设备扫描完成")
        }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
    }
}
