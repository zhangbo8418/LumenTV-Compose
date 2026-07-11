package com.corner.ui.dlna

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.corner.cast.CastService
import com.corner.cast.DeviceRegistry
import com.corner.cast.LanDeviceScanner
import com.corner.database.entity.History
import com.corner.dlna.CastDevice
import com.corner.dlna.CastVideo
import com.corner.dlna.DLNACast
import com.corner.dlna.DLNACastManager
import com.corner.ui.scene.Dialog
import com.corner.ui.scene.SnackBar

@Composable
fun CastDialog(
    show: Boolean,
    title: String,
    url: String,
    position: Long = 0L,
    headers: Map<String, String> = emptyMap(),
    history: History? = null,
    onClose: () -> Unit,
    onCastSuccess: () -> Unit = {},
) {
    if (!show) return

    DisposableEffect(show) {
        if (show) {
            DLNACastManager.init()
            DeviceRegistry.clear()
            LanDeviceScanner.start { }
        }
        onDispose {
            LanDeviceScanner.stop()
        }
    }

    val dlnaDevices by DLNACastManager.devices.collectAsState()
    val lumenDevices by DeviceRegistry.devices.collectAsState()
    val allDevices = remember(dlnaDevices, lumenDevices) {
        (lumenDevices + dlnaDevices).distinctBy { it.uuid }
    }
    val video = remember(title, url, position, headers) {
        CastVideo(title, url, position, headers)
    }

    Dialog(
        showDialog = show,
        onClose = onClose,
        modifier = Modifier.width(380.dp).heightIn(max = 520.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("投屏到设备", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = {
                    DLNACastManager.search()
                    DeviceRegistry.clear()
                    LanDeviceScanner.start { }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新设备")
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (allDevices.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("正在搜索设备…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(allDevices, key = { it.uuid }) { device ->
                        val subtitle = when {
                            device.isLumen -> "LumenTV / FongMi"
                            else -> "DLNA"
                        }
                        ListItem(
                            headlineContent = { Text(device.name) },
                            supportingContent = { Text(subtitle) },
                            leadingContent = {
                                Icon(
                                    if (device.isLumen) Icons.Default.Computer else Icons.Default.Cast,
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.clickable {
                                if (device.isDlna) {
                                    DLNACast(
                                        video = video,
                                        onSuccess = {
                                            SnackBar.postMsg("已投屏到 ${device.name}", type = SnackBar.MessageType.INFO)
                                            onCastSuccess()
                                            onClose()
                                        },
                                    ).cast(device)
                                } else {
                                    val castHistory = history
                                    if (castHistory == null) {
                                        SnackBar.postMsg("无播放历史，无法续播投屏", type = SnackBar.MessageType.WARNING)
                                        return@clickable
                                    }
                                    CastService.castTo(
                                        device = device,
                                        history = castHistory,
                                        playUrl = url,
                                        positionMs = position,
                                        onSuccess = {
                                            onCastSuccess()
                                            onClose()
                                        },
                                    )
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
            ) {
                Text("取消")
            }
        }
    }
}
