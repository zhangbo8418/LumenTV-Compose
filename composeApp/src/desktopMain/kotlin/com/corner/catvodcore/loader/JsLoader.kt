package com.corner.catvodcore.loader

import com.fongmi.quickjs.crawler.Loader
import com.fongmi.quickjs.utils.Module
import com.github.catvod.crawler.Spider
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private class JsSpiderAdapter(private val delegate: com.fongmi.quickjs.crawler.Spider) : Spider() {
    init {
        siteKey = delegate.siteKey
    }

    override fun init(extend: String?) {
        delegate.init(extend ?: "")
        siteKey = delegate.siteKey
    }

    override fun homeContent(filter: Boolean): String = delegate.homeContent(filter)

    override fun homeVideoContent(): String = delegate.homeVideoContent()

    override fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>
    ): String = delegate.categoryContent(tid, pg, filter, extend)

    override fun detailContent(ids: List<String?>?): String =
        delegate.detailContent(ids?.filterNotNull()?.map { it } ?: emptyList())

    override fun searchContent(key: String?, quick: Boolean): String =
        delegate.searchContent(key ?: "", quick)

    override fun searchContent(key: String?, quick: Boolean, pg: String?): String =
        delegate.searchContent(key ?: "", quick, pg ?: "1")

    override fun playerContent(flag: String?, id: String?, vipFlags: List<String?>?): String =
        delegate.playerContent(flag ?: "", id ?: "", vipFlags?.filterNotNull() ?: emptyList())

    override fun liveContent(url: String?): String = delegate.liveContent(url ?: "")

    override fun manualVideoCheck(): Boolean = delegate.manualVideoCheck()

    override fun isVideoFormat(url: String?): Boolean = delegate.isVideoFormat(url ?: "")

    override fun proxy(params: Map<String, String>?): Array<Any>? = delegate.proxy(params ?: emptyMap())

    override fun action(action: String?): String? = delegate.action(action ?: "")

    override fun destroy() = delegate.destroy()
}

object JsLoader {
    private val log = LoggerFactory.getLogger("JsLoader")
    private val spiders = ConcurrentHashMap<String, Spider>()
    private val loader = Loader()
    @Volatile
    private var recent: String? = null

    fun clear() {
        spiders.values.forEach { spider ->
            try {
                spider.destroy()
            } catch (e: Exception) {
                log.warn("destroy js spider failed", e)
            }
        }
        Module.get().clear()
        spiders.clear()
        recent = null
    }

    fun setRecent(key: String) {
        recent = key
    }

    fun getSpider(key: String, api: String, ext: String, jar: String): Spider {
        return spiders.computeIfAbsent(key) {
            try {
                val jsSpider = loader.spider(api, BaseLoader.dex(jar))
                jsSpider.siteKey = key
                val adapter = JsSpiderAdapter(jsSpider)
                adapter.init(ext)
                adapter
            } catch (e: Throwable) {
                log.error("load js spider failed: key={} api={}", key, api, e)
                Spider()
            }
        }
    }

    fun proxy(params: Map<String, String>): Array<Any>? {
        val key = recent ?: return null
        val spider = spiders[key] ?: return null
        return try {
            spider.proxy(params)
        } catch (e: Exception) {
            log.error("js proxy failed", e)
            null
        }
    }
}
