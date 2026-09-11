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

    private var player: ExoPlayer? = null
    private var current: Channel? = null
    private var lineIdx = 0
    private var failed = 0
    private val handler = Handler(Looper.getMainLooper())
    private var bufferingSince = 0L

    private val watch = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && current != null) {
                if (p.playbackState == Player.STATE_BUFFERING) {
                    if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - bufferingSince > 15000L) {
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
        current = ch
        lineIdx = if (ch.lines.isEmpty()) 0 else startLine % ch.lines.size
        failed = 0
        openLine()
    }

    fun switchLine(delta: Int) {
        val ch = current ?: return
        if (ch.lines.size <= 1) return
        lineIdx = ((lineIdx + delta) % ch.lines.size + ch.lines.size) % ch.lines.size
        failed = 0
        openLine()
    }

    private fun lineFailed(msg: String) {
        val ch = current ?: return
        failed++
        if (failed >= ch.lines.size || ch.lines.isEmpty()) {
            onAllFailed?.invoke(msg)
            return
        }
        lineIdx = (lineIdx + 1) % ch.lines.size
        openLine()
    }

    private fun openLine() {
        val ch = current ?: return
        val p = player ?: return
        if (ch.lines.isEmpty()) return
        val line = ch.lines[lineIdx % ch.lines.size]
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
