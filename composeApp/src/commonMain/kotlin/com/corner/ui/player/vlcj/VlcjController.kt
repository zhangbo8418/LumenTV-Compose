package com.corner.ui.player.vlcj

import com.corner.util.settings.PlayerStateCache
import com.corner.util.settings.SettingStore
import com.corner.catvodcore.bean.Vod
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.database.entity.History
import com.corner.server.PlaybackMediaState
import com.corner.ui.player.MediaInfo
import com.corner.ui.player.PlayState
import com.corner.ui.player.PlayerController
import com.corner.ui.player.PlayerLifecycleManager
import com.corner.ui.player.PlayerState
import com.corner.ui.player.VodPlaybackHost
import com.corner.ui.scene.SnackBar
import com.corner.util.core.catch
import com.corner.util.net.Traffic
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.DurationUnit

private val log = LoggerFactory.getLogger("VlcjController")

class VlcjController : PlayerController {
    var player: EmbeddedMediaPlayer? = null
    private var lifecycleManager: PlayerLifecycleManager? = null
    @Volatile
    private var host: VodPlaybackHost? = null
    @Volatile
    private var frame: VlcjFrameController? = null

    fun setLifecycleManager(manager: PlayerLifecycleManager) {
        this.lifecycleManager = manager
    }

    fun lifecycleManagerOrNull(): PlayerLifecycleManager? = lifecycleManager

    fun attachFrame(frameController: VlcjFrameController) {
        frame = frameController
    }

    fun bindHost(playbackHost: VodPlaybackHost) {
        host = playbackHost
    }

    fun unbindHost(playbackHost: VodPlaybackHost) {
        if (host === playbackHost) {
            host = null
        }
    }

    fun currentHost(): VodPlaybackHost? = host

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
    private var trafficJob: Job? = null         // 缓冲网速轮询（对齐 TV Traffic）
    private var activeSubtitleUrl: String = ""
    private val mediaMutex = Mutex()
    private val vlcStateLock = Any()
    private val loadGeneration = AtomicInteger(0)

    /**
     * 对齐 TV player().clear()：清空当前播放状态，不阻塞 VLC。
     */
    fun clearPlaybackState() {
        playerLoading = false
        playerPlaying = false
        playerRealStartTime = 0
    }

    /** 换集/离开时临时压音；起播后按用户原 mute 状态恢复，避免叠音或返回后仍出声 */
    @Volatile
    private var audioSuppressed = false
    @Volatile
    private var muteBeforeSuppress = false

    private fun suppressAudio(reason: String) {
        runCatching {
            val audio = player?.audio() ?: return
            if (!audioSuppressed) {
                muteBeforeSuppress = audio.isMute
                audioSuppressed = true
            }
            if (!audio.isMute) {
                audio.setMute(true)
            }
            _state.update { it.copy(isMuted = true) }
            log.info("已抑制音频: {}", reason)
        }.onFailure { log.warn("抑制音频失败: {}", it.message) }
    }

    private fun restoreAudioIfSuppressed() {
        if (!audioSuppressed) return
        audioSuppressed = false
        runCatching {
            val audio = player?.audio() ?: return
            audio.setMute(muteBeforeSuppress)
            _state.update { it.copy(isMuted = muteBeforeSuppress) }
            log.info("已恢复音频 mute={}", muteBeforeSuppress)
        }.onFailure { log.warn("恢复音频失败: {}", it.message) }
    }

    /**
     * 对齐 TV stopPlaybackForRefresh：换集/换线前立刻作废排队并异步 mute。
     * mute 不走 player.submit，避免堵死后续 prepare/play。
     */
    fun stopPlaybackForRefresh(): Int {
        val gen = loadGeneration.incrementAndGet()
        expectedMrl = ""
        clearPlaybackState()
        playerLoading = true
        abortStuckVlcLoad("stopPlaybackForRefresh gen=$gen")
        audioSuppressed = true
        _state.update { it.copy(isMuted = true) }
        scope.launch(Dispatchers.IO) {
            suppressAudio("stopPlaybackForRefresh")
        }
        return gen
    }

    /** @deprecated 用 [stopPlaybackForRefresh] */
    fun invalidatePendingLoads(): Int = stopPlaybackForRefresh()

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

