package com.corner.catvodcore.bean

import com.corner.util.json.Jsons
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.Base64

@Serializable
data class Parse(
    var name: String,
    var type: Int,
    var url: String,
    var ext: Ext? = null
) {
    fun isEmpty(): Boolean = type == 0 && url.isBlank()

    fun extUrl(): String {
        val index = url.indexOf('?')
        val extObj = ext
        if (extObj == null || extObj.isEmpty() || index == -1) return url
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(Jsons.encodeToString(extObj).toByteArray())
        return url.substring(0, index + 1) + "cat_ext=$encoded&" + url.substring(index + 1)
    }

    fun mixMap(): HashMap<String, String> = hashMapOf(
        "type" to type.toString(),
        "ext" to (ext?.let { Jsons.encodeToString(it) } ?: "{}"),
        "url" to url,
    )

    companion object {
        fun god(): Parse = Parse(name = "超级", type = 4, url = "")
    }
}

@Serializable
data class Ext(
    val flag: List<String>? = null,
    val header: Map<String, String>? = null,
) {
    fun isEmpty(): Boolean = flag.isNullOrEmpty() && header.isNullOrEmpty()
}
