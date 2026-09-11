package com.rhodes.tv

/** 运行期状态：供本机 HTTP 接口与 UDP 广播上报给手机端 */
object TvState {

    @Volatile
    var currentName: String = ""
    @Volatile
    var currentGroup: String = ""
    @Volatile
    var currentIndex: Int = -1
    @Volatile
    var currentLine: Int = 1
    @Volatile
    var totalLines: Int = 1
    @Volatile
    var playing: Boolean = false
    @Volatile
    var lastError: String = ""
    @Volatile
    var lastPlayAt: Long = 0L
    @Volatile
    var lastRefreshAt: Long = 0L
    @Volatile
    var lastRefreshResult: String = ""

    val startedAt: Long = System.currentTimeMillis()

    fun onPlay(ch: Channel, index: Int) {
        currentName = ch.name
        currentGroup = if (ch.group.isNullOrBlank()) "\u672a\u5206\u7ec4" else ch.group
        currentIndex = index
        totalLines = ch.lines.size
        currentLine = 1
        playing = true
        lastError = ""
        lastPlayAt = System.currentTimeMillis()
    }

    fun onError(msg: String) {
        playing = false
        lastError = msg
    }

    fun uptimeSec(): Long = (System.currentTimeMillis() - startedAt) / 1000
}
