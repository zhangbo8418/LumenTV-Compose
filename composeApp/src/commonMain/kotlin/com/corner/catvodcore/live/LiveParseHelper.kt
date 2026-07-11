package com.corner.catvodcore.live

import com.corner.catvodcore.parse.ParseHelper

object LiveParseHelper {
    suspend fun parse(webUrl: String, headers: Map<String, String>, timeoutMs: Long = 15_000): String? {
        return ParseHelper.parseLive(webUrl, headers, timeoutMs)
    }
}
