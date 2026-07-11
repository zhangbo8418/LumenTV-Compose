package com.corner.ui.player.vlcj

import com.corner.util.settings.PlayerStateCache
import com.corner.util.settings.SettingStore
import com.corner.catvodcore.bean.Vod
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.database.entity.History
import com.corner.server.PlaybackMediaState
import com.corner.service.player.PlayerOperationUtils
import com.corner.ui.nav.vm.DetailViewModel
import com.corner.ui.player.MediaInfo
import com.corner.ui.player.PlayState
import com.corner.ui.player.PlayerController
import com.corner.ui.player.PlayerLifecycleManager
import com.corner.ui.player.PlayerState
import com.corner.ui.scene.SnackBar
import com.corner.util.core.catch
import com.corner.util.net.Traffic
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.media.MediaSlaveType
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.DurationUnit

private val log = LoggerFactory.getLogger("VlcjController")

class VlcjController(val vm: DetailViewModel) : PlayerController {
    var player: EmbeddedMediaPlayer? = null
    private var lifecycleManager: PlayerLifecycleManager? = null
    fun setLifecycleManager(manager: PlayerLifecycleManager) {
        this.lifecycleManager = manager
    }

    override var playerLoading = false
    override var playerPlaying = false
    override var showTip = MutableStateFlow(false)
    override var tip = MutableStateFlow("")
    override var history: MutableStateFlow<History?> = MutableStateFlow(null)
    private val deferredEffects = mutableListOf<(MediaPlayer) -> Unit>()
    private var isAccelerating = false
    private var originSpeed = 1.0F
    private var currentSpeed = 1.0F
    var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    override var endingHandled = false          // 视频结束处理，避免重复操作
    private var cleanupJob: Job? = null         // 添加清理任务跟踪变量
    private var trafficJob: Job? = null         // 缓冲网速轮询（对齐 TV Traffic）
    private var isCleaned = false               // 添加清理状态标志
    private var activeSubtitleUrl: String = ""
    private val mediaMutex = Mutex()
    private val vlcStateLock = Any()
    @Volatile
    private var vlcThread = newVlcExecutor()
    private val refreshSupervisor = SupervisorJob()
    private var refreshStopJob: Job? = null
    private val loadGeneration = AtomicInteger(0)

