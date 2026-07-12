package com.corner.ui.player

import com.corner.database.entity.History

/**
 * 点播详情页与全局 VLC 单例之间的窄回调接口。
 * 离开页面 unbind 后，播放器事件不再回调到旧 ViewModel。
 */
interface VodPlaybackHost {
    fun shouldApplyPlayback(): Boolean
    fun playbackError(msg: String? = null)
    fun nextEP()
    val isLastEpisode: Boolean
    val suppressAutoLineSwitch: Boolean
    fun updateHistory(history: History)
    val isDLNA: Boolean
}
