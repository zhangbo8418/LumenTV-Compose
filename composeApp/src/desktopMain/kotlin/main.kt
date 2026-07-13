import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.LightDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.corner.RootContent
import com.corner.util.settings.SettingStore
import com.corner.util.net.Utils.printSystemInfo
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.init.Init
import com.corner.init.TVLogConfigurator
import com.corner.init.generateImageLoader
import com.corner.ui.UpdateDialog
import com.corner.ui.Util
import com.corner.ui.scene.SnackBar
import com.corner.util.update.DownloadProgress
import com.corner.util.update.UpdateDownloader
import com.corner.util.update.UpdateLauncher
import com.corner.util.update.UpdateManager
import com.corner.util.update.UpdateResult
import com.corner.util.update.fetchChangelogFromUrl
import com.seiko.imageloader.LocalImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import lumentv_compose.composeapp.generated.resources.LumenTV_icon_png
import org.jetbrains.compose.resources.painterResource
import org.slf4j.LoggerFactory
import lumentv_compose.composeapp.generated.resources.Res
import java.io.File
import java.awt.Dimension

private val log = LoggerFactory.getLogger("main")
private const val CHANGE_LOG_URL = "https://raw.githubusercontent.com/clevebitr/LumenTV-Compose/refs/heads/main/CHANGELOG.md"

fun main() {
    // 初始化 Log4j2 日志配置
    TVLogConfigurator.configure()

    // JVM QuickJS 需手动加载原生库（否则 JS 源 createContext 失败）
    runCatching { com.corner.catvodcore.loader.QuickJsNative.ensureLoaded() }
        .onFailure { log.warn("QuickJS native 预加载失败（JS 源将不可用）: {}", it.message) }
    
    launchErrorCatcher()
    printSystemInfo()

    application {
        val windowState = rememberWindowState(
            size = Util.getPreferWindowSize(600, 500), position = WindowPosition.Aligned(Alignment.Center)
        )
        GlobalAppState.windowState = windowState

        val scope = rememberCoroutineScope()

        var showUpdateDialog by remember { mutableStateOf(false) }
        var updateResult by remember { mutableStateOf<UpdateResult.Available?>(null) }
        var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
        var changelog by remember { mutableStateOf<String?>(null) }
        var isLoadingChangelog by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            supervisorScope {
                launch(Dispatchers.Default) {
                    runCatching { Init.start() }.onFailure {
                        log.error("应用初始化失败", it)
                    }
                }
                launch(Dispatchers.IO) {
                    runCatching {
                        when (val result = UpdateManager.checkForUpdate()) {
                            is UpdateResult.Available -> {
                                updateResult = result
                                isLoadingChangelog = true
                                changelog = fetchChangelogFromUrl(CHANGE_LOG_URL)
                                isLoadingChangelog = false
                                showUpdateDialog = true
                            }
                            is UpdateResult.Error -> {
                                log.warn("启动时检查更新失败: {}", result.message)
                            }
                            UpdateResult.NoUpdate -> Unit
                        }
                    }.onFailure {
                        log.warn("启动时检查更新异常: {}", it.message)
                    }
                }
            }
        }

        val contextMenuRepresentation =
            if (isSystemInDarkTheme()) DarkDefaultContextMenuRepresentation else LightDefaultContextMenuRepresentation
        Window(
            onCloseRequest = ::exitApplication, icon = painterResource(Res.drawable.LumenTV_icon_png), title = "LumenTV",
            state = windowState,
            undecorated = true,
            transparent = false,
        ) {
            GlobalAppState.prepareWindowForFullscreen(window)
            window.minimumSize = Dimension(800, 600)
            CompositionLocalProvider(
                LocalImageLoader provides remember { generateImageLoader() },
                LocalContextMenuRepresentation provides remember { contextMenuRepresentation },
            ) {
                RootContent()
            }
            scope.launch {
                GlobalAppState.closeApp.collect {
                    if (it) {
                        try {
                            window.isVisible = false
                            SettingStore.write()
                            // 清理全局协程资源,避免内存泄漏
                            GlobalAppState.cancelAllOperations("Application shutdown")
                            Init.stop()
                        } catch (e: Exception) {
                            log.error("关闭应用异常", e)
                        } finally {
                            exitApplication()
                        }
                    }
                }
            }

            if (showUpdateDialog && updateResult != null) {
                UpdateDialog(
                    currentVersion = updateResult!!.currentVersion,
                    latestVersion = updateResult!!.latestVersion,
                    downloadProgress = downloadProgress,
                    onDismiss = {
                        showUpdateDialog = false
                        downloadProgress = null
                    },
                    changelog = changelog,
                    isLoadingChangelog = isLoadingChangelog,
                    onNoRemind = {
                        UpdateManager.setNoRemindForVersion(updateResult!!.latestVersion)
                        showUpdateDialog = false
                        downloadProgress = null
                    },
                    onUpdate = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                log.info("Starting update process")
                                val tempDir = System.getProperty("java.io.tmpdir")
                                val zipFile = File(tempDir, "LumenTV-update.zip")

                                suspend fun launchIfReady(): Boolean {
                                    if (!zipFile.exists() || zipFile.length() <= 0L) {
                                        SnackBar.postMsg("更新包不存在或已损坏", type = SnackBar.MessageType.ERROR)
                                        return false
                                    }
                                    val launched = UpdateLauncher.launchUpdater(zipFile, updateResult!!.updaterUrl)
                                    if (launched) {
                                        UpdateLauncher.exitApplication()
                                    } else {
                                        SnackBar.postMsg(
                                            "更新程序启动失败，请检查网络后重试",
                                            type = SnackBar.MessageType.ERROR,
                                        )
                                    }
                                    return launched
                                }

                                if (zipFile.exists() && zipFile.length() > 0L) {
                                    log.info("Update file already exists, launching updater directly")
                                    launchIfReady()
                                    return@launch
                                }

                                UpdateDownloader.downloadUpdate(
                                    updateResult!!.downloadUrl,
                                    zipFile,
                                ).collect { progress ->
                                    downloadProgress = progress
                                    when (progress) {
                                        is DownloadProgress.Completed -> launchIfReady()
                                        is DownloadProgress.Failed -> {
                                            log.error("下载更新失败: {}", progress.error)
                                            SnackBar.postMsg(
                                                "下载更新失败: ${progress.error}",
                                                type = SnackBar.MessageType.ERROR,
                                            )
                                        }
                                        else -> Unit
                                    }
                                }
                            } catch (e: Exception) {
                                log.error("更新流程异常", e)
                                SnackBar.postMsg(
                                    "更新失败: ${e.message}",
                                    type = SnackBar.MessageType.ERROR,
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

private fun launchErrorCatcher() {
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        SnackBar.postMsg("未知异常,请检查日志", type = SnackBar.MessageType.ERROR)
        log.error("未知异常", e)
//        Init.stop()
    }
}