    /** 离开页 endPlayback 幂等；起播时清零 */
    private val playbackEnded = AtomicBoolean(false)

    /**
     * 对齐 TV PlaybackService.suspend：离开详情立刻 mute+pause。
     *
     * 根因（日志已证实）：`controls().stop()` 会在 CallbackVideoSurface 场景下卡住——
     * `stopped` 事件已触发，但 stop() 仍不返回并占着 libvlc native 锁；
     * 之后 `media().prepare`/`play` 永远走不到「engine.start 完成」。
     * 放弃等待也不能救：挂起的 stop 线程仍占锁。因此离开页绝不调 stop。
     */
    fun endPlayback() {
        if (!playbackEnded.compareAndSet(false, true)) {
            log.debug("endPlayback 幂等跳过")
            return
        }
        clearPlaybackState()
        playerLoading = false
        abortStuckVlcLoad("endPlayback")
        audioSuppressed = true
        _state.update { it.copy(state = PlayState.PAUSE, isMuted = true) }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val audio = player?.audio()
                if (audio != null) {
                    muteBeforeSuppress = audio.isMute
                    if (!audio.isMute) audio.setMute(true)
                }
                player?.controls()?.setPause(true)
                log.info("endPlayback 完成（mute+pause，跳过 stop 避免 libvlc 死锁）")
            }.onFailure { log.warn("endPlayback mute/pause 失败: {}", it.message) }
        }
    }

    suspend fun endPlaybackAndClearFrame() {
        endPlayback()
        withContext(Dispatchers.Swing) {
            frame?.clearVideoFrame()
        }
    }

    @Volatile
    private var lastAbortAtMs: Long = 0L
    @Volatile
    var onPlayerRecreated: ((EmbeddedMediaPlayer) -> Unit)? = null

    private fun abortStuckVlcLoad(reason: String) {
        lastAbortAtMs = System.currentTimeMillis()
        expectedMrl = ""
        log.info("中断 VLC 加载(保留播放器): {}", reason)
    }

    /** 当前期望播放的 MRL，用于丢弃换媒体时旧流的 finished/stopped */
    @Volatile
    private var expectedMrl: String = ""
    @Volatile
    private var loadStartedAtMs: Long = 0L

    // 视频播放状态
    private var playerStartTime: Long = 0
    private var playerRealStartTime: Long = 0
    private var playerEndTime: Long = 0
    private val decodeFailureTureShould = 5000L

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
            frame?.resumeVideoRendering()
            restoreAudioIfSuppressed()
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
            if (host?.suppressAutoLineSwitch == true) {
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
                scope.launch { host?.playbackError("播放失败，时长过短") }
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
                scope.launch { host?.playbackError("播放中断") }
                return
            }
            if (isHlsMrl(mrl) && mediaLength > 60_000L && playDuration < mediaLength - 15_000L && playDuration < 60_000L) {
                log.warn(
                    "HLS 未播完即结束 (播放{}ms / 总长{}ms)，触发 playbackError",
                    playDuration,
                    mediaLength,
                )
                _state.update { it.copy(state = PlayState.PAUSE) }
                scope.launch { host?.playbackError("播放中断") }
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
                    val h = host
                    if (h?.isDLNA == true) {
                        log.info("DLNA投屏模式，跳过自动换集")
                        SnackBar.postMsg("投屏播放完成", type = SnackBar.MessageType.INFO)
                    } else if (h != null && !h.isLastEpisode) {
                        log.info("切换下一集")
                        h.nextEP()
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
                        host?.nextEP()
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
                runCatching { history.value?.let { host?.updateHistory(it) } }
                host?.playbackError("播放错误")
            }
        }
    }

    private val _state = MutableStateFlow(PlayerState())

    override val state: StateFlow<PlayerState>
        get() = _state.asStateFlow()

    override fun init() {
        try {
            if (player != null && ::factory.isInitialized) {
                log.debug("VLC 已初始化，跳过重复创建")
                return
            }
            if (!::factory.isInitialized) {
                factory = MediaPlayerFactory(vlcjArgs)
            }
            if (player == null) {
                player = factory.mediaPlayers()?.newEmbeddedMediaPlayer()?.apply {
                    events().addMediaPlayerEventListener(stateListener)
                    video().setScale(0.0f)
                    val muted = audio()?.isMute ?: false
                    _state.update { it.copy(isMuted = muted) }
                }
            }
        } catch (e: Exception) {
            log.error("vlcj初始化失败", e)
            SnackBar.postMsg("vlcj初始化失败!", type = SnackBar.MessageType.ERROR)
        }
    }

    /** 单例安全：只停播/清排队，不拆线程 */
    override suspend fun cleanupAsync() {
        withContext(Dispatchers.IO) {
            stopTrafficMonitor()
            stopPlaybackForRefresh()
            runCatching {
                player?.controls()?.setPause(true)
                _state.update { it.copy(state = PlayState.PAUSE) }
            }
            deferredEffects.clear()
            log.debug("cleanupAsync 完成（保留单例实例）")
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
        if (isLoadGenerationStale(generation) || host?.shouldApplyPlayback() != true) {
            log.info("丢弃过期 loadURL: 入队前校验失败 generation={}", generation)
            return false
        }

        frame?.resumeVideoRendering()
        log.info("engine.start 开始 generation={}", generation)
        return engineStart(generation, url)
    }

    /**
     * 对齐 TV ExoPlayerEngine.startInternal：
     * prepare(media) → play()；续播起点写入 :start-time（秒）。
     * 不先 controls().stop()：与 display/play 并发易在 libvlc 死锁。
     */
    private fun engineStart(generation: Int, url: String): Boolean {
        if (isLoadGenerationStale(generation)) {
            log.info("丢弃过期 engine.start: generation={}", generation)
            return false
        }
        if (host?.shouldApplyPlayback() != true) {
            log.info("丢弃过期 engine.start: pending 与当前集不一致")
            return false
        }

        val shouldSkip = synchronized(vlcStateLock) {
            val currentMrl = runCatching { player?.media()?.info()?.mrl() }.getOrNull()
            if (currentMrl == url && playerPlaying) {
                log.warn("engine.start - 已在播放相同URL，跳过: {}", url)
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

        if (isLoadGenerationStale(generation) || host?.shouldApplyPlayback() != true) {
            log.info("丢弃过期 engine.start: 起播前校验失败 generation={}", generation)
            synchronized(vlcStateLock) { playerLoading = false }
            return false
        }

        val p = player
        if (p == null || p.media() == null) {
            log.warn("播放器未就绪，尝试重新初始化后起播")
            runCatching {
                if (!::factory.isInitialized || factory == null) {
                    factory = MediaPlayerFactory(vlcjArgs)
                }
                if (player == null) {
                    player = factory.mediaPlayers()?.newEmbeddedMediaPlayer()?.apply {
                        events().addMediaPlayerEventListener(stateListener)
                        video().setScale(0.0f)
                    }
                    onPlayerRecreated?.invoke(player!!)
                }
            }.onFailure { log.error("重新初始化播放器失败", it) }
        }
        val playable = player
        if (playable?.media() == null) {
            synchronized(vlcStateLock) { playerLoading = false }
            throw IllegalStateException("播放器媒体对象为空!")
        }

        val startPositionMs = resolveStartPositionMs()
        log.info("engine.start 设置媒体：{} startMs={}", url, startPositionMs)
        expectedMrl = url
        loadStartedAtMs = System.currentTimeMillis()
        startTrafficMonitor()
        _state.update { it.copy(state = PlayState.BUFFERING, bufferProgression = 0f) }
        val options = buildMediaOptions(url, startPositionMs).toTypedArray()
        playbackEnded.set(false)
        val nativeDone = AtomicBoolean(false)
        // 交给 VLC native 队列，立刻返回，避免阻塞换集
        playable.submit {
            if (isLoadGenerationStale(generation)) {
                log.info("丢弃过期 engine.start(native): generation={}", generation)
                nativeDone.set(true)
                return@submit
            }
            try {
                // 离开页可能 mute 过：起播前恢复，避免有画无声
                restoreAudioIfSuppressed()
                // 对齐 TV：setMedia/prepare → play（vlcj prepare 即 changeMedia）
                val prepared = playable.media().prepare(url, *options)
                if (!prepared) {
                    log.warn("media().prepare 返回 false")
                    synchronized(vlcStateLock) { playerLoading = false }
                    nativeDone.set(true)
                    return@submit
                }
                if (isLoadGenerationStale(generation)) {
                    log.info("engine.start prepare 后已过期，忽略 generation={}", generation)
                    nativeDone.set(true)
                    return@submit
                }
                playable.controls().play()
                if (isLoadGenerationStale(generation)) {
                    log.info("engine.start play 后已过期，忽略 generation={}", generation)
                    nativeDone.set(true)
                    return@submit
                }
                log.info("engine.start 完成 generation={}", generation)
            } catch (e: Exception) {
                log.error("engine.start 失败 generation={}", generation, e)
                synchronized(vlcStateLock) { playerLoading = false }
            } finally {
                nativeDone.set(true)
            }
        }
        log.info("engine.start 已提交 native generation={}", generation)
        // 若此前 stop 已把 native 锁卡死，prepare 会永远不回；超时后重建实例再起播一次
        scope.launch {
            delay(4_000L)
            if (nativeDone.get() || isLoadGenerationStale(generation)) return@launch
            log.warn("engine.start native 卡住 generation={}，重建 EmbeddedMediaPlayer", generation)
            recreateEmbeddedPlayer("prepare hung gen=$generation")
            if (isLoadGenerationStale(generation) || host?.shouldApplyPlayback() != true) return@launch
            val retryPlayer = player ?: return@launch
            retryPlayer.submit {
                if (isLoadGenerationStale(generation)) return@submit
                runCatching {
                    restoreAudioIfSuppressed()
                    if (retryPlayer.media().prepare(url, *options)) {
                        retryPlayer.controls().play()
                        log.info("engine.start 重建后完成 generation={}", generation)
                    }
                }.onFailure {
                    log.error("engine.start 重建后仍失败 generation={}", generation, it)
                    synchronized(vlcStateLock) { playerLoading = false }
                }
            }
        }
        return true
    }

    /** 丢弃可能被 stop/prepare 卡死的实例，新建 EmbeddedMediaPlayer（旧实例后台尝试 release） */
    private fun recreateEmbeddedPlayer(reason: String) {
        val old = player
        log.warn("重建点播播放器: {}", reason)
        runCatching { old?.events()?.removeMediaPlayerEventListener(stateListener) }
        runCatching {
            if (!::factory.isInitialized || factory == null) {
                factory = MediaPlayerFactory(vlcjArgs)
            }
            player = factory.mediaPlayers()?.newEmbeddedMediaPlayer()?.apply {
                events().addMediaPlayerEventListener(stateListener)
                video().setScale(0.0f)
            }
            player?.let { onPlayerRecreated?.invoke(it) }
        }.onFailure { log.error("重建播放器失败", it) }
        if (old != null) {
            Thread({
                runCatching { old.release() }
                    .onFailure { log.warn("旧播放器 release 失败: {}", it.message) }
            }, "vlc-orphan-release").apply { isDaemon = true; start() }
        }
    }

    /** 对齐 TV setMediaItem(position)：续播/片头起点 */
    private fun resolveStartPositionMs(): Long {
        val position = history.value?.position ?: 0L
        val opening = _state.value.opening?.takeIf { it > 0 } ?: 0L
        return max(position, opening).coerceAtLeast(0L)
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
        // 与 endPlayback 相同：不调 libvlc stop，避免 CallbackVideoSurface 下卡死
        runCatching {
            player?.audio()?.setMute(true)
            player?.controls()?.setPause(true)
        }
        _state.update { it.copy(state = PlayState.PAUSE) }
    }

    override suspend fun stopAsync() {
        withContext(Dispatchers.IO) {
            log.debug("异步停止播放（mute+pause，跳过 libvlc stop）...")
            showTips("停止")
            runCatching {
                player?.audio()?.setMute(true)
                player?.controls()?.setPause(true)
            }.onFailure { log.warn("异步停止失败: {}", it.message) }
            _state.update { it.copy(state = PlayState.PAUSE) }
        }
        frame?.clearVideoFrame()
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

    private fun buildMediaOptions(url: String = "", startPositionMs: Long = 0L): List<String> {
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
        // 对齐 TV setMediaItem(position)；HLS 仍走 handleOpeningSeek，避免 :start-time 不稳
        if (startPositionMs > 1_000L && url.isNotBlank() && !isHlsMrl(url) && !isStreamingUrl(url)) {
            options.add(":start-time=${startPositionMs / 1000.0}")
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