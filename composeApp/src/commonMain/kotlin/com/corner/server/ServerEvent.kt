package com.corner.server

import com.corner.cast.CastReceivePayload
import kotlinx.coroutines.flow.MutableSharedFlow

object ServerEvent {
    val pushUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val searchWord = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val settingConfig = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
    val danmakuText = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val subtitlePath = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val castReceive = MutableSharedFlow<CastReceivePayload>(extraBufferCapacity = 1)

    fun push(text: String) {
        pushUrl.tryEmit(text)
    }

    fun search(word: String) {
        searchWord.tryEmit(word)
    }

    fun setting(text: String, name: String = "") {
        settingConfig.tryEmit(text to name)
    }

    fun danmaku(text: String) {
        danmakuText.tryEmit(text)
    }

    fun subtitle(path: String) {
        subtitlePath.tryEmit(path)
    }

    fun cast(deviceJson: String, configJson: String, historyJson: String) {
        CastReceivePayload.fromParams(deviceJson, configJson, historyJson)?.let {
            castReceive.tryEmit(it)
        }
    }
}
