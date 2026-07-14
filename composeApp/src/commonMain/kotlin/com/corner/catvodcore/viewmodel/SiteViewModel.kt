package com.corner.catvodcore.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.corner.catvodcore.bean.Site
import com.corner.catvodcore.bean.Vod
import com.corner.catvodcore.bean.Vod.Companion.setVodFlags
import com.corner.catvodcore.bean.Collect
import com.corner.catvodcore.bean.Result
import com.corner.catvodcore.bean.Url
import com.corner.player.PlaySource
import com.corner.util.download.DownloadUrlResolver
import com.corner.catvodcore.bean.add
import com.corner.catvodcore.bean.v
import com.corner.catvodcore.config.ApiConfig
import com.corner.util.net.Http
import com.corner.util.json.Jsons
import com.corner.util.net.Utils
import com.corner.util.copyAdd
import com.corner.util.scope.createCoroutineScope
import com.github.catvod.crawler.Spider
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import okhttp3.Call
import okhttp3.Headers.Companion.toHeaders
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import com.corner.ui.nav.data.DialogState
import com.corner.ui.nav.data.DialogState.changeDialogState
import com.corner.ui.nav.data.ViewModelState
import com.corner.ui.scene.SnackBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private val log = LoggerFactory.getLogger("SiteViewModel")

object SiteViewModel {
    private val _state = MutableStateFlow(ViewModelState())
    val state: MutableStateFlow<ViewModelState> = _state
    val result: MutableState<Result> by lazy { mutableStateOf(Result()) }
    val detail: MutableState<Result> by lazy { mutableStateOf(Result()) }
    val player: MutableState<Result> by lazy { mutableStateOf(Result()) }
    val search: MutableState<CopyOnWriteArrayList<Collect>> =
        mutableStateOf(CopyOnWriteArrayList(listOf(Collect.all())))
    val quickSearch: MutableState<CopyOnWriteArrayList<Collect>> =
        mutableStateOf(CopyOnWriteArrayList(listOf(Collect.all())))

    /**
     * 使用SupervisorJob确保单个任务失败不影响其他任务
     * 注意: 由于是全局单例,此scope不会自动取消,需手动管理
     */
    private val supervisorJob = SupervisorJob()
    val viewModelScope = createCoroutineScope(Dispatchers.IO)

    /**
     * 取消所有正在进行的任务
     * 应在应用退出或需要重置时调用
     */
    fun cancelAll() {
        supervisorJob.cancelChildren()
    }

    fun clearSpecialVideoLink() {
        _state.update { it.copy(isSpecialVideoLink = false) }
        changeDialogState(false)
        DialogState.dismissPngDialog()
    }

    fun getSearchResultActive(): Collect {
        return search.value.first { it.activated.value }
    }

