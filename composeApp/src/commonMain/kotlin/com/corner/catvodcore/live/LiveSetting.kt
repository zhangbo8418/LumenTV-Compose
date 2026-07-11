package com.corner.catvodcore.live

import com.corner.util.settings.SettingStore
import com.corner.util.settings.SettingType

object LiveSetting {
    fun isAcross(): Boolean {
        return SettingStore.getSettingItem(SettingType.LIVE_ACROSS).ifBlank { "true" }.toBoolean()
    }

    fun isAutoChangeLine(): Boolean {
        return SettingStore.getSettingItem(SettingType.LIVE_AUTO_LINE).ifBlank { "true" }.toBoolean()
    }

    fun isInvert(): Boolean {
        return SettingStore.getSettingItem(SettingType.LIVE_INVERT).toBoolean()
    }
}
