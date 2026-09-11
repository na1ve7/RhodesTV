package com.rhodes.tv

import android.content.Context
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * UDP 自动发现信标：
 *  - 监听 8766 端口，收到手机发来的 `RHODESTV?` 立即单播回复身份 JSON；
 *  - 同时每 3 秒向 255.255.255.255:8766 广播一次身份 JSON（手机被动监听即可发现）。
 */
class UdpBeacon(private val ctx: Context) {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    val port = 8766

    /** 手机端的发现请求前缀（完整报文 RHODESTV?） */
    val query = "RHODESTV?"

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.broadcast = true
                sock.bind(InetSocketAddress(port))
                sock.soTimeout = 500
                val buf = ByteArray(512)
                val bcast = InetAddress.getByName("255.255.255.255")
                var lastBeat = 0L
                while (running) {
                    val now = System.currentTimeMillis()
                    if (now - lastBeat >= 3000) {
                        lastBeat = now
                        try {
                            val p = json().toByteArray(Charsets.UTF_8)
                            sock.send(DatagramPacket(p, p.size, bcast, port))
                        } catch (e: Exception) {
                        }
                    }
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                        if (text.startsWith("RHODESTV")) {
                            val p = json().toByteArray(Charsets.UTF_8)
                            sock.send(DatagramPacket(p, p.size, pkt.address, pkt.port))
                        }
                    } catch (e: SocketTimeoutException) {
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            } finally {
                try {
                    sock?.close()
                } catch (e: Exception) {
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try {
            thread?.join(1500)
        } catch (e: Exception) {
        }
    }

    private fun json(): String {
        val o = JSONObject()
        o.put("app", "RhodesTV")
        o.put("proto", 1)
        o.put("beacon", true)
        o.put("ip", ConfigServer.localIp())
        o.put("port", 8765)
        o.put("beaconPort", port)
        o.put("device", android.os.Build.MODEL ?: "AndroidTV")
        o.put("channel", TvState.currentName)
        o.put("group", TvState.currentGroup)
        o.put("playing", TvState.playing)
        o.put("channels", ChannelStore.load(ctx).size)
        o.put("favs", Prefs.favSet(ctx).size)
        o.put("uptimeSec", TvState.uptimeSec())
        o.put("ts", System.currentTimeMillis())
        return o.toString()
    }

    companion object {
        @Volatile
        private var inst: UdpBeacon? = null

        /** 全局单例（同一进程只广播/监听一份） */
        fun shared(ctx: Context): UdpBeacon {
            val cur = inst
            if (cur != null) return cur
            synchronized(this) {
                val again = inst
                if (again != null) return again
                val b = UdpBeacon(ctx.applicationContext)
                b.start()
                inst = b
                return b
            }
        }
    }
}
