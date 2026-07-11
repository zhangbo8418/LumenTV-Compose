package com.corner.catvodcore.setting

import com.corner.catvodcore.config.ApiConfig
import com.corner.util.settings.SettingStore
import com.corner.util.settings.SettingType

object DanmakuSetting {
    fun isLoad(): Boolean = SettingStore.getSettingItem(SettingType.DANMAKU_LOAD).toBoolean()

    fun setLoad(value: Boolean) {
        SettingStore.setValue(SettingType.DANMAKU_LOAD, value.toString())
        if (value) setShow(true)
    }

    fun isAuto(): Boolean = SettingStore.getSettingItem(SettingType.DANMAKU_AUTO).toBoolean()

    fun setAuto(value: Boolean) = SettingStore.setValue(SettingType.DANMAKU_AUTO, value.toString())

    fun isSpiderFirst(): Boolean = SettingStore.getSettingItem(SettingType.DANMAKU_SPIDER_FIRST).toBoolean()

    fun setSpiderFirst(value: Boolean) = SettingStore.setValue(SettingType.DANMAKU_SPIDER_FIRST, value.toString())

    fun isShow(): Boolean = SettingStore.getSettingItem(SettingType.DANMAKU_SHOW).toBoolean()

    fun setShow(value: Boolean) = SettingStore.setValue(SettingType.DANMAKU_SHOW, value.toString())

    fun getApiUrl(): String = SettingStore.getSettingItem(SettingType.DANMAKU_API_URL)

    fun setApiUrl(url: String) = SettingStore.setValue(SettingType.DANMAKU_API_URL, url)

    fun getTextScale(): Float = SettingStore.getSettingItem(SettingType.DANMAKU_TEXT_SCALE).toFloatOrNull() ?: 1f

    fun setTextScale(value: Float) = SettingStore.setValue(SettingType.DANMAKU_TEXT_SCALE, value.toString())

    fun getDurationMs(): Long = SettingStore.getSettingItem(SettingType.DANMAKU_DURATION).toLongOrNull() ?: 8000L

    fun setDurationMs(value: Long) = SettingStore.setValue(SettingType.DANMAKU_DURATION, value.toString())

    fun getMaxOnScreen(): Int = SettingStore.getSettingItem(SettingType.DANMAKU_MAX_ON_SCREEN).toIntOrNull() ?: 80

    fun setMaxOnScreen(value: Int) = SettingStore.setValue(SettingType.DANMAKU_MAX_ON_SCREEN, value.toString())

    fun effectiveApiUrl(): String {
        val user = getApiUrl()
        if (user.isNotBlank()) return user
        return ApiConfig.api.data.let { data ->
            runCatching {
                val regex = """"danmaku"\s*:\s*"([^"]+)"""".toRegex()
                regex.find(data)?.groupValues?.get(1).orEmpty()
            }.getOrDefault("")
        }
    }

    fun canSearch(): Boolean = isLoad() && isAuto() && effectiveApiUrl().isNotBlank()
}
