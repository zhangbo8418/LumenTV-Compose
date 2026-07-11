package com.corner.catvodcore.loader

import com.github.catvod.crawler.Spider

actual object PlatformSpiderLoader {
    actual fun getJsSpider(key: String, api: String, ext: String, jar: String): Spider =
        JsLoader.getSpider(key, api, ext, jar)

    actual fun getPySpider(key: String, api: String, ext: String, jar: String): Spider =
        PyLoader.getSpider(key, api, ext, jar)

    actual fun clearJsPy() {
        JsLoader.clear()
        PyLoader.clear()
    }

    actual fun setRecentJs(key: String) = JsLoader.setRecent(key)

    actual fun setRecentPy(key: String) = PyLoader.setRecent(key)

    actual fun recycleRecentPy() = PyLoader.recycleRecent()

    actual fun jsProxy(params: Map<String, String>): Array<Any>? = JsLoader.proxy(params)

    actual fun pyProxy(params: Map<String, String>): Array<Any>? = PyLoader.proxy(params)
}
