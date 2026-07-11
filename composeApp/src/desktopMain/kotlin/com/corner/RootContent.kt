package com.corner

import com.corner.ui.theme.AppTheme
import com.corner.ui.scene.SnackBar.SnackBarList
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.corner.util.settings.SettingStore
import com.corner.catvodcore.bean.Vod
import com.corner.catvodcore.enum.Menu
import com.corner.cast.CastReceivePayload
import com.corner.catvodcore.viewmodel.DetailFromPage
import com.corner.ui.cast.ReceiveCastDialog
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.catvodcore.push.PushService
import com.corner.init.Init
import com.corner.server.ServerEvent
import com.corner.ui.danmaku.DanmakuManager
import com.corner.ui.DLNAPlayer
import com.corner.ui.DetailScene
import com.corner.ui.FpsMonitor
import com.corner.ui.HistoryScene
import com.corner.ui.LiveScene
import com.corner.ui.SettingScene
import com.corner.ui.nav.vm.*
import com.corner.ui.navigation.TVScreen
import com.corner.ui.scene.BrowserDownloadDialog
import com.corner.ui.scene.LoadingIndicator
import com.corner.ui.search.SearchScene
import com.corner.ui.video.VideoScene
import com.corner.util.FirefoxGray
import com.corner.util.jcef.JcefBrowserManager
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.corner.util.settings.SettingType
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val log = LoggerFactory.getLogger("RootContent")

