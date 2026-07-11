package com.corner.dlna

import com.corner.server.RemoteDeviceInfo
import org.jupnp.model.meta.RemoteDevice

data class CastDevice(
    val uuid: String,
    val name: String,
    val type: Int = TYPE_DLNA,
    val ip: String = "",
) {
    val isDlna: Boolean get() = type == TYPE_DLNA

    val isLumen: Boolean get() = type == TYPE_LUMEN

    companion object {
        const val TYPE_DLNA = 2
        const val TYPE_LUMEN = 3

        fun from(device: RemoteDevice): CastDevice {
            return CastDevice(
                uuid = device.identity.udn.identifierString,
                name = device.details.friendlyName ?: "未知设备",
                type = TYPE_DLNA,
            )
        }

        fun fromRemote(info: RemoteDeviceInfo): CastDevice {
            return CastDevice(
                uuid = info.uuid,
                name = info.name,
                type = TYPE_LUMEN,
                ip = info.ip,
            )
        }
    }
}
