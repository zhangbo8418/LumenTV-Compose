package com.corner.catvodcore.live

import com.corner.catvodcore.bean.Live
import com.corner.catvodcore.bean.LiveChannel
import com.corner.catvodcore.bean.LiveGroup
import com.corner.database.Db
import com.corner.database.entity.Keep
import kotlinx.coroutines.flow.first

object LiveKeep {
    const val TYPE_LIVE = 1L
    const val GROUP_NAME = "收藏"

    fun keepKey(live: Live, channel: LiveChannel): String {
        return "${live.name}${Db.SYMBOL}${channel.name}"
    }

    suspend fun isKept(live: Live, channel: LiveChannel): Boolean {
        return Db.Keep.findLive(keepKey(live, channel)) != null
    }

    suspend fun toggle(live: Live, channel: LiveChannel): Boolean {
        val key = keepKey(live, channel)
        val existing = Db.Keep.findLive(key)
        return if (existing != null) {
            Db.Keep.deleteLive(key)
            false
        } else {
            Db.Keep.insert(
                Keep(
                    key = key,
                    siteName = live.name,
                    vodName = channel.name,
                    vodPic = channel.logo.ifBlank { null },
                    createTime = System.currentTimeMillis(),
                    type = TYPE_LIVE,
                    cid = 0,
                )
            )
            true
        }
    }

    suspend fun buildKeepGroup(live: Live): LiveGroup {
        val keeps = Db.Keep.getLive().first()
        val channelMap = live.groups
            .filter { !it.isKeep }
            .flatMap { it.channels }
            .associateBy { keepKey(live, it) }

        val group = LiveGroup(name = GROUP_NAME, isKeep = true)
        keeps.forEach { keep ->
            val source = channelMap[keep.key]
            if (source != null) {
                group.channels.add(source.copyRef())
            } else if (keep.siteName == live.name) {
                val channelName = keep.vodName ?: keep.key.substringAfter(Db.SYMBOL, "")
                group.channels.add(
                    LiveChannel(
                        name = channelName,
                        logo = keep.vodPic.orEmpty(),
                    )
                )
            }
        }
        return group
    }

    private fun LiveChannel.copyRef(): LiveChannel {
        return LiveChannel(
            name = name,
            logo = logo,
            number = number,
            tvgId = tvgId,
            tvgName = tvgName,
            urls = urls.toMutableList(),
            live = live,
            epgList = epgList.toMutableList(),
        ).apply { urlIndex = this@copyRef.urlIndex }
    }
}
