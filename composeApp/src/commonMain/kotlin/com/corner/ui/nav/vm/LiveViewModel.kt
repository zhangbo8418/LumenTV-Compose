package com.corner.ui.nav.vm

import com.corner.catvodcore.bean.EpgData
import com.corner.catvodcore.bean.Live
import com.corner.catvodcore.bean.LiveChannel
import com.corner.catvodcore.bean.LiveGroup
import com.corner.catvodcore.live.LiveConfig
import com.corner.catvodcore.live.LiveEpg
import com.corner.catvodcore.live.LiveKeep
import com.corner.catvodcore.live.LiveSetting
import com.corner.catvodcore.live.LiveUrlResolver
import com.corner.ui.nav.BaseViewModel
import com.corner.ui.scene.SnackBar
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class LiveScreenState(
    val lives: List<Live> = emptyList(),
    val currentLive: Live? = null,
    val groups: List<LiveGroup> = emptyList(),
    val selectedGroup: LiveGroup? = null,
    val channels: List<LiveChannel> = emptyList(),
    val selectedChannel: LiveChannel? = null,
    val channelIndex: Int = -1,
    val playUrl: String = "",
    val playHeaders: Map<String, String> = emptyMap(),
    val lineLabel: String = "",
    val isLoading: Boolean = false,
    val isResolving: Boolean = false,
    val keptKeys: Set<String> = emptySet(),
    val epgDayOffset: Int = 0,
    /** 频道 EPG 加载完成后递增，驱动节目单/信息条刷新 */
    val epgTick: Long = 0,
)

class LiveViewModel : BaseViewModel() {
    private val _state = MutableStateFlow(LiveScreenState())
    val state = _state.asStateFlow()
    private val groupEpgJobGen = AtomicLong(0)

    init {
        refreshSources()
    }

    fun refreshSources() {
        LiveConfig.syncFromApi()
        val lives = LiveConfig.lives.value
        val home = LiveConfig.getHome() ?: lives.firstOrNull()
        _state.update { it.copy(lives = lives, currentLive = home) }
        home?.let { loadLive(it) }
    }

    fun selectLive(live: Live) {
        LiveConfig.setHome(live)
        _state.update { it.copy(currentLive = live, selectedChannel = null, playUrl = "", channelIndex = -1) }
        loadLive(live)
    }

    fun selectGroup(group: LiveGroup) {
        _state.update {
            it.copy(
                selectedGroup = group,
                channels = group.channels,
                selectedChannel = null,
                playUrl = "",
                channelIndex = -1,
            )
        }
        preloadGroupCurrentPrograms(group.channels)
    }

    fun selectChannel(channel: LiveChannel) {
        _state.update { it.copy(epgDayOffset = 0) }
        resolveAndPlay(channel, channelIndexInGroup(channel), resetLine = true)
    }

    fun setEpgDayOffset(offset: Int) {
        _state.update { it.copy(epgDayOffset = offset) }
    }

    fun toggleKeep(channel: LiveChannel) {
        val live = _state.value.currentLive ?: return
        scope.launch {
            val added = LiveKeep.toggle(live, channel)
            SnackBar.postMsg(
                if (added) "已加入收藏" else "已取消收藏",
                type = SnackBar.MessageType.INFO
            )
            reloadKeepGroup(live)
            refreshKeptKeys(live)
        }
    }

    fun prevChannel() = moveChannel(if (LiveSetting.isInvert()) 1 else -1)

    fun nextChannel() = moveChannel(if (LiveSetting.isInvert()) -1 else 1)

    fun nextLine() = switchLine(next = true)

    fun prevLine() = switchLine(next = false)

    fun onPlaybackError() {
        if (!LiveSetting.isAutoChangeLine()) {
            SnackBar.postMsg("播放失败", type = SnackBar.MessageType.ERROR)
            return
        }
        val channel = _state.value.selectedChannel ?: return
        if (!channel.isLastLine()) {
            switchLine(next = true)
        } else {
            SnackBar.postMsg("所有线路播放失败", type = SnackBar.MessageType.ERROR)
        }
    }

