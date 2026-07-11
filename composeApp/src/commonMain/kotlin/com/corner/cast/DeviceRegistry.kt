package com.corner.cast

import com.corner.dlna.CastDevice
import com.corner.server.RemoteDeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object DeviceRegistry {
    val devices = MutableStateFlow<List<CastDevice>>(emptyList())

    fun add(info: RemoteDeviceInfo) {
        if (info.uuid == RemoteDeviceInfo.current().uuid) return
        val device = CastDevice.fromRemote(info)
        devices.update { current ->
            if (current.any { it.uuid == device.uuid }) current
            else (current + device).sortedBy { it.name }
        }
    }

    fun clear() {
        devices.value = emptyList()
    }
}
