package com.corner.catvodcore.config

import com.corner.util.settings.SettingStore
import com.corner.util.settings.SettingType
import com.corner.catvodcore.bean.Rule
import com.corner.catvodcore.bean.Site
import com.corner.catvodcore.bean.Api
import com.corner.catvodcore.bean.Parse
import com.corner.catvodcore.config.ApiConfig.api
import com.corner.catvodcore.enum.ConfigType
import com.corner.catvodcore.live.LiveConfig
import com.corner.catvodcore.config.ParseConfig
import com.corner.catvodcore.config.WallConfig
import com.corner.catvodcore.config.ConfigDepot
import com.corner.catvodcore.loader.BaseLoader
import com.corner.catvodcore.loader.JarLoader
import com.corner.util.net.Http
import com.corner.util.json.Jsons
import com.corner.util.json.cleanJsonComments
import com.corner.util.io.Urls
import com.corner.catvodcore.viewmodel.GlobalAppState
import com.corner.database.Db
import com.corner.database.entity.Config
import com.corner.ui.scene.SnackBar
import com.corner.util.scope.createCoroutineScope
import com.corner.util.isEmpty
import com.github.catvod.crawler.Spider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import com.corner.util.core.NoStackTraceException
import com.github.catvod.bean.Doh

private val log = LoggerFactory.getLogger("apiConfig")

object ApiConfig {
    private val scope = createCoroutineScope()
    var apiFlow = MutableStateFlow(Api(spider = ""))
    var api: Api = apiFlow.value

    init {
        collectApi()
    }

    private fun collectApi() {
        scope.launch {
            apiFlow.collect { api = it }
        }
    }

    fun clear() {
        apiFlow.value = Api(spider = "")
        ParseConfig.clear()
        // 对齐 TV VodConfig.clear：home = null
        GlobalAppState.home.value = Site.get("", "")
    }

    fun applySnapshot(snapshot: Api, home: Site) {
        apiFlow.value = snapshot
        api = snapshot
        setHome(home, save = false)
        if (snapshot.spider.isNotBlank()) {
            runCatching { BaseLoader.parseJar(snapshot.spider, false) }
        }
        runCatching { ParseConfig.init(snapshot.parses, snapshot.cfg?.parse) }
        runCatching { WallConfig.init(snapshot.wallpaper) }
        GlobalAppState.refreshHome()
    }

    fun parseConfig(
        cfg: Config,
        isJson: Boolean,
        onSuccess: () -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ): Boolean {
        return try {
            log.info("解析配置开始, cfg:{} isJson:{}", cfg, isJson)

            val source = if (isJson) cfg.json.orEmpty() else cfg.url.orEmpty()
            if (!isJson && source.isBlank()) {
                val error = NoStackTraceException("点播源地址无效")
                SnackBar.postMsg("点播源地址无效", type = SnackBar.MessageType.ERROR)
                onError(error)
                return false
            }

            val data = getData(source, isJson).orEmpty()
            if (StringUtils.isBlank(data)) {
                val error = NoStackTraceException("配置数据为空")
                SnackBar.postMsg("配置数据为空，请检查配置文件或链接", type = SnackBar.MessageType.WARNING)
                onError(error)
                return false
            }

            val cleanedData = cleanJsonComments(data)
            ConfigDepot.resolve(cleanedData, cfg.url)?.let { depotCfg ->
                log.info("检测到仓库索引，展开首个地址: {}", depotCfg.url)
                depotCfg.url?.takeIf { it.isNotBlank() }?.let {
                    SettingStore.setValue(SettingType.VOD, it)
                }
                return parseConfig(depotCfg, isJson = false, onSuccess, onError)
            }

            val apiConfig = Jsons.decodeFromString<Api>(cleanedData)
            if (apiConfig.sites.isEmpty()) {
                val error = NoStackTraceException("站点列表为空")
                SnackBar.postMsg("配置中没有可用站点", type = SnackBar.MessageType.WARNING)
                onError(error)
                return false
            }

            val logoRaw = apiConfig.logo?.takeIf { it.isNotBlank() && !it.contains("{") }
                ?: extractRootString(cleanedData, "logo")
            val resolvedLogo = resolveConfigAsset(cfg.url, logoRaw)
            val updatedApi = apiConfig.copy(
                url = cfg.url,
                data = data,
                cfg = cfg,
                ref = apiConfig.ref + 1,
                logo = resolvedLogo,
            )
            apiFlow.update { updatedApi }
            api = updatedApi

            BaseLoader.parseJar(apiConfig.spider, true)
            ParseConfig.init(apiConfig.parses, cfg.parse)

            // 对齐 TV initSite：先解析站点，再按 config.home 设首页（save=false）
            api.initSite()
            val homeSite = resolveHomeSite(cfg.home)
            setHome(homeSite, save = false)

            scope.launch {
                api.sites = Db.Site.update(cfg, api)
                // DB 回写后可能带回相对路径，再解析一次
                api.initSite()
                LiveConfig.syncFromApi()
            }

            WallConfig.init(api.wallpaper)
            log.info("仓库头像: {}", resolvedLogo ?: "(无)")

            log.info("解析配置结束")
            // 对齐 TV：load 完成后 ConfigEvent.vod → RefreshEvent.home
            GlobalAppState.refreshHome()
            onSuccess()
            true
        } catch (e: Exception) {
            log.error("解析配置失败", e)
            SnackBar.postMsg("配置解析失败: ${e.message}", type = SnackBar.MessageType.ERROR)
            onError(e)
            false
        }
    }

    /** 对齐 TV：config.home 匹配 key，找不到则 sites[0]（优先非隐藏） */
    private fun resolveHomeSite(homeKey: String?): Site? {
        if (api.sites.isEmpty()) return null
        val visible = api.sites.filter { !it.isHide() }.ifEmpty { api.sites.toList() }
        if (homeKey.isNullOrBlank()) return visible.firstOrNull()
        return api.sites.find { it.key == homeKey } ?: visible.firstOrNull()
    }

    /** 相对资源按点播基址解析；含 `{` 的模板（如直播 logo）不算仓库头像 */
    private fun resolveConfigAsset(baseUrl: String?, raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value.contains("{")) return null
        if (value.startsWith("http", ignoreCase = true) || value.startsWith("file", ignoreCase = true)) {
            return com.corner.util.io.Urls.convert(value).ifBlank { value }
        }
        val base = baseUrl?.takeIf { it.isNotBlank() } ?: return value
        return com.corner.util.io.Urls.convert(base, value).ifBlank { value }
    }

    /** 部分仓库 JSON 结构不规范时，kotlinx 可能丢字段，用正则兜底取根级字符串 */
    private fun extractRootString(raw: String, key: String): String? {
        val matches = Regex(""""$key"\s*:\s*"([^"]+)"""").findAll(raw).map { it.groupValues[1] }.toList()
        return matches.lastOrNull { !it.contains("{") }
    }

    /**
     * 对齐 TV 公开 setHome：写 DB key + RefreshEvent.home()
     */
    fun setHome(home: Site?) {
        setHome(home, save = true)
    }

    /**
     * 对齐 TV private setHome(config, site, save)：
     * - save=false：解析配置恢复首页，只改内存
     * - save=true：用户换站，持久化 key 并刷新首页
     */
    fun setHome(home: Site?, save: Boolean) {
        val site = home ?: Site.get("", "")
        GlobalAppState.home.value = site
        val cfg = api.cfg
        if (cfg != null && site.key.isNotBlank()) {
            api.cfg = cfg.copy(home = site.key)
            if (save) {
                scope.launch {
                    Db.Config.setHome(cfg.url, ConfigType.SITE.ordinal, site.key)
                }
            }
        }
        if (save) {
            GlobalAppState.refreshHome()
        }
    }

    fun setParse(parse: Parse) {
        ParseConfig.setParse(parse)
    }

    fun getSpider(site: Site): Spider {
        val jar = site.jar?.takeIf { it.isNotBlank() } ?: api.spider
        // 防御竞态：首页若早于 initSite，这里再解析一次相对路径
        val resolvedApi = parseApi(site.api, api.url)
        if (resolvedApi != site.api) site.api = resolvedApi
        return BaseLoader.getSpider(site.key, site.api, site.ext ?: "", jar)
    }

    fun getSite(key: String): Site? {
        return api.sites.find { it.key == key }
    }

    fun setRecent(site: Site) {
        api.recent = site.key
        val jar = site.jar?.takeIf { it.isNotBlank() } ?: api.spider
        BaseLoader.setRecent(site.key, site.api, jar)
    }

    fun parseExt(ext: String): String {
        var currentExt = ext
        var attempts = 0
        val maxAttempts = 5

        while (attempts < maxAttempts) {
            when {
                StringUtils.isBlank(currentExt) -> return currentExt
                currentExt.startsWith("file") -> {
                    val path = Urls.convert(currentExt)
                    val file = Paths.get(path)
                    if (!Files.exists(file)) {
                        log.warn("parseExt 本地文件不存在，保留原 ext: {}", path)
                        return ext
                    }
                    return Files.readString(file)
                }
                currentExt.endsWith(".js") || currentExt.endsWith(".json") || currentExt.endsWith(".txt") -> {
                    val newExt = Urls.convert(api.url ?: "", currentExt)
                    if (newExt == currentExt) return currentExt // 无变化时终止
                    currentExt = newExt
                }

                else -> return currentExt
            }
            attempts++
        }
        log.warn("parseExt 超过最大重试，保留原 ext: {}", ext)
        return ext
    }

    fun parseApi(str: String, base: String? = null): String {
        if (StringUtils.isBlank(str)) return ""
        if (str.startsWith("http") || str.startsWith("file")) return str
        // js/py 相对路径按配置基址解析（如 ./js/drpy2.min.js → config 同级），不要相对 spider.jar
        if (str.endsWith(".js") || str.endsWith(".py")) {
            val resolveBase = sequenceOf(base, api.url, api.spider)
                .mapNotNull { it?.takeIf { u -> u.isNotBlank() } }
                .firstOrNull { it.startsWith("http") || it.startsWith("file") }
                ?: base?.takeIf { it.isNotBlank() }
                ?: api.url.orEmpty()
            if (resolveBase.isNotBlank()) {
                val resolved = Urls.convert(resolveBase, str)
                if (resolved.isNotBlank()) return resolved
            }
        }
        return str
    }

    /**
     * Proxy
     */
    fun initProxy() {
        Http.setProxyHosts(getRuleByName("proxy")?.hosts)
    }

    /**
     * Doh
     */
    fun initDoh() {
        val dohEnabled = SettingStore.getSettingItem(SettingType.DOH_ENABLED).toBoolean()
        if (dohEnabled) {
            val serverName = SettingStore.getSettingItem(SettingType.DOH_SERVER)
            val doh = Doh.defaultDoh().find { it.name == serverName }
            doh?.let { Http.setDoh(it) }
        }
    }

    /**
     * AdBlocker
     */
    fun initAdBlocker(adDomains: List<String>) {
        if (adDomains.isNotEmpty()) {
            // 使用 Http 类中的单例拦截器
            val interceptor = Http.getAdDomainInterceptor()
            interceptor.setAdDomains(adDomains)
            log.info("Initialized with ${adDomains.size} ad domains")
        } else {
            log.debug("No ad domains configured")
        }
    }

    fun getRuleByName(name: String): Rule? {
        return api.rules.find { i -> i.name == name }
    }

    private fun getData(str: String, isJson: Boolean): String? {
        try {
            if (StringUtils.isBlank(str)) {
                log.debug("getData: 输入字符串为空, isJson={}", isJson)
                return ""
            }
            if (isJson) {
                // 如果已经是JSON字符串，直接返回，不需要再次解析
                log.debug("getData: 使用JSON模式，字符串长度={}", str.length)
                return str
            } else if (str.startsWith("http")) {
                log.debug("getData: 从URL获取配置: {}", str)
                return Http.get(str, connectTimeout = 60, readTimeout = 60)
                    .execute()
                    .use { response ->
                        val body = response.body.string()
                        log.debug("getData: 从URL获取成功，长度={}", body.length)
                        body
                    }
            } else if (str.startsWith("file")) {
                val file = Urls.convert(str).toPath().toFile()
                if (!file.exists()) {
                    log.debug("getData: 文件不存在: {}", str)
                    return ""
                }
                return Files.readString(file.toPath())
            }
        } catch (e: Exception) {
            SnackBar.postMsg("获取配置失败: " + e.message, type = SnackBar.MessageType.ERROR)
            log.error("获取配置失败", e)
            return ""
        }
        return ""
    }

}

fun Api.init() {
    ApiConfig.initProxy()
    ApiConfig.initDoh()
    ApiConfig.initAdBlocker(api.ads)
    initSite()
}

fun Api.initSite() {
    if (sites.isEmpty()) return
    for (site in sites) {
        try {
            // 对齐 TV：站点未指定 jar 时继承配置 spider
            if (site.jar.isNullOrBlank() && spider.isNotBlank()) {
                site.jar = spider
            }
            // js/py 相对路径相对配置 URL；csp 不走相对解析
            site.api = ApiConfig.parseApi(site.api, url)
            site.ext = ApiConfig.parseExt(site.ext ?: "")
        } catch (e: Exception) {
            log.warn("初始化站点失败，已跳过: key={} name={} err={}", site.key, site.name, e.message)
        }
    }
    // 首页站点只在 parseConfig 根据 cfg.home 设置一次。
    // 切勿在此默认切到第一个站：会先触发加载，随后 setHome 正确源时又因 homeLoaded 跳过。
}