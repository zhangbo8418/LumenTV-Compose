package com.corner.ui.scene

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 内嵌浏览器下载确认对话框
 */
@Composable
fun BrowserDownloadDialog(
    reason: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    isDownloading: Boolean = false,
    downloadProgress: Float = 0f,
) {
    Dialog(
        onDismissRequest = { if (!isDownloading) onCancel() },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading,
        ),
    ) {
        Card(
            modifier = Modifier.width(450.dp).padding(16.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "需要下载内嵌浏览器",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "$reason\n\n" +
                        "将下载 Chromium 109 组件（约 100MB+），用于网页解析。发行包若已捆绑则可跳过下载。\n\n" +
                        "也可在设置 > 浏览器 中手动管理。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
                if (isDownloading) {
                    CircularProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.size(80.dp),
                        strokeWidth = 6.dp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "正在下载… ${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (!isDownloading) {
                        TextButton(onClick = onCancel) { Text("取消") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onConfirm) { Text("下载") }
                    }
                }
            }
        }
    }
}
