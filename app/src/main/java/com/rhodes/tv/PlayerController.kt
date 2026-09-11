package com.rhodes.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@UnstableApi
class PlayerController(private val ctx: Context, private val view: PlayerView) {

    var onLineChanged: ((Int, Int) -> Unit)? = null
    var onAllFailed: ((String) -> Unit)? = null
    var onReady: (() -> Unit)? = null

    /** 全部线路都失败后自动重试时回调（参数=等待秒数） */
    var onRetry: ((Int) -> Unit)? = null

    private var player: ExoPlayer? = null
    private var current: Channel? = null
    private var lineIdx = 0
    /** 线路尝试顺序：好线路在前、近期失败的坏线路垫底（只降权不禁用） */
    private var order: List<Int> = emptyList()
    private var pos = 0
    private var failed = 0
    private var retryRound = 0
    private val handler = Handler(Looper.getMainLooper())
    private var bufferingSince = 0L

    /** 单条线路起播/缓冲最长容忍时间，超过即判失败切下一条 */
    private val bufferTimeoutMs = 10000L

    /** 全部线路都失败后的自动重试轮数（每轮间隔 5 秒，抗瞬时网络抖动） */
    private val maxRetryRound = 2

    private val retryTask = Runnable {
        if (current != null) {
            failed = 0
            pos = 0
            openLine()
        }
    }

    private val watch = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && current != null) {
                if (p.playbackState == Player.STATE_BUFFERING) {
                    if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - bufferingSince > bufferTimeoutMs) {
                        bufferingSince = 0L
                        lineFailed("缓冲超时")
                    }
                } else {
                    bufferingSince = 0L
                }
            }
            handler.postDelayed(this, 2500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            lineFailed(error.errorCodeName)
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                failed = 0
                retryRound = 0
                val ch = current
                if (ch != null) {
                    Prefs.saveLineMem(ctx, ch.name, lineIdx)   // 记住这个频道当前可用的线路
                    ch.lines.getOrNull(lineIdx)?.let { Prefs.clearBad(ctx, it.url) }
                }
                onReady?.invoke()
            }
        }
    }

    fun init() {
        if (player != null) return
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8000, 50000, 1500, 3000)
            .build()
        val p = ExoPlayer.Builder(ctx).setLoadControl(lc).build()
        p.addListener(listener)
        view.player = p
        view.useController = false
        view.keepScreenOn = true
        player = p
        handler.postDelayed(watch, 2500)
    }

    fun lineNo(): Int = lineIdx + 1
    fun lineTotal(): Int = current?.lines?.size ?: 0
    fun channel(): Channel? = current

    fun play(ch: Channel, startLine: Int) {
        init()
        handler.removeCallbacks(retryTask)
        current = ch
        failed = 0
        retryRound = 0
        val n = ch.lines.size
        if (n == 0) {
            order = emptyList()
            pos = 0
            lineIdx = 0
        } else {
            val bad = Prefs.badLines(ctx)
            order = (0 until n).filter { !bad.contains(ch.lines[it].url) } +
                (0 until n).filter { bad.contains(ch.lines[it].url) }
            // startLine < 0 → 用线路记忆（该频道上次成功的线路）
            val want = if (startLine >= 0) startLine else Prefs.lineMem(ctx, ch.name)
            pos = order.indexOf(want).takeIf { it >= 0 } ?: 0
        }
        openLine()
    }

    fun switchLine(delta: Int) {
        val ch = current ?: return
        if (ch.lines.size <= 1) return
        if (order.isEmpty()) order = ch.lines.indices.toList()
        pos = ((pos + delta) % order.size + order.size) % order.size
        failed = 0
        handler.removeCallbacks(retryTask)
        openLine()
    }

    private fun lineFailed(msg: String) {
        val ch = current ?: return
        if (ch.lines.isEmpty()) {
            onAllFailed?.invoke(msg)
            return
        }
        // 记入坏线路：下次打开该频道时它会被排到最后（不是禁用，万一其它线路全挂还要靠它）
        ch.lines.getOrNull(lineIdx)?.let { Prefs.markBad(ctx, it.url) }
        failed++
        if (failed >= ch.lines.size) {
            // 所有线路都试过了 → 隔 5 秒自动重试一轮，仍然全挂才报错
            if (retryRound < maxRetryRound) {
                retryRound++
                failed = 0
                pos = 0
                onRetry?.invoke(5)
                handler.postDelayed(retryTask, 5000L)
            } else {
                onAllFailed?.invoke(msg)
            }
            return
        }
        pos = (pos + 1) % order.size
        openLine()
    }

    private fun openLine() {
        val ch = current ?: return
        val p = player ?: return
        if (ch.lines.isEmpty()) return
        if (order.isEmpty()) order = ch.lines.indices.toList()
        if (pos !in order.indices) pos = 0
        lineIdx = order[pos]
        val line = ch.lines[lineIdx]
        bufferingSince = 0L

        val ds = OkHttpDataSource.Factory(ChannelStore.http)
        val ua = line.ua ?: Prefs.ua(ctx)
        ds.setUserAgent(ua)
        val props = HashMap<String, String>()
        props["User-Agent"] = ua
        val r = line.referer
        if (r != null) props["Referer"] = r
        ds.setDefaultRequestProperties(props)

        val item = MediaItem.Builder()
            .setUri(line.url)
            .setMimeType(if (line.url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null)
            .build()
        try {
            p.setMediaSource(DefaultMediaSourceFactory(ds).createMediaSource(item))
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) {
            lineFailed("源无效")
            return
        }
        onLineChanged?.invoke(lineIdx + 1, ch.lines.size)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        view.player = null
    }
}
