package com.corner.catvodcore.config

import com.corner.catvodcore.bean.Rule

object RuleConfig {
    fun getRules(): List<Rule> = ApiConfig.api.rules.toList()

    fun getAds(): List<String> = ApiConfig.api.ads
}
