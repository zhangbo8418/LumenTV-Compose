package com.corner.util.core

import com.corner.catvodcore.bean.Result
import com.corner.catvodcore.bean.isEmpty
import com.corner.catvodcore.bean.v
import com.corner.catvodcore.config.ApiConfig
import com.corner.catvodcore.config.ParseConfig

/**
 * 检查播放结果是否为空（URL 为空）
 */
fun Result.playResultIsEmpty(): Boolean {
    return url.isEmpty()
}

/**
 * 是否需要解析（与 TV Result.needParse 一致）
 */
fun Result.needParse(): Boolean = parse == 1 || jx == 1

/**
 * 是否使用全局解析器（与 TV Result.isUseParse 一致）
 */
fun Result.isUseParse(): Boolean {
    if (!ParseConfig.hasParse()) return false
    return (playUrl.isNullOrBlank() && flag != null && ApiConfig.api.flags.contains(flag)) || jx == 1
}

/**
 * 播放地址拼接（playUrl 前缀 + url）
 */
fun Result.realUrl(): String = playUrl.orEmpty() + url.v()

/**
 * 检查详情结果是否为空
 * 注意：如果 list[0] 本身 isEmpty (无ID或无线路) 但提供了 ID，可能属于 token 验证场景，此处返回 false
 */
fun Result.detailIsEmpty(): Boolean {
    if (list.isEmpty()) return true
    if (list[0].isEmpty()) return false 
    return list[0].vodFlags[0].episodes.isEmpty()
}
