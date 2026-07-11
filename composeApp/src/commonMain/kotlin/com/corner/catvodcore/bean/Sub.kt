package com.corner.catvodcore.bean

import kotlinx.serialization.Serializable

@Serializable
data class Sub(
    val name: String = "",
    val url: String = "",
    val lang: String = "",
    val format: String = "",
    val flag: Int = 0,
) {
    fun label(): String {
        if (name.isNotBlank()) return name
        if (lang.isNotBlank()) return lang
        return url.substringAfterLast('/').ifBlank { "字幕" }
    }
}
