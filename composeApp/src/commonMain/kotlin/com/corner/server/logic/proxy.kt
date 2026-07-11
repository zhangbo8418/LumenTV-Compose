package com.corner.server.logic

import com.corner.catvodcore.loader.BaseLoader

fun proxy(params: Map<String, String>): Array<Any>? {
    return BaseLoader.proxy(params)
}
