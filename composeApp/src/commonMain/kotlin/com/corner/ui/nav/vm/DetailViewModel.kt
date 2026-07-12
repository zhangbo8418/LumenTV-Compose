package com.corner.ui.nav.vm

import com.corner.catvodcore.viewmodel.SiteViewModel
import com.corner.ui.scene.SnackBar
import androidx.compose.runtime.*
import com.corner.util.settings.SettingStore
import com.corner.util.settings.SettingType
import com.corner.service.player.PlayerType
import com.corner.util.settings.getPlayerSetting
import com.corner.catvodcore.bean.Vod
import com.corner.catvodcore.bean.Vod.Companion.getPage
import com.corner.util.core.playResultIsEmpty
import com.corner.util.core.needParse
import com.corner.util.core.isUseParse
import com.corner.util.core.detailIsEmpty
import com.corner.catvodcore.push.PushService
import com.corner.catvodcore.parse.ParseHelper
import com.corner.catvodcore.keep.VodKeep
import com.corner.server.PlaybackControl
import com.corner.server.PlaybackMediaState
import com.corner.server.ServerEvent
import com.corner.util.download.DownloadUrlResolver
import com.corner.util.core.buildUpdatedDetail
import com.corner.util.core.updateFlagActivationStates
import com.corner.catvodcore.bean.*
import com.corner.catvodcore.loader.PlatformSpiderLoader
import com.corner.catvodcore.config.ApiConfig
import com.corner.util.net.Utils
import com.corner.catvodcore.viewmodel.DetailFromPage
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.catvodcore.viewmodel.GlobalAppState.hideProgress
import com.corner.service.history.HistoryService
import com.corner.service.di.ServiceModule
import com.corner.service.episode.EpisodeManager
import com.corner.service.player.PlayerStrategyFactory
import com.corner.service.player.PlayerStrategyConfig
import com.corner.database.entity.History
import com.corner.ui.nav.BaseViewModel
import com.corner.ui.nav.data.DetailScreenState
import com.corner.ui.onUserSelectEpisode
import com.corner.ui.player.PlayState
import com.corner.ui.player.PlayerLifecycleManager
import com.corner.ui.player.PlayerLifecycleState.*
import com.corner.ui.player.VodPlaybackHost
import com.corner.ui.player.vlcj.VlcJInit
import com.corner.ui.player.vlcj.VlcjFrameController
import com.corner.util.core.Constants
import com.corner.util.core.isEmpty
import com.corner.util.play.BrowserUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import org.apache.commons.lang3.StringUtils
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger


class DetailViewModel : BaseViewModel(), VodPlaybackHost {
    // ==================== 状态管理 ====================
    private val _state = MutableStateFlow(DetailScreenState())
    val state: StateFlow<DetailScreenState> = _state

    // ==================== 协程作用域 ====================
    private var supervisor = SupervisorJob()
    private val searchScope = CoroutineScope(Dispatchers.Default + supervisor)

