package com.corner.ui.nav.vm

import com.corner.catvodcore.config.ApiConfig
import com.corner.database.Db
import com.corner.ui.nav.BaseViewModel
import com.corner.ui.nav.data.HistoryScreenState
import com.corner.ui.nav.data.HistoryTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


class HistoryViewModel: BaseViewModel() {
    private val _state = MutableStateFlow(HistoryScreenState())
    val state: StateFlow<HistoryScreenState> = _state

    fun selectTab(tab: HistoryTab) {
        _state.update { it.copy(selectedTab = tab) }
    }

    fun clearHistory() {
        scope.launch {
            Db.History.deleteAll()
        }
    }

    fun clearKeep() {
        scope.launch {
            Db.Keep.deleteAllVod()
            fetchKeepList()
        }
    }

    fun deleteBatchHistory(listOf: List<String>) {
        scope.launch {
            Db.History.deleteBatch(listOf)
            fetchHistoryList()
        }
    }

    fun deleteKeep(key: String) {
        scope.launch {
            val cid = ApiConfig.api.cfg?.id ?: return@launch
            Db.Keep.deleteVod(cid, key)
            fetchKeepList()
        }
    }

    fun fetchHistoryList() {
        scope.launch {
            Db.History.findAll(ApiConfig.api.cfg?.id).collect { list ->
                _state.update { it.copy(historyList = list.toMutableList()) }
            }
        }
    }

    fun fetchKeepList() {
        scope.launch {
            Db.Keep.getVod().collect { list ->
                _state.update { it.copy(keepList = list.toMutableList()) }
            }
        }
    }
}