package com.rhodes.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 对抗性独立验证 (验证者本人编写, 不复用实现者断言).
 * 真跑 ConfigServer(8765 HTTP) + UdpBeacon(8766 UDP) + Prefs/ChannelStore.
 * 每个探测独立 try/catch, 结果以 "VERIFY|id|PASS|note" 打到 stdout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerifyIndependentTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var fails = 0
    private val ids = ArrayList<String>()

    private fun check(id: String, cond: Boolean, note: String) {
        val tag = if (cond) "PASS" else "FAIL"
        if (!cond) fails++
        ids.add("$id=$tag")
        println("VERIFY|$id|$tag|$note")
    }

    private fun probe(id: String, note: String, body: () -> Boolean) {
        try {
            val ok = body()
            check(id, ok, note)
        } catch (e: Throwable) {
            check(id, false, "$note EXCEPTION=${e.javaClass.simpleName}:${e.message}")
        }
    }

    private fun http(method: String, path: String, body: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:8765$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 5000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val s = if (code < 400) c.inputStream else c.errorStream
        val text = try { s?.bufferedReader(Charsets.UTF_8)?.readText() ?: "" } catch (e: Exception) { "" }
        return code to text
    }

    private fun j(s: String): JSONObject = JSONObject(s)

    private fun waitServer() {
        for (i in 1..60) {
            try {
                val (code, txt) = http("GET", "/api/ping")
                if (code == 200 && j(txt).optBoolean("ok")) { println("VERIFY|server_ready|PASS|attempts=$i"); return }
            } catch (e: Exception) { }
            Thread.sleep(250)
        }
        println("VERIFY|server_ready|FAIL|never became ready")
        fails++
    }

    @Test
    fun independentFullVerification() {
        // ---------- 0. 启动真实服务器 ----------
        ConfigServer.shared(ctx)
        waitServer()

        // ---------- 1. GET /api/status 完整字段 ----------
        probe("status_keys", "status must have ok/app/ip/port/now/channels/favs/groups/lastRefreshAt/version", {
            val (code, txt) = http("GET", "/api/status")
            println("VERIFY|status_body|$txt")
            val o = j(txt)
            code == 200 && o.optBoolean("ok") && o.optString("app") == "RhodesTV" &&
                o.has("ip") && o.optInt("port") == 8765 && o.has("now") &&
                o.has("channels") && o.has("favs") && o.has("groups") &&
                o.has("lastRefreshAt") && o.has("version") &&
                o.getJSONObject("now").has("channel") && o.getJSONObject("now").has("playing")
        })

        // ---------- 2. /api/ping /api/groups /api/channels ----------
        probe("ping", "ping ok", { j(http("GET", "/api/ping").second).optBoolean("ok") })
        probe("groups_shape", "groups ok+count+array", {
            val o = j(http("GET", "/api/groups").second)
            o.optBoolean("ok") && o.has("count") && o.has("groups")
        })

        // ---------- 3. ADV: limit=-1 ----------
        probe("adv_limit_neg", "limit=-1 must be coerced to 1", {
            val o = j(http("GET", "/api/channels?limit=-1").second)
            println("VERIFY|adv_limit_neg_body|limit=" + o.optInt("limit"))
            o.optInt("limit") == 1
        })

        // ---------- 4. ADV: limit=999999 (超大) ----------
        probe("adv_limit_huge", "limit=999999 must be coerced to 2000", {
            val o = j(http("GET", "/api/channels?limit=999999").second)
            println("VERIFY|adv_limit_huge_body|limit=" + o.optInt("limit"))
            o.optInt("limit") == 2000
        })

        // ---------- 5. ADV: offset=-5 ----------
        probe("adv_offset_neg", "offset=-5 coerced >=0", {
            val o = j(http("GET", "/api/channels?offset=-5").second)
            o.optInt("offset") == 0
        })

        // ---------- 6. ADV: 空 name ----------
        probe("adv_empty_name", "POST /api/fav without name -> ok:false missing name", {
            val o = j(http("POST", "/api/fav", "action=add").second)
            println("VERIFY|adv_empty_name_body|$o")
            !o.optBoolean("ok") && o.optString("error").contains("name")
        })

        // ---------- 7. ADV: 未知名 toggle + 幂等 ----------
        probe("adv_unknown_name", "unknown channel name toggle works then untoggles", {
            val a = j(http("POST", "/api/fav", "name=NOSUCH_ADVNAME_xyz&action=toggle").second)
            val b = j(http("POST", "/api/fav", "name=NOSUCH_ADVNAME_xyz&action=toggle").second)
            println("VERIFY|adv_unknown_name_body|first=$a second=$b")
            a.optBoolean("ok") && a.optBoolean("fav") && b.optBoolean("ok") && !b.optBoolean("fav")
        })

        // ---------- 8. ADV: add 幂等 (同请求两次) ----------
        probe("adv_add_idempotent", "add same name twice -> fav stays true, count stable", {
            val a = j(http("POST", "/api/fav", "name=IDEM_CH&action=add").second)
            val n1 = a.optInt("favs")
            val b = j(http("POST", "/api/fav", "name=IDEM_CH&action=add").second)
            val n2 = b.optInt("favs")
            println("VERIFY|adv_add_idempotent_body|fav=$a/$b count=$n1/$n2")
            a.optBoolean("fav") && b.optBoolean("fav") && n1 == n2 && Prefs.isFav(ctx, "IDEM_CH")
        })

        // ---------- 9. ADV: remove 幂等 ----------
        probe("adv_remove_idempotent", "remove twice -> fav false, count stable", {
            val a = j(http("POST", "/api/fav", "name=IDEM_CH&action=remove").second)
            val n1 = a.optInt("favs")
            val b = j(http("POST", "/api/fav", "name=IDEM_CH&action=remove").second)
            val n2 = b.optInt("favs")
            println("VERIFY|adv_remove_idempotent_body|fav=$a/$b count=$n1/$n2")
            !a.optBoolean("fav") && !b.optBoolean("fav") && n1 == n2 && !Prefs.isFav(ctx, "IDEM_CH")
        })

        // ---------- 10. ADV: 未知 route ----------
        probe("adv_unknown_route", "/api/does_not_exist -> ok:false unknown api", {
            val o = j(http("GET", "/api/does_not_exist").second)
            println("VERIFY|adv_unknown_route_body|$o")
            !o.optBoolean("ok") && o.optString("error").contains("unknown")
        })

        // ---------- 11. ADV: 未知 action ----------
        probe("adv_unknown_action", "action=bogus behaves as toggle (no crash)", {
            val o = j(http("POST", "/api/fav", "name=ACT_CH&action=bogus").second)
            println("VERIFY|adv_unknown_action_body|$o")
            o.optBoolean("ok") && Prefs.isFav(ctx, "ACT_CH")
        })
        Prefs.toggleFav(ctx, "ACT_CH")

        // ---------- 12. 兼容路由 /api/favorite ----------
        probe("alias_route", "/api/favorite same as /api/fav", {
            val a = j(http("POST", "/api/favorite", "name=ALIAS_CH&action=add").second)
            val listed = j(http("GET", "/api/favorite").second).optString("favs").contains("ALIAS_CH")
            val b = j(http("POST", "/api/favorite", "name=ALIAS_CH&action=remove").second)
            a.optBoolean("fav") && listed && !b.optBoolean("fav")
        })

        // ---------- 13. 分组 order 幂等 ----------
        probe("group_order_idempotent", "order twice -> [新闻,央视] stable", {
            http("POST", "/api/group", "order=%E6%96%B0%E9%97%BB,%E5%A4%AE%E8%A7%86")
            val o = j(http("POST", "/api/group", "order=%E6%96%B0%E9%97%BB,%E5%A4%AE%E8%A7%86").second)
            println("VERIFY|group_order_body|$o")
            o.optBoolean("ok") && o.getJSONArray("order").length() == 2 &&
                Prefs.groupOrder(ctx) == listOf("新闻", "央视")
        })

        // ---------- 14. 分组 hidden 幂等 (覆盖式) ----------
        probe("group_hidden_idempotent", "hidden=地方 twice -> hidden once", {
            http("POST", "/api/group", "hidden=%E5%9C%B0%E6%96%B9")
            val o = j(http("POST", "/api/group", "hidden=%E5%9C%B0%E6%96%B9").second)
            println("VERIFY|group_hidden_body|$o")
            Prefs.isGroupHidden(ctx, "地方") && o.getJSONArray("hidden").length() == 1
        })

        // ---------- 15. 分组 hidden 空串 = 全部显示 ----------
        probe("group_hidden_clear", "hidden= (empty) -> no hidden", {
            val o = j(http("POST", "/api/group", "hidden=").second)
            println("VERIFY|group_hidden_clear_body|$o")
            Prefs.hiddenGroups(ctx).isEmpty() && o.optBoolean("ok")
        })

        // ---------- 16. toggle 分组 ----------
        probe("group_toggle", "toggle=地方 flips", {
            val before = Prefs.isGroupHidden(ctx, "地方")
            j(http("POST", "/api/group", "toggle=%E5%9C%B0%E6%96%B9").second)
            Prefs.isGroupHidden(ctx, "地方") != before
        })
        if (Prefs.isGroupHidden(ctx, "地方")) Prefs.toggleGroupHidden(ctx, "地方")

        // ---------- 17. 收藏按频道名(换线路/刷新源后仍有效) ----------
        probe("fav_by_name_survives_refresh", "fav keyed by name survives channel list change", {
            ChannelStore.save(ctx, listOf(
                Channel(key = "k1", name = "验证频道A", group = "测试组",
                    lines = mutableListOf(StreamLine("http://old/1.m3u8")))
            ))
            Prefs.get(ctx).edit().remove(Prefs.KEY_FAV).commit()
            Prefs.toggleFav(ctx, "验证频道A")
            val listed1 = j(http("GET", "/api/channels?q=%E9%AA%8C%E8%AF%81%E9%A2%91%E9%81%93A").second)
            val fav1 = listed1.getJSONArray("channels").getJSONObject(0).optBoolean("fav")
            // 源刷新: 同名频道但线路全变
            ChannelStore.save(ctx, listOf(
                Channel(key = "k2", name = "验证频道A", group = "测试组",
                    lines = mutableListOf(StreamLine("http://new/9.m3u8")))
            ))
            val listed2 = j(http("GET", "/api/channels?q=%E9%AA%8C%E8%AF%81%E9%A2%91%E9%81%93A").second)
            val fav2 = if (listed2.getJSONArray("channels").length() > 0)
                listed2.getJSONArray("channels").getJSONObject(0).optBoolean("fav") else false
            println("VERIFY|fav_by_name_body|favBefore=$fav1 favAfter=${fav2} favSet=${Prefs.favSet(ctx)}")
            fav1 && fav2 && Prefs.isFav(ctx, "验证频道A")
        })

        // ---------- 18. FAV_GROUP 字面量 ----------
        probe("fav_group_literal", "Prefs.FAV_GROUP == '⭐ 收藏'", {
            println("VERIFY|fav_group_body|${Prefs.FAV_GROUP}")
            Prefs.FAV_GROUP == "\u2b50 \u6536\u85cf"
        })

        // ---------- 19. Prefs 直接幂等 ----------
        probe("prefs_toggle_idempotent", "toggleFav twice returns to original", {
            val a = Prefs.toggleFav(ctx, "PT_X")
            val b = Prefs.toggleFav(ctx, "PT_X")
            a && !b && !Prefs.isFav(ctx, "PT_X")
        })

        // ---------- 20. UDP 8766: RHODESTV? 单播应答 ----------
        probe("udp_query_reply", "UDP 8766 RHODESTV? -> identity JSON unicast", {
            UdpBeacon.shared(ctx)
            Thread.sleep(1000)
            DatagramSocket().use { s ->
                s.soTimeout = 5000
                val q = "RHODESTV?".toByteArray(Charsets.UTF_8)
                s.send(DatagramPacket(q, q.size, InetAddress.getByName("127.0.0.1"), 8766))
                val buf = ByteArray(2048)
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                println("VERIFY|udp_query_body|$text")
                val o = j(text)
                o.optString("app") == "RhodesTV" && o.optInt("port") == 8765 &&
                    o.optInt("beaconPort") == 8766 && o.optBoolean("beacon")
            }
        })

        // ---------- 21. ADV: 非 RHODESTV 报文不应回复 ----------
        probe("adv_udp_garbage_no_reply", "garbage UDP packet -> no reply (timeout)", {
            Thread.sleep(500)
            DatagramSocket().use { s ->
                s.soTimeout = 2000
                val q = "HELLO-NOT-A-BEACON".toByteArray(Charsets.UTF_8)
                s.send(DatagramPacket(q, q.size, InetAddress.getByName("127.0.0.1"), 8766))
                val buf = ByteArray(2048)
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    s.receive(pkt)
                    println("VERIFY|adv_udp_garbage_body|unexpected reply=" +
                        String(pkt.data, 0, pkt.length, Charsets.UTF_8))
                    false
                } catch (e: SocketTimeoutException) {
                    true
                }
            }
        })

        println("VERIFY_SUMMARY|fails=$fails|${ids.joinToString(",")}")
        org.junit.Assert.assertEquals("independent verification failures: " + ids, 0, fails)
    }
}