    private fun newVlcExecutor() = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vlc-play").apply { isDaemon = true }
    }

    /**
     * 对齐 TV player().clear()：清空当前播放状态，不阻塞 VLC。
     */
    fun clearPlaybackState() {
        playerLoading = false
        playerPlaying = false
        playerRealStartTime = 0
    }

    /**
     * 换集/换线：递增 generation，并从其他线程 stop + 重建队列，
     * 避免 media().play 卡死占住 vlc-play 导致后续换集永不「设置媒体」。
     */
    fun invalidatePendingLoads(): Int {
        val gen = loadGeneration.incrementAndGet()
        expectedMrl = ""
        clearPlaybackState()
        // 换集 stop 会产生 finished/stopped，先标 loading 避免误触发秒停换线
        playerLoading = true
        abortStuckVlcLoad("invalidate gen=$gen")
        return gen
    }

    fun currentLoadGeneration(): Int = loadGeneration.get()

    private fun isLoadGenerationStale(expected: Int): Boolean =
        expected != loadGeneration.get()

    private fun shouldIgnoreEndEvent(mrl: String, event: String): Boolean {
        if (playerLoading) {
            log.debug("忽略换媒体期间的 {}: playerLoading", event)
            return true
        }
        val expected = expectedMrl
        if (expected.isNotBlank() && mrl.isNotBlank() && !mrlEquals(expected, mrl)) {
            log.info("忽略过期 {}: mrl 不匹配", event)
            return true
        }
        return false
    }

    private fun mrlEquals(a: String, b: String): Boolean {
        if (a == b) return true
        fun norm(s: String) = s.removePrefix("file://").removePrefix("file:")
        return norm(a) == norm(b)
    }

    private suspend fun awaitRefreshStop(timeoutMs: Long = 3_000L) {
        val job = refreshStopJob ?: return
        withTimeoutOrNull(timeoutMs) { job.join() }
    }

    /** 换集：清状态并异步打断卡住的 play（不在此线程阻塞） */
    fun stopForRefresh() {
        clearPlaybackState()
        playerLoading = true
        abortStuckVlcLoad("stopForRefresh")
    }

    /** @deprecated 与 stopForRefresh 相同 */
    fun softStopForRefresh() {
        stopForRefresh()
    }

    /**
     * 从独立线程 stop，打断可能卡住的 native play。
     */
    private val abortEpoch = AtomicInteger(0)
    @Volatile
    private var lastAbortAtMs: Long = 0L

    private fun abortStuckVlcLoad(reason: String) {
        abortEpoch.incrementAndGet()
        lastAbortAtMs = System.currentTimeMillis()
        currentLoadFuture = null
        Thread({
            try {
                log.info("中断 VLC 加载: {}", reason)
                runCatching { player?.controls()?.stop() }
            } catch (e: Exception) {
                log.warn("中断 VLC 加载失败: {}", e.message)
            }
        }, "vlc-abort").apply { isDaemon = true }.start()
    }

    /** 保留：异常场景下重建（当前 loadURL 已不依赖此队列起播） */
    private fun resetVlcThread(reason: String) {
        synchronized(vlcStateLock) {
            log.warn("重建 VLC 线程: {}", reason)
            val old = vlcThread
            vlcThread = newVlcExecutor()
            old.shutdownNow()
        }
    }

    @Volatile
    private var currentLoadFuture: java.util.concurrent.Future<*>? = null
    /** 当前期望播放的 MRL，用于丢弃换媒体时旧流的 finished/stopped */
    @Volatile
    private var expectedMrl: String = ""
    @Volatile
    private var loadStartedAtMs: Long = 0L

    // 视频播放状态
    private var playerStartTime: Long = 0
    private var playerRealStartTime: Long = 0   // 记录实际开始播放的时间
    private var playerEndTime: Long = 0
    private val decodeFailureTureShould = 5000L // 5秒阈值

    private val vlcjArgs = mutableListOf(
        "-q",                                   // 最低级别日志
        "--no-video-on-top",                    // 禁用窗口置顶
        "--avcodec-hw=any",                     // 系统自动选择解码器
        "--network-caching=2500",               // 起播与稳定性折中
        "--live-caching=300",                   // 减少直播缓存
        "--preparse-timeout=500",               // 与预解析超时单位ms
    )

    override fun isPlayerInstanceReady(): Boolean {// 检查 player 实例是否已创建
        return player != null
    }

    override fun resetOpeningEnding() {//重置开尾和开头
        _state.update { it.copy(opening = -1L, ending = -1L) }
        history.update { it?.copy(opening = -1L, ending = -1L) }
    }

    internal lateinit var factory: MediaPlayerFactory

    override fun doWithMediaPlayer(block: (MediaPlayer) -> Unit) {
        player?.let {
            block(it)
        } ?: run {
            deferredEffects.add(block)
        }
    }

    override fun onMediaPlayerReady(mediaPlayer: EmbeddedMediaPlayer) {
        this.player = mediaPlayer
        _state.update { it.copy(duration = player?.status()?.length() ?: 0L) }
        deferredEffects.forEach { block ->
            block(mediaPlayer)
        }
        deferredEffects.clear()
    }

    private val stateListener = object : MediaPlayerEventAdapter() {

        override fun mediaPlayerReady(mediaPlayer: MediaPlayer) {
            log.info("播放器初始化完成")
            _state.update { it.copy(duration = mediaPlayer.status().length(), state = PlayState.PLAY) }
            syncPlaybackMediaState(mediaPlayer)
            val mediaInfo = _state.value.mediaInfo
            log.info("当前媒体信息: $mediaInfo")
        }


        override fun videoOutput(mediaPlayer: MediaPlayer?, newCount: Int) {
            val trackInfo = mediaPlayer?.media()?.info()?.videoTracks()?.first()
            if (trackInfo != null) {
                _state.update {
                    it.copy(
                        mediaInfo = MediaInfo(
                            url = mediaPlayer.media()?.info()?.mrl() ?: "",
                            height = trackInfo.height(),
                            width = trackInfo.width(),
                            videoCodec = trackInfo.codecName(),
                            bitRate = trackInfo.bitRate(),
                            duration = mediaPlayer.status().length(),
                            codecDescription = trackInfo.codecDescription()
                        )
                    )
                }
            }
        }

        override fun buffering(mediaPlayer: MediaPlayer?, newCache: Float) {
            if (newCache != 100F) {
                startTrafficMonitor()
                _state.update { it.copy(state = PlayState.BUFFERING, bufferProgression = newCache) }
            } else {
                stopTrafficMonitor()
                _state.update { it.copy(state = PlayState.PLAY, bufferProgression = newCache) }
            }
        }

        override fun corked(mediaPlayer: MediaPlayer?, corked: Boolean) {
            log.debug("corked： $corked")
        }

        override fun opening(mediaPlayer: MediaPlayer?) {
            playerLoading = true
            startTrafficMonitor()
            _state.update { it.copy(state = PlayState.BUFFERING) }
            log.debug("opening - 媒体开始打开")
        }


        override fun playing(mediaPlayer: MediaPlayer) {
            playerLoading = false
            playerPlaying = true
            loadStartedAtMs = 0L
            // 换集后强制恢复渲染，避免黑屏
            vm.controller.resumeVideoRendering()
            if (playerRealStartTime == 0L) {
                playerRealStartTime = System.currentTimeMillis()
                log.info("播放真正开始，设置实际开始时间: $playerRealStartTime")
            }
            _state.update { it.copy(state = PlayState.PLAY) }
            syncPlaybackMediaState(mediaPlayer, playing = true)
            log.debug("playing - 媒体开始播放")
            // 起播后稍后再停网速，避免一闪而过看不到
            scope.launch {
                delay(2000)
                if (_state.value.state != PlayState.BUFFERING) stopTrafficMonitor()
            }

            scope.launch {
                // HLS 过早 seek 易触发 demux 结束；等缓冲后再跳
                delay(if ((history.value?.position ?: 0L) > 0L || (_state.value.opening ?: -1L) > 0L) 800 else 100)
                handleOpeningSeek()
            }
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            stopTrafficMonitor()
            _state.update { it.copy(state = PlayState.PAUSE) }
            syncPlaybackMediaState(mediaPlayer, playing = false)
        }

        override fun stopped(mediaPlayer: MediaPlayer) {
            val mrl = mediaPlayer.media()?.info()?.mrl().orEmpty()
            if (shouldIgnoreEndEvent(mrl, "stopped")) return
            log.info("stopped")
            playerPlaying = false
            playerLoading = false
            playerRealStartTime = 0
            stopTrafficMonitor()
            _state.update { it.copy(state = PlayState.PAUSE) }
            syncPlaybackMediaState(mediaPlayer, playing = false)
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            val mrl = mediaPlayer.media()?.info()?.mrl().orEmpty()
            if (shouldIgnoreEndEvent(mrl, "finished")) return
            log.info("finished")
            playerPlaying = false
            playerEndTime = System.currentTimeMillis()
            stopTrafficMonitor()

            val hadRealStart = playerRealStartTime > 0
            val playDuration = if (hadRealStart) {
                playerEndTime - playerRealStartTime
            } else {
                playerEndTime - playerStartTime
            }

            log.info("播放时长: ${playDuration}ms")

            playerRealStartTime = 0

            if (!hadRealStart) {
                log.debug("未真正开始播放，跳过自动换线")
                return
            }
            if (vm.suppressAutoLineSwitch) {
                log.debug("正在切换线路，跳过自动换线")
                return
            }
            // 换集/换线 abort 后的 finished 常会秒停，勿当播放失败
            if (System.currentTimeMillis() - lastAbortAtMs < 2_000L) {
                log.debug("刚中断加载后的 finished，跳过自动换线")
                return
            }

            // 换集后秒停：按播放失败处理，走 TV 同款 fallback
            if (playDuration < decodeFailureTureShould) {
                log.warn("播放时长过短 ({}ms)，触发 playbackError", playDuration)
                _state.update { it.copy(state = PlayState.PAUSE) }
                scope.launch { vm.playbackError("播放失败，时长过短") }
                return
            }

            val mediaLength = runCatching { mediaPlayer.status().length() }.getOrDefault(-1L)
                .takeIf { it > 0 }
                ?: PlaybackMediaState.duration.takeIf { it > 0 }
                ?: -1L
            // 本地/远程 m3u8：起播后很快 finished（length 有时尚未就绪）一律当失败
            if (isHlsMrl(mrl) && playDuration < 15_000L) {
                log.warn(
                    "HLS 起播后很快结束 (播放{}ms / 总长{}ms)，触发 playbackError",
                    playDuration,
                    mediaLength,
                )
                _state.update { it.copy(state = PlayState.PAUSE) }
                scope.launch { vm.playbackError("播放中断") }
                return
            }
            if (isHlsMrl(mrl) && mediaLength > 60_000L && playDuration < mediaLength - 15_000L && playDuration < 60_000L) {
                log.warn(
                    "HLS 未播完即结束 (播放{}ms / 总长{}ms)，触发 playbackError",
                    playDuration,
                    mediaLength,
                )
                _state.update { it.copy(state = PlayState.PAUSE) }
                scope.launch { vm.playbackError("播放中断") }
                return
            }
            if (isHlsMrl(mrl) || isStreamingUrl(mrl)) {
                if (mediaLength > 0 && playDuration < mediaLength - 10_000) {
                    log.warn(
                        "流媒体未播完即 finished (播放{}ms / 总长{}ms)",
                        playDuration,
                        mediaLength,
                    )
                } else {
                    log.debug("流媒体结束 finished (播放{}ms)", playDuration)
                }
                _state.update { it.copy(state = PlayState.PAUSE) }
                return
            }

            _state.update { it.copy(state = PlayState.PAUSE) }
            scope.launch {
                delay(500)
                log.debug("finished:运行协程任务")
                try {
                    if (vm.state.value.isDLNA) {
                        log.info("DLNA投屏模式，跳过自动换集")
                        SnackBar.postMsg("投屏播放完成", type = SnackBar.MessageType.INFO)
                    } else if (!vm.isLastEpisode) {
                        log.info("切换下一集")
                        vm.nextEP()
                    } else {
                        log.info("已经是最后一集了")
                        SnackBar.postMsg("已经是最后一集了", type = SnackBar.MessageType.INFO)
                    }
                } catch (e: Exception) {
                    log.error("finished error", e)
                }
            }
        }

        override fun muted(mediaPlayer: MediaPlayer, muted: Boolean) {
            _state.update { it.copy(isMuted = muted) }
        }

        override fun volumeChanged(mediaPlayer: MediaPlayer, volume: Float) {
            if (volume > 0f) {
                SettingStore.doWithCache {
                    var state = it["playerState"]
                    if (state == null) {
                        state = PlayerStateCache()
                        it["playerState"] = state
                    }
                    (state as PlayerStateCache).add("volume", volume.toString())
                }
            }
            _state.update { it.copy(volume = volume) }
        }


        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
            scope.launch {
                history.value?.let { hist ->
                    val ending = hist.ending
                    if (ending != null && ending != -1L && ending <= newTime && !endingHandled) {
                        stop()
                        endingHandled = true
                        vm.nextEP()
                    }

                    // 每 25 秒同步一次进度，但要确保播放器已真正开始播放且时间大于0
                    if (newTime > 0 && (newTime / 1000 % 25) == 0L) {
                        history.emit(hist.copy(position = newTime))
                    }
                }
            }
            _state.update { it.copy(timestamp = newTime) }
            player?.let { syncPlaybackMediaState(it) }
        }


        override fun error(mediaPlayer: MediaPlayer?) {
            // 仅做轻量状态更新；禁止在此线程同步 stop / 换线（易与 libvlc 死锁导致整窗卡死）
            val mrl = runCatching { mediaPlayer?.media()?.info()?.mrl() }.getOrNull().orEmpty()
            log.error("播放错误: {}", mrl)
            playerPlaying = false
            playerLoading = false
            stopTrafficMonitor()
            _state.update { it.copy(state = PlayState.ERROR, msg = "播放错误") }
            scope.launch {
                runCatching { history.value?.let { vm.updateHistory(it) } }
                vm.playbackError("播放错误")
            }
        }
    }

    private val _state = MutableStateFlow(PlayerState())

    override val state: StateFlow<PlayerState>
        get() = _state.asStateFlow()

    override fun init() {
        isCleaned = false

        try {
            factory = MediaPlayerFactory(vlcjArgs)
            player = factory.mediaPlayers()?.newEmbeddedMediaPlayer()?.apply {
                events().addMediaPlayerEventListener(stateListener)
                video().setScale(0.0f)
                // 把 VLC 当前静音状态同步到 PlayerState
                val muted = audio()?.isMute ?: false
                _state.update { it.copy(isMuted = muted) }
            }
        } catch (e: Exception) {
            // 处理异常
            // dispose()
            log.error("vlcj初始化失败", e)
            SnackBar.postMsg("vlcj初始化失败!", type = SnackBar.MessageType.ERROR)
        }
    }

    override suspend fun cleanupAsync() {
        withContext(Dispatchers.IO) {
            if (isCleaned) return@withContext
            isCleaned = true
            stopTrafficMonitor()
            invalidatePendingLoads()
            refreshSupervisor.cancelChildren(CancellationException("cleanup"))
            runCatching { vlcThread.shutdownNow() }

            withTimeoutOrNull(5_000L) {
                PlayerOperationUtils.safeExecute(
                    operationName = "清理资源",
                    showUserError = false,
                    timeoutMillis = 3_000L,
                ) {
                    log.debug("开始异步清理资源...")
                    player?.let { p ->
                        PlayerOperationUtils.safeExecute(
                            operationName = "停止播放",
                            showUserError = false,
                            timeoutMillis = 2_000L,
                        ) {
                            p.controls()?.stop()
                        }
                    }
                    deferredEffects.clear()
                    log.debug("异步清理资源完成!")
                }
            } ?: log.warn("播放器资源清理超时，跳过后续等待")
            scope.cancel("异步停止播放")
        }
    }

    override fun load(url: String): PlayerController {
        log.debug("load -- 加载：$url")
        if (StringUtils.isBlank(url)) {
            SnackBar.postMsg("播放地址为空", type = SnackBar.MessageType.WARNING)
            return this
        }
        endingHandled = false
        catch {
            player?.media()?.prepare(url, *buildMediaOptions().toTypedArray())
        }
        return this
    }

    // 对齐 TV engine.start：generation 丢弃过期任务；不再用单线程队列（play 会堵死后续换集）
    override suspend fun loadURL(url: String, timeoutMillis: Long): PlayerController {
        val generation = loadGeneration.get()
        var applied = false
        try {
            withTimeout(timeoutMillis) {
                applied = withContext(Dispatchers.IO) {
                    loadURLDirect(generation, url)
                }
            }
        } catch (e: TimeoutCancellationException) {
            mediaMutex.withLock { playerLoading = false }
            log.warn("loadURL 超时: {}", url)
        } catch (e: CancellationException) {
            mediaMutex.withLock { playerLoading = false }
            if (isLoadGenerationStale(generation)) {
                log.info("loadURL 已取消（过期 generation={}）", generation)
            } else {
                log.info("loadURL 已取消")
                throw e
            }
        } catch (e: Exception) {
            mediaMutex.withLock { playerLoading = false }
            log.error("loadURL 失败", e)
            withContext(Dispatchers.Swing) {
                SnackBar.postMsg("加载媒体失败: ${e.message}", type = SnackBar.MessageType.ERROR)
            }
        }
        if (!applied) {
            mediaMutex.withLock { playerLoading = false }
        }
        return this@VlcjController
    }

    private fun loadURLDirect(generation: Int, url: String): Boolean {
        if (StringUtils.isBlank(url)) {
            SnackBar.postMsg("播放地址为空", type = SnackBar.MessageType.WARNING)
            return false
        }
        if (!isPlayableMediaUrl(url)) {
            log.warn("拒绝非媒体地址，避免当作本地文件: {}", url)
            return false
        }
        if (isLoadGenerationStale(generation) || !vm.shouldApplyPlayback() || isCleaned) {
            log.info("丢弃过期 loadURL: 入队前校验失败 generation={}", generation)
            return false
        }

        vm.controller.resumeVideoRendering()
        log.info("loadURL 开始 generation={}", generation)
        return executeLoadOnVlcThread(generation, url)
    }

    /** 对齐 TV：stop 后 play；play 提交到 VLC native 队列，本方法立即返回 */
    private fun executeLoadOnVlcThread(generation: Int, url: String): Boolean {
        if (isLoadGenerationStale(generation)) {
            log.info("丢弃过期 loadURL: generation={}", generation)
            return false
        }
        if (!vm.shouldApplyPlayback()) {
            log.info("丢弃过期 loadURL: pending 与当前集不一致")
            return false
        }

        val shouldSkip = synchronized(vlcStateLock) {
            val currentMrl = runCatching { player?.media()?.info()?.mrl() }.getOrNull()
            if (currentMrl == url && playerPlaying) {
                log.warn("loadURL - 已在播放相同URL，跳过: {}", url)
                true
            } else {
                endingHandled = false
                playerRealStartTime = 0
                playerPlaying = false
                playerLoading = true
                false
            }
        }
        if (shouldSkip) return true

        if (isLoadGenerationStale(generation) || !vm.shouldApplyPlayback()) {
            log.info("丢弃过期 loadURL: 起播前校验失败 generation={}", generation)
            synchronized(vlcStateLock) { playerLoading = false }
            return false
        }

        val p = player
        if (p?.media() == null) {
            synchronized(vlcStateLock) { playerLoading = false }
            throw IllegalStateException("播放器媒体对象为空!")
        }

        log.info("设置媒体：$url")
        expectedMrl = url
        loadStartedAtMs = System.currentTimeMillis()
        startTrafficMonitor()
        _state.update { it.copy(state = PlayState.BUFFERING, bufferProgression = 0f) }
        // 交给 VLC native 队列，立刻返回，避免阻塞换集
        p.submit {
            if (isLoadGenerationStale(generation)) {
                log.info("丢弃过期 loadURL(native): generation={}", generation)
                return@submit
            }
            try {
                runCatching { p.controls().stop() }
                if (isLoadGenerationStale(generation)) {
                    log.info("丢弃过期 loadURL(native): stop 后 generation={}", generation)
                    return@submit
                }
                val ok = p.media().play(url, *buildMediaOptions().toTypedArray())
                if (isLoadGenerationStale(generation)) {
                    log.info("loadURL 完成后已过期，忽略 generation={}", generation)
                    return@submit
                }
                if (!ok) {
                    log.warn("media().play 返回 false")
                    synchronized(vlcStateLock) { playerLoading = false }
                    return@submit
                }
                log.info("设置媒体完成 generation={}", generation)
            } catch (e: Exception) {
                log.error("loadURL play 失败 generation={}", generation, e)
                synchronized(vlcStateLock) { playerLoading = false }
            }
        }
        log.info("设置媒体已提交 native generation={}", generation)
        return true
    }


    private fun handleOpeningSeek() {
        try {
            val position = history.value?.position ?: 0L
            val opening = _state.value.opening?.takeIf { it > 0 } ?: 0L
            // 续播用 position；新集 position=0 时仅跳片头。勿把上一集进度带进新集。
            val seekPosition = max(position, opening)
            if (seekPosition <= 0L) return

            val currentTime = player?.status()?.time() ?: 0L
            log.debug(
                "handleOpeningSeek - position={}, opening={}, seekPosition={}, currentTime={}",
                position, opening, seekPosition, currentTime,
            )
            if (currentTime < seekPosition) {
                log.info("跳转到续播/片头位置: {} ms", seekPosition)
                player?.controls()?.setTime(seekPosition)
                _state.update { it.copy(timestamp = seekPosition) }
            }
        } catch (e: Exception) {
            log.error("处理片头跳转失败", e)
        }
    }
    
    override fun play() {
        catch {
            showTips("播放")
            playerStartTime = System.currentTimeMillis()
            player?.controls()?.play()
        }
    }

    override fun play(url: String) = catch {
        showTips("播放")
        log.debug("play -- play: $url")
        player?.media()?.play(url)
    }

    override fun pause() = catch {
        showTips("暂停")
        player?.controls()?.setPause(true)
    }

    fun showTips(text: String) {
        scope.launch {
            tip.emit(text)
            showTip.emit(true)
        }
    }

    override fun stop() = catch {
        showTips("停止")
        player?.controls()?.stop()
    }

    override suspend fun stopAsync() {
        withContext(Dispatchers.IO) {
            log.debug("异步停止播放...")
            showTips("停止")
            try {
                withTimeout(2_000) {
                    player?.controls()?.stop()
                }
            } catch (e: Exception) {
                log.warn("停止播放失败或超时: {}", e.message)
            }
        }
        vm.controller.clearVideoFrame()
    }

    private fun isStreamingUrl(mrl: String): Boolean {
        val lower = mrl.lowercase()
        return lower.contains(".m3u8") || lower.contains("/flv/") || lower.contains(".flv?")
    }

    /** 与 DetailViewModel.isDirectlyPlayable 对齐：接受 http(s)/流协议，以及 Windows 本地 lumen-m3u8 */
    private fun isPlayableMediaUrl(url: String): Boolean {
        val u = url.trim()
        if (u.isBlank()) return false
        val lower = u.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        if (lower.startsWith("file:") || lower.startsWith("rtmp") ||
            lower.startsWith("rtp://") || lower.startsWith("udp://")
        ) {
            return true
        }
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

    private fun isHlsMrl(mrl: String): Boolean {
        val lower = mrl.lowercase()
        return lower.contains(".m3u8") || lower.contains("lumen-m3u8") ||
            lower.contains("cached_m3u8") || lower.contains("getm3u8")
    }

    override fun dispose() = catch {
        log.debug("dispose - 释放播放器资源")
        scope.cancel()
        deferredEffects.clear()
        player?.events()?.removeMediaPlayerEventListener(stateListener)
        player?.release()
        // 只有当 factory 已初始化时才释放
        if (::factory.isInitialized) {
            factory.release()
        }
        player = null
        log.debug("dispose - 释放成功")
    }

    override fun seekTo(timestamp: Long) = catch {
        _state.update { it.copy(timestamp = timestamp) }
        player?.controls()?.setTime(timestamp)
    }

    override fun setVolume(value: Float) = catch {
        player?.audio()?.setVolume((value * 100).toInt().coerceIn(0..150))
        _state.update { it.copy(volume = value) }
        if (value > 0f) {
            SettingStore.doWithCache {
                var state = it["playerState"]
                if (state == null) {
                    state = PlayerStateCache()
                    it["playerState"] = state
                }
                (state as PlayerStateCache).add("volume", value.toString())
            }
        }
        scope.launch {
            delay(50) // 短暂延迟确保状态同步
            showTips("音量：${(value * 100).toInt().coerceIn(0..100)}")
        }
    }


    private val volumeStep = 5

    override fun volumeUp() {
        val currentVolume = player?.audio()?.volume() ?: 0
        val newVolume = (currentVolume + volumeStep).coerceIn(0..150)
        player?.audio()?.setVolume(newVolume)
        _state.update { it.copy(volume = newVolume / 100f) }
        showTips("音量：$newVolume")
    }

    override fun volumeDown() {
        val currentVolume = player?.audio()?.volume() ?: 0
        val newVolume = (currentVolume - volumeStep).coerceIn(0..150)
        player?.audio()?.setVolume(newVolume)
        _state.update { it.copy(volume = newVolume / 100f) }
        showTips("音量：$newVolume")
    }

    /**
     * 快进 单位 秒
     */
    override fun forward(time: String) {
        showTips("快进：$time")
        player?.controls()?.skipTime(Duration.parse(time).toLong(DurationUnit.MILLISECONDS))
        _state.update { it.copy(timestamp = (player?.status()?.time() ?: 0)) }
    }

    override fun backward(time: String) {
        showTips("快退：$time")
        player?.controls()?.skipTime(-Duration.parse(time).toLong(DurationUnit.MILLISECONDS))
        _state.update { it.copy(timestamp = (player?.status()?.time() ?: 0)) }
    }

    override fun toggleSound() = catch {
        val newMuted = !(player?.audio()?.isMute ?: false)
        player?.audio()?.mute()
        _state.update { it.copy(isMuted = newMuted) }
    }

    override fun toggleFullscreen() = catch {
        val videoFullScreen = GlobalAppState.toggleVideoFullScreen()
        if (videoFullScreen) showTips("[ESC]退出全屏")
    }

    override fun togglePlayStatus() {
        scope.launch(Dispatchers.IO) {
            try {
                val isPlaying = player?.status()?.isPlaying == true
                if (isPlaying) {
                    showTips("暂停")
                    player?.controls()?.setPause(true)
                } else {
                    showTips("播放")
                    val currentMrl = player?.media()?.info()?.mrl()
                    val fallbackUrl = PlaybackMediaState.url
                    if (currentMrl.isNullOrBlank() && fallbackUrl.isNotBlank()) {
                        loadURL(fallbackUrl, 15_000L)
                    } else {
                        player?.controls()?.play()
                    }
                }
            } catch (e: Exception) {
                log.warn("切换播放状态失败: {}", e.message)
            }
        }
    }

    fun markBufferingForSwitch() {
        startTrafficMonitor()
        _state.update { it.copy(state = PlayState.BUFFERING, bufferProgression = 0f) }
    }

    /** 对齐 TV showProgress + Traffic.setSpeed：缓冲期间刷新网速 */
    private fun startTrafficMonitor() {
        if (trafficJob?.isActive == true) return
        Traffic.reset()
        trafficJob = scope.launch {
            // 先采一帧打底，再进入 500ms 刷新（TV 为 1s，桌面起播更快需更密）
            Traffic.sampleSpeed()
            while (isActive) {
                delay(500)
                val speed = Traffic.sampleSpeed().ifBlank {
                    readVlcTrafficSpeed()
                }
                _state.update {
                    it.copy(trafficSpeed = speed.ifBlank { it.trafficSpeed })
                }
            }
        }
    }

    private fun stopTrafficMonitor() {
        trafficJob?.cancel()
        trafficJob = null
        Traffic.reset()
        // 稍留最后一帧，避免闪成「加载中」；下一状态会清掉
        _state.update { it.copy(trafficSpeed = "") }
    }

    private fun readVlcTrafficSpeed(): String {
        return try {
            val stats = player?.media()?.info()?.statistics() ?: return ""
            val bps = stats.inputBitrate().toDouble().takeIf { it > 0 }
                ?: stats.demuxBitrate().toDouble()
            if (bps <= 0) return ""
            val kb = bps / 1024.0
            if (kb < 1000) "${kb.toInt()} KB/s" else "%.1f MB/s".format(kb / 1024.0)
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun awaitRefreshStopForSwitch() {
        awaitRefreshStop()
    }

    override fun speed(speed: Float) = catch {
        showTips("倍速：$speed")
        player?.controls()?.setRate(speed)
    }

    override fun stopForward() {
        isAccelerating = false
        speed(originSpeed)
    }

    override fun fastForward() {
        if (!isAccelerating) {
            currentSpeed = player?.status()?.rate() ?: 1.0f
            originSpeed = currentSpeed.toDouble().toFloat()
            isAccelerating = true
        }
        acceleratePlayback()
    }

    private val maxSpeed = 8.0f

    private fun acceleratePlayback() {
        if (isAccelerating) {
            currentSpeed += 0.5f
            currentSpeed = currentSpeed.coerceAtMost(maxSpeed)
            speed(currentSpeed)
            log.info("Playback rate: $currentSpeed x")
        }
    }

    override fun updateEnding(detail: Vod?) {
        _state.update { it.copy(ending = player?.status()?.time() ?: -1) }
//        if (_state.value.ending == -1L) {
//        } else {
//            _state.update { it.copy(ending = -1) }
//        }
        history.update { it?.copy(ending = player?.status()?.time() ?: -1) }
    }

    override fun updateOpening(detail: Vod?) {
        _state.update { it.copy(opening = player?.status()?.time() ?: -1) }
//        if (_state.value.opening == -1L) {
//        } else {
//            _state.update { it.copy(opening = -1) }
//        }
        history.update { it?.copy(opening = player?.status()?.time() ?: -1) }
    }

    override fun doWithPlayState(func: (MutableStateFlow<PlayerState>) -> Unit) {
        func(_state)
    }

    override fun setStartEnding(opening: Long, ending: Long) {
        _state.update { it.copy(opening = opening, ending = ending) }
    }

    override fun setAspectRatio(aspectRatio: String) = catch {
        player?.video()?.setAspectRatio(aspectRatio)
        _state.update { it.copy(aspectRatio = aspectRatio) }
        showTips("视频比例: ${getAspectRatioDisplayName(aspectRatio)}")
    }

    override fun getAspectRatio(): String {
        return player?.video()?.aspectRatio() ?: ""
    }

    private fun getAspectRatioDisplayName(ratio: String): String {
        return when (ratio) {
            "16:9" -> "16:9"
            "4:3" -> "4:3"
            "1:1" -> "1:1"
            "16:10" -> "16:10"
            "21:9" -> "21:9"
            "2.35:1" -> "2.35:1"
            "2.39:1" -> "2.39:1"
            "5:4" -> "5:4"
            "" -> "原始比例"
            else -> ratio
        }
    }

    fun setSubtitleUrl(url: String) {
        activeSubtitleUrl = url
        PlaybackMediaState.subtitleUrl = url
    }

    fun applySubtitle(url: String) {
        if (url.isBlank()) return
        activeSubtitleUrl = url
        PlaybackMediaState.subtitleUrl = url
        doWithMediaPlayer { mediaPlayer ->
            runCatching {
                mediaPlayer.media().addSlave(MediaSlaveType.SUBTITLE, url, true)
                log.info("已加载字幕: {}", url.take(80))
            }.onFailure {
                log.warn("加载字幕失败: {}", it.message)
            }
        }
    }

    private fun buildMediaOptions(): List<String> {
        val options = mutableListOf<String>()
        val headers = PlaybackMediaState.headers
        val userAgent = headers.entries.firstOrNull {
            it.key.equals("User-Agent", ignoreCase = true) || it.key.equals("ua", ignoreCase = true)
        }?.value ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
        options.add("http-user-agent=$userAgent")
        headers.entries.firstOrNull {
            it.key.equals("Referer", ignoreCase = true) || it.key.equals("referer", ignoreCase = true)
        }?.value?.takeIf { it.isNotBlank() }?.let { options.add("http-referrer=$it") }
        headers.entries.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value
            ?.takeIf { it.isNotBlank() }?.let { options.add("http-cookie=$it") }
        val subtitle = activeSubtitleUrl.ifBlank { PlaybackMediaState.subtitleUrl }
        if (subtitle.isNotBlank()) {
            options.add(":sub-file=$subtitle")
        }
        return options
    }

    private fun syncPlaybackMediaState(mediaPlayer: MediaPlayer, playing: Boolean? = null) {
        PlaybackMediaState.position = mediaPlayer.status().time()
        PlaybackMediaState.duration = mediaPlayer.status().length()
        PlaybackMediaState.speed = currentSpeed
        playing?.let { PlaybackMediaState.playing = it }
        mediaPlayer.media()?.info()?.mrl()?.let { PlaybackMediaState.url = it }
    }
}