    /**
     * 用于资源清理的独立协程作用域
     * 不受ViewModel生命周期影响，确保清理操作能完整执行
     */
    private val cleanupJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupJob)

    // ==================== 播放器相关（进程级单例，租用而非拥有）====================
    val controller: VlcjFrameController
        get() = VlcJInit.ensureCreated()
    val lifecycleManager: PlayerLifecycleManager
        get() {
            VlcJInit.ensureCreated()
            return checkNotNull(VlcJInit.getLifecycleManager()) { "VLC lifecycleManager 未初始化" }
        }
    var controllerHistory: History? = null
    val vmPlayerType = SettingStore.getSettingItem(SettingType.PLAYER.id)
        .getPlayerSetting(_state.value.detail.site?.playerType)

    // ==================== Service层 ====================
    private val historyService: HistoryService = ServiceModule.provideHistoryService { controller }
    private val episodeManager: EpisodeManager = ServiceModule.provideEpisodeManager()

    // ==================== VodPlaybackHost ====================
    override val isDLNA: Boolean
        get() = _state.value.isDLNA

    // ==================== 业务状态 ====================
    @Volatile
    private var launched = false
    @Volatile
    override var suppressAutoLineSwitch = false
    /** playbackError 防抖：避免 error/finished 连发导致 stop/load 风暴卡死 */
    @Volatile
    private var lastPlaybackErrorAtMs = 0L
    private var currentSiteKey = MutableStateFlow("")
    private val jobList = mutableListOf<Job>()
    private var flagSwitchJob: Job? = null
    private var playJob: Job? = null
    private var playbackJob: Job? = null
    private var loadDetailJob: Job? = null
    private var quickSearchJob: Job? = null
    private val loadDetailSession = AtomicInteger(0)
    private val playerContentTaskId = AtomicInteger(0)
    /** 本集自动换线时已失败的线路，避免末线无法回退、以及死循环 */
    private val failedFlagsForEpisode = mutableSetOf<String>()
    /** 单集自动换线次数上限，防止 stop/load 风暴卡死 UI */
    private var autoFallbackCount = 0
    private val maxAutoFallbackPerEpisode = 6
    @Volatile
    private var pendingPlayRequest: VodPlayRequest? = null
    @Volatile
    private var fetchingPlayKey: String? = null
    private val playFetchLock = Any()
    private var fromSearchLoadJob: Job = Job()
    var currentSelectedEpNumber by mutableStateOf(1)
    val currentEpisodeIndex: Int get() = currentSelectedEpNumber

    private val _currentFlagName = MutableStateFlow("")
    val currentFlagName: StateFlow<String> = _currentFlagName

    private val nextEpisodeLock = Object()
    private val playerStateLock = Mutex()
    private var consecutiveLoadFailures = 0
    private val maxConsecutiveFailures = 3
    var isDownloadUrl = MutableStateFlow<Boolean>(false)
    private val lock = Any()

    override val isLastEpisode: Boolean
        get() {
            val detail = _state.value.detail
            val totalEpisodes = detail.currentFlag.episodes.size
            val currentEp = detail.currentFlag.episodes.find { it.activated }
            if (currentEp != null) {
                val currentIndex = detail.currentFlag.episodes.indexOf(currentEp)
                return currentIndex >= totalEpisodes - 1
            }
            return false
        }

    init {
        BrowserUtils.initialize(this)
        setupPlayerStateObserver()
    }

    /**
     * 设置播放器状态观察者
     */
    private fun setupPlayerStateObserver() {
        scope.launch {
            controller.state.collect { playerState ->
                handlePlayerStateChange(playerState)
            }
        }
    }

    /**
     * 处理播放器状态变化
     */
    private fun handlePlayerStateChange(playerState: com.corner.ui.player.PlayerState) {
        when (playerState.state) {
            PlayState.ERROR -> handlePlayError()
            PlayState.BUFFERING -> _state.update { it.copy(isBuffering = true) }
            else -> _state.update { it.copy(isBuffering = false) }
        }
    }

    /**
     * 处理播放错误
     */
    private fun handlePlayError() {
        log.error("播放错误")
        scope.launch {
            when (lifecycleManager.lifecycleState.value) {
                Playing -> {
                    lifecycleManager.stop()
                    lifecycleManager.ended()
                }

                Loading, Ready, Paused, Ended, Error -> {
                    lifecycleManager.ended()
                }

                Initialized -> {
                    // Initialized 状态需要先经过 Loading 才能到 Ended
                    lifecycleManager.loading()
                    lifecycleManager.ended()
                }

                else -> {
                    // 其他未知状态，尝试 ended
                    lifecycleManager.ended()
                }
            }
        }
    }

    // ==================== 工具方法 ====================

    /** 对齐 TV VodPlayRequest */
    private data class VodPlayRequest(
        val siteKey: String,
        val flag: String,
        val episodeUrl: String,
        val episodeName: String,
    ) {
        fun matches(siteKey: String, flag: Flag, episode: Episode): Boolean {
            return this.siteKey == siteKey &&
                this.flag == (flag.flag ?: "") &&
                episodeUrl == episode.url
        }

        fun accepts(result: Result): Boolean {
            val resultFlag = result.flag?.takeIf { it.isNotBlank() } ?: return true
            return flag == resultFlag
        }
    }

    private fun cancelPlayerContentRequest(keepJob: Job? = null): Int {
        playJob?.takeIf { it != keepJob }?.cancel()
        return playerContentTaskId.incrementAndGet()
    }

    /** 换站点等场景：取消拉地址 + 播放加载，可选回收 Py */
    private fun invalidatePlaybackFully(recyclePy: Boolean = false, keepJob: Job? = null): Int {
        playJob?.takeIf { it != keepJob }?.cancel()
        playbackJob?.cancel()
        if (recyclePy) {
            PlatformSpiderLoader.recycleRecentPy()
        }
        return playerContentTaskId.incrementAndGet()
    }

    /** 对齐 TV ViewModelTaskRunner：仅用于 playerContent 回调是否仍有效 */
    private fun isPlayerContentActive(taskId: Int): Boolean = playerContentTaskId.get() == taskId

    /** 对齐 TV cannotApply：仅校验 pending 请求 vs 当前集（拉地址后、解析前） */
    private fun cannotApplyRequest(): Boolean = cannotApply(result = null)

    /** 对齐 TV cannotApply：pending 请求 vs 当前选中集 + result.flag */
    private fun cannotApply(result: Result? = null): Boolean {
        val request = pendingPlayRequest
        if (request == null) {
            log.info("丢弃播放结果: pending 请求为空")
            return true
        }
        val detail = _state.value.detail
        val currentEp = _state.value.currentEp
        if (currentEp == null) {
            log.info("丢弃播放结果: 当前集为空")
            return true
        }
        if (!request.matches(detail.site?.key ?: "", detail.currentFlag, currentEp)) {
            log.info(
                "丢弃播放结果: pending={} current={}",
                request.episodeName,
                currentEp.name,
            )
            return true
        }
        if (result != null && !request.accepts(result)) {
            log.info(
                "丢弃播放结果: flag 不匹配 result={} request={}",
                result.flag,
                request.flag,
            )
            return true
        }
        return false
    }

    /** 供 VLC loadURL 等起播前校验：pending 请求是否仍对应当前集 */
    override fun shouldApplyPlayback(): Boolean = !cannotApplyRequest()

    /** 对齐 TV playbackError(msg) → VodFallbackPolicy.playbackError() */
    override fun playbackError(msg: String?) {
        if (msg != null && (msg.contains("cancel", ignoreCase = true) || msg.contains("已取消"))) {
            log.debug("跳过 cancellation 触发的 playbackError: {}", msg)
            return
        }
        if (suppressAutoLineSwitch) {
            log.debug("正在切换线路，跳过 playbackError")
            return
        }
        if (playJob?.isActive == true && _state.value.isBuffering) {
            log.debug("正在拉取播放地址，跳过 playbackError")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastPlaybackErrorAtMs < 1_000L) {
            log.debug("playbackError 防抖，跳过: {}", msg)
            return
        }
        lastPlaybackErrorAtMs = now
        log.warn("playbackError: {}", msg ?: "播放失败")
        scope.launch {
            stopPlaybackForRefresh()
            _state.update { it.copy(isBuffering = false, isLoading = false) }
            tryAutoFallback(msg ?: "播放失败")
        }
    }

    /** @return false 表示已达上限，不再自动换线 */
    private fun tryAutoFallback(reason: String): Boolean {
        if (autoFallbackCount >= maxAutoFallbackPerEpisode) {
            log.warn("自动换线已达上限({}): {}", maxAutoFallbackPerEpisode, reason)
            SnackBar.postMsg("多次播放失败，请手动切换线路或片源", type = SnackBar.MessageType.ERROR)
            return false
        }
        autoFallbackCount++
        fallbackToNextLineOrSource()
        return true
    }

    private fun isSiteChangeable(): Boolean =
        _state.value.detail.site?.isChangeable() == true

    /** 对齐 TV VodFallbackPolicy.fallbackToNextLineOrSource() */
    private fun fallbackToNextLineOrSource() {
        // 先尝试同站换线（即使 site.changeable=false，多线路时仍应自动切）
        if (fallbackToNextLine()) return
        if (!isSiteChangeable()) {
            log.warn("无可用线路且站点不可换源，playbackError 仅提示失败")
            SnackBar.postMsg("播放失败，请手动切换线路", type = SnackBar.MessageType.ERROR)
            return
        }
        log.info("无下一线路，尝试换源")
        fallbackToNextSource()
    }

    /** 对齐 TV fallbackToNextLine() → selectFlag(next)；末线则从头绕回，跳过已失败线路 */
    private fun fallbackToNextLine(): Boolean {
        val flags = _state.value.detail.vodFlags
        if (flags.size <= 1) return false
        val currentIndex = flags.indexOfFirst { it.activated }.coerceAtLeast(0)
        val currentFlag = flags.getOrNull(currentIndex)?.flag
        if (!currentFlag.isNullOrBlank()) {
            failedFlagsForEpisode.add(currentFlag)
        }
        for (offset in 1..flags.size) {
            val idx = (currentIndex + offset) % flags.size
            val candidate = flags[idx]
            val name = candidate.flag.orEmpty()
            if (name.isNotBlank() && name in failedFlagsForEpisode) continue
            SnackBar.postMsg("切换线路[${candidate.flag}]", type = SnackBar.MessageType.INFO)
            selectFlagForPlayback(candidate, fromFallback = true)
            return true
        }
        return false
    }

    private fun fallbackToNextSource() {
        val detail = _state.value.detail.copy()
        log.info("没有更多线路，尝试快速搜索换源")
        handleEmptyFlagWithQuickSearch(detail)
    }

    /**
     * 对齐 TV selectFlag → seamless → refresh。
     * fromFallback=true 时即使同 flag 也会继续（fallback 已选下一线路）。
     */
    private fun selectFlagForPlayback(selectedFlag: Flag, fromFallback: Boolean = false) {
        flagSwitchJob?.cancel()
        flagSwitchJob = scope.launch {
            suppressAutoLineSwitch = true
            try {
                if (!fromFallback && selectedFlag.activated) {
                    log.debug("线路已选中，跳过: {}", selectedFlag.flag)
                    return@launch
                }

                val detail = _state.value.detail.copy()
                detail.vodFlags.forEach { it.activated = it.flag == selectedFlag.flag }
                detail.currentFlag = selectedFlag
                detail.subEpisode = selectedFlag.episodes.getPage(detail.currentTabIndex).toMutableList()

                _currentFlagName.value = selectedFlag.flag.toString()
                controller.doWithHistory { it.copy(vodFlag = selectedFlag.flag) }
                GlobalAppState.chooseVod.value = detail.copy()
                _state.update { it.copy(detail = detail, isLoading = false, isBuffering = false) }

                saveCurrentHistory()
                // 换线拉地址挂到 playJob，便于换集时 cancel 打断
                playJob = coroutineContext[Job]
                val taskId = cancelPlayerContentRequest(keepJob = coroutineContext[Job])
                // 仅在 stop 期间抑制 VLC finished 误换线；拉地址阶段要允许空结果继续换线
                stopPlaybackForRefresh()
                suppressAutoLineSwitch = false

                if (!isPlayerContentActive(taskId)) return@launch
                playAfterFlagSwitch(taskId, detail)
            } finally {
                suppressAutoLineSwitch = false
            }
        }
    }

    /** 对齐 TV refresh() 前 saveCurrentHistory() */
    private fun saveCurrentHistory() {
        val history = controller.history.value ?: return
        scope.launch {
            try {
                historyService.updateHistory(history)
            } catch (e: Exception) {
                log.warn("保存播放历史失败: {}", e.message)
            }
        }
    }

    /**
     * 对齐 TV stopPlaybackForRefresh：换集/换线前 pause 并作废排队，随后 engine.start。
     */
    private suspend fun stopPlaybackForRefresh() {
        PlaybackMediaState.playing = false
        controller.stopPlaybackForRefresh()
    }

    /** 对齐 TV PlaybackService.suspend：离开详情停播，保留单例 */
    private suspend fun endPlayback() {
        PlaybackMediaState.playing = false
        VlcJInit.stopPlayback()
    }

    private suspend fun ensurePlayerLifecycleReady() {
        VlcJInit.ensureCreated()
        val lm = lifecycleManager
        when (lm.lifecycleState.value) {
            Idle, Error -> lm.initializeSync()
            else -> {
                // 单例已初始化：确保 surface 可用
                if (!controller.hasPlayer()) {
                    controller.vlcjFrameInit()
                }
            }
        }
    }

    /**
     * 对齐 TV refresh()：save → stopPlaybackForRefresh → requestPlayer。
     */
    private fun refreshPlayback(detail: Vod, ep: Episode) {
        saveCurrentHistory()
        // 同步 pause（等价停播），再异步拉地址起播
        runCatching { controller.stopPlaybackForRefreshSync() }
        requestPlayer(detail, ep, recyclePy = false)
    }

    private fun requestPlayer(detail: Vod, ep: Episode, recyclePy: Boolean) {
        startPlay(detail, ep, recyclePy)
    }

    /**
     * 统一错误处理
     */
    private fun handleError(message: String, e: Exception? = null) {
        if (e != null) {
            log.error(message, e)
        } else {
            log.error(message)
        }
        SnackBar.postMsg(message, type = SnackBar.MessageType.ERROR)
    }

    /**
     * 安全执行异步操作
     */
    private suspend fun <T> safeExecute(
        errorMessage: String,
        block: suspend () -> T
    ): T? {
        return try {
            block()
        } catch (e: Exception) {
            handleError(errorMessage, e)
            null
        }
    }

    // ==================== 生命周期管理 ====================

    /**
     * ViewModel销毁时：只 stop + unbind，不释放全局 VLC 单例。
     */
    override fun onCleared() {
        super.onCleared()
        clearPlaybackControl()
        log.debug("DetailViewModel onCleared - 开始清理")
        supervisor.cancel()
        // Composition 可能已销毁：只关渲染；pause 丢到独立 IO，避免 sync 进 libvlc
        runCatching { VlcJInit.beginLeavePlayback() }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { VlcJInit.stopPlayback() }
        }
        runCatching { VlcJInit.unbindHost(this@DetailViewModel) }
        cleanupJob.cancel()
        log.debug("DetailViewModel onCleared - 已 beginLeave/unbind")
    }

    // ==================== 页面加载流程 ====================


    /**
     * 加载详情页并根据不同来源执行相应操作
     */
    suspend fun load() {
        registerPlaybackControl()
        observeRemoteSubtitle()
        if (vmPlayerType.first() == PlayerType.Innie.id) {
            VlcJInit.bindHost(this)
            ensurePlayerLifecycleReady()
        }
        val chooseVod = loadChooseVod()

        try {
            _state.update { it.copy(isLoading = true) }
            SiteViewModel.viewModelScope.launch {
                if (GlobalAppState.detailFrom == DetailFromPage.SEARCH) {
                    loadFromSearch(chooseVod)
                } else {
                    loadFromNonSearch(chooseVod)
                }
            }.invokeOnCompletion { _state.update { it.copy(isLoading = false) } }
        } catch (e: Exception) {
            log.error("启动加载任务失败", e)
            _state.update { it.copy(isLoading = false) }
            SnackBar.postMsg("启动加载失败: ${e.message}", type = SnackBar.MessageType.ERROR)
        }
    }

    /**
     * 从搜索页加载
     */
    private suspend fun loadFromSearch(chooseVod: Vod) {
        val list = SiteViewModel.getSearchResultActive().list
        loadSearchResult(chooseVod, list)
    }

    /**
     * 从非搜索页加载详情
     */
    private suspend fun loadFromNonSearch(chooseVod: Vod) {
        if (PushService.isPushSite(chooseVod.site?.key)) {
            loadPush(chooseVod)
            return
        }
        val dt = fetchDetailContent(chooseVod)

        if (chooseVod.vodId.isBlank()) return
        if (dt == null || dt.detailIsEmpty()) {
            quickSearch()
        } else {
            loadVodDetail(dt)
            val detail = _state.value.detail
            val ep = detail.subEpisode.find { it.activated }
                ?: detail.currentFlag.episodes.firstOrNull { it.activated }
                ?: Episode.create("", "")
            startPlay(detail, ep)
        }
    }

    private suspend fun loadPush(vod: Vod) {
        val url = DownloadUrlResolver.resolve(PushService.extractUrl(vod.vodId))
        val displayName = vod.vodName?.takeIf { it.isNotBlank() } ?: PushService.displayName(url)
        val episode = Episode.create(displayName, url)
        val detail = vod.copy(
            vodId = url,
            vodName = displayName,
            site = Site.get(PushService.SITE_KEY, "推送"),
            subEpisode = mutableListOf(episode),
        )
        _state.update { it.copy(detail = detail) }
        currentSiteKey.value = PushService.SITE_KEY
        updateEpisodeActivation(episode)
        startPlay(detail, episode)
    }

    private fun registerPlaybackControl() {
        PlaybackControl.play = { controller.play() }
        PlaybackControl.pause = { controller.pause() }
        PlaybackControl.stop = {
            scope.launch {
                controller.stop()
                PlaybackMediaState.playing = false
            }
        }
        PlaybackControl.next = {
            getNextEpisodeUrl()?.let { playEp(_state.value.detail, Episode.create("", it)) }
        }
        PlaybackControl.prev = {
            SnackBar.postMsg("上一集请使用播放器控制", type = SnackBar.MessageType.INFO)
        }
    }

    private fun clearPlaybackControl() {
        PlaybackControl.play = null
        PlaybackControl.pause = null
        PlaybackControl.stop = null
        PlaybackControl.next = null
        PlaybackControl.prev = null
    }

    fun selectSubtitle(url: String) {
        _state.update { it.copy(selectedSubUrl = url) }
        PlaybackMediaState.subtitleUrl = url
        controller.setSubtitleUrl(url)
        controller.applySubtitle(url)
        SnackBar.postMsg("已选择字幕", type = SnackBar.MessageType.INFO)
    }

    private fun observeRemoteSubtitle() {
        scope.launch {
            ServerEvent.subtitlePath.collect { path ->
                if (path.isBlank()) return@collect
                val url = when {
                    path.startsWith("http", ignoreCase = true) -> path
                    path.startsWith("file", ignoreCase = true) -> java.io.File(
                        path.removePrefix("file://").removePrefix("file:/")
                    ).takeIf { it.exists() }?.toURI()?.toString() ?: path
                    else -> path
                }
                selectSubtitle(url)
            }
        }
    }

    /**
     * 获取详情内容
     */
    private suspend fun fetchDetailContent(chooseVod: Vod): Result? {
        return try {
            SiteViewModel.detailContent(chooseVod.site?.key ?: "", chooseVod.vodId)
        } catch (e: Exception) {
            log.error("加载详情失败: {}", e.message, e)
            SnackBar.postMsg("加载失败: ${e.message}", type = SnackBar.MessageType.ERROR)
            null
        }
    }

    // ==================== 历史记录管理 ====================

    /**
     * 更新历史记录信息
     */
    override fun updateHistory(it: History) {
        scope.launch {
            historyService.updateHistory(it)
        }
    }

    /**
     * 从Controller触发的历史记录同步
     * 用于播放器重新加载后同步历史记录状态
     */
    internal fun syncHistoryFromController() {
        val detail = _state.value.detail
        if (detail.vodId.isNotBlank()) {
            scope.launch {
                try {
                    historyService.syncHistory(detail)
                } catch (e: Exception) {
                    log.error("同步历史记录失败", e)
                }
            }
        }
    }

    // ==================== 数据加载辅助方法 ====================

    /**
     * 获取视频信息，并更新当前站点key
     */
    private fun loadChooseVod(): Vod {
        val chooseVod = getChooseVod()
        GlobalAppState.consumeCastResume()?.let { castHistory ->
            controller.setControllerHistory(castHistory)
        }
        _state.update { it.copy(detail = chooseVod) }
        currentSiteKey.value = chooseVod.site?.key ?: ""
        refreshKeepStatus(chooseVod)
        return chooseVod
    }

    private fun getChooseVod(): Vod = GlobalAppState.chooseVod.value

    /**
     * 加载搜索详情页信息
     */
    private fun loadSearchResult(chooseVod: Vod, list: MutableList<Vod>) {
        _state.update {
            it.copy(
                detail = chooseVod,
                quickSearchResult = CopyOnWriteArrayList(list)
            )
        }
        fromSearchLoadJob = SiteViewModel.viewModelScope.launch {
            if (_state.value.quickSearchResult.isNotEmpty()) {
                _state.value.detail.let { loadDetail(it) }
            }
        }
    }

    /**
     * 加载详情页信息
     */
    private fun loadVodDetail(dt: Result) {
        var detail = dt.list[0]
        detail = detail.copy(subEpisode = detail.currentFlag.episodes.getPage(detail.currentTabIndex))

        if (StringUtils.isNotBlank(getChooseVod().vodRemarks)) {
            for (it: Episode in detail.subEpisode) {
                if (it.name == getChooseVod().vodRemarks) {
                    it.activated = true
                    break
                }
            }
        }
        detail.site = getChooseVod().site
        _state.update { it.copy(detail = detail) }
        _currentFlagName.value = detail.currentFlag.flag.toString()
        refreshKeepStatus(detail)
        scope.launch { VodKeep.update(detail) }
    }

    // ==================== 快速搜索功能 ====================

    /**
     * 执行快速搜索操作，从可切换的站点中搜索视频信息
     * @param onComplete 搜索完成后的回调函数
     */
    fun quickSearch(onComplete: ((List<Vod>) -> Unit)? = null) {
        quickSearchJob?.cancel()
        loadDetailJob?.cancel()
        resetQuickSearchState()

        quickSearchJob = searchScope.launch {
            _state.update { it.copy(isLoading = true, isBuffering = false) }
            val quickSearchSites = ApiConfig.api.sites.filter { it.changeable == 1 && !it.isHide() }.shuffled()
            val totalSites = quickSearchSites.size

            if (totalSites == 0) {
                handleNoSearchSites(onComplete)
                return@launch
            }

            log.info("开始执行快搜，站点数: {}", totalSites)
            postQuickSearchProgress(0, totalSites)

            try {
                executeSearchTasks(quickSearchSites, totalSites, onComplete)
            } finally {
                if (isActive) {
                    _state.update { it.copy(isLoading = false) }
                }
                onComplete?.invoke(_state.value.quickSearchResult)
            }
        }
    }

    private fun resetQuickSearchState() {
        launched = false
        consecutiveLoadFailures = 0
        jobList.clear()
    }

    private suspend fun handleNoSearchSites(onComplete: ((List<Vod>) -> Unit)?) {
        log.warn("没有可用的搜索站点")
        _state.update { it.copy(isLoading = false) }
        SnackBar.postMsg("暂无可用站点", type = SnackBar.MessageType.WARNING)
        onComplete?.invoke(emptyList())
    }

    private suspend fun executeSearchTasks(
        quickSearchSites: List<com.corner.catvodcore.bean.Site>,
        totalSites: Int,
        onComplete: ((List<Vod>) -> Unit)?
    ) {
        val semaphore = Semaphore(2)
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val hasLoadedDetail = java.util.concurrent.atomic.AtomicBoolean(false)

        quickSearchSites.forEach { site ->
            val job = launchSearchTask(site, semaphore, completedCount, totalSites, hasLoadedDetail)
            jobList.add(job)
        }

        try {
            jobList.joinAll()
        } catch (e: Exception) {
            log.error("等待搜索任务完成时发生异常: {}", e.message)
        } finally {
            jobList.clear()
        }

        handleSearchCompletion()
    }

    private fun launchSearchTask(
        site: com.corner.catvodcore.bean.Site,
        semaphore: Semaphore,
        completedCount: java.util.concurrent.atomic.AtomicInteger,
        totalSites: Int,
        hasLoadedDetail: java.util.concurrent.atomic.AtomicBoolean
    ): Job {
        val job = searchScope.launch {
            semaphore.acquire()
            try {
                withTimeout(2500L) {
                    SiteViewModel.searchContent(site, getChooseVod().vodName ?: "", true)
                    log.debug("{}完成搜索", site.name)
                }
            } catch (e: TimeoutCancellationException) {
                log.warn("搜索站点 {} 超时", site.name)
            } catch (e: Exception) {
                log.error("搜索站点 {} 时发生异常: {}", site.name, e.message)
            } finally {
                semaphore.release()
            }
        }

        job.invokeOnCompletion { throwable ->
            handleSearchTaskCompletion(
                throwable,
                completedCount,
                totalSites,
                hasLoadedDetail,
                searchScope
            )
        }

        return job
    }

    private fun handleSearchTaskCompletion(
        throwable: Throwable?,
        completedCount: java.util.concurrent.atomic.AtomicInteger,
        totalSites: Int,
        hasLoadedDetail: java.util.concurrent.atomic.AtomicBoolean,
        scope: CoroutineScope
    ) {
        val count = completedCount.incrementAndGet()
        if (throwable != null && throwable !is TimeoutCancellationException) {
            log.error("quickSearch 协程执行异常: {}", throwable.message)
        }

        val currentSiteName = ApiConfig.api.sites.filter { it.changeable == 1 && !it.isHide() }
            .getOrNull(count - 1)?.name ?: "完成"
        postQuickSearchProgress(count, totalSites, currentSiteName)

        updateSearchResults()

        if (shouldLoadDetail(throwable, hasLoadedDetail)) {
            synchronized(lock) {
                if (!_state.value.quickSearchResult.isEmpty() &&
                    _state.value.detail.isEmpty() &&
                    !launched
                ) {
                    scope.launch {
                        try {
                            log.info("开始加载详情")
                            launched = true
                            val firstResult = _state.value.quickSearchResult.firstOrNull()
                            if (firstResult != null) {
                                loadDetailInternal(loadDetailSession.incrementAndGet(), firstResult)
                            } else {
                                launched = false
                                hasLoadedDetail.set(false)
                            }
                        } catch (e: CancellationException) {
                            launched = false
                            hasLoadedDetail.set(false)
                            throw e
                        } catch (e: Exception) {
                            log.error("加载详情时发生异常: {}", e.message)
                            launched = false
                            hasLoadedDetail.set(false)
                        }
                    }
                }
            }
        }
    }

    private fun updateSearchResults() {
        try {
            val searchResults = SiteViewModel.quickSearch.value
            if (searchResults.isNotEmpty() && searchResults[0].list.isNotEmpty()) {
                _state.update { state ->
                    val existingUrls = state.quickSearchResult.map { it.vodId }.toSet()
                    val newVods = searchResults[0].list.filter { it.vodId !in existingUrls }
                    if (newVods.isNotEmpty()) {
                        val updatedList = CopyOnWriteArrayList(state.quickSearchResult)
                        updatedList.addAll(newVods)
                        state.copy(quickSearchResult = updatedList)
                    } else {
                        state
                    }
                }
            }
        } catch (e: Exception) {
            log.error("更新搜索结果时发生异常: {}", e.message)
        }
    }

    private fun shouldLoadDetail(
        throwable: Throwable?,
        hasLoadedDetail: java.util.concurrent.atomic.AtomicBoolean
    ): Boolean {
        return (throwable == null || throwable is TimeoutCancellationException) &&
                !_state.value.quickSearchResult.isEmpty() &&
                _state.value.detail.isEmpty() &&
                !launched &&
                hasLoadedDetail.compareAndSet(false, true)
    }

    private fun handleSearchCompletion() {
        if (_state.value.quickSearchResult.isEmpty() && _state.value.detail.isEmpty()) {
            _state.update {
                it.copy(
                    detail = GlobalAppState.chooseVod.value,
                    isLoading = false
                )
            }
            SnackBar.postMsg("暂无线路数据", type = SnackBar.MessageType.WARNING)
        }
    }


    // ==================== 详情加载与切换 ====================

    /**
     * 加载快速搜索出的视频的详细信息
     */
    fun loadDetail(vod: Vod) {
        loadDetailJob?.cancel()
        invalidatePlaybackFully(recyclePy = true)
        controller.prepareForEpisodeSwitch()
        val session = loadDetailSession.incrementAndGet()
        loadDetailJob = scope.launch {
            loadDetailInternal(session, vod)
        }
    }

    private suspend fun loadDetailInternal(session: Int, vod: Vod) {
        if (loadDetailSession.get() != session) return

        log.info("加载详情 <${vod.vodName}> <${vod.vodId}> site:<${vod.site?.name}>")

        try {
            _state.update { it.copy(isLoading = true, isBuffering = false) }
            val siteKey = vod.site?.key ?: run {
                handleSiteEmpty()
                return
            }

            val dt = withTimeout(45_000) {
                fetchDetailWithRetry(siteKey, vod.vodId)
            }
            if (loadDetailSession.get() != session) return

            if (dt == null || dt.detailIsEmpty()) {
                handleDetailLoadFailure(vod)
                return
            }

            val first = dt.list.firstOrNull()
            if (first == null || first.isEmpty()) {
                handleEmptyDetail(vod)
                return
            }

            onDetailLoadSuccess(first, vod)
        } catch (e: CancellationException) {
            log.info("加载详情已取消: {}", vod.vodName)
            throw e
        } catch (e: TimeoutCancellationException) {
            if (loadDetailSession.get() != session) return
            log.warn("加载详情超时: {}", vod.site?.name)
            SnackBar.postMsg("加载详情超时: ${vod.site?.name}", type = SnackBar.MessageType.WARNING)
            _state.update { it.copy(isLoading = false) }
            if (incrementAndCheckFailures()) {
                nextSite(vod)
            }
        } catch (e: Exception) {
            if (loadDetailSession.get() != session) return
            handleError("加载详情时发生未预期异常: ${e.message}", e)
            _state.update { it.copy(isLoading = false) }
        } finally {
            launched = false
            if (loadDetailSession.get() == session) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun handleSiteEmpty() {
        log.warn("站点为空")
        SnackBar.postMsg("站点为空", type = SnackBar.MessageType.WARNING)
        _state.update { it.copy(isLoading = false) }
    }

    private suspend fun fetchDetailWithRetry(siteKey: String, vodId: String): Result? {
        return safeExecute("获取视频详情信息时发生异常") {
            SiteViewModel.detailContent(siteKey, vodId)
        }
    }

    private fun handleDetailLoadFailure(vod: Vod) {
        log.info("请求详情为空，加载下一个站源数据")
        SnackBar.postMsg("请求详情为空，尝试下一个站源", type = SnackBar.MessageType.INFO)
        _state.update { it.copy(isLoading = false) }

        if (incrementAndCheckFailures()) {
            nextSite(vod)
        }
    }

    private fun handleEmptyDetail(vod: Vod) {
        log.warn("详情对象为空，尝试下一个站源")
        _state.update { it.copy(isLoading = false) }

        if (incrementAndCheckFailures()) {
            nextSite(vod)
        }
    }

    private fun incrementAndCheckFailures(): Boolean {
        consecutiveLoadFailures++
        if (consecutiveLoadFailures >= maxConsecutiveFailures) {
            log.warn("连续加载失败次数达到上限")
            SnackBar.postMsg("连续加载失败次数达到上限，取消加载", type = SnackBar.MessageType.WARNING)
            return false
        }
        return true
    }

    private fun onDetailLoadSuccess(first: Vod, vod: Vod) {
        consecutiveLoadFailures = 0
        first.site = vod.site
        setDetail(first)
        log.debug("切换站源，新的站源: {}", first.site?.name)
        _currentFlagName.value = first.currentFlag.flag.toString()

        // 取消剩余的搜索任务
        supervisor.cancelChildren()
        jobList.forEach { it.cancel("detail loaded") }
        jobList.clear()
    }

    /**
     * 尝试从快速搜索结果中加载下一个视频的详情
     */
    fun nextSite(lastVod: Vod?) {
        if (_state.value.quickSearchResult.isEmpty()) {
            log.warn("快搜结果为空,无法加载下一个视频")
            _state.update { it.copy(isLoading = false) }
            SnackBar.postMsg("暂无更多视频", type = SnackBar.MessageType.WARNING)
            return
        }

        val list = _state.value.quickSearchResult
        if (lastVod != null) {
            val remove = list.remove(lastVod)
            log.debug("remove last vod result:$remove")
        }

        _state.update { it.copy(quickSearchResult = list) }

        if (_state.value.quickSearchResult.isNotEmpty()) {
            loadDetail(_state.value.quickSearchResult[0])
        } else {
            _state.update { it.copy(isLoading = false) }
        }
    }

    /**
     * 设置快速搜索出的视频详情信息并准备播放新视频
     */
    private fun setDetail(vod: Vod) {
        if (currentSiteKey.value != vod.site?.key) {
            SnackBar.postMsg("正在切换站源至 [${vod.site!!.name}]", type = SnackBar.MessageType.INFO)
        }

        updateDetailState(vod)
        setupDefaultEpisode(vod)
        _state.update { it.copy(isLoading = false) }

        scope.launch {
            log.info("开始播放视频: ${vod.vodName}")
            val effectiveEpisode = vod.vodFlags.firstOrNull()?.episodes?.firstOrNull()
                ?: Episode.create("", "")
            startPlay(vod, effectiveEpisode)
        }
    }

    private fun updateDetailState(vod: Vod) {
        _state.update {
            it.copy(
                detail = vod.copy(
                    subEpisode = vod.vodFlags.firstOrNull()?.episodes
                        ?.getPage(vod.currentTabIndex)?.toMutableList()
                        ?: mutableListOf()
                ),
                isLoading = false
            )
        }
        refreshKeepStatus(vod)
        scope.launch { VodKeep.update(vod) }
    }

    fun toggleKeep() {
        val detail = _state.value.detail
        if (detail.vodId.isBlank()) return
        scope.launch {
            val added = VodKeep.toggle(detail)
            _state.update { it.copy(isKept = added) }
            SnackBar.postMsg(
                if (added) "已加入收藏" else "已取消收藏",
                type = SnackBar.MessageType.INFO
            )
        }
    }

    private fun refreshKeepStatus(detail: Vod) {
        if (detail.vodId.isBlank()) {
            _state.update { it.copy(isKept = false) }
            return
        }
        scope.launch {
            val kept = VodKeep.isKept(detail)
            _state.update { it.copy(isKept = kept) }
        }
    }

    private fun setupDefaultEpisode(vod: Vod) {
        val firstEpisode = vod.vodFlags.first().episodes.firstOrNull()
        if (firstEpisode != null) {
            updateEpisodeActivation(firstEpisode)
        } else {
            _state.update { it.copy(currentEp = null) }
        }
    }

    // ==================== UI辅助方法 ====================

    /**
     * 发布快速搜索进度消息
     */
    fun postQuickSearchProgress(current: Int, total: Int, currentSite: String = "") {
        val message = if (currentSite.isNotEmpty()) {
            "搜索进度: $current/$total - $currentSite"
        } else {
            "搜索进度: $current/$total"
        }
        SnackBar.postMsg(message, priority = 1, type = SnackBar.MessageType.INFO, key = "quick_search_progress")
    }

    /**
     * 清理详情页相关资源和状态。
     * @param unbindHost 离开详情页时解绑回调；页内快搜传 false 以保持播放能力。
     */
    fun clear(
        unbindHost: Boolean = true,
        onComplete: () -> Unit = {},
    ) {
        log.debug("----------开始清理详情页资源----------")
        // 先取消拉地址/播放任务，避免返回主页后 Py RPC 回调还改状态
        runCatching { cancelPlayerContentRequest() }
        playbackJob?.cancel()
        loadDetailJob?.cancel()
        quickSearchJob?.cancel()

        // Composition onDispose 里禁止同步调 libvlc：只关渲染，pause 全部异步
        if (unbindHost) {
            runCatching { VlcJInit.beginLeavePlayback() }
        }

        cleanupScope.launch(Dispatchers.IO) {
            try {
                if (unbindHost) {
                    runCatching { VlcJInit.stopPlayback() }
                }
                withTimeoutOrNull(1_500L) {
                    performCleanup(unbindHost)
                } ?: log.warn("详情页资源清理超时，强制继续")
                resetStateAndResources()

                withContext(Dispatchers.Swing) {
                    onComplete()
                }
            } catch (_: CancellationException) {
                log.debug("详情页清理被取消（停播已异步发起）")
            } catch (e: Exception) {
                log.error("----------清理过程中出错----------", e)
            } finally {
                runCatching {
                    withContext(Dispatchers.Swing) {
                        hideProgress()
                    }
                }
                _state.update { it.copy(isLoading = false, isBuffering = false) }
            }
        }
    }

    private suspend fun performCleanup(unbindHost: Boolean) {
        log.debug("<清理资源>播放器类型:{} unbind={}", vmPlayerType.first(), unbindHost)

        if (vmPlayerType.first() != PlayerType.Innie.id) return

        if (unbindHost) {
            // clear()/onCleared 已同步 endPlayback，这里只解绑，避免二次停播
            VlcJInit.unbindHost(this)
        }
    }

    private fun resetStateAndResources() {
        loadDetailJob?.cancel()
        quickSearchJob?.cancel()
        playbackJob?.cancel()
        jobList.forEach {
            try {
                it.cancel("detail clear")
            } catch (e: Exception) {
                log.warn("取消协程任务时出错", e)
            }
        }
        jobList.clear()

        _state.update { it.copy() }
        SiteViewModel.clearQuickSearch()
        launched = false

        BrowserUtils.cleanup()
        BrowserUtils.detailViewModel = null

        log.debug("----------清理详情页资源完成----------")
    }

    // ==================== 播放控制 - 内部播放器 ====================

    /**
     * 内部播放器播放入口
     */
    fun inniePlay(result: Result?) {
        if (result == null || result.playResultIsEmpty()) {
            SnackBar.postMsg("加载内容失败，尝试切换线路", type = SnackBar.MessageType.WARNING)
            playbackError("加载内容失败")
            return
        }

        scope.launch {
            try {
                val playable = resolvePlayResult(result) ?: run {
                    SnackBar.postMsg("解析播放地址失败，尝试切换线路", type = SnackBar.MessageType.WARNING)
                    playbackError("解析播放地址失败")
                    return@launch
                }
                updatePlaybackState(playable)
                prepareForPlayback(playable)
            } catch (e: Exception) {
                handleError("播放器初始化失败: ${e.message}", e)
                _state.update { it.copy() }
            }
        }
    }

    private suspend fun resolvePlayResult(result: Result): Result? {
        if (!result.needParse() && !result.isUseParse()) return result
        return ParseHelper.parseVod(result, useParse = result.isUseParse())
    }

    private fun updatePlaybackState(result: Result) {
        _state.update {
            it.copy(
                currentPlayUrl = result.url.v(),
                playResult = result,
                isDLNA = false
            )
        }
    }

    /**
     * 准备播放：确保播放器处于Ready状态
     */
    private suspend fun prepareForPlayback(result: Result) {
        log.debug("当前状态:{}", lifecycleManager.lifecycleState.value)

        val success = when (lifecycleManager.lifecycleState.value) {
            Ready -> {
                playInitPlayer(result)
                return
            }

            Playing -> transitionFromPlayingToReady()
            Loading, Ended -> lifecycleManager.ready().isSuccess
            Error -> recoverFromErrorState()
            else -> transitionFromOtherStatesToReady()
        }

        if (success) {
            playInitPlayer(result)
        } else {
            handleError("播放器状态错误，无法准备播放")
        }
    }

    private suspend fun transitionFromPlayingToReady(): Boolean {
        log.debug("当前状态为playing，需要状态转换")
        return lifecycleManager.stop().isSuccess &&
                lifecycleManager.ended().isSuccess &&
                lifecycleManager.ready().isSuccess
    }

    private suspend fun recoverFromErrorState(): Boolean {
        return try {
            // 单例：禁止拆实例式 cleanup；离开式停播后重新就绪
            endPlayback()
            ensurePlayerLifecycleReady()

            val loadingSuccess = lifecycleManager.loading().isSuccess
            if (!loadingSuccess) {
                handleError("播放器加载失败")
                return false
            }

            val readySuccess = lifecycleManager.ready().isSuccess
            if (!readySuccess) {
                handleError("播放器准备就绪失败")
                return false
            }

            log.debug("错误状态恢复成功")
            true
        } catch (e: Exception) {
            handleError("错误状态恢复过程中发生异常: ${e.message}", e)
            false
        }
    }

    private suspend fun transitionFromOtherStatesToReady(): Boolean {
        val currentState = lifecycleManager.lifecycleState.value
        log.debug("当前状态:{}，转换到ready状态", currentState)

        return when (currentState) {
            Initialized -> {
                lifecycleManager.loading().isSuccess &&
                        lifecycleManager.ready().isSuccess
            }

            else -> {
                lifecycleManager.ended().isSuccess &&
                        lifecycleManager.ready().isSuccess
            }
        }
    }

    /**
     * 初始化播放器并加载视频
     */
    private suspend fun playInitPlayer(result: Result) {
        _state.update { it.copy(isLoading = false, isBuffering = false) }

        if (!validatePlayerState()) return

        if (!loadVideoUrl(result)) return

        startPlaybackWithTimeout()
    }

    private fun validatePlayerState(): Boolean {
        if (lifecycleManager.lifecycleState.value != Ready) {
            log.error("播放器状态不正确: {},播放器检查失败！", lifecycleManager.lifecycleState.value)
            return false
        }
        return true
    }

    private suspend fun loadVideoUrl(result: Result): Boolean {
        return try {
            controller.loadURL(
                result.url.v(),
                PlayerStrategyConfig.INNIE_LOAD_URL_TIMEOUT_MS
            )
            true
        } catch (e: Exception) {
            handleError("加载播放链接失败: ${e.message}", e)
            false
        }
    }

    private suspend fun startPlaybackWithTimeout() {
        try {
            withTimeout(PlayerStrategyConfig.DETAIL_PLAYBACK_LOAD_TIMEOUT_MS) {
                log.info("播放器加载完成，开始转换状态")
                transitionToPlayingState()
            }
        } catch (e: TimeoutCancellationException) {
            handleError("播放器加载超时")
            lifecycleManager.ended()
        } catch (e: Exception) {
            handleError("播放器准备就绪时发生错误: ${e.message}", e)
            lifecycleManager.ended()
        }
    }

    private suspend fun transitionToPlayingState() {
        lifecycleManager.transitionTo(Playing) {
            lifecycleManager.start()
                .onFailure {
                    handleError("播放器状态转换 Playing 失败: ${it.message}")
                }
        }.onFailure { e ->
            handleError("播放器就绪失败: ${e.message}")
        }
    }

    /**
     * 播放指定视频的指定剧集
     */
    private fun playEp(detail: Vod, ep: Episode) {
        refreshPlayback(detail, ep)
    }

    /** 对齐 TV VodPlayRequest.id = episode.getUrl() */
    private fun buildPlayKey(detail: Vod, ep: Episode): String {
        return "${detail.site?.key}|${ep.url}"
    }

    private suspend fun requestPlayerAndPlay(taskId: Int, detail: Vod, ep: Episode) {
        if (!isPlayerContentActive(taskId)) return
        updateCurrentEpisodeState(detail, ep)
        pendingPlayRequest = VodPlayRequest(
            siteKey = detail.site?.key ?: "",
            flag = detail.currentFlag.flag ?: "",
            episodeUrl = ep.url,
            episodeName = ep.name,
        )
        log.info(
            "requestPlayer: ep={}, taskId={}, generation={}",
            ep.name,
            taskId,
            controller.currentLoadGeneration(),
        )
        playEpInternal(taskId, detail, ep)
    }

    private suspend fun playEpInternal(taskId: Int, detail: Vod, ep: Episode) {
        if (!isPlayerContentActive(taskId)) return

        val playKey = buildPlayKey(detail, ep)
        synchronized(playFetchLock) {
            if (fetchingPlayKey == playKey) {
                log.info("跳过重复拉地址: ep={}", ep.name)
                return
            }
            fetchingPlayKey = playKey
        }

        _state.update { it.copy(isBuffering = true) }
        onUserSelectEpisode()
        currentSelectedEpNumber = ep.number

        try {
            log.info("playEpInternal 开始获取播放地址: ep={}, flag={}", ep.name, detail.currentFlag.flag)
            var result = withTimeout(60_000) {
                withContext(Dispatchers.IO) {
                    ensureActive()
                    fetchPlayResult(detail, ep)
                }
            }
            log.info("playEpInternal 获取播放地址完成: ep={}", ep.name)
            if (!isPlayerContentActive(taskId) || cannotApplyRequest()) return

            if (handleSpecialLink(ep, result)) return

            if (result == null || result.playResultIsEmpty()) {
                handleEmptyPlayResult()
                return
            }

            result = withContext(Dispatchers.IO) {
                com.corner.util.m3u8.M3u8PlayUrlResolver.resolveForPlayback(result)
            }
            // OkHttp 阻塞不会被 cancel 打断，处理后必须再校验，否则会污染 currentEp
            if (!isPlayerContentActive(taskId) || cannotApplyRequest()) {
                log.info("丢弃过期播放结果(M3U8处理后): ep={}", ep.name)
                return
            }
            // 广告网关等处理后可能清空地址，立刻换线，避免起播秒停
            if (result.playResultIsEmpty()) {
                handleEmptyPlayResult(tryNextLine = true)
                return
            }
            // 对齐 TV：非直链先强制走解析，禁止「不可直接播放就立刻换线」跳过 ParseHelper
            val rawPlayUrl = result.url.v()
            if (!isDirectlyPlayable(rawPlayUrl) && !result.needParse() && !result.isUseParse()) {
                if (com.corner.catvodcore.config.ParseConfig.hasParse() &&
                    !com.corner.util.VideoSniffer.isVideoFormat(rawPlayUrl)
                ) {
                    log.info("非直链地址，强制走解析: {}", rawPlayUrl.take(80))
                    result.parse = 1
                } else if (!com.corner.util.VideoSniffer.isVideoFormat(rawPlayUrl)) {
                    log.warn("播放地址不可直接播放且无解析器: {}", rawPlayUrl.take(80))
                    handleEmptyPlayResult(tryNextLine = true)
                    return
                }
            }

            log.info("applyPlayerResult: ep={}", ep.name)
            updatePlayState(result, ep)

            var playable = result
            if (playable.needParse() || playable.isUseParse()) {
                val beforeParse = playable.url.v()
                playable = resolvePlayResult(playable) ?: run {
                    // 解析失败但原地址可播：对齐 TV，直接播直链
                    if (isDirectlyPlayable(beforeParse) ||
                        com.corner.util.VideoSniffer.isVideoFormat(beforeParse)
                    ) {
                        log.warn("解析失败，回退原始直链: {}", beforeParse.take(120))
                        playable
                    } else {
                        if (!isPlayerContentActive(taskId)) return
                        SnackBar.postMsg("解析播放地址失败，尝试切换线路", type = SnackBar.MessageType.WARNING)
                        handleEmptyPlayResult(tryNextLine = true)
                        return
                    }
                }
                if (playable.playResultIsEmpty()) {
                    if (!isPlayerContentActive(taskId)) return
                    handleEmptyPlayResult(tryNextLine = true)
                    return
                }
                if (!isPlayerContentActive(taskId) || cannotApply(playable)) return
                updatePlayState(playable, ep)
            }

            if (!isPlayerContentActive(taskId) || cannotApply(playable)) return
            if (!isDirectlyPlayable(playable.url.v())) {
                log.warn("解析后仍不可直接播放，尝试换线: {}", playable.url.v().take(80))
                handleEmptyPlayResult(tryNextLine = true)
                return
            }
            startPlayback(playable, ep)
        } catch (e: CancellationException) {
            log.info("播放任务已取消: ep={}", ep.name)
            throw e
        } catch (e: TimeoutCancellationException) {
            if (!isPlayerContentActive(taskId)) return
            handleError("获取播放地址超时，请稍后重试")
        } catch (e: Exception) {
            if (!isPlayerContentActive(taskId)) return
            handleError("获取播放地址失败: ${e.message}", e)
        } finally {
            synchronized(playFetchLock) {
                if (fetchingPlayKey == playKey) fetchingPlayKey = null
            }
            _state.update { state ->
                state.copy(
                    isLoading = false,
                    isBuffering = if (isPlayerContentActive(taskId)) state.isBuffering else false,
                )
            }
        }
    }

    /**
     * 对齐 TV applyPlayerResult → startPlayback：同步起播，不用 playbackJob 排队竞争。
     */
    private suspend fun startPlayback(result: Result, ep: Episode) {
        if (cannotApply(result)) return
        try {
            executePlaybackByType(result, ep)
        } catch (e: CancellationException) {
            log.info("播放执行已取消: ep={}", ep.name)
            throw e
        } catch (e: Exception) {
            if (!cannotApply(result)) {
                handleError("播放执行失败: ${e.message}", e)
            }
        } finally {
            _state.update { it.copy(isBuffering = false) }
        }
    }

    private fun fetchPlayResult(detail: Vod, ep: Episode): Result? {
        return SiteViewModel.playerContent(
            detail.site?.key ?: "",
            detail.currentFlag.flag ?: "",
            ep.url
        )
    }

    private fun handleSpecialLink(ep: Episode, result: Result?): Boolean {
        if (Utils.isDownloadLink(ep.url)) {
            isDownloadUrl.value = true
            _state.update { it.copy(isBuffering = false) }
            log.info("检测到磁力链接，将通过对话框提示用户选择，url:{}", ep.url)
            return true
        }
        return false
    }

    private fun handleEmptyPlayResult(tryNextLine: Boolean = false) {
        _state.update { it.copy(isBuffering = false, isLoading = false, useParse = false) }
        log.warn("播放结果为空,无法播放")
        SnackBar.postMsg("获取播放地址失败", type = SnackBar.MessageType.ERROR)
        // 拉地址失败必须允许继续换线；suppress 只挡 VLC stop/finished 误触发
        if (tryNextLine) {
            tryAutoFallback("播放结果为空")
        }
    }

    /** VLC 可直接打开的地址；加密串/相对路径等会变成「File name too long」 */
    private fun isDirectlyPlayable(url: String): Boolean {
        val u = url.trim()
        if (u.isBlank()) return false
        val lower = u.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        if (lower.startsWith("file:") || lower.startsWith("rtmp") ||
            lower.startsWith("rtp://") || lower.startsWith("udp://")
        ) {
            return true
        }
        // 本地缓存 m3u8：Windows 为 C:\...\lumen-m3u8\xxx.m3u8，不能只认 Unix 的 /
        if (lower.contains("lumen-m3u8") || lower.contains("cached_m3u8")) return true
        val isWinAbs = u.length >= 3 && u[0].isLetter() && u[1] == ':' &&
            (u[2] == '\\' || u[2] == '/')
        if ((u.startsWith("/") || isWinAbs) && (
                lower.endsWith(".m3u8") || lower.endsWith(".mp4") ||
                    lower.endsWith(".mkv") || lower.endsWith(".flv") || lower.endsWith(".ts")
                )
        ) {
            return true
        }
        return false
    }

    private fun updatePlayState(result: Result, ep: Episode) {
        _state.update { state ->
            val subs = result.subs.orEmpty()
            val selected = state.selectedSubUrl.ifBlank { subs.firstOrNull()?.url.orEmpty() }
            state.copy(
                currentUrl = result.url,
                currentPlayUrl = result.url.v(),
                playResult = result,
                useParse = result.isUseParse(),
                availableSubs = subs,
                selectedSubUrl = selected,
            )
        }

        PlaybackMediaState.title = _state.value.detail.vodName.orEmpty()
        PlaybackMediaState.url = result.url.v()
        PlaybackMediaState.headers = result.header ?: emptyMap()
        PlaybackMediaState.subtitleUrl = _state.value.selectedSubUrl
        PlaybackMediaState.playing = true
        controller.setSubtitleUrl(_state.value.selectedSubUrl)

        com.corner.ui.danmaku.DanmakuManager.onPlayStart(
            danmakuUrl = result.danmaku,
            vodName = _state.value.detail.vodName.orEmpty(),
            episodeName = ep.name.orEmpty(),
        )

        controller.doWithHistory { history ->
            // episodeUrl 可能已在 updateCurrentEpisodeState 里改过，用备注判断是否换集
            val switched = !history.vodRemarks.isNullOrBlank() && history.vodRemarks != ep.name
            history.copy(
                episodeUrl = ep.url,
                vodRemarks = ep.name,
                // 换集必须清进度，否则片头 seek 会跳到上一集位置导致秒停
                position = if (switched) 0L else (history.position ?: 0L),
            )
        }

        updateEpisodeActivation(ep)
    }

    /**
     * 根据播放器类型执行播放（使用策略模式）
     */
    private suspend fun executePlaybackByType(result: Result, ep: Episode) {
        if (cannotApply(result)) return

        val strategy = PlayerStrategyFactory.createStrategy(
            playerType = vmPlayerType.first(),
            controller = controller,
            lifecycleManager = lifecycleManager,
            viewModelScope = scope
        )

        log.info("executePlayback: ep={}, strategy={}", ep.name, strategy.getStrategyName())

        try {
            strategy.play(
                result = result,
                episode = ep,
                onPlayStarted = {
                    if (cannotApply(result)) return@play
                    _state.update { it.copy(isBuffering = false) }
                },
                onError = { error ->
                    if (cannotApply(result)) return@play
                    playbackError(error)
                }
            )
        } catch (e: CancellationException) {
            log.debug("播放策略已取消")
            throw e
        } catch (e: Exception) {
            if (!cannotApply(result)) {
                handleError("播放执行失败: ${e.message}", e)
                _state.update { it.copy(isBuffering = false) }
            }
        }
    }

    /**
     * 启动视频播放流程
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startPlay(dt: Vod, ep: Episode = Episode.create("", ""), recyclePy: Boolean = false) {
        if (dt.isEmpty()) {
            handleError("视频详情为空")
            return
        }

        val newJob = scope.launch {
            try {
                val effectiveEp = resolveEffectiveEpisode(ep, dt)
                if (effectiveEp == null) {
                    log.warn("未找到可播放剧集: {}", ep.name)
                    _state.update { it.copy(isBuffering = false, isLoading = false) }
                    return@launch
                }

                // 换集时取消自动换线任务，避免旧线路 M3U8 结果回写 currentEp
                flagSwitchJob?.cancel()
                suppressAutoLineSwitch = false

                val inFlightJob = playJob
                if (!recyclePy &&
                    pendingPlayRequest?.episodeUrl == effectiveEp.url &&
                    inFlightJob != null &&
                    inFlightJob.isActive &&
                    inFlightJob != coroutineContext[Job]
                ) {
                    log.info("相同剧集已在请求中，跳过重复拉地址: {}", effectiveEp.name)
                    return@launch
                }

                // 换集清空失败线路记录，允许重新尝试
                if (pendingPlayRequest?.episodeUrl != effectiveEp.url) {
                    failedFlagsForEpisode.clear()
                    autoFallbackCount = 0
                }

                inFlightJob?.takeIf { it != coroutineContext[Job] }?.cancel()
                playJob = coroutineContext[Job]

                val currentJob = coroutineContext[Job]
                val taskId = if (recyclePy) {
                    invalidatePlaybackFully(recyclePy = true, keepJob = currentJob)
                } else {
                    cancelPlayerContentRequest(keepJob = currentJob)
                }
                if (!isPlayerContentActive(taskId)) return@launch

                // 对齐 TV refresh：先 stopPlaybackForRefresh，再拉地址；engine.start 覆盖新媒体
                stopPlaybackForRefresh()
                log.info("startPlay: 开始拉地址 ep={}", effectiveEp.name)
                requestPlayerAndPlay(taskId, dt, effectiveEp)
            } catch (e: CancellationException) {
                log.debug("startPlay 已取消: ep={}", ep.name)
                _state.update { it.copy(isBuffering = false, isLoading = false) }
                throw e
            } catch (e: Exception) {
                log.error("startPlay 失败: ep={}", ep.name, e)
                _state.update { it.copy(isBuffering = false, isLoading = false) }
                handleError("播放启动失败: ${e.message}", e)
            }
        }
        playJob = newJob
    }

    private suspend fun resolveEffectiveEpisode(ep: Episode, dt: Vod): Episode? {
        if (ep.url.isNotBlank()) {
            return ep
        }

        dt.currentFlag.episodes
            .firstOrNull { it.name == ep.name && it.url.isNotBlank() }
            ?.let { return it }

        dt.subEpisode
            .firstOrNull { it.name == ep.name && it.url.isNotBlank() }
            ?.let { return it }

        if (ep.number > 0) {
            dt.currentFlag.episodes.firstOrNull { it.number == ep.number && it.url.isNotBlank() }
                ?.let { return it }
        }

        return try {
            withTimeout(PlayerStrategyConfig.DETAIL_HISTORY_QUERY_TIMEOUT_MS) {
                historyService.handlePlaybackHistory(dt, currentEpisodeIndex)
            }
        } catch (e: TimeoutCancellationException) {
            log.warn("查询播放历史超时，使用列表首集")
            null
        } ?: dt.subEpisode.firstOrNull { it.url.isNotBlank() }
    }

    /**
     * 返回下一集的链接，并更新详情页状态。
     * 1. 查找当前激活的剧集，计算下一集的索引。
     * 2. 如果当前分组已播完最后一集，自动切换到下一分组。
     * 3. 如果没有更多剧集，返回 null。
     */
    fun getNextEpisodeUrl(): String? {
        synchronized(nextEpisodeLock) {
            val currentDetail = _state.value.detail
            val currentEp = currentDetail.subEpisode.find { it.activated }

            // 使用EpisodeManager获取下一集URL
            val nextEpUrl = episodeManager.getNextEpisodeUrl(currentDetail, currentEp)
                ?: return null

            // 如果没有激活的剧集，从当前分组的第一个开始
            if (currentEp == null) {
                val firstEp = currentDetail.subEpisode.firstOrNull() ?: return null
                log.debug("没有激活的剧集，从当前分组的第一个开始更新ui激活剧集{}", firstEp.name)
                updateEpisodeActivation(firstEp)
                updateEpisodeUI(firstEp)
                return decryptUrl(firstEp.url)
            }

            val currentIndex = currentDetail.subEpisode.indexOf(currentEp)
            val nextIndex = currentIndex + 1

            // 处理分组切换
            if (nextIndex >= currentDetail.subEpisode.size) {
                val nextTabIndex = currentDetail.currentTabIndex + 1
                val totalEpisodes = currentDetail.currentFlag.episodes.size
                val totalPages = (totalEpisodes + Constants.EP_SIZE - 1) / Constants.EP_SIZE

                if (nextTabIndex >= totalPages) {
                    return null
                }

                val start = nextTabIndex * Constants.EP_SIZE
                val end = minOf(start + Constants.EP_SIZE, totalEpisodes)
                val newSubEpisodes = currentDetail.currentFlag.episodes.subList(start, end)
                val newFirstEp = newSubEpisodes.firstOrNull() ?: return null

                updateEpisodeActivation(newFirstEp, nextTabIndex, newSubEpisodes)
                updateEpisodeUI(newFirstEp)
                return decryptUrl(newFirstEp.url)
            }

            // 正常切换到下一集
            val nextEp = currentDetail.subEpisode[nextIndex]
            log.debug("切换下一集更新ui激活剧集{}", nextEp.name)
            updateEpisodeActivation(nextEp)
            scope.launch {
                historyService.updateCurrentEpisode(nextEp, currentDetail)
            }
            updateEpisodeUI(nextEp)
            return decryptUrl(nextEp.url)
        }
    }

    /**更新选集ui*/
    private fun updateEpisodeUI(episode: Episode) {
        _state.update { state ->
            val updatedSubEpisodes = state.detail.subEpisode.map { ep ->
                ep.copy(activated = ep == episode)
            }.toMutableList()

            state.copy(
                detail = state.detail.copy(subEpisode = updatedSubEpisodes),
                currentEp = episode
            )
        }
    }


    /**
     * 更新剧集中激活状态和当前选中的剧集信息。
     */
    private fun updateEpisodeActivation(
        activeEp: Episode,
        newTabIndex: Int? = null,
        newSubEpisodes: List<Episode>? = null
    ) {
        currentSelectedEpNumber = activeEp.number

        _state.update { state ->
            clearAllEpisodesActivation(state)
            activateTargetEpisode(activeEp)

            val updatedSubEpisodes = buildUpdatedSubEpisodes(state, activeEp, newSubEpisodes)

            state.copy(
                detail = state.detail.copy(
                    currentTabIndex = newTabIndex ?: state.detail.currentTabIndex,
                    subEpisode = updatedSubEpisodes
                ),
                currentEp = activeEp,
            )
        }
    }

    private fun clearAllEpisodesActivation(state: DetailScreenState) {
        state.detail.currentFlag.episodes.forEach { episode ->
            episode.activated = false
        }
    }

    private fun activateTargetEpisode(activeEp: Episode) {
        activeEp.activated = true
    }

    private fun buildUpdatedSubEpisodes(
        state: DetailScreenState,
        activeEp: Episode,
        newSubEpisodes: List<Episode>?
    ): MutableList<Episode> {
        return if (newSubEpisodes != null) {
            newSubEpisodes.map { ep -> ep.copy(activated = ep.url == activeEp.url) }.toMutableList()
        } else {
            state.detail.subEpisode.map { ep -> ep.copy(activated = ep.url == activeEp.url) }.toMutableList()
        }
    }


    /**
     * 解密URL获取播放链接
     */
    private fun decryptUrl(url: String): String? {
        return SiteViewModel.playerContent(
            _state.value.detail.site?.key ?: "",
            _state.value.detail.currentFlag.flag ?: "",
            url
        )?.url?.v()
    }

    /**
     * 尝试播放下一集视频
     */
    override fun nextEP() {
        log.info("加载下一集")
        val detail = _state.value.detail
        val currentEp = detail.subEpisode.find { it.activated }

        if (currentEp == null) {
            log.debug("当前没有激活的剧集")
            SnackBar.postMsg("当前没有激活的剧集", type = SnackBar.MessageType.WARNING)
            return
        }

        controller.doWithHistory { it.copy(position = 0) }
        currentSelectedEpNumber = currentEp.number

        // 使用EpisodeManager处理下一集逻辑
        episodeManager.nextEpisode(detail, currentEp) { updatedDetail, nextEp ->
            currentSelectedEpNumber = nextEp.number
            updateCurrentEpisodeState(updatedDetail, nextEp)
            refreshPlayback(updatedDetail, nextEp)
        }

        // 检查是否还有更多剧集
        val currentIndex = detail.subEpisode.indexOf(currentEp)
        val nextIndex = currentIndex + 1
        val totalEpisodes = detail.currentFlag.episodes.size

        if (totalEpisodes <= nextIndex) {
            SnackBar.postMsg("没有更多剧集", type = SnackBar.MessageType.INFO)
            return
        }
    }

    fun nextFlag() {
        scope.launch {
            stopPlaybackForRefresh()
            _state.update { it.copy(isBuffering = false, isLoading = false) }
            fallbackToNextLineOrSource()
        }
    }

    private fun handleEmptyFlagWithQuickSearch(detail: Vod) {
        log.info("当前线路为空，需要执行快速搜索寻找可用站源")
        detail.vodId = ""

        quickSearch { results ->
            scope.launch {
                handleQuickSearchResults(results, detail)
            }
        }
    }

    private suspend fun handleQuickSearchResults(results: List<Vod>, detail: Vod) {
        if (results.isNotEmpty() && !results.all { it.vodId.isBlank() }) {
            log.info("快速搜索完成，找到 {} 个结果，准备加载详情", results.size)
            SnackBar.postMsg(
                "找到 ${results.size} 个可用站源，正在加载...",
                type = SnackBar.MessageType.INFO
            )
            loadDetail(results.first())
        } else {
            log.warn("快速搜索完成但未找到有效结果，取消自动换源")
            _state.update {
                it.copy(
                    detail = detail,
                    isLoading = false,
                    isBuffering = false
                )
            }
            SnackBar.postMsg("未找到可用站源，自动换源已取消", type = SnackBar.MessageType.WARNING)
        }
    }

    private suspend fun playAfterFlagSwitch(taskId: Int, detail: Vod) {
        val history = controller.history.value
        val findEp = if (history != null) {
            // 对齐 TV seamless：用新线路 + vodRemarks 模糊匹配（2 ↔ 第2集）
            detail.findAndSetEpByName(
                history.copy(vodFlag = detail.currentFlag.flag),
                currentEpisodeIndex,
            )
        } else {
            log.warn("自动切换线路时历史记录为空，使用第一个剧集")
            null
        }

        val episodeToPlay = findEp ?: detail.subEpisode.firstOrNull()
        if (episodeToPlay != null) {
            requestPlayerAndPlay(taskId, detail, episodeToPlay)
        } else {
            log.error("切换线路后无可用剧集")
            SnackBar.postMsg("切换线路失败：无可用剧集", type = SnackBar.MessageType.ERROR)
        }
    }


    /**
     * 切换剧集选择对话框的显示状态
     */
    fun clickShowEp() {
        _state.update {
            it.copy(
                showEpChooserDialog = !_state.value.showEpChooserDialog,
                isLoading = false,
                isBuffering = false
            )
        }
    }


    /**
     * 切换解析器并重新解析播放（与 TV selectParse 一致）
     */
    fun selectParse(parse: Parse) {
        ApiConfig.setParse(parse)
        val detail = _state.value.detail
        val ep = detail.subEpisode.find { it.activated } ?: _state.value.currentEp ?: return

        scope.launch {
            saveCurrentHistory()
            val taskId = cancelPlayerContentRequest()
            _state.update { it.copy(isBuffering = true) }
            pendingPlayRequest = VodPlayRequest(
                siteKey = detail.site?.key ?: "",
                flag = detail.currentFlag.flag ?: "",
                episodeUrl = ep.url,
                episodeName = ep.name,
            )
            stopPlaybackForRefresh()

            var result = withContext(Dispatchers.IO) {
                fetchPlayResult(detail, ep)
            }
            if (!isPlayerContentActive(taskId)) return@launch

            if (result == null || result.playResultIsEmpty()) {
                handleEmptyPlayResult()
                return@launch
            }

            if (result.needParse() || result.isUseParse()) {
                result = ParseHelper.parseVod(result, useParse = true, forcedParse = parse)
                if (result == null || result.playResultIsEmpty()) {
                    SnackBar.postMsg("解析失败: ${parse.name}", type = SnackBar.MessageType.ERROR)
                    _state.update { it.copy(isBuffering = false) }
                    return@launch
                }
            }

            if (!isPlayerContentActive(taskId) || cannotApply(result)) return@launch
            log.info("applyPlayerResult: ep={}", ep.name)
            updatePlayState(result, ep)
            startPlayback(result, ep)
        }
    }

    /**
     * 切换视频播放线路
     */
    fun chooseFlag(detail: Vod, selectedFlag: Flag) {
        val selectedName = selectedFlag.flag.orEmpty()
        if (selectedName.isNotBlank() && selectedName == _currentFlagName.value) {
            log.debug("线路已选中，跳过: {}", selectedFlag.flag)
            return
        }
        // 兼容旧状态：仅 activated 不可靠（show 重复时会误标多条）
        if (selectedFlag.activated && selectedName == detail.currentFlag.flag) {
            log.debug("线路已选中(activated)，跳过: {}", selectedFlag.flag)
            return
        }
        flagSwitchJob?.cancel()
        flagSwitchJob = scope.launch {
            val oldNumber = currentSelectedEpNumber
            val newEpisodes = selectedFlag.episodes
            val newEp = findEpisodeByNumber(newEpisodes, oldNumber)

            currentSelectedEpNumber = newEp?.number ?: 1
            log.debug("chooseFlag -- 切换线路，新的线路标识: {}, 剧集编号{}", selectedFlag.flag, newEp?.number)

            _currentFlagName.value = selectedFlag.flag.toString()
            _state.update { it.copy(isLoading = true, isBuffering = false) }

            try {
                executeFlagSwitch(detail, selectedFlag, newEp)
            } catch (e: CancellationException) {
                _state.update { it.copy(isLoading = false, isBuffering = false) }
                throw e
            } catch (e: TimeoutCancellationException) {
                handleFlagSwitchTimeout(e)
            } catch (e: Exception) {
                handleFlagSwitchError(e)
            }
        }
    }

    private suspend fun executeFlagSwitch(detail: Vod, selectedFlag: Flag, newEp: Episode?) {
        val taskId = cancelPlayerContentRequest()
        suppressAutoLineSwitch = true

        try {
            detail.updateFlagActivationStates(selectedFlag)

            val updatedDetail = detail.buildUpdatedDetail(selectedFlag, newEp)

            controller.doWithHistory { it.copy(vodFlag = detail.currentFlag.flag) }

            saveCurrentHistory()
            stopPlaybackForRefresh()

            if (!isPlayerContentActive(taskId)) {
                suppressAutoLineSwitch = false
                return
            }

            _state.update { model ->
                model.copy(
                    detail = updatedDetail,
                    isLoading = false,
                    isBuffering = false
                )
            }

            playJob = scope.launch {
                try {
                    playEpisodeAfterFlagSwitch(taskId, updatedDetail, newEp)
                } finally {
                    suppressAutoLineSwitch = false
                }
            }
        } catch (e: Exception) {
            suppressAutoLineSwitch = false
            throw e
        }
    }

    private fun findEpisodeByNumber(episodes: List<Episode>, number: Int): Episode? {
        return episodes.find { it.number == number } ?: episodes.firstOrNull()
    }

    private fun handleFlagSwitchTimeout(e: TimeoutCancellationException) {
        log.error("切换线路时停止播放超时", e)
        SnackBar.postMsg("切换线路超时，请稍后重试", type = SnackBar.MessageType.ERROR)
        _state.update { it.copy(isLoading = false, isBuffering = false) }
    }

    private suspend fun playEpisodeAfterFlagSwitch(taskId: Int, updatedDetail: Vod, newEp: Episode?) {
        val history = controller.history.value

        if (history != null) {
            val findEp = updatedDetail.findAndSetEpByName(
                history.copy(vodFlag = updatedDetail.currentFlag.flag),
                currentSelectedEpNumber,
            )
            log.debug("切换线路，新的剧集数据: {}", findEp)

            if (findEp != null) {
                requestPlayerAndPlay(taskId, updatedDetail, findEp)
                return
            }
        }

        if (newEp != null) {
            requestPlayerAndPlay(taskId, updatedDetail, newEp)
        }
    }

    private fun handleFlagSwitchError(e: Exception) {
        log.error("切换线路失败", e)
        SnackBar.postMsg("切换线路失败: ${e.message}", type = SnackBar.MessageType.ERROR)
        _state.update { it.copy(isLoading = false, isBuffering = false) }
    }


    /**
     * 清晰度选择
     */
    fun chooseLevel(url: Url?, playUrl: String?) {
        _state.update { it.copy(isLoading = true, isBuffering = false) }

        scope.launch {
            try {
                log.debug("切换清晰度,当前播放链接: {}", url)

                if (handleDownloadLinkCheck(url)) {
                    return@launch
                }

                if (playUrl != null) {
                    executePlaybackByPlayerType(playUrl)
                }

                updatePlayUrlState(url, playUrl)
            } catch (e: Exception) {
                log.error("切换清晰度时发生错误", e)
                SnackBar.postMsg("切换清晰度失败: ${e.message}", type = SnackBar.MessageType.ERROR)
            }
        }.invokeOnCompletion {
            _state.update { it.copy(isLoading = false, isBuffering = false) }
        }
    }

    private fun handleDownloadLinkCheck(url: Url?): Boolean {
        val isDownloadLink = Utils.isDownloadLink(url.toString())

        if (isDownloadLink) {
            log.warn("切换清晰度失败！当前播放链接是下载链接！")
            SnackBar.postMsg("切换清晰度失败！当前播放链接是下载链接！", type = SnackBar.MessageType.WARNING)
            return true
        }

        return false
    }

    /**
     * 根据播放器类型执行播放（用于清晰度切换）
     */
    private fun executePlaybackByPlayerType(playUrl: String) {
        val currentEp = _state.value.currentEp
        val episodeName = currentEp?.name ?: "未知剧集"

        // 创建临时Episode对象
        val tempEpisode = Episode.create(episodeName, playUrl)
        tempEpisode.number = currentEp?.number ?: -1

        // 创建策略并执行播放
        val strategy = PlayerStrategyFactory.createStrategy(
            playerType = vmPlayerType.first(),
            controller = controller,
            lifecycleManager = lifecycleManager,
            viewModelScope = scope
        )

        log.debug("使用播放器策略: {}", strategy.getStrategyName())

        // 创建临时Result对象
        val tempResult = Result().apply {
            url = com.corner.catvodcore.bean.Url().apply { add(playUrl) }
        }

        scope.launch {
            try {
                strategy.play(
                    result = tempResult,
                    episode = tempEpisode,
                    onPlayStarted = {
                        _state.update { it.copy(isBuffering = false) }
                    },
                    onError = { error ->
                        handleError(error)
                        _state.update { it.copy(isBuffering = false) }
                    }
                )
            } catch (e: Exception) {
                handleError("播放执行失败: ${e.message}", e)
                _state.update { it.copy(isBuffering = false) }
            }
        }
    }

    private fun updatePlayUrlState(url: Url?, playUrl: String?) {
        _state.update {
            it.copy(
                currentPlayUrl = playUrl ?: "",
                currentUrl = url,
            )
        }
    }


    /**
     * 隐藏剧集选择对话框
     */
    fun showEpChooser() {
        _state.update {
            it.copy(showEpChooserDialog = !it.showEpChooserDialog)
        }
    }

    /**
     * 根据传入的索引切换到对应的剧集分组
     */
    fun chooseEpBatch(index: Int) {
        val detail = state.value.detail
        val currentGlobalActiveEpisodeUrl = _state.value.currentEp?.url
        log.debug("批量选择剧集，当前全局激活剧集url: {}", currentGlobalActiveEpisodeUrl)

        // 使用EpisodeManager处理批量选择逻辑
        val updatedDetail = episodeManager.chooseEpisodeBatch(detail, index, currentGlobalActiveEpisodeUrl)

        _state.update { it.copy(detail = updatedDetail, isLoading = false, isBuffering = false) }
    }

    /**
     * 选择指定剧集（对齐 TV selectEpisode → refresh，无防抖）
     */
    fun chooseEp(episode: Episode, openUri: (String) -> Unit) {
        val currentEp = _state.value.currentEp
        if (currentEp?.url == episode.url &&
            controller.state.value.state == PlayState.PLAY
        ) {
            log.debug("已是当前播放剧集，跳过: {}", episode.name)
            return
        }

        currentSelectedEpNumber = episode.number
        log.info("切换剧集: {}", episode.name)
        _state.update { it.copy(isBuffering = true, isLoading = false) }

        scope.launch {
            val currentDetail = _state.value.detail
            episodeManager.chooseEpisode(
                episode = episode,
                detail = currentDetail,
                playerTypeId = vmPlayerType.first(),
                lifecycleManager = lifecycleManager,
                onOpenUri = openUri,
                onPlayEpisode = { updatedDetail, selectedEp ->
                    updateCurrentEpisodeState(updatedDetail, selectedEp)
                    refreshPlayback(updatedDetail, selectedEp)
                }
            )
        }
    }

    private fun updateCurrentEpisodeState(updatedDetail: Vod, episode: Episode) {
        _state.update { model ->
            val switched = model.currentEp?.url != episode.url || model.currentEp?.name != episode.name
            if (switched) {
                controller.doWithHistory { it.copy(position = 0L) }
            }
            controller.doWithHistory {
                it.copy(episodeUrl = episode.url, vodRemarks = episode.name)
            }
            model.copy(currentEp = episode, detail = updatedDetail)
        }
    }

    /**
     * 设置当前播放的 URL（用于DLNA）
     */
    fun setPlayUrl(string: String) {
        _state.update { it.copy(isLoading = true) }
        log.debug("<DLNA> 开始播放")

        // 如果已经在投屏中，先结束当前投屏
        if (_state.value.isDLNA) {
            log.debug("<DLNA> 检测到正在投屏，先结束当前投屏")
            endDLNASession()
        }

        // 创建临时Episode和Result对象用于策略模式
        val tempEpisode = Episode.create("LumenTV-DLNA", string)
        val tempResult = Result().apply {
            url = com.corner.catvodcore.bean.Url().apply { add(string) }
        }

        // 对于外部播放器和Web播放器，直接使用对应的启动方式
        when (vmPlayerType.first()) {
            PlayerType.Outie.id, PlayerType.Web.id -> {
                val strategy = PlayerStrategyFactory.createStrategy(
                    playerType = vmPlayerType.first(),
                )
                scope.launch {
                    strategy.play(
                        result = tempResult,
                        episode = tempEpisode,
                        onPlayStarted = {
                            _state.update {
                                it.copy(currentPlayUrl = string, isDLNA = true, isLoading = false)
                            }
                        },
                        onError = { error ->
                            handleError(error)
                            _state.update {
                                it.copy(isLoading = false)
                            }
                        }
                    )
                }
                return
            }
        }

        // 内部播放器：使用InniePlayerStrategy
        val strategy = PlayerStrategyFactory.createStrategy(
            playerType = PlayerType.Innie.id,
            controller = controller,
            lifecycleManager = lifecycleManager,
            viewModelScope = scope
        )

        log.debug("<DLNA> 使用播放器策略: {}", strategy.getStrategyName())

        scope.launch {
            try {
                strategy.play(
                    result = tempResult,
                    episode = tempEpisode,
                    onPlayStarted = {
                        _state.update {
                            it.copy(currentPlayUrl = string, isDLNA = true, isLoading = false)
                        }
                    },
                    onError = { error ->
                        handleError(error)
                        _state.update { it.copy(isLoading = false, isDLNA = false) }
                    }
                )
            } catch (e: Exception) {
                handleError("DLNA播放执行失败: ${e.message}", e)
                _state.update { it.copy(isLoading = false, isDLNA = false) }
            }
        }
    }

    /**
     * 结束DLNA投屏会话
     * 仅对内部播放器需要释放资源
     */
    fun endDLNASession() {
        if (vmPlayerType.first() == PlayerType.Innie.id) {
            log.debug("<DLNA> 结束投屏，释放内部播放器资源")
            scope.launch {
                try {
                    // 停止播放并释放资源
                    if (lifecycleManager.canTransitionTo(Ended)) {
                        lifecycleManager.ended()
                    }
                    // 重置投屏状态
                    _state.update { it.copy(isDLNA = false, currentPlayUrl = "") }
                } catch (e: Exception) {
                    log.error("<DLNA> 结束投屏失败", e)
                }
            }
        } else {
            // 外部播放器和Web播放器只需重置状态
            _state.update { it.copy(isDLNA = false, currentPlayUrl = "") }
        }
    }
}