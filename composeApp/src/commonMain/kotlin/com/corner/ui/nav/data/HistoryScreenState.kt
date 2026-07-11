package com.corner.ui.nav.data

import com.corner.database.entity.History
import com.corner.database.entity.Keep


data class HistoryScreenState(
    var historyList: MutableList<History> = mutableListOf(),
    var keepList: MutableList<Keep> = mutableListOf(),
    var selectedTab: HistoryTab = HistoryTab.HISTORY,
)

enum class HistoryTab {
    HISTORY,
    KEEP,
}