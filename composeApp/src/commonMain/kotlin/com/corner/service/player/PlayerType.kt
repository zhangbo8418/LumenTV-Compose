package com.corner.service.player

enum class PlayerType(
    val display: String,
    val id: String
) {
    Innie("内部", "innie"),
    Outie("外部", "outie");

    companion object {
        fun getById(id: String): PlayerType {
            return when (id.lowercase()) {
                Innie.id, "web" -> Innie // 旧版「浏览器」播放器已移除，回落内置
                Outie.id -> Outie
                else -> Outie
            }
        }
    }
}