@Composable
fun WindowScope.RootContent(
    navController: NavHostController = rememberNavController()
) {
    val toDetail = fun(it: Vod, from: DetailFromPage) {
        GlobalAppState.chooseVod.value = it
        GlobalAppState.detailFrom = from
        navController.navigate(TVScreen.DetailScreen.name)
    }

    val isFullScreen = GlobalAppState.videoFullScreen.collectAsState()

    val modifierVar = derivedStateOf {
        if (isFullScreen.value) {
            Modifier.fillMaxSize().border(border = BorderStroke(0.dp, Color.Black))
        } else {
            Modifier.fillMaxSize().border(BorderStroke(1.dp, Color.FirefoxGray)).shadow(15.dp)
        }
    }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        ServerEvent.pushUrl.collect { text ->
            toDetail(PushService.buildVod(text), DetailFromPage.HOME)
        }
    }
    LaunchedEffect(Unit) {
        ServerEvent.searchWord.collect { word ->
            GlobalAppState.pendingSearch.value = word
            navController.navigate(TVScreen.SearchScreen.name)
        }
    }
    LaunchedEffect(Unit) {
        ServerEvent.settingConfig.collect { (url, _) ->
            if (url.isNotBlank()) {
                SettingStore.setValue(SettingType.VOD, url)
                Init.initConfig(forceReinit = true)
            }
        }
    }
    LaunchedEffect(Unit) {
        ServerEvent.danmakuText.collect { text ->
            DanmakuManager.send(text)
        }
    }

    var castPayload by remember { mutableStateOf<CastReceivePayload?>(null) }
    LaunchedEffect(Unit) {
        ServerEvent.castReceive.collect { castPayload = it }
    }
    castPayload?.let { payload ->
        ReceiveCastDialog(
            payload = payload,
            onClose = { castPayload = null },
            onAccepted = { vod ->
                GlobalAppState.chooseVod.value = vod
                GlobalAppState.detailFrom = DetailFromPage.HOME
                navController.navigate(TVScreen.DetailScreen.name)
            },
        )
    }

    // Web 解析需要内嵌浏览器时全局弹出下载确认
    var showBrowserDialog by remember { mutableStateOf(false) }
    var browserReason by remember { mutableStateOf("网页解析") }
    var browserDownloading by remember { mutableStateOf(false) }
    var browserProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        JcefBrowserManager.installRequested.collect { reason ->
            browserReason = reason.ifBlank { "网页解析" }
            showBrowserDialog = true
        }
    }
    if (showBrowserDialog) {
        BrowserDownloadDialog(
            reason = browserReason,
            isDownloading = browserDownloading,
            downloadProgress = browserProgress,
            onConfirm = {
                scope.launch {
                    browserDownloading = true
                    browserProgress = 0f
                    val result = withContext(Dispatchers.IO) {
                        JcefBrowserManager.ensureReady { p ->
                            browserProgress = p.toFloat()
                        }
                    }
                    browserDownloading = false
                    showBrowserDialog = false
                    if (result.isSuccess) {
                        JcefBrowserManager.resetInstallPrompt()
                    }
                }
            },
            onCancel = {
                if (!browserDownloading) {
                    showBrowserDialog = false
                }
            },
        )
    }

    // 创建一个 SettingViewModel 实例来监听设置变化
    val settingViewModel: SettingViewModel = viewModel { SettingViewModel() }
    val settingVersion by settingViewModel.state.collectAsState()

    scope.launch {
        GlobalAppState.DLNAUrl.collect {
            if (it.isBlank()) return@collect
            navController.navigate(TVScreen.DLNAPlayerScreen.name)
        }
    }

    AppTheme {
        Box(
            modifier = modifierVar.value.background(Color.Black)
        ) {
            NavHost(
                navController,
                startDestination = TVScreen.VideoScreen.name
            ) {
                composable(TVScreen.VideoScreen.name) {
                    VideoScene(
                        viewModel { VideoViewModel() },
                        modifier = Modifier,
                        { toDetail(it, DetailFromPage.HOME) }) { menu ->
                        when (menu) {
                            Menu.SEARCH -> navController.navigate(TVScreen.SearchScreen.name)
                            Menu.HOME -> navController.navigate(TVScreen.VideoScreen.name)
                            Menu.LIVE -> navController.navigate(TVScreen.LiveScreen.name)
                            Menu.SETTING -> navController.navigate(TVScreen.SettingsScreen.name)
                            Menu.HISTORY -> navController.navigate(TVScreen.HistoryScreen.name)
                        }
                    }
                }

                composable(TVScreen.DetailScreen.name) {
                    DetailScene(
                        viewModel { DetailViewModel() }
                    ) { navController.popBackStack() }
                }

                composable(TVScreen.SearchScreen.name) {
                    SearchScene(
                        viewModel { SearchViewModel() },
                        { toDetail(it, DetailFromPage.SEARCH) }) { navController.popBackStack() }
                }

                composable(TVScreen.HistoryScreen.name) {
                    HistoryScene(
                        viewModel { HistoryViewModel() },
                        { toDetail(it, DetailFromPage.HOME) }) { navController.popBackStack() }
                }

                composable(TVScreen.LiveScreen.name) {
                    LiveScene(
                        viewModel { LiveViewModel() }
                    ) { navController.popBackStack() }
                }

                composable(TVScreen.SettingsScreen.name) {
                    SettingScene(
                        settingViewModel,
                        config = SettingStore.getM3U8FilterConfig()
                    ) { navController.popBackStack() }
                }

                composable(TVScreen.DLNAPlayerScreen.name) {
                    val viewModel = viewModel { DetailViewModel() }
                    DLNAPlayer(viewModel) { navController.popBackStack() }
                }
            }
            SnackBarList()
            val showProgress = GlobalAppState.showProgress.collectAsState()
            LoadingIndicator(showProgress = showProgress.value, withOverlay = true)

            // FPS 监控组件 - 使用 settingVersion 确保响应式更新
            val fpsMonitorEnabled by remember(settingVersion.version) {
                derivedStateOf { SettingStore.getSettingItem(SettingType.FPS_MONITOR).toBoolean() }
            }
            if (fpsMonitorEnabled) {
                FpsMonitor(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    settingVersion = settingVersion.version,
                    fpsMonitorEnabled = fpsMonitorEnabled
                )
            }
        }
    }
}