    fun playEpgProgram(program: EpgData) {
        val channel = _state.value.selectedChannel ?: return
        if (!channel.hasCatchup()) {
            SnackBar.postMsg("当前频道不支持回看", type = SnackBar.MessageType.WARNING)
            return
        }
        val catchupUrl = channel.buildCatchupUrl(program) ?: return
        scope.launch {
            _state.update { it.copy(isResolving = true) }
            try {
                val resolved = LiveUrlResolver.resolveUrl(catchupUrl, channel)
                if (resolved.url.isBlank()) {
                    SnackBar.postMsg("回看地址无效", type = SnackBar.MessageType.WARNING)
                    return@launch
                }
                _state.update {
                    it.copy(
                        playUrl = resolved.url,
                        playHeaders = resolved.headers,
                        isResolving = false,
                    )
                }
                SnackBar.postMsg("正在播放回看: ${program.title}", type = SnackBar.MessageType.INFO)
            } catch (e: Exception) {
                _state.update { it.copy(isResolving = false) }
                SnackBar.postMsg("回看播放失败: ${e.message}", type = SnackBar.MessageType.ERROR)
            }
        }
    }

    private fun moveChannel(delta: Int) {
        val groups = _state.value.groups.filter { it.channels.isNotEmpty() }
        val group = _state.value.selectedGroup ?: return
        val channels = group.channels
        if (channels.isEmpty()) return

        var index = _state.value.channelIndex.coerceAtLeast(0) + delta
        if (index in channels.indices) {
            resolveAndPlay(channels[index], index, resetLine = true)
            return
        }

        if (!LiveSetting.isAcross() || groups.size <= 1) {
            index = ((index % channels.size) + channels.size) % channels.size
            resolveAndPlay(channels[index], index, resetLine = true)
            return
        }

        val groupIndex = groups.indexOfFirst { it.name == group.name && it.isKeep == group.isKeep }
        if (groupIndex < 0) return
        var nextGroupIndex = groupIndex + if (delta > 0) 1 else -1
        nextGroupIndex = ((nextGroupIndex % groups.size) + groups.size) % groups.size
        val nextGroup = groups[nextGroupIndex]
        val nextChannelIndex = if (delta > 0) 0 else nextGroup.channels.lastIndex
        selectGroupAndPlay(nextGroup, nextChannelIndex)
    }

    private fun selectGroupAndPlay(group: LiveGroup, channelIndex: Int) {
        val channel = group.channels.getOrNull(channelIndex) ?: return
        _state.update {
            it.copy(
                selectedGroup = group,
                channels = group.channels,
            )
        }
        preloadGroupCurrentPrograms(group.channels)
        resolveAndPlay(channel, channelIndex, resetLine = true)
    }

    private fun switchLine(next: Boolean) {
        val channel = _state.value.selectedChannel ?: return
        if (!channel.hasMultipleLines()) return
        channel.switchLine(next)
        resolveAndPlay(channel, _state.value.channelIndex, resetLine = false)
    }

    private fun resolveAndPlay(channel: LiveChannel, index: Int, resetLine: Boolean) {
        scope.launch {
            if (resetLine) channel.urlIndex = 0
            _state.update {
                it.copy(
                    isResolving = true,
                    selectedChannel = channel,
                    channelIndex = index,
                    epgDayOffset = if (resetLine) 0 else it.epgDayOffset,
                )
            }
            try {
                val zoneId = _state.value.currentLive?.getZoneId()
                LiveEpg.loadChannelEpg(channel, zoneId ?: java.time.ZoneId.systemDefault())
                val resolved = LiveUrlResolver.resolve(channel)
                if (resolved.url.isBlank()) {
                    _state.update {
                        it.copy(
                            isResolving = false,
                            selectedChannel = channel,
                            epgTick = it.epgTick + 1,
                        )
                    }
                    SnackBar.postMsg("频道地址为空", type = SnackBar.MessageType.WARNING)
                    return@launch
                }
                _state.update {
                    it.copy(
                        playUrl = resolved.url,
                        playHeaders = resolved.headers,
                        lineLabel = channel.lineLabel(),
                        selectedChannel = channel,
                        isResolving = false,
                        epgTick = it.epgTick + 1,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isResolving = false) }
                SnackBar.postMsg("解析播放地址失败: ${e.message}", type = SnackBar.MessageType.ERROR)
            }
        }
    }

