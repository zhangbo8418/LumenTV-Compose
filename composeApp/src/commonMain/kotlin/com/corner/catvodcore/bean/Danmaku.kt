package com.corner.catvodcore.bean

import com.corner.util.json.Jsons
import kotlinx.serialization.Serializable

@Serializable
data class Danmaku(
    val name: String? = null,
    val url: String? = null,
) {
    fun displayName(): String = name?.takeIf { it.isNotBlank() } ?: url.orEmpty()
}
