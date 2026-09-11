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
    val lines: MutableList<StreamLine> = mutableListOf()
)

data class EpgProgramme(
    val channelId: String,
    val title: String,
    val start: Long,
    val stop: Long,
    val desc: String? = null
)
