package com.corner.util

import com.corner.catvodcore.bean.Rule
import com.corner.catvodcore.config.RuleConfig
import java.net.URI

object VideoSniffer {
    private val defaultSniffer = Regex(
        """https?://[^\s]{12,}\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)(?:\?.*)?|https?://.*?video/tos[^\s]*|rtmp:[^\s]+"""
    )

    fun isVideoFormat(url: String): Boolean {
        if (url.isBlank()) return false
        val rule = findRule(url)
        rule.exclude.orEmpty().forEach { exclude ->
            if (url.contains(exclude)) return false
            runCatching {
                if (Regex(exclude).containsMatchIn(url)) return false
            }
        }
        rule.regex.orEmpty().forEach { regex ->
            if (url.contains(regex)) return true
            runCatching {
                if (Regex(regex).containsMatchIn(url)) return true
            }
        }
        if (url.contains("url=http") || url.contains("v=http") || url.contains(".html")) return false
        return defaultSniffer.containsMatchIn(url)
    }

    fun isAdHost(host: String): Boolean {
        if (host.isBlank()) return false
        return RuleConfig.getAds().any { containOrMatch(host, it) }
    }

    /** 对齐 TV Sniffer.getScript：按 hosts 规则返回自动点击脚本 */
    fun getScript(url: String): List<String> = findRule(url).script.orEmpty()

    private fun findRule(url: String): Rule {
        val uri = runCatching { URI(url) }.getOrNull() ?: return Rule.empty()
        val nestedHost = runCatching {
            uri.query?.split("&")?.firstOrNull { it.startsWith("url=") }
                ?.removePrefix("url=")
                ?.let { URI(it).host }
        }.getOrNull()
        val hosts = listOfNotNull(uri.host, nestedHost).joinToString(",")
        if (hosts.isBlank()) return Rule.empty()
        for (rule in RuleConfig.getRules()) {
            for (host in rule.hosts) {
                if (containOrMatch(hosts, host)) return rule
            }
        }
        return Rule.empty()
    }

    private fun containOrMatch(text: String, pattern: String): Boolean {
        if (pattern.contains("*") || pattern.contains("(") || pattern.contains("[") || pattern.contains("^")) {
            return runCatching { text.contains(Regex(pattern, RegexOption.IGNORE_CASE)) }.getOrDefault(false)
        }
        return text.contains(pattern, ignoreCase = true)
    }
}
