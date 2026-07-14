package com.corner.catvodcore.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.corner.util.HotData
import com.corner.util.settings.SettingStore
import com.corner.util.settings.SettingType
import com.corner.catvodcore.bean.Site
import com.corner.catvodcore.bean.Vod
import com.corner.database.entity.History
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jupnp.UpnpService
import org.slf4j.LoggerFactory
import java.awt.Rectangle
import java.awt.Window
import javax.swing.SwingUtilities

object GlobalAppState {
    private val log = LoggerFactory.getLogger(GlobalAppState::class.java)

    /**
     * 主题偏好：system / dark / light
     */
    val themePreference = MutableStateFlow(
        try {
            SettingStore.getSettingItem(SettingType.THEME).ifBlank { "system" }
        } catch (e: Exception) {
            e.printStackTrace()
            "system"
        }
    )

    /**
     * 当前是否深色（由 AppTheme 根据 themePreference + 系统主题同步）
     */
    val isDarkTheme = MutableStateFlow(themePreference.value == "dark")

    /**
     * 是否显示加载指示器
     */
    var showProgress = MutableStateFlow(false)

    /**
     * 热搜列表
     */
    val hotList = MutableStateFlow(listOf<HotData>())

    /**
     * 选择的视频
     */
    val chooseVod = mutableStateOf(Vod())

    /**
     * 投屏续播历史（消费后清空）
     */
    val castResumeHistory = mutableStateOf<History?>(null)

    fun consumeCastResume(): History? {
        return castResumeHistory.value.also { castResumeHistory.value = null }
    }

    /**
     * 主页站点
     */
    val home = MutableStateFlow(Site.get("", ""))

    /**
     * 对齐 TV RefreshEvent.home：递增后触发首页内容重载。
     * 配置恢复用 setHome(save=false) 只改内存；加载结束后再 refreshHome。
     * 用户换站走 setHome(save=true) 内直接 refreshHome。
     */
    val homeRefreshEpoch = MutableStateFlow(0L)

    fun refreshHome() {
        homeRefreshEpoch.update { it + 1 }
    }

    /**
     * 是否清除数据
     */
    val clear = MutableStateFlow(false)

    /**
     * 是否关闭App
     */
    val closeApp = MutableStateFlow(false)

    /**
     * 是否全屏
     */
    val videoFullScreen = MutableStateFlow(false)

    /**
     * DLNA播放地址
     */
    val DLNAUrl = MutableStateFlow("")

    /**
     * DLNA投屏停止回调
     */
    var onDLNAStop: (() -> Unit)? = null

    /**
     * 根协程Job
     */
    private val rootJob = Job()

    /**
     * 根协程作用域
     */
    val rootScope = CoroutineScope(Dispatchers.IO + rootJob)

    /**
     * UPNP服务锁
     */
    private val upnpServiceLock = Any()

    /**
     * 内部UPNP服务访问
     */
    private var _upnpService: UpnpService? = null

    /**
     * UPNP服务
     */
    var upnpService: UpnpService?
        get() = synchronized(upnpServiceLock) { _upnpService }
        set(value) = synchronized(upnpServiceLock) { _upnpService = value }

    /**
     * 窗口状态
     */
    var windowState: WindowState? = null

    /**
     * AWT 窗口（无边框窗口在 macOS 上 Fullscreen placement 不可靠，需手动设 bounds）
     */
    var awtWindow: Window? = null

    private var preFullscreenBounds: Rectangle? = null
    private var preFullscreenSize: DpSize? = null
    private var preFullscreenPosition: WindowPosition? = null
    private var usedMacNativeFullscreen = false
    private var usedExclusiveFullscreen = false
    private var exclusiveDevice: java.awt.GraphicsDevice? = null

    private val isMacOs: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    /**
     * 远程搜索关键词（由 /action?do=search 触发）
     */
    val pendingSearch = MutableStateFlow("")

    /**
     * 壁纸本地路径与类型
     */
    val wallpaperPath = MutableStateFlow<String?>(null)
    val wallpaperType = MutableStateFlow(com.corner.catvodcore.config.WallConfig.WallType.IMAGE)

    /**
     * 详情页来源页面
     */
    var detailFrom = DetailFromPage.HOME

    /**
     * 取消所有操作
     * @param reason 停止的原因
     */
    fun cancelAllOperations(reason: String = "Normal shutdown") {
        if (!rootJob.isCancelled) {
            log.info("Cancelling all operations: $reason")
            rootScope.cancel(reason)
        }
    }

    /**
     * 切换全屏状态
     * @return 当前全屏状态
     */
    fun toggleVideoFullScreen(): Boolean {
        val entering = !videoFullScreen.value
        if (entering) enterVideoFullscreen() else exitVideoFullscreen()
        videoFullScreen.value = entering
        return entering
    }

