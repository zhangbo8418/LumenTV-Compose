package com.corner.catvodcore.loader

import com.corner.util.net.Utils
import com.github.catvod.crawler.Spider
import org.apache.commons.lang3.StringUtils
import java.io.ByteArrayInputStream

object BaseLoader {
    private fun isJs(api: String): Boolean = api.contains(".js")
    private fun isPy(api: String): Boolean = api.contains(".py")
    private fun isCsp(api: String): Boolean = api.startsWith("csp_")

    fun clear() {
        JarLoader.clear()
        PlatformSpiderLoader.clearJsPy()
    }

    fun getSpider(key: String, api: String, ext: String, jar: String): Spider {
        return when {
            isPy(api) -> PlatformSpiderLoader.getPySpider(key, api, ext, jar)
            isJs(api) -> PlatformSpiderLoader.getJsSpider(key, api, ext, jar)
            isCsp(api) -> JarLoader.getSpider(key, api, ext, jar)
            else -> Spider()
        }
    }

    fun setRecent(key: String, api: String, jar: String?) {
        when {
            isJs(api) -> PlatformSpiderLoader.setRecentJs(key)
            isPy(api) -> PlatformSpiderLoader.setRecentPy(key)
            isCsp(api) -> JarLoader.setRecentJar(jar)
        }
    }

    fun proxy(params: Map<String, String>): Array<Any>? {
        // 爬虫侧 Proxy.adjustPort 探测本机端口，不依赖 jar 是否已加载
        if (params["do"] == "ck") {
            return arrayOf(
                200,
                "text/plain; charset=utf-8",
                ByteArrayInputStream("ok".toByteArray(Charsets.UTF_8)),
            )
        }
        params["siteKey"]?.takeIf { it.isNotBlank() }?.let { siteKey ->
            val site = com.corner.catvodcore.config.ApiConfig.getSite(siteKey) ?: return null
            return getSpider(site.key, site.api, site.ext ?: "", site.jar ?: "").proxy(params)
        }
        return when (params["do"]) {
            "js" -> PlatformSpiderLoader.jsProxy(params)
            "py" -> PlatformSpiderLoader.pyProxy(params)
            else -> JarLoader.proxyInvoke(params)
        }
    }

    fun parseJar(jar: String, recent: Boolean) {
        if (StringUtils.isBlank(jar)) return
        val key = Utils.md5(jar)
        JarLoader.loadJar(key, jar)
        if (recent) JarLoader.setRecentJar(jar)
    }

    fun dex(jar: String?): ClassLoader? {
        if (StringUtils.isBlank(jar)) return null
        return JarLoader.getClassLoader(jar!!)
    }

    fun jsonExt(key: String, jxs: LinkedHashMap<String, String>, url: String): String? {
        return JarLoader.jsonExt(key, jxs, url)
    }

    fun jsonExtMix(
        flag: String,
        key: String,
        name: String,
        jxs: LinkedHashMap<String, HashMap<String, String>>,
        url: String,
    ): String? {
        return JarLoader.jsonExtMix(flag, key, name, jxs, url)
    }
}
