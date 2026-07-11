package com.corner.cast

import com.corner.database.entity.History
import com.corner.server.RemoteDeviceInfo
import com.corner.util.json.Jsons

data class CastReceivePayload(
    val device: RemoteDeviceInfo,
    val configJson: String,
    val history: History,
) {
    companion object {
        fun fromParams(deviceJson: String, configJson: String, historyJson: String): CastReceivePayload? {
            val device = runCatching {
                Jsons.decodeFromString<RemoteDeviceInfo>(deviceJson)
            }.getOrNull() ?: return null
            val history = CastHistoryUtil.parseHistory(historyJson) ?: return null
            return CastReceivePayload(device, configJson, history)
        }
    }
}
