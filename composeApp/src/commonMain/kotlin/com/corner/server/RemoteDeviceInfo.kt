package com.corner.server

import com.corner.util.json.Jsons
import com.corner.util.net.NetworkUtil
import kotlinx.serialization.Serializable
import java.net.InetAddress

@Serializable
data class RemoteDeviceInfo(
    val uuid: String,
    val name: String,
    val ip: String,
    val type: Int = 3,
    val time: Long = System.currentTimeMillis(),
) {
    companion object {
        private val deviceUuid: String by lazy {
            runCatching {
                val host = InetAddress.getLocalHost().hostName
                val user = System.getProperty("user.name").orEmpty()
                "${host}_${user}".hashCode().toUInt().toString(16)
            }.getOrDefault("lumen_desktop")
        }

        fun current(): RemoteDeviceInfo {
            val lanIp = NetworkUtil.getLanIp()
            val port = KtorD.getPort()
            return RemoteDeviceInfo(
                uuid = deviceUuid,
                name = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("LumenTV"),
                ip = "http://$lanIp:$port/",
            )
        }

        fun toJson(): String = Jsons.encodeToString(current())
    }
}
