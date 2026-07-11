package com.corner.catvodcore.config

import com.corner.catvodcore.bean.Parse
import com.corner.catvodcore.enum.ConfigType
import com.corner.database.Db
import com.corner.util.scope.createCoroutineScope
import kotlinx.coroutines.launch

object ParseConfig {
    private val scope = createCoroutineScope()
    private var selected: Parse = Parse("", 0, "")
    private var items: List<Parse> = emptyList()

    fun init(parses: Collection<Parse>, savedName: String?) {
        val list = parses.distinctBy { it.name }.toMutableList()
        if (list.isNotEmpty()) {
            list.add(0, Parse.god())
        }
        items = list
        selected = when {
            !savedName.isNullOrBlank() -> list.find { it.name == savedName } ?: list.firstOrNull() ?: emptyParse()
            else -> list.firstOrNull() ?: emptyParse()
        }
    }

    fun clear() {
        items = emptyList()
        selected = emptyParse()
    }

    fun hasParse(): Boolean = items.isNotEmpty()

    fun getParse(): Parse = selected

    fun getParse(name: String): Parse = items.find { it.name == name } ?: emptyParse()

    fun getParses(): List<Parse> = items

    fun getParses(type: Int): List<Parse> = items.filter { it.type == type }

    fun getParses(type: Int, flag: String?): List<Parse> {
        val typed = getParses(type)
        if (flag.isNullOrBlank()) return typed
        val filtered = typed.filter { item ->
            val flags = item.ext?.flag.orEmpty()
            flags.isNotEmpty() && flags.contains(flag)
        }
        return filtered.ifEmpty { typed }
    }

    fun setParse(parse: Parse) {
        selected = parse
        val cfg = ApiConfig.api.cfg ?: return
        val url = ApiConfig.api.url ?: cfg.url ?: return
        scope.launch {
            Db.Config.setParse(url, ConfigType.SITE.ordinal, parse.name)
        }
    }

    fun typeLabel(type: Int): String = when (type) {
        0 -> "Web"
        1 -> "JSON"
        2 -> "扩展"
        3 -> "混合"
        4 -> "超级"
        else -> "未知"
    }

    private fun emptyParse(): Parse = Parse("", 0, "")
}
