package com.corner.ui.player.vlcj

import com.corner.util.settings.PlayerStateCache
import com.corner.util.settings.SettingStore
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.database.entity.History
import com.corner.ui.player.MediaInfo
import com.corner.ui.player.PlayState
import com.corner.ui.player.PlayerController
import com.corner.ui.player.PlayerLifecycleManager
import com.corner.ui.player.PlayerState
import com.corner.ui.scene.SnackBar
import com.corner.util.core.catch
import com.corner.util.net.Traffic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.util.concurrent.atomic.AtomicInteger

private val log = LoggerFactory.getLogger("LiveVlcjController")

class LiveVlcjController : PlayerController {
    var player: EmbeddedMediaPlayer? = null
    internal lateinit var factory: MediaPlayerFactory
    private var lifecycleManager: PlayerLifecycleManager? = null

    fun setLifecycleManager(manager: PlayerLifecycleManager) {
        lifecycleManager = manager
    }

    override var playerLoading = false
    override var playerPlaying = false
    override var showTip = MutableStateFlow(false)
    override var tip = MutableStateFlow("")
    override var history: MutableStateFlow<History?> = MutableStateFlow(null)
    override var endingHandled = false

    private val deferredEffects = mutableListOf<(MediaPlayer) -> Unit>()
    var controllerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isCleaned = false
    private var trafficJob: Job? = null
    private var switchJob: Job? = null
    private val switchGeneration = AtomicInteger(0)

