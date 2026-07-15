package com.corner.util.settings

import org.apache.logging.log4j.Level
import com.corner.service.player.PlayerType
import com.corner.util.json.Jsons
import com.corner.util.io.Paths
import com.corner.util.m3u8.M3U8FilterConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.io.path.exists

private val log = LoggerFactory.getLogger("Setting")

@Serializable
data class Setting(val id: String, val label: String, var value: String = "")

@Serializable
sealed interface Cache {
    fun getName(): String

    fun add(t: String)
}

@Serializable
class SearchHistoryCache : Cache {

    private val maxSize: Int = 30

    var searchHistoryList: LinkedHashSet<String> = linkedSetOf()
    override fun getName(): String {
        return "searchHistory"
    }

    override fun add(t: String) {
        if (searchHistoryList.size >= maxSize) {
            val list: LinkedHashSet<String> = linkedSetOf()
            list.addAll(searchHistoryList.drop(1))
            searchHistoryList = list
        }
        searchHistoryList.remove(t)
        searchHistoryList.add(t)
    }

    fun getSearchList(): List<String> {
        return searchHistoryList.toList().reversed()
    }

    fun remove(query: String) {
        searchHistoryList.remove(query)
    }

}

@Serializable
class PlayerStateCache : Cache {
    private val map: MutableMap<String, String> = mutableMapOf()
    override fun getName(): String {
        return "playerState"
    }

    override fun add(t: String) {

    }

    fun add(key: String, value: String) {
        map[key] = value
    }

    fun get(key: String): String? {
        return map[key]
    }

}

@Serializable
data class SettingFile(val list: MutableList<Setting>, val cache: MutableMap<String, Cache>)

enum class SettingType(val id: String) {
    PLAYER("player"),
    VOD("vod"),
    LIVE("live"),
    LOG("log"),
    SEARCHHISTORY("searchHistory"),
    PROXY("proxy"),
    THEME("theme"),
    AD_FILTER("adFilter"),
    M3U8_FILTER_CONFIG("m3u8FilterConfig"),
    CRAWLER_SEARCH_TERMS("crawlerSearchTerms"),
    DOH_ENABLED("dohEnabled"),
    DOH_SERVER("dohServer"),
    FPS_MONITOR("fpsMonitor"),
    MINI_PROGRESS_BAR("miniProgressBar"),
    LIVE_ACROSS("liveAcross"),
    LIVE_AUTO_LINE("liveAutoLine"),
    LIVE_INVERT("liveInvert"),
    ARIA2_ENABLED("aria2Enabled"),
    ARIA2_RPC("aria2Rpc"),
    ARIA2_SECRET("aria2Secret"),
    ARIA2_DIR("aria2Dir"),
    DANMAKU_LOAD("danmakuLoad"),
    DANMAKU_AUTO("danmakuAuto"),
    DANMAKU_SHOW("danmakuShow"),
    DANMAKU_API_URL("danmakuApiUrl"),
    DANMAKU_TEXT_SCALE("danmakuTextScale"),
    DANMAKU_DURATION("danmakuDuration"),
    DANMAKU_MAX_ON_SCREEN("danmakuMaxOnScreen"),
    DANMAKU_SPIDER_FIRST("danmakuSpiderFirst");
}

object SettingStore {
    private val defaultList = listOf(
        Setting("vod", "点播", ""),
        Setting("live", "直播", ""),
        Setting("log", "日志级别", Level.INFO.toString()),
        Setting("player", "播放器", "innie#"),
        Setting("proxy", "代理", "false#"),
        Setting("theme", "主题", "system"),
        Setting("adFilter", "广告过滤", "true"),
        Setting("m3u8FilterConfig", "M3U8 过滤配置", ""),
        Setting("crawlerSearchTerms", "爬虫搜索词", "阿甘正传"),
        Setting("dohEnabled", "DoH启用", "false"),
        Setting("dohServer", "DoH服务器", "Tencent"),
        Setting("fpsMonitor", "FPS监控", "false"),
        Setting("miniProgressBar", "迷你进度条", "false"),
        Setting("liveAcross", "直播跨分组换台", "true"),
        Setting("liveAutoLine", "直播失败自动换线路", "true"),
        Setting("liveInvert", "直播换台方向反转", "false"),
        Setting("aria2Enabled", "aria2下载", "false"),
        Setting("aria2Rpc", "aria2 RPC地址", "http://127.0.0.1:6800/jsonrpc"),
        Setting("aria2Secret", "aria2 RPC密钥", ""),
        Setting("aria2Dir", "aria2下载目录", ""),
        Setting("danmakuLoad", "弹幕加载", "true"),
        Setting("danmakuAuto", "弹幕自动搜索", "true"),
        Setting("danmakuShow", "显示弹幕", "true"),
        Setting("danmakuApiUrl", "弹幕API", ""),
        Setting("danmakuTextScale", "弹幕字号", "1.0"),
        Setting("danmakuDuration", "弹幕时长ms", "8000"),
        Setting("danmakuMaxOnScreen", "弹幕最大数量", "80"),
        Setting("danmakuSpiderFirst", "弹幕优先爬虫", "false")
    )

