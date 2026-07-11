package com.corner.ui.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Movie
import com.corner.cast.CastHistoryUtil
import com.corner.cast.getSiteKey
import com.corner.cast.getVodId
import com.corner.cast.CastReceivePayload
import com.corner.catvodcore.bean.Site
import com.corner.catvodcore.bean.Vod
import com.corner.database.entity.History
import com.corner.catvodcore.config.ApiConfig
import com.corner.catvodcore.push.PushService
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.database.Db
import com.corner.init.Init
import com.corner.ui.scene.Dialog
import com.corner.ui.scene.SnackBar
import com.corner.util.settings.SettingStore
import com.corner.util.settings.SettingType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReceiveCastDialog(
    payload: CastReceivePayload,
    onClose: () -> Unit,
    onAccepted: (Vod) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }

    Dialog(
        showDialog = true,
        onClose = onClose,
        modifier = Modifier.width(380.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("收到投屏", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(72.dp, 96.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        payload.history.vodName.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "来自 ${payload.device.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    payload.history.position?.takeIf { it > 0 }?.let { pos ->
                        val min = pos / 60_000
                        val sec = (pos % 60_000) / 1000
                        Text(
                            "续播 ${min}:${sec.toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("忽略") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            runCatching {
                                acceptCast(payload, onAccepted)
                            }.onFailure {
                                SnackBar.postMsg("投屏续播失败: ${it.message}", type = SnackBar.MessageType.ERROR)
                            }
                            loading = false
                            onClose()
                        }
                    },
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("播放")
                    }
                }
            }
        }
    }
}

private suspend fun acceptCast(payload: CastReceivePayload, onAccepted: (Vod) -> Unit) {
    val configUrl = CastHistoryUtil.extractConfigUrl(payload.configJson)
    val currentConfig = SettingStore.getSettingItem(SettingType.VOD)
    if (configUrl.isNotBlank() && configUrl != currentConfig) {
        SettingStore.setValue(SettingType.VOD, configUrl)
        if (!Init.initConfig(forceReinit = true)) {
            throw IllegalStateException("投屏配置加载失败")
        }
    }
    val history = payload.history.copy(
        cid = ApiConfig.api.cfg?.id ?: payload.history.cid,
        position = payload.history.position ?: 0L,
    )
    withContext(Dispatchers.IO) {
        val existing = Db.History.findHistory(history.key)
        if (existing != null) Db.History.update(history) else Db.History.save(history)
    }
    GlobalAppState.castResumeHistory.value = history
    onAccepted(vodFromCastHistory(history))
}

private fun vodFromCastHistory(history: com.corner.database.entity.History): Vod {
    val siteKey = history.getSiteKey()
    val vodId = history.getVodId()
    return if (vodId.startsWith("http", ignoreCase = true) || PushService.isPushSite(siteKey)) {
        PushService.buildVod(vodId).copy(
            vodName = history.vodName ?: PushService.displayName(vodId),
            vodPic = history.vodPic,
        )
    } else {
        Vod().apply {
            this.vodId = vodId
            vodName = history.vodName
            vodPic = history.vodPic
            site = Site.get(siteKey, "")
        }
    }
}