    /** 在窗口创建后调用，允许 macOS 原生全屏（隐藏 Dock / 菜单栏）。 */
    fun prepareWindowForFullscreen(window: Window) {
        awtWindow = window
        if (isMacOs) {
            runCatching { setMacWindowCanFullScreen(window, true) }
                .onFailure { log.debug("prepare mac fullscreen failed: {}", it.message) }
        }
    }

    /**
     * 全屏策略：
     * - macOS：优先系统原生全屏（独立 Space，隐藏 Dock）
     * - Windows / Linux：优先 GraphicsDevice 独占/模拟全屏（盖住任务栏）
     * - 再退回拉满屏幕 + 置顶
     */
    private fun enterVideoFullscreen() {
        val state = windowState
        val win = awtWindow
        if (state != null) {
            preFullscreenSize = state.size
            preFullscreenPosition = state.position
        }
        if (win != null) {
            preFullscreenBounds = Rectangle(win.bounds)
        }

        // macOS：系统全屏体验最好
        if (win != null && isMacOs && requestMacNativeFullscreen(win)) {
            usedMacNativeFullscreen = true
            log.info("entered macOS native fullscreen")
            return
        }

        // Windows / Linux（以及 mac 回退）：独占或模拟全屏，通常可盖住任务栏
        if (win != null) {
            val device = win.graphicsConfiguration?.device
                ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            usedExclusiveFullscreen = true
            exclusiveDevice = device
            SwingUtilities.invokeLater {
                state?.placement = WindowPlacement.Floating
                // isFullScreenSupported=false 时 JDK 仍会模拟为铺满屏幕
                device.fullScreenWindow = win
                // Linux 部分桌面需要置顶才能压住任务栏
                if (!isMacOs) {
                    win.isAlwaysOnTop = true
                }
                win.toFront()
                win.requestFocus()
            }
            log.info(
                "entered device fullscreen (supported={}) on {}",
                device.isFullScreenSupported,
                System.getProperty("os.name"),
            )
            return
        }

        state?.placement = WindowPlacement.Fullscreen
    }

    private fun exitVideoFullscreen() {
        val state = windowState
        val win = awtWindow
        SwingUtilities.invokeLater {
            when {
                usedMacNativeFullscreen && win != null -> {
                    requestMacNativeFullscreen(win)
                    usedMacNativeFullscreen = false
                }
                usedExclusiveFullscreen -> {
                    exclusiveDevice?.fullScreenWindow = null
                    exclusiveDevice = null
                    usedExclusiveFullscreen = false
                    win?.isAlwaysOnTop = false
                    restoreWindowBounds(win, state)
                }
                else -> {
                    win?.isAlwaysOnTop = false
                    restoreWindowBounds(win, state)
                }
            }
            state?.placement = WindowPlacement.Floating
        }
    }

    private fun restoreWindowBounds(win: Window?, state: WindowState?) {
        val bounds = preFullscreenBounds
        if (win != null && bounds != null) {
            win.bounds = bounds
        }
        preFullscreenSize?.let { state?.size = it }
        preFullscreenPosition?.let { state?.position = it }
        preFullscreenBounds = null
        preFullscreenSize = null
        preFullscreenPosition = null
    }

    private fun requestMacNativeFullscreen(window: Window): Boolean {
        return try {
            setMacWindowCanFullScreen(window, true)
            val appClass = Class.forName("com.apple.eawt.Application")
            val app = appClass.getMethod("getApplication").invoke(null)
            appClass.getMethod("requestToggleFullScreen", Window::class.java).invoke(app, window)
            true
        } catch (e: Exception) {
            log.warn("macOS native fullscreen unavailable: {}", e.message)
            false
        }
    }

    private fun setMacWindowCanFullScreen(window: Window, can: Boolean) {
        val clazz = Class.forName("com.apple.eawt.FullScreenUtilities")
        clazz.getMethod(
            "setWindowCanFullScreen",
            Window::class.java,
            java.lang.Boolean.TYPE,
        ).invoke(null, window, can)
    }

    /**
     * 显示加载指示器
     */
    fun showProgress() {
        showProgress.update { true }
    }

    /**
     * 隐藏加载指示器
     */
    fun hideProgress() {
        showProgress.update { false }
    }

    /**
     * 重置所有状态
     * */
    fun resetAllStates() {
        showProgress = MutableStateFlow(false)
        hotList.value = emptyList()
        home.value = Site.get("", "")
        clear.value = false
        videoFullScreen.value = false
        DLNAUrl.value = ""
        chooseVod.value = Vod()
    }
}

/**
 * 详情页来源页面
 */
enum class DetailFromPage {
    SEARCH, HOME
}