    private val vlcjArgs = listOf(
        "-q",
        "--no-video-on-top",
        "--avcodec-hw=any",
        "--network-caching=500",
        "--live-caching=300",
        "--preparse-timeout=500",
    )

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val stateListener = object : MediaPlayerEventAdapter() {
        override fun mediaPlayerReady(mediaPlayer: MediaPlayer) {
            _state.update { it.copy(duration = mediaPlayer.status().length(), state = PlayState.PLAY) }
        }

        override fun videoOutput(mediaPlayer: MediaPlayer?, newCount: Int) {
            val trackInfo = mediaPlayer?.media()?.info()?.videoTracks()?.firstOrNull() ?: return
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

        override fun buffering(mediaPlayer: MediaPlayer?, newCache: Float) {
            if (newCache != 100F) {
                startTrafficMonitor()
                _state.update { it.copy(state = PlayState.BUFFERING, bufferProgression = newCache) }
            } else {
                stopTrafficMonitor()
                _state.update { it.copy(state = PlayState.PLAY, bufferProgression = newCache) }
            }
        }

        override fun opening(mediaPlayer: MediaPlayer?) {
            playerLoading = true
            startTrafficMonitor()
            _state.update { it.copy(state = PlayState.BUFFERING) }
        }

        override fun playing(mediaPlayer: MediaPlayer) {
            playerLoading = false
            playerPlaying = true
            stopTrafficMonitor()
            _state.update { it.copy(state = PlayState.PLAY) }
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            stopTrafficMonitor()
            _state.update { it.copy(state = PlayState.PAUSE) }
        }

        override fun stopped(mediaPlayer: MediaPlayer) {
            playerPlaying = false
            stopTrafficMonitor()
            _state.update { it.copy(state = PlayState.PAUSE) }
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            playerPlaying = false
            stopTrafficMonitor()
            _state.update { it.copy(state = PlayState.PAUSE) }
        }

        override fun volumeChanged(mediaPlayer: MediaPlayer, volume: Float) {
            if (volume > 0f) {
                SettingStore.doWithCache {
                    var cache = it["playerState"]
                    if (cache == null) {
                        cache = PlayerStateCache()
                        it["playerState"] = cache
                    }
                    (cache as PlayerStateCache).add("volume", volume.toString())
                }
            }
            _state.update { it.copy(volume = volume) }
        }

        override fun error(mediaPlayer: MediaPlayer?) {
            log.error("直播播放错误: ${mediaPlayer?.media()?.info()?.mrl()}")
            _state.update { it.copy(state = PlayState.ERROR, msg = "播放错误") }
            SnackBar.postMsg("直播播放失败", type = SnackBar.MessageType.ERROR)
            super.error(mediaPlayer)
        }
    }

    override fun isPlayerInstanceReady(): Boolean = player != null

    override fun resetOpeningEnding() {
        _state.update { it.copy(opening = -1L, ending = -1L) }
    }

    override fun doWithMediaPlayer(block: (MediaPlayer) -> Unit) {
        player?.let(block) ?: deferredEffects.add(block)
    }

    override fun onMediaPlayerReady(mediaPlayer: EmbeddedMediaPlayer) {
        player = mediaPlayer
        _state.update { it.copy(duration = player?.status()?.length() ?: 0L) }
        deferredEffects.forEach { it(mediaPlayer) }
        deferredEffects.clear()
    }

    override fun init() {
        isCleaned = false
        try {
            factory = MediaPlayerFactory(vlcjArgs)
            player = factory.mediaPlayers()?.newEmbeddedMediaPlayer()?.apply {
                events().addMediaPlayerEventListener(stateListener)
                video().setScale(0.0f)
                val muted = audio()?.isMute ?: false
                _state.update { it.copy(isMuted = muted) }
            }
        } catch (e: Exception) {
            log.error("直播播放器初始化失败", e)
            SnackBar.postMsg("直播播放器初始化失败", type = SnackBar.MessageType.ERROR)
        }
    }

    override suspend fun cleanupAsync() {
        withContext(Dispatchers.IO) {
            if (isCleaned) return@withContext
            isCleaned = true
            stopTrafficMonitor()
            try {
                player?.controls()?.stop()
            } catch (e: Exception) {
                log.warn("停止直播播放失败", e)
            }
        }
    }

    override fun load(url: String): PlayerController {
        switchChannel(url, emptyMap())
        return this
    }

    /** 换台/换线：先 pause 再 prepare，避免旧台音频拖尾；快速连切只保留最后一档 */
    fun switchChannel(url: String, headers: Map<String, String>): PlayerController {
        val gen = switchGeneration.incrementAndGet()
        switchJob?.cancel()
        switchJob = controllerScope.launch {
            runCatching { player?.controls()?.setPause(true) }
            try {
                loadURL(url, 10000, headers)
                if (gen != switchGeneration.get() || !isActive) return@launch
                catch { player?.controls()?.play() }
            } catch (_: CancellationException) {
                // 被下一次换台取消
            } catch (e: Exception) {
                if (gen == switchGeneration.get()) {
                    log.error("直播换台失败", e)
                }
            }
        }
        return this
    }

    override suspend fun loadURL(url: String, timeoutMillis: Long): PlayerController =
        loadURL(url, timeoutMillis, emptyMap())

    suspend fun loadURL(url: String, timeoutMillis: Long, headers: Map<String, String>): PlayerController =
        withContext(Dispatchers.IO) {
        if (StringUtils.isBlank(url)) {
            SnackBar.postMsg("播放地址为空", type = SnackBar.MessageType.WARNING)
            return@withContext this@LiveVlcjController
        }
        try {
            playerLoading = true
            runCatching { player?.controls()?.setPause(true) }
            val optionsList = buildVlcOptions(headers)
            player?.media()?.prepare(url, *optionsList.toTypedArray())
        } catch (e: Exception) {
            playerLoading = false
            log.error("加载直播地址失败", e)
            SnackBar.postMsg("加载直播地址失败", type = SnackBar.MessageType.ERROR)
        }
        this@LiveVlcjController
    }

    private fun buildVlcOptions(headers: Map<String, String>): List<String> {
        val options = mutableListOf(
            "http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
        )
        headers["User-Agent"]?.takeIf { it.isNotBlank() }?.let {
            options[0] = "http-user-agent=$it"
        }
        headers["Referer"]?.takeIf { it.isNotBlank() }?.let {
            options.add("http-referrer=$it")
        }
        headers["Cookie"]?.takeIf { it.isNotBlank() }?.let {
            options.add(":http-cookies=$it")
        }
        return options
    }

    override fun play() = catch {
        showTips("播放")
        player?.controls()?.play()
    }

    override fun play(url: String) = catch {
        player?.media()?.play(url)
    }

    override fun pause() = catch {
        showTips("暂停")
        player?.controls()?.setPause(true)
    }

    override fun stop() = catch {
        showTips("停止")
        player?.controls()?.stop()
    }

    override suspend fun stopAsync() {
        withContext(Dispatchers.IO) {
            player?.controls()?.stop()
        }
    }

    private fun startTrafficMonitor() {
        if (trafficJob?.isActive == true) return
        Traffic.reset()
        trafficJob = controllerScope.launch {
            Traffic.sampleSpeed()
            while (isActive) {
                delay(500)
                val speed = Traffic.sampleSpeed().ifBlank { readVlcTrafficSpeed() }
                _state.update { it.copy(trafficSpeed = speed.ifBlank { it.trafficSpeed }) }
            }
        }
    }

    private fun stopTrafficMonitor() {
        trafficJob?.cancel()
        trafficJob = null
        Traffic.reset()
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

    override fun dispose() = catch {
        stopTrafficMonitor()
        controllerScope.cancel()
        deferredEffects.clear()
        player?.events()?.removeMediaPlayerEventListener(stateListener)
        player?.release()
        if (::factory.isInitialized) factory.release()
        player = null
    }

    override fun seekTo(timestamp: Long) = catch {
        _state.update { it.copy(timestamp = timestamp) }
        player?.controls()?.setTime(timestamp)
    }

    override fun setVolume(value: Float) = catch {
        player?.audio()?.setVolume((value * 100).toInt().coerceIn(0..150))
        _state.update { it.copy(volume = value) }
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

    override fun toggleSound() = catch {
        player?.audio()?.mute()
        _state.update { it.copy(isMuted = player?.audio()?.isMute ?: false) }
    }

    override fun toggleFullscreen() = catch {
        val fullScreen = GlobalAppState.toggleVideoFullScreen()
        if (fullScreen) showTips("[ESC]退出全屏")
    }

    override fun togglePlayStatus() {
        if (player?.status()?.isPlaying == true) pause() else play()
    }

    override fun speed(rate: Float) = catch {
        player?.controls()?.setRate(rate)
    }

    override fun stopForward() {}
    override fun fastForward() {}
    override fun forward(time: String) {}
    override fun backward(time: String) {}
    override fun updateEnding(detail: com.corner.catvodcore.bean.Vod?) {}
    override fun updateOpening(detail: com.corner.catvodcore.bean.Vod?) {}
    override fun doWithPlayState(func: (MutableStateFlow<PlayerState>) -> Unit) {
        func(_state)
    }

    override fun setStartEnding(opening: Long, ending: Long) {
        _state.update { it.copy(opening = opening, ending = ending) }
    }

    override fun setAspectRatio(aspectRatio: String) = catch {
        player?.video()?.setAspectRatio(aspectRatio)
        _state.update { it.copy(aspectRatio = aspectRatio) }
    }

    override fun getAspectRatio(): String = player?.video()?.aspectRatio() ?: ""

    private fun showTips(text: String) {
        controllerScope.launch {
            tip.emit(text)
            showTip.emit(true)
            delay(1500)
            tip.emit("")
            showTip.emit(false)
        }
    }
}
