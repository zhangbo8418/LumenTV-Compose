package com.corner.catvodcore.config

import com.corner.catvodcore.bean.Depot
import com.corner.catvodcore.enum.ConfigType
import com.corner.database.Db
import com.corner.database.entity.Config
import com.corner.util.json.Jsons
import com.corner.util.json.cleanJsonComments
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

object ConfigDepot {
    private val log = LoggerFactory.getLogger("ConfigDepot")

    /**
     * 解析仓库索引 JSON（含 urls 数组），批量入库并返回首个可用配置。
     * [indexUrl] 为索引自身地址，展开后删除该记录，避免历史列表出现无名字的总站。
     */
    fun resolve(rawJson: String, indexUrl: String? = null): Config? {
        val root = runCatching {
            Jsons.parseToJsonElement(cleanJsonComments(rawJson))
        }.getOrNull() as? JsonObject ?: return null

        val urlsElement = root["urls"] ?: return null
        val depots = parseDepotList(urlsElement.toString())
        if (depots.isEmpty()) {
            log.warn("仓库索引 urls 为空")
            return null
        }

        return runBlocking {
            depots.forEach { depot -> upsertConfig(depot.url, depot.displayName()) }
            deleteIndexUrl(indexUrl, depots.map { it.url }.toSet())
            Db.Config.find(depots.first().url, ConfigType.SITE.ordinal.toLong()).firstOrNull()
        }
    }

    private suspend fun deleteIndexUrl(indexUrl: String?, depotUrls: Set<String>) {
        val url = indexUrl?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (url in depotUrls) return
        val type = ConfigType.SITE.ordinal.toLong()
        val existing = Db.Config.find(url, type).firstOrNull() ?: return
        Db.Config.deleteById(existing.id)
        log.info("已删除仓库索引记录: {}", url)
    }

    suspend fun upsertConfig(url: String, name: String? = null) {
        val type = ConfigType.SITE.ordinal.toLong()
        val existing = Db.Config.find(url, type).firstOrNull()
        if (existing == null) {
            Db.Config.save(
                Config(
                    type = type,
                    url = url,
                    name = name,
                )
            )
        } else if (!name.isNullOrBlank() && existing.name != name) {
            Db.Config.update(existing.copy(name = name, time = System.currentTimeMillis()))
        } else {
            Db.Config.updateUrl(existing.id, url)
        }
    }

    private fun parseDepotList(raw: String): List<Depot> {
        val objects = runCatching { Jsons.decodeFromString<List<Depot>>(raw) }.getOrNull()
        if (!objects.isNullOrEmpty()) {
            return objects.filter { it.url.isNotBlank() }
        }
        val plainUrls = runCatching { Jsons.decodeFromString<List<String>>(raw) }.getOrNull().orEmpty()
        return plainUrls.filter { it.isNotBlank() }.map { Depot(url = it) }
    }
}
