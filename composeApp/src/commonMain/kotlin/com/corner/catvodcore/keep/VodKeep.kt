package com.corner.catvodcore.keep

import com.corner.catvodcore.bean.Vod
import com.corner.catvodcore.config.ApiConfig
import com.corner.database.Db
import com.corner.database.entity.Keep
import com.corner.util.net.Utils

object VodKeep {
    const val TYPE_VOD = 0L

    fun keepKey(siteKey: String, vodId: String): String {
        return Utils.getHistoryKey(siteKey, vodId)
    }

    fun keepKey(detail: Vod): String {
        return keepKey(detail.site?.key.orEmpty(), detail.vodId)
    }

    suspend fun isKept(detail: Vod): Boolean {
        val cid = ApiConfig.api.cfg?.id ?: return false
        return Db.Keep.findVod(cid, keepKey(detail)) != null
    }

    suspend fun toggle(detail: Vod): Boolean {
        val siteKey = detail.site?.key ?: return false
        val cid = ApiConfig.api.cfg?.id ?: return false
        val key = keepKey(siteKey, detail.vodId)
        val existing = Db.Keep.findVod(cid, key)
        return if (existing != null) {
            Db.Keep.deleteVod(cid, key)
            false
        } else {
            Db.Keep.insert(
                Keep(
                    key = key,
                    siteName = detail.site?.name,
                    vodName = detail.vodName,
                    vodPic = detail.vodPic,
                    createTime = System.currentTimeMillis(),
                    type = TYPE_VOD,
                    cid = cid,
                )
            )
            true
        }
    }

    suspend fun update(detail: Vod) {
        val siteKey = detail.site?.key ?: return
        val cid = ApiConfig.api.cfg?.id ?: return
        val key = keepKey(siteKey, detail.vodId)
        val existing = Db.Keep.findVod(cid, key) ?: return
        Db.Keep.insert(
            existing.copy(
                vodName = detail.vodName,
                vodPic = detail.vodPic,
                siteName = detail.site?.name,
            )
        )
    }
}

fun Keep.getSiteKey(): String = key.split(Db.SYMBOL)[0]

fun Keep.getVodId(): String = key.split(Db.SYMBOL)[1]

fun Keep.buildVod(): Vod {
    val vod = Vod()
    vod.vodName = vodName
    vod.vodId = getVodId()
    vod.vodPic = vodPic
    vod.site = ApiConfig.getSite(getSiteKey())
    return vod
}
