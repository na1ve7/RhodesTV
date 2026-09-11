package com.rhodes.tv

data class StreamLine(
    val url: String,
    val ua: String? = null,
    val referer: String? = null
)

data class Channel(
    val key: String,
    val name: String,
    val group: String,
    val logo: String? = null,
    val tvgId: String? = null,
    /** 频道号（云端规范库下发；用于组内排序与左侧数字方块）。null = 上游未提供，按顺序补位 */
    var chno: Int? = null,
    /** 云端标记「暂无可用线路」的占位频道：保留在列表里灰显，不参与播放 */
    var dead: Boolean = false,
    val lines: MutableList<StreamLine> = mutableListOf()
) {
    /** 是否有真正可播放的线路（dead:// 是占位协议） */
    val playable: Boolean get() = lines.any { !it.url.startsWith(DEAD_SCHEME) }

    companion object {
        const val DEAD_SCHEME = "dead://"
    }
}

data class EpgProgramme(
    val channelId: String,
    val title: String,
    val start: Long,
    val stop: Long,
    val desc: String? = null
)