    fun homeContent(): Result {
        val site: Site = GlobalAppState.home.value
        result.value = Result()
        try {
            when (site.type) {
                3 -> {
                    val spider = ApiConfig.getSpider(site)
                    val homeContent = spider.homeContent(true)
                    ApiConfig.setRecent(site)
                    val rst: Result = decodeResultOrEmpty(homeContent)
                    if (rst.list.isNotEmpty()) result.value = rst
                    // homeVod 失败不应抹掉已成功的分类/筛选
                    val homeVideoContent = runCatching { spider.homeVideoContent() }
                        .onFailure { log.warn("homeVideoContent 失败 site={}: {}", site.name, it.message) }
                        .getOrNull()
                    rst.list.addAll(decodeResultOrEmpty(homeVideoContent).list)
                    result.value = rst.also { this.result.value = it }
                }

                4 -> {
                    val params: MutableMap<String, String> = mutableMapOf()
                    params["filter"] = "true"
                    val homeContent = call(site, params, false)
                    result.value = Jsons.decodeFromString<Result>(homeContent).also { this.result.value = it }
                }

                else -> {
                    val homeContent: String =
                        Http.newCall(site.api, site.header.toHeaders()).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("Unexpected code $response")
                            val body = response.body
                            body.string()
                        }
                    fetchPic(site, Jsons.decodeFromString<Result>(homeContent)).also { result.value = it }
                }
            }
        } catch (e: IllegalStateException) {
            if (e.message?.contains("JCEF", ignoreCase = true) == true ||
                e.message?.contains("内嵌浏览器", ignoreCase = true) == true ||
                e.message?.contains("Playwright", ignoreCase = true) == true
            ) {
                throw e
            }
            log.error("home Content site:{}", site.name, e)
            return Result(false)
        } catch (e: Exception) {
            log.error("home Content site:{}", site.name, e)
            return Result(false)
        }
        result.value.list.forEach { it.site = site }
        return result.value
    }

    fun detailContent(key: String, id: String): Result? {
        DialogState.resetBrowserChoice()

        changeDialogState(false)
        _state.update { it.copy(isSpecialVideoLink = false) }

        val site: Site = ApiConfig.api.sites.find { it.key == key } ?: return null
        var rst = Result()
        try {
            if (site.type == 3) {
                val spider: Spider = ApiConfig.getSpider(site)
                val detailContent = spider.detailContent(listOf(id))
                ApiConfig.setRecent(site)
                rst = Jsons.decodeFromString<Result>(detailContent)
                if (rst.list.isNotEmpty()) rst.list[0].setVodFlags()
                detail.value = rst
            } else if (site.key.isEmpty() && site.name.isEmpty() && key == "push_agent") {
                val vod = Vod()
                vod.vodId = id
                vod.vodName = id
                vod.vodPic = "https://pic.rmb.bdstatic.com/bjh/1d0b02d0f57f0a42201f92caba5107ed.jpeg"
                val rs = Result()
                rs.list = mutableListOf(vod)
                detail.value = rs
            } else {
                val params: MutableMap<String, String> =
                    mutableMapOf()
                params["ac"] = if (site.type == 0) "videolist" else "detail"
                params["ids"] = id
                val detailContent = call(site, params, true)
                log.debug("detail: $detailContent")
                rst = Jsons.decodeFromString<Result>(detailContent)
                if (rst.list.isNotEmpty()) rst.list[0].setVodFlags()
                detail.value = rst
            }
        } catch (e: Exception) {
            log.error("${site.name} detailContent 失败: {}", e.message, e)
            SnackBar.postMsg(
                "${site.name}: ${e.message ?: "详情加载失败"}",
                type = SnackBar.MessageType.ERROR
            )
            return null
        }
        rst.list.forEach { it.site = site }

        return rst
    }

    /**
     * 获取视频播放信息并处理播放链接
     * @param key 站点唯一标识
     * @param flag 播放标识（区分不同播放源）
     * @param id 视频唯一标识
     * @return Pair对象，第一个元素为Result对象，第二个元素表示是否为特殊链接
     */
    fun playerContent(key: String, flag: String, id: String): Result? {
        if (key == "push_agent") {
            return try {
                _state.update { it.copy(isSpecialVideoLink = false) }
                changeDialogState(false)
                val result = Result().apply {
                    url = Url().add(id)
                    parse = 0
                    this.flag = flag
                }
                PlaySource.fetch(result)
                resolvePlayerUrl(result)
                result
            } catch (e: Exception) {
                log.error("push_agent playerContent error: flag=$flag, id=$id", e)
                null
            }
        }

        val site = ApiConfig.getSite(key) ?: return null

        return try {
            // 重置特殊链接标志位
            _state.update { it.copy(isSpecialVideoLink = false) }
            changeDialogState(false)

            val rawResult = when (site.type) {
                3 -> handleType3Site(site, flag, id)        // 爬虫类型站点
                4 -> handleType4Site(site, flag, id)        // 参数请求类型站点
                else -> handleOtherTypeSite(site, flag, id) // 其他类型站点
            }

            rawResult.let { result ->
                if (result.header.isNullOrEmpty()) {
                    result.header = site.header
                } else if (site.header.isNotEmpty()) {
                    result.header = site.header + result.header!!
                }
                if (StringUtils.isNotBlank(flag)) result.flag = flag
                if (site.type == 3) result.key = key // 仅类型3需要key
                PlaySource.fetch(result)
                resolvePlayerUrl(result)
                return result
            }

        } catch (e: Exception) {
            log.error("Site [${site.name}] (key: $key) playerContent error. Flag: $flag, ID: $id", e)
            null
        }
    }

    /**
     * 处理「类型3（爬虫类型）」站点的差异化逻辑
     */
    private fun handleType3Site(site: Site, flag: String, id: String): Result {
        val spider = ApiConfig.getSpider(site)
        val playerContentStr = spider.playerContent(flag, id, ApiConfig.api.flags.toList())
        ApiConfig.setRecent(site) // 类型3特有：记录最近访问站点
        return Jsons.decodeFromString<Result>(playerContentStr) // 解析为Result
    }

    /**
     * 处理「类型4（参数请求类型）」站点的差异化逻辑
     */
    private fun handleType4Site(site: Site, flag: String, id: String): Result {
        // 类型4特有：构建请求参数
        val params = mutableMapOf(
            "play" to id,
            "flag" to flag
        )
        val playerContentStr = call(site, params, true) // 调用参数请求接口
        return Jsons.decodeFromString<Result>(playerContentStr) // 解析为Result
    }

    /**
     * 处理「其他类型」站点的差异化逻辑
     */
    private fun handleOtherTypeSite(site: Site, flag: String, id: String): Result {
        // 其他类型特有：初始化URL并处理JSON类型链接
        var url = Url().add(id)
        val urlType = Url(id).parameters["type"]
        if (urlType == "json" && StringUtils.isNotBlank(id)) {
            // 请求并解析JSON类型的真实链接
            val responseBody = Http.newCall(id, site.header.toHeaders()).execute().use { response ->
                if (!response.isSuccessful) {
                    log.error("获取JSON链接失败，状态码: ${response.code}")
                    return@use ""
                }
                response.body.string()
            }
            if (StringUtils.isNotBlank(responseBody)) {
                url = Jsons.decodeFromString<Result>(responseBody).url
            }
        }

        // 构建基础Result对象
        return Result().apply {
            this.url = url
            this.playUrl = site.playUrl
            // 其他类型特有：对齐 TV Sniffer — 非直链媒体格式则强制解析
            this.parse = if (com.corner.util.VideoSniffer.isVideoFormat(id) &&
                StringUtils.isBlank(site.playUrl)
            ) {
                0
            } else {
                1
            }
            if (StringUtils.isNotBlank(flag)) {
                this.flag = flag
            }
        }
    }

    /**
     * 对齐 TV SiteApi.playerContent：仅做轻量 URL 归一化，不做同步 M3U8 下载/过滤。
     */
    private fun resolvePlayerUrl(result: Result) {
        val resolved = DownloadUrlResolver.resolve(result.url.v())
        if (resolved.isNotBlank() && resolved != result.url.v()) {
            result.url = Url().add(resolved)
            log.debug("归一化播放地址: {}", resolved)
        }
    }

    /**
     * 根据站点和关键词进行搜索操作，支持快速搜索模式
     *
     * @param site 搜索使用的站点信息
     * @param keyword 搜索的关键词
     * @param quick 是否为快速搜索模式
     */
    fun searchContent(site: Site, keyword: String, quick: Boolean) {
        try {
            // 检查站点类型是否为 3
            if (site.type == 3) {
                val spider: Spider = ApiConfig.getSpider(site)
                val searchContent = spider.searchContent(keyword, quick)
            log.debug("search: " + site.name + "," + searchContent)
            val result = Jsons.decodeFromString<Result>(searchContent)
            post(site, result, quick)
        } else {
            // 非类型 3 的站点，构建搜索请求参数
            val params = mutableMapOf<String, String>()
            params["wd"] = keyword
            params["quick"] = quick.toString()
            val searchContent = call(site, params, true)
            log.debug(site.name + "," + searchContent)
            val result = Jsons.decodeFromString<Result>(searchContent)
            post(site, fetchPic(site, result), quick)
        }
    } catch (e: Exception) {
        log.error("{} search error: {}", site.name, e.message, e)
    }
    }


    /**
     * 根据指定站点、关键词和页码进行搜索操作，并将搜索结果存储在 `result` 状态中。
     *
     * @param site 搜索使用的站点信息
     * @param keyword 搜索的关键词
     * @param page 搜索的页码
     */
    @Suppress("unused")
    fun searchContent(site: Site, keyword: String, page: String) {
        try {
            if (site.type == 3) {
                val spider: Spider = ApiConfig.getSpider(site)
                val searchContent = spider.searchContent(keyword, false, page)
                log.debug(site.name + "," + searchContent)
                val rst = Jsons.decodeFromString<Result>(searchContent)
                for (vod in rst.list) vod.site = site
                result.value = rst
            } else {
                val params = mutableMapOf<String, String>()
                params["wd"] = keyword
                params["pg"] = page
                val searchContent = call(site, params, true)
                log.debug(site.name + "," + searchContent)
                val rst: Result = fetchPic(site, Jsons.decodeFromString<Result>(searchContent))
                for (vod in rst.list) vod.site = site
                result.value = rst
            }
        } catch (e: Exception) {
            log.error("${site.name} searchContent error", e)
        }
    }


    /**
     * 根据站点 key、分类 ID、页码、过滤标志和扩展参数获取分类内容
     *
     * @param key 站点的唯一标识
     * @param tid 分类的 ID
     * @param page 请求的页码
     * @param filter 是否启用过滤
     * @param extend 扩展参数，包含额外的请求信息
     * @return 包含分类内容的 Result 对象，若出错则返回表示失败的 Result 对象
     */
    fun categoryContent(
        key: String,
        tid: String,
        page: String,
        filter: Boolean,
        extend: HashMap<String, String>
    ): Result {
        log.info("categoryContent key:{} tid:{} page:{} filter:{} extend:{}", key, tid, page, filter, extend)
        val site: Site = ApiConfig.getSite(key) ?: return Result(false)
        try {
            if (site.type == 3) {
                val spider: Spider = ApiConfig.getSpider(site)
                val categoryContent = spider.categoryContent(tid, page, filter, extend)
                ApiConfig.setRecent(site)
                result.value = Jsons.decodeFromString<Result>(categoryContent)
                if (isEmptyResult(result.value)) {
                    log.warn("type3 cate is Empty: {}", categoryContent)
                }
                // 获取封面图片
                fetchPic(site, result.value)
            } else {
                val params = mutableMapOf<String, String>()
                if (site.type == 1 && extend.isNotEmpty()) params["f"] = Jsons.encodeToString(extend)
                else if (site.type == 4) params["ext"] = Utils.base64(Jsons.encodeToString(extend))
                params["ac"] = if (site.type == 0) "videolist" else "detail"
                params["t"] = tid
                params["pg"] = page
                val categoryContent = call(site, params, true)
                result.value = Jsons.decodeFromString<Result>(categoryContent)
                if (isEmptyResult(result.value)) {
                    log.warn("type${site.type} cate is Empty: {}", categoryContent)
                }
                // 获取封面图片
                fetchPic(site, result.value)
            }
        } catch (e: Exception) {
            log.error("${site.name} category error", e)
            result.value = Result(false)
        }
        result.value.list.forEach { it.site = site }
        return result.value
    }


    private fun post(site: Site, result: Result, quick: Boolean) {
        if (result.list.isEmpty()) {
            return
        }
        for (vod in result.list) vod.site = site
        if (quick) {
            search.value = quickSearch.value.copyAdd(Collect.create(result.list))
            if (quickSearch.value.isEmpty()) {
                search.value = quickSearch.value.copyAdd(Collect.all())
            }
            quickSearch.value[0].list.addAll(result.list)
        } else {
            search.value = search.value.copyAdd(Collect.create(result.list))
            if (search.value.isEmpty()) {
                search.value = search.value.copyAdd(Collect.all())
            }
            search.value[0].list.addAll(result.list)
        }
    }

    fun clearSearch() {
        search.value.clear()
        search.value.add(Collect.all())
    }

    fun clearQuickSearch() {
        quickSearch.value.clear()
        quickSearch.value.add(Collect.all())
    }
}