    private fun channelIndexInGroup(channel: LiveChannel): Int {
        return _state.value.channels.indexOfFirst { it.name == channel.name }
    }

    private fun loadLive(live: Live) {
        scope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    groups = emptyList(),
                    channels = emptyList(),
                    selectedGroup = null,
                    selectedChannel = null,
                    playUrl = "",
                    lineLabel = "",
                )
            }
            try {
                val loaded = LiveConfig.loadChannels(live)
                val groups = buildGroups(loaded)
                val firstGroup = groups.firstOrNull()
                val keptKeys = collectKeptKeys(loaded)
                _state.update {
                    it.copy(
                        currentLive = loaded,
                        groups = groups,
                        selectedGroup = firstGroup,
                        channels = firstGroup?.channels.orEmpty(),
                        keptKeys = keptKeys,
                        isLoading = false,
                    )
                }
                if (groups.isEmpty()) {
                    SnackBar.postMsg("直播源无可用频道", type = SnackBar.MessageType.WARNING)
                } else if (_state.value.selectedChannel == null) {
                    firstGroup?.channels?.firstOrNull()?.let { selectChannel(it) }
                }
                firstGroup?.channels?.let { preloadGroupCurrentPrograms(it) }
                // EPG XML 不阻塞频道列表展示
                launch {
                    runCatching { LiveConfig.loadEpg(loaded) }
                        .onFailure { log.warn("后台加载 EPG 失败: ${loaded.name}", it) }
                    _state.update { it.copy(epgTick = it.epgTick + 1) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
                SnackBar.postMsg("加载直播源失败: ${e.message}", type = SnackBar.MessageType.ERROR)
            }
        }
    }

    /**
     * 预加载当前分组各频道「今天」节目，使列表副标题无需点选也能显示。
     */
    private fun preloadGroupCurrentPrograms(channels: List<LiveChannel>) {
        if (channels.isEmpty()) return
        val gen = groupEpgJobGen.incrementAndGet()
        val zoneId = _state.value.currentLive?.getZoneId() ?: java.time.ZoneId.systemDefault()
        scope.launch {
            channels.chunked(6).forEach { chunk ->
                if (gen != groupEpgJobGen.get()) return@launch
                coroutineScope {
                    chunk.map { channel ->
                        async {
                            runCatching {
                                LiveEpg.loadChannelEpg(channel, zoneId, offsets = listOf(0))
                            }.onFailure {
                                log.debug("预加载频道节目失败: {}", channel.name, it)
                            }
                        }
                    }.awaitAll()
                }
                if (gen == groupEpgJobGen.get()) {
                    _state.update { it.copy(epgTick = it.epgTick + 1) }
                }
            }
        }
    }

    private suspend fun buildGroups(live: Live): List<LiveGroup> {
        val keepGroup = LiveKeep.buildKeepGroup(live)
        val groups = live.groups
            .filter { !it.isKeep && it.channels.isNotEmpty() }
            .toMutableList()
        if (keepGroup.channels.isNotEmpty()) {
            groups.add(0, keepGroup)
        }
        return groups
    }

    private suspend fun reloadKeepGroup(live: Live) {
        val keepGroup = LiveKeep.buildKeepGroup(live)
        val groups = _state.value.groups.filter { !it.isKeep }.toMutableList()
        if (keepGroup.channels.isNotEmpty()) {
            groups.add(0, keepGroup)
        }
        val selected = _state.value.selectedGroup
        val newSelected = when {
            selected?.isKeep == true -> keepGroup.takeIf { it.channels.isNotEmpty() } ?: groups.firstOrNull()
            else -> groups.find { it.name == selected?.name } ?: groups.firstOrNull()
        }
        _state.update {
            it.copy(
                groups = groups,
                selectedGroup = newSelected,
                channels = newSelected?.channels.orEmpty(),
            )
        }
    }

    private suspend fun collectKeptKeys(live: Live): Set<String> {
        val kept = LiveKeep.buildKeepGroup(live).channels
            .map { LiveKeep.keepKey(live, it) }
            .toSet()
        return kept
    }

    private suspend fun refreshKeptKeys(live: Live) {
        _state.update { it.copy(keptKeys = collectKeptKeys(live)) }
    }
}
