package com.corner.catvodcore.bean

import kotlinx.serialization.Serializable

@Serializable
data class Depot(
    val url: String = "",
    val name: String? = null,
) {
    fun displayName(): String = name?.takeIf { it.isNotBlank() } ?: url
}
