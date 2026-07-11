package com.corner.catvodcore.loader

import com.github.catvod.crawler.Spider

expect object PlatformSpiderLoader {
    fun getJsSpider(key: String, api: String, ext: String, jar: String): Spider
    fun getPySpider(key: String, api: String, ext: String, jar: String): Spider
    fun clearJsPy()
    fun setRecentJs(key: String)
    fun setRecentPy(key: String)
    fun recycleRecentPy()
    fun jsProxy(params: Map<String, String>): Array<Any>?
    fun pyProxy(params: Map<String, String>): Array<Any>?
}
