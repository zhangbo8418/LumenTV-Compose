package com.corner.init

import androidx.compose.runtime.mutableStateOf
import com.corner.util.Hot
import com.corner.util.settings.SettingStore
import com.corner.util.settings.SettingType
import com.corner.catvodcore.config.ApiConfig
import com.corner.catvodcore.config.init
import com.corner.catvodcore.enum.ConfigType
import com.corner.catvodcore.loader.BaseLoader
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.catvodcore.viewmodel.GlobalAppState.hideProgress
import com.corner.catvodcore.viewmodel.GlobalAppState.showProgress
import com.corner.database.Db
import com.corner.database.appModule
import com.corner.dlna.TVMUpnpService
import com.corner.server.KtorD
import com.corner.ui.player.vlcj.VlcJInit
import com.corner.ui.scene.SnackBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.StringUtils
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory
import androidx.compose.runtime.State
import com.corner.catvodcore.viewmodel.GlobalAppState.resetAllStates
import com.corner.database.entity.Config
import com.corner.util.spider.SpiderTestUtil

private val log = LoggerFactory.getLogger("Init")

class Init {
    companion object {
        /**
         * 初始化成功状态
         * */
        private val _isInitializedSuccessfully = mutableStateOf(false)
        val isInitializedSuccessfully: State<Boolean> = _isInitializedSuccessfully

        /**
         * Koin实例
         * */
        private var instance: KoinApplication? = null

        /**
         * 初始化应用
         * */
        suspend fun start() {
            showProgress()
            try {
                initJarFileSystemProvider()
                //Koin
                initKoin()
                // JS local 持久化（对齐 TV Prefers）
                com.github.catvod.utils.Prefers.init(
                    java.io.File(com.corner.util.io.Paths.root(), "cache/js-prefers.properties")
                )
                //Http Server
                KtorD.init()
                //点播源配置
                initConfig()
                //一致性初始化
                initPlatformSpecify()
                //热搜
                Hot.getHotList()
                //播放器
                VlcJInit.init()
                //DLNA
                initDLNA()
                //初始化爬虫状态
                SpiderTestUtil.initializeSpiderStatuses()
            } finally {
                hideProgress()
            }
        }
        
        /**
         * 初始化 JAR 文件系统提供者（生产环境 JAR 内资源访问）
         */
        private fun initJarFileSystemProvider() {
            try {
                Class.forName("jdk.nio.zipfs.ZipFileSystemProvider")
                log.info("JAR 文件系统提供者已加载")
            } catch (e: Exception) {
                log.warn("加载 JAR 文件系统提供者失败: ${e.message}")
            }
        }

        /**
         * 关闭应用服务
         * */
        fun stop() {
            GlobalAppState.cancelAllOperations("Application shutdown")// stop CoroutineScope
            try {
                VlcJInit.release()      //release VlcJ
                resetAllStates()        //reset all states
                KtorD.stop()            //stop KtorD
                stopKoin()              //stop Koin
                stopDLNA()              //stop DLNA
                BaseLoader.clear()       //clear loaders
            } catch (e: Throwable) {
                log.error("Cleanup error", e)
            }
        }

        /**
         * 初始化Koin注入框架（Database）
         * */
        private fun initKoin() {
            instance = startKoin {
                modules(appModule)
            }
        }

        /**
         * 停止Koin注入框架（Database）
         * */
        private fun stopKoin() {
            log.info("Stop Koin")
            instance?.close()
            instance = null
        }

        /**
         * 初始化DLNA功能
         * */
        private fun initDLNA() {
            GlobalAppState.upnpService = TVMUpnpService().apply {
                startup()
                sendAlive()
            }
        }

        /**
         * 停止DLNA服务
         * */
        private fun stopDLNA() {
            log.info("Stop DLNA Service")
            GlobalAppState.upnpService?.shutdown()
            GlobalAppState.upnpService = null
        }

        /**
         * 初始化点播源配置
         * @return 是否加载成功
         */
        fun initConfig(forceReinit: Boolean = false): Boolean {
            if (!forceReinit && _isInitializedSuccessfully.value) {
                log.warn("配置已初始化，跳过重复操作")
                return true
            }

            val vod = SettingStore.getSettingItem(SettingType.VOD.id)
            if (StringUtils.isBlank(vod)) {
                log.warn("未配置点播源，跳过初始化")
                hideProgress()
                _isInitializedSuccessfully.value = false
                return false
            }

            val siteConfig = runBlocking {
                withContext(Dispatchers.IO) {
                    Db.Config.find(vod, ConfigType.SITE.ordinal.toLong()).firstOrNull()
                        ?: Db.Config.findOneByType(ConfigType.SITE.ordinal.toLong())
                }
            } ?: run {
                log.error("未找到站点配置")
                _isInitializedSuccessfully.value = false
                hideProgress()
                return false
            }

            val hadValidConfig = _isInitializedSuccessfully.value && ApiConfig.api.sites.isNotEmpty()
            val snapshotApi = if (hadValidConfig) ApiConfig.apiFlow.value else null
            val snapshotHome = if (hadValidConfig) GlobalAppState.home.value else null

            log.info("初始化开始....")
            BaseLoader.clear()
            ApiConfig.clear()
            GlobalAppState.clear.update { !it }

            val loaded = loadSiteConfig(siteConfig)
            if (loaded) {
                log.info("初始化完成!")
                _isInitializedSuccessfully.value = true
                return true
            }

            if (snapshotApi != null && snapshotHome != null) {
                ApiConfig.applySnapshot(snapshotApi, snapshotHome)
                SnackBar.postMsg("配置更新失败，已保留原配置", type = SnackBar.MessageType.WARNING)
                _isInitializedSuccessfully.value = true
            } else {
                SnackBar.postMsg("配置加载失败，请检查点播源地址", type = SnackBar.MessageType.ERROR)
                _isInitializedSuccessfully.value = false
            }
            hideProgress()
            return false
        }

        private fun loadSiteConfig(siteConfig: Config): Boolean {
            if (tryParseConfig(siteConfig, isJson = false)) return true
            if (!siteConfig.json.isNullOrBlank() && tryParseConfig(siteConfig, isJson = true)) return true
            return false
        }

        private fun tryParseConfig(siteConfig: Config, isJson: Boolean): Boolean {
            if (!ApiConfig.parseConfig(siteConfig, isJson)) return false
            ApiConfig.api.init()
            return true
        }
    }
}

/**
 * 平台特定的初始化
 */
expect fun initPlatformSpecify()