private fun call(site: Site, params: MutableMap<String, String>, limit: Boolean): String {
    val call: Call = if (fetchExt(site, params, limit).length <= 1000) {
        Http.newCall(site.api, site.header.toHeaders(), params)
    } else {
        Http.newCall(site.api, site.header.toHeaders(), Http.toBody(params))
    }

    return call.execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response")
        }

        val body = response.body

        body.string()
    }
}

private fun fetchExt(site: Site, params: MutableMap<String, String>, limit: Boolean): String {
    var extend: String = site.ext
    if (extend.startsWith("http")) extend = fetchExt(site)
    if (limit && extend.length > 1000) extend = extend.take(1000)
    if (extend.isNotEmpty()) params["extend"] = extend
    return extend
}

private fun fetchExt(site: Site): String {
    return Http.newCall(site.ext, site.header.toHeaders()).execute().use { res ->
        if (res.code != 200) return@use ""
        res.body.string().also { site.ext = it }
    }
}

private fun fetchPic(site: Site, result: Result): Result {
    if (result.list.isEmpty() || StringUtils.isNotBlank((result.list[0].vodPic))) return result
    val ids = ArrayList<String>()
    for (item in result.list) ids.add(item.vodId)
    val params: MutableMap<String, String> = mutableMapOf()
    params["ac"] = if (site.type == 0) "videolist" else "detail"
    params["ids"] = StringUtils.join(ids, ",")
    val response: String =
        Http.newCall(site.api, site.header.toHeaders(), params).execute().use { resp ->
            if (!resp.isSuccessful) {
                log.error("获取视频图片失败，状态码: ${resp.code}")
                return@use ""
            }
            resp.body.string()
        }
    result.list.clear()
    result.list.addAll(Jsons.decodeFromString<Result>(response).list)
    return result
}

private fun isEmptyResult(result: Result): Boolean {
    return result.list.isEmpty() && 
           result.types.isEmpty() && 
           result.filters.isEmpty() &&
           result.url.values.isEmpty()
}

/** py/js 可能返回 null、空串或非对象；避免整页 home 被 homeVod 拖垮 */
private fun decodeResultOrEmpty(raw: String?): Result {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "null" || text == "undefined") return Result()
    return try {
        Jsons.decodeFromString<Result>(text)
    } catch (e: Exception) {
        log.warn("Result JSON 解析失败，按空结果处理: {}", e.message)
        Result()
    }
}
