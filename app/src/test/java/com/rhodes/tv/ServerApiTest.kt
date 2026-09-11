package com.rhodes.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * 独立实测（JVM 上跑真实 Android 代码）：
 *  1) 真实启动 ConfigServer(8765) 并发 HTTP 请求，校验 JSON 与读写生效；
 *  2) 真实启动 UdpBeacon(8766) 发 RHODESTV? ，校验单播应答；
 *  3) Prefs 收藏/分组顺序/隐藏 的幂等与持久化。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerApiTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun http(method: String, path: String, body: String? = null): String {
        val c = URL("http://127.0.0.1:8765" + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 5000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            c.outputStream.write(body.toByteArray(Charsets.UTF_8))
        }
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        val text = stream.bufferedReader(Charsets.UTF_8).readText()
        assertEquals("http " + method + " " + path + " -> " + code + " body=" + text, 200, code)
        return text
    }

    @Test
    fun httpApiEndpoints() {
        ConfigServer.shared(ctx)
        var status = ""
        for (i in 1..40) {
            try {
                status = http("GET", "/api/status")
                break
            } catch (e: Exception) {
                Thread.sleep(250)
            }
        }
        assertTrue("server not ready", status.isNotEmpty())
        val o = JSONObject(status)
        assertEquals(true, o.getBoolean("ok"))
        assertEquals("RhodesTV", o.getString("app"))
        assertEquals(8765, o.getInt("port"))
        assertTrue(o.has("now") && o.has("channels") && o.has("favs") && o.has("uptimeSec"))
        println("STATUS=" + status)

        assertEquals(true, JSONObject(http("GET", "/api/ping")).getBoolean("ok"))
        assertEquals(false, JSONObject(http("GET", "/api/nope")).getBoolean("ok"))
        val g = JSONObject(http("GET", "/api/groups"))
        assertEquals(true, g.getBoolean("ok"))
        assertTrue(g.getInt("count") >= 0)
        val ch = JSONObject(http("GET", "/api/channels?limit=5&offset=0"))
        assertEquals(true, ch.getBoolean("ok"))

        val addRes = JSONObject(http("POST", "/api/fav", "name=CCTV-1%20%E7%BB%BC%E5%90%88&action=add"))
        assertEquals(true, addRes.getBoolean("fav"))
        assertTrue(Prefs.isFav(ctx, "CCTV-1 综合"))
        assertTrue(JSONObject(http("GET", "/api/fav")).getString("favs").contains("CCTV-1 综合"))
        val delRes = JSONObject(http("POST", "/api/fav", "name=CCTV-1%20%E7%BB%BC%E5%90%88&action=remove"))
        assertEquals(false, delRes.getBoolean("fav"))
        assertTrue(!Prefs.isFav(ctx, "CCTV-1 综合"))

        assertEquals(true, JSONObject(http("POST", "/api/fav", "name=TOGGLE")).getBoolean("fav"))
        assertEquals(false, JSONObject(http("POST", "/api/fav", "name=TOGGLE")).getBoolean("fav"))

        // 兼容路由 /api/favorite + 分组接口 /api/group
        assertEquals(true, JSONObject(http("POST", "/api/favorite", "name=ALIAS&action=add")).getBoolean("fav"))
        assertTrue(Prefs.isFav(ctx, "ALIAS"))
        assertEquals(false, JSONObject(http("POST", "/api/favorite", "name=ALIAS&action=remove")).getBoolean("fav"))

        val go = JSONObject(http("POST", "/api/group", "order=%E6%96%B0%E9%97%BB,%E5%A4%AE%E8%A7%86"))
        assertEquals(true, go.getBoolean("ok"))
        assertEquals(listOf("新闻", "央视"), Prefs.groupOrder(ctx))
        val gh = JSONObject(http("POST", "/api/group", "hidden=%E5%9C%B0%E6%96%B9"))
        assertEquals(true, gh.getBoolean("ok"))
        assertTrue(Prefs.isGroupHidden(ctx, "地方"))
        assertEquals(true, JSONObject(http("POST", "/api/group", "toggle=%E5%9C%B0%E6%96%B9")).getBoolean("ok"))
        assertTrue(!Prefs.isGroupHidden(ctx, "地方"))

        val r = JSONObject(http("POST", "/api/refresh", ""))
        assertTrue(r.has("ok"))
        println("REFRESH=" + r)
    }

    @Test
    fun udpDiscovery() {
        UdpBeacon.shared(ctx)
        Thread.sleep(800)
        DatagramSocket().use { s ->
            s.soTimeout = 5000
            val q = "RHODESTV?".toByteArray(Charsets.UTF_8)
            s.send(DatagramPacket(q, q.size, InetAddress.getByName("127.0.0.1"), 8766))
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            s.receive(pkt)
            val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
            println("UDP_REPLY=" + text)
            val o = JSONObject(text)
            assertEquals("RhodesTV", o.getString("app"))
            assertEquals(8765, o.getInt("port"))
        }
    }

    @Test
    fun prefsFavAndGroupsIdempotent() {
        assertTrue(!Prefs.isFav(ctx, "X1"))
        assertEquals(true, Prefs.toggleFav(ctx, "X1"))
        assertEquals(true, Prefs.isFav(ctx, "X1"))
        assertEquals(false, Prefs.toggleFav(ctx, "X1"))
        assertEquals(false, Prefs.isFav(ctx, "X1"))

        Prefs.toggleFav(ctx, "X1")
        assertEquals(true, Prefs.favSet(ctx).contains("X1"))
        Prefs.toggleFav(ctx, "X1")

        val order = listOf("新闻", "央视", "地方")
        Prefs.saveGroupOrder(ctx, order)
        assertEquals(order, Prefs.groupOrder(ctx))
        assertEquals(order, Prefs.groupOrder(ctx))

        assertEquals(false, Prefs.isGroupHidden(ctx, "地方"))
        assertEquals(true, Prefs.toggleGroupHidden(ctx, "地方"))
        assertEquals(true, Prefs.isGroupHidden(ctx, "地方"))
        assertEquals(false, Prefs.toggleGroupHidden(ctx, "地方"))
        assertEquals(false, Prefs.isGroupHidden(ctx, "地方"))
    }
}
