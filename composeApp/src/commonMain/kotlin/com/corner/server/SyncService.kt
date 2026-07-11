package com.corner.server

import com.corner.catvodcore.config.ApiConfig
import com.corner.database.Db
import com.corner.database.entity.History
import com.corner.database.entity.Keep
import com.corner.ui.scene.SnackBar
import com.corner.util.json.Jsons
import com.corner.util.net.Http
import com.corner.util.scope.createCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.FormBody
import org.slf4j.LoggerFactory

object SyncService {
    private val log = LoggerFactory.getLogger("SyncService")
    private val scope = createCoroutineScope()

    fun handle(params: Map<String, String>) {
        val type = params["type"] ?: return
        val force = params["force"] == "true"
        val mode = params["mode"] ?: "0"
        scope.launch {
            runCatching {
                when {
                    mode == "0" || mode == "2" -> when (type) {
                        "history" -> receiveHistory(params, force)
                        "keep" -> receiveKeep(params, force)
                    }
                    mode == "1" -> when (type) {
                        "history" -> sendHistory(params)
                        "keep" -> sendKeep(params)
                    }
                }
            }.onFailure {
                log.warn("同步失败: {}", it.message)
                SnackBar.postMsg("同步失败: ${it.message}", type = SnackBar.MessageType.ERROR)
            }
        }
    }

    private suspend fun receiveHistory(params: Map<String, String>, force: Boolean) {
        val cid = ApiConfig.api.cfg?.id ?: return
        val targets = decodeHistory(params["targets"]) ?: return
        if (force) {
            targets.forEach { item ->
                item.key.takeIf { it.isNotBlank() }?.let { Db.History.deleteBatch(listOf(it)) }
            }
        }
        syncHistory(targets, cid)
        SnackBar.postMsg("已同步 ${targets.size} 条历史记录", type = SnackBar.MessageType.INFO)
    }

    private suspend fun receiveKeep(params: Map<String, String>, force: Boolean) {
        val targets = decodeKeep(params["targets"]) ?: return
        if (force) Db.Keep.deleteAllVod()
        targets.filter { it.type == 0L }.forEach { keep ->
            Db.Keep.insert(keep.copy(cid = ApiConfig.api.cfg?.id ?: keep.cid))
        }
        SnackBar.postMsg("已同步 ${targets.size} 条收藏", type = SnackBar.MessageType.INFO)
    }

    suspend fun syncHistory(targets: List<History>, cid: Long) {
        targets.forEach { target ->
            val name = target.vodName?.takeIf { it.isNotBlank() } ?: return@forEach
            val items = Db.History.findByName(name, cid)
            val normalized = target.copy(cid = cid)
            if (items.isEmpty()) {
                Db.History.save(normalized.copy(key = normalized.key.ifBlank { buildHistoryKey(target, cid) }))
            } else {
                val latest = items.maxOf { it.createTime ?: 0L }
                if ((target.createTime ?: 0L) >= latest) {
                    Db.History.update(normalized.copy(key = items.first().key))
                }
            }
        }
    }

    private fun buildHistoryKey(target: History, cid: Long): String {
        val parts = target.key.split(Db.SYMBOL)
        return if (parts.size >= 2) {
            "${parts[0]}${Db.SYMBOL}${parts[1]}${Db.SYMBOL}$cid"
        } else {
            "sync${Db.SYMBOL}${nameHash(target.vodName)}${Db.SYMBOL}$cid"
        }
    }

    private fun nameHash(name: String?): String {
        return (name?.hashCode() ?: System.currentTimeMillis()).toString()
    }

    private suspend fun sendHistory(params: Map<String, String>) {
        val deviceIp = params["device"]?.let { decodeDeviceIp(it) } ?: return
        val cid = ApiConfig.api.cfg?.id ?: return
        val histories = Db.History.findAllOnce(cid)
        val body = FormBody.Builder()
            .add("do", "sync")
            .add("type", "history")
            .add("mode", "0")
            .add("force", "false")
            .add("targets", Jsons.encodeToString(histories))
            .build()
        Http.newCall(
            "${deviceIp.trimEnd('/')}/action",
            okhttp3.Headers.Builder().build(),
            body,
        ).execute().close()
        SnackBar.postMsg("已发送历史记录到远程设备", type = SnackBar.MessageType.INFO)
    }

    private suspend fun sendKeep(params: Map<String, String>) {
        val deviceIp = params["device"]?.let { decodeDeviceIp(it) } ?: return
        val keeps = Db.Keep.findVodOnce()
        val body = FormBody.Builder()
            .add("do", "sync")
            .add("type", "keep")
            .add("mode", "0")
            .add("force", "false")
            .add("targets", Jsons.encodeToString(keeps))
            .build()
        Http.newCall(
            "${deviceIp.trimEnd('/')}/action",
            okhttp3.Headers.Builder().build(),
            body,
        ).execute().close()
        SnackBar.postMsg("已发送收藏到远程设备", type = SnackBar.MessageType.INFO)
    }

    private fun decodeHistory(json: String?): List<History>? {
        if (json.isNullOrBlank()) return null
        return Jsons.decodeFromString(ListSerializer(History.serializer()), json)
    }

    private fun decodeKeep(json: String?): List<Keep>? {
        if (json.isNullOrBlank()) return null
        return Jsons.decodeFromString(ListSerializer(Keep.serializer()), json)
    }

    private fun decodeDeviceIp(deviceJson: String): String? {
        return runCatching {
            Jsons.decodeFromString<RemoteDeviceInfo>(deviceJson).ip.trimEnd('/')
        }.getOrNull()
    }
}
