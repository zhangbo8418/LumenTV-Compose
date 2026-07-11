package com.corner.util.update

import kotlinx.serialization.Serializable

@Serializable
data class VersionInfo(
    val version: String,
    val windows: PlatformInfo? = null,
    val linux: PlatformInfo? = null,
    /** 兼容旧字段：默认指向当前主流 mac 包（arm64） */
    val mac: PlatformInfo? = null,
    val mac_arm64: PlatformInfo? = null,
    val mac_amd64: PlatformInfo? = null,
    val updater_url: String? = null
)

@Serializable
data class PlatformInfo(
    val download_url: String,
    val msi_url: String? = null,
    val deb_url: String? = null,
    val dmg_url: String? = null
)