    private var settingFile = SettingFile(mutableListOf(), mutableMapOf())

    init {
        getSettingList()
        getM3U8FilterConfig()
    }

    fun getSettingItem(s: String): String {
        return settingFile.list.find { it.id == s }?.value ?: ""
    }

    fun getSettingItem(type: SettingType): String {
        return getSettingItem(type.id)
    }

    fun getSettingList(): MutableList<Setting> {
        if (settingFile.list.isEmpty()) {
            initSetting()
        }
        return settingFile.list
    }

    fun reset() {
        settingFile = SettingFile(mutableListOf(), mutableMapOf())
        settingFile.list.addAll(defaultList)
        write()
    }

    fun write() {
        try {
            Files.write(Paths.setting(), Jsons.encodeToString(settingFile).toByteArray())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setValue(type: SettingType, s: String) {
        settingFile.list.find { i -> i.id == type.id }?.value = s
        log.info("将${type.id}设置为：${s}")
        write()
    }

    fun doWithCache(func: (MutableMap<String, Cache>) -> Unit) {
        func(settingFile.cache)
        write()
    }

    fun getCache(name: String): Cache? {
        return settingFile.cache[name]
    }

    private fun initSetting() {
        // 初始化设置文件
        val file = Paths.setting()
        if (file.exists() && settingFile.list.isEmpty()) {
            try {
                settingFile = Jsons.decodeFromString<SettingFile>(Files.readString(file))
                if (settingFile.list.size != defaultList.size) {
                    defaultList.forEach { setting ->
                        if (settingFile.list.find { setting.id == it.id } == null) {
                            settingFile.list.add(setting)
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("配置文件解析失败，将重置为默认值: ${e.message}")
                settingFile = SettingFile(mutableListOf(), mutableMapOf())
                settingFile.list.addAll(defaultList)
                Files.write(file, Jsons.encodeToString(settingFile).toByteArray())
                return
            }
        }
        // 初始化缓存
        if (settingFile.list.isEmpty()) {
            settingFile.list.addAll(defaultList)
            Files.write(file, Jsons.encodeToString(settingFile).toByteArray())
        }
    }

    fun getHistoryList(): Set<String> {
        if (settingFile.list.isEmpty()) {
            initSetting()
        }
        val cache = getCache(SettingType.SEARCHHISTORY.id)
        if (cache != null) {
            return (cache as SearchHistoryCache).getSearchList().toSet()
        }
        return setOf()
    }

    fun addSearchHistory(s: String) {
        val cache = getCache(SettingType.SEARCHHISTORY.id)
        if (cache == null) settingFile.cache[SettingType.SEARCHHISTORY.id] = SearchHistoryCache()
        if (s.trim().isNotBlank()) {
            getCache(SettingType.SEARCHHISTORY.id)!!.add(s)
            write()
        }
    }

    fun isAdFilterEnabled(): Boolean {
        return getSettingItem(SettingType.AD_FILTER.id).toBoolean()
    }

    fun setAdFilterEnabled(enabled: Boolean) {
        setValue(SettingType.AD_FILTER, enabled.toString())
    }

    fun getM3U8FilterConfig(): M3U8FilterConfig {
        val configJson = getSettingItem(SettingType.M3U8_FILTER_CONFIG)
        return if (configJson.isBlank()) {
            M3U8FilterConfig()
        } else {
            try {
                val config = Jsons.decodeFromString<M3U8FilterConfig>(configJson)
                config
            } catch (e: Exception) {
                log.error("M3U8FilterConfig 解析失败", e)
                M3U8FilterConfig()
            }
        }
    }

    fun setM3U8FilterConfig(config: M3U8FilterConfig) {
        log.debug("保存 M3U8FilterConfig: {}", config)
        val compactJson = Json { encodeDefaults = true }
        val configJson = compactJson.encodeToString(config)
        setValue(SettingType.M3U8_FILTER_CONFIG, configJson)
    }

    fun deleteSearchHistory(query: String) {
        getCache(SettingType.SEARCHHISTORY.id)?.let { cache ->
            (cache as? SearchHistoryCache)?.remove(query)
            write()
        }
    }
}

data class SettingEnable(
    val isEnabled: Boolean,
    val value: String
) {
    companion object {
        fun default(): SettingEnable {
            return SettingEnable(false, "")
        }
    }
}

/**
 * boolean#字符串 转换为数组
 */
fun String.parseAsSettingEnable(): SettingEnable {
    val split = this.split("#")
    return if (split.size == 1) {
        SettingEnable(split.first().toBoolean(), "")
    } else {
        SettingEnable(split.first().toBoolean(), split.last())
    }
}

fun String.getPlayerSetting(sitePlayerType: String? = ""): List<String> {
    val internalPlayer = this.split("#")
    val rawType = if (StringUtils.isNotBlank(sitePlayerType)) {
        PlayerType.getById(sitePlayerType ?: "").id
    } else {
        internalPlayer.first()
    }
    // 旧版「浏览器」播放器已移除，统一回落内置
    val type = if (rawType == "web") PlayerType.Innie.id else rawType
    return listOf(type, internalPlayer[1])
}