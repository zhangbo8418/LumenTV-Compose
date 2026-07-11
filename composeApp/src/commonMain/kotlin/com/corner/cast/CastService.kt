package com.corner.cast

import com.corner.dlna.CastDevice
import com.corner.database.entity.History
import com.corner.server.RemoteDeviceInfo
import com.corner.ui.scene.SnackBar
import com.corner.util.net.Http
import com.corner.util.scope.createCoroutineScope
import com.corner.util.settings.SettingStore
import com.corner.util.settings.SettingType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.Headers
import org.slf4j.LoggerFactory

object CastService {
    private val log = LoggerFactory.getLogger("CastService")
    private val scope = createCoroutineScope()

    fun castTo(
        device: CastDevice,
        history: History,
        playUrl: String,
        positionMs: Long,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val historyJson = CastHistoryUtil.buildCastJson(history, playUrl, positionMs)
                val config = SettingStore.getSettingItem(SettingType.VOD)
                val body = FormBody.Builder()
                    .add("device", RemoteDeviceInfo.toJson())
                    .add("config", config)
                    .add("history", historyJson)
                    .build()
                val url = device.castUrl()
                Http.newCall(url, Headers.Builder().build(), body).execute().use { response ->
                    val text = response.body.string()
                    if (response.isSuccessful && text.equals("OK", ignoreCase = true)) {
                        SnackBar.postMsg("已投屏到 ${device.name}", type = SnackBar.MessageType.INFO)
                        onSuccess()
                    } else {
                        onError("设备离线或投屏失败")
                        SnackBar.postMsg("投屏失败", type = SnackBar.MessageType.WARNING)
                    }
                }
            } catch (e: Exception) {
                log.warn("投屏失败: {}", e.message)
                onError(e.message.orEmpty())
                SnackBar.postMsg("投屏失败: ${e.message}", type = SnackBar.MessageType.ERROR)
            }
        }
    }
}

private fun CastDevice.castUrl(): String {
    val base = ip.trimEnd('/')
    return "$base/action?do=cast"
}
