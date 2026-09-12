package com.rhodes.tv

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.time.Duration

/** 独立 UI 流程实测：真启动 MainActivity，喂本地 m3u，验证长按OK收藏/⭐置顶/数字键多位数/菜单/返回。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityUiTest {

    private val fails = ArrayList<String>()

    private fun probe(id: String, cond: Boolean, detail: String) {
        println("UIFLOW|" + id + "|" + (if (cond) "PASS" else "FAIL") + "|" + detail)
        if (!cond) fails.add(id + ": " + detail)
    }

    private fun key(code: Int) = KeyEvent(KeyEvent.ACTION_DOWN, code)

    @Test
    fun ui_flow_long_press_fav_number_keys_menu_back() {
        val ctx = RuntimeEnvironment.getApplication()
        Prefs.get(ctx).edit().clear().commit()

        // ---- 本地 m3u 真源: 60 个频道 ----
        // 频道名带真实特征（用于验证「按名称重分类」），上游 group-title 故意写成乱的，且每 2 个频道一组
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 1..60) {
            val name = when {
                i == 1 -> "频道1"          // 无特征名 → 其他频道
                i <= 20 -> "CCTV$i 央视频道$i"   // → 央视频道
                i <= 40 -> "湖南卫视$i"          // → 卫视频道
                else -> "辽宁频道$i"              // → 地方频道
            }
            val g = if (i % 2 == 0) "上游乱组A" else "上游乱组B"
            sb.append("#EXTINF:-1 tvg-id=\"c$i\" group-title=\"$g\",$name\n")
            sb.append("http://127.0.0.1:1/$i.m3u8\n")
        }
        // ---- 迷你 HTTP 服务器(ServerSocket 手写, 不依赖 com.sun) ----
        val srv = ServerSocket(0)
        val body = sb.toString().toByteArray(Charsets.UTF_8)
        val th = Thread {
            while (!srv.isClosed) {
                try {
                    val c = srv.accept()
                    val ins = c.getInputStream()
                    val buf = ByteArray(1024)
                    ins.read(buf)
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: audio/x-mpegurl\r\nContent-Length: " + body.size + "\r\nConnection: close\r\n\r\n"
                    val os = c.getOutputStream()
                    os.write(head.toByteArray(Charsets.UTF_8))
                    os.write(body)
                    os.flush()
                    c.close()
                } catch (e: Exception) {
                    if (!srv.isClosed) println("UIFLOW|srv_err|" + e.message)
                }
            }
        }
        th.isDaemon = true
        th.start()
        val url = "http://127.0.0.1:" + srv.localPort + "/p.m3u"
        Prefs.save(ctx, Prefs.KEY_SUBS, listOf(url))
        println("UIFLOW|serving|" + url)

        // ---- 真启动 Activity ----
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        val list = a.findViewById<ListView>(R.id.channelList)
        val nav = a.findViewById<ListView>(R.id.navList)
        val menuRoot = a.findViewById<View>(R.id.menuRoot)
        val videoDim = a.findViewById<View>(R.id.videoDim)
        val toast = a.findViewById<TextView>(R.id.tvToast)

        var count = 0
        for (k in 0 until 150) {
            shadowOf(Looper.getMainLooper()).idle()
            count = list.adapter?.count ?: 0
            if (count > 0) break
            Thread.sleep(200)
        }
        probe("channels_loaded", count == 60, "list.adapter.count=" + count + " (expect 60)")
        val navCount = nav.adapter?.count ?: 0
        // v1.9: 「我的收藏」恒常驻（空也显示）→ 左栏由 6 行变 7 行
        probe("groups_loaded", navCount == 7, "navList.adapter.count=" + navCount + " (全部+收藏+央视频道+卫视频道+地方频道+其他频道+设置)")

        val navTexts = (0 until (nav.adapter?.count ?: 0))
            .map { i -> nav.adapter?.getView(i, null, nav)?.findViewById<TextView>(R.id.tvNavName)?.text?.toString() ?: "" }
        probe("reclassified_groups", listOf("央视频道", "卫视频道", "地方频道", "其他频道").all { navTexts.contains(it) },
            "nav=" + navTexts)

        // ---- 长按 OK = 收藏 ----
        val lcl = list.onItemLongClickListener
        probe("longclick_wired", lcl != null, "onItemLongClickListener!=null -> " + (lcl != null))
        lcl?.onItemLongClick(list, list.getChildAt(0), 0, 0L)
        shadowOf(Looper.getMainLooper()).idle()
        probe("long_click_dpad_center_maps_toggleFav", Prefs.isFav(ctx, "频道1"), "Prefs.isFav(频道1)=" + Prefs.isFav(ctx, "频道1"))
        val navCount2 = nav.adapter?.count ?: 0
        val firstRow = nav.adapter?.getView(1, null, nav)
        val navName1 = firstRow?.findViewById<TextView>(R.id.tvNavName)?.text?.toString() ?: ""
        probe("fav_group_pinned", navCount2 == 7 && navName1.contains("收藏"),
            "navCount=" + navCount2 + " nav[1]='" + navName1 + "' (收藏组恒在 index 1)")

        // 再长按一次 = 取消收藏
        lcl?.onItemLongClick(list, list.getChildAt(0), 0, 0L)
        shadowOf(Looper.getMainLooper()).idle()
        probe("long_click_idempotent_untoggle", !Prefs.isFav(ctx, "频道1"), "第二次长按后 isFav=" + Prefs.isFav(ctx, "频道1"))
        // v1.9 需求1: 取消掉最后一个收藏后，「我的收藏」分组仍常驻（只是列表为空）
        val navCount3 = nav.adapter?.count ?: 0
        probe("fav_group_stays_when_empty", navCount3 == 7, "取消最后一个收藏后 navCount=" + navCount3 + " (收藏组不应消失)")

        // ---- 数字键多位数: 5 0 -> 第 50 台 ----
        a.onKeyDown(KeyEvent.KEYCODE_5, key(KeyEvent.KEYCODE_5))
        a.onKeyDown(KeyEvent.KEYCODE_0, key(KeyEvent.KEYCODE_0))
        shadowOf(Looper.getMainLooper()).idle()
        probe("numkey_pending_prompt", toast.text.toString().contains("50"), "tvToast='" + toast.text + "'")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        probe("numkey_50_jumped", list.selectedItemPosition == 49, "selectedItemPosition=" + list.selectedItemPosition + " (expect 49)")

        // 越界数字: 9 9 9 -> 提示没有第 999 个
        a.onKeyDown(KeyEvent.KEYCODE_9, key(KeyEvent.KEYCODE_9))
        a.onKeyDown(KeyEvent.KEYCODE_9, key(KeyEvent.KEYCODE_9))
        a.onKeyDown(KeyEvent.KEYCODE_9, key(KeyEvent.KEYCODE_9))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        probe("numkey_out_of_range_msg", toast.text.toString().contains("999"), "tvToast='" + toast.text + "'")

        // ---- 返回键收起菜单 ----
        a.onKeyDown(KeyEvent.KEYCODE_BACK, key(KeyEvent.KEYCODE_BACK))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        probe("back_hides_panel", menuRoot.visibility == View.GONE || menuRoot.translationX < -1f,
            "menuRoot.visibility=" + menuRoot.visibility + " translationX=" + menuRoot.translationX)
        // 再按返回 -> 面板已收起, 交回系统(Activity 应进入 finish 流程, 这里只验证不崩溃)
        a.onKeyDown(KeyEvent.KEYCODE_BACK, key(KeyEvent.KEYCODE_BACK))
        shadowOf(Looper.getMainLooper()).idle()
        probe("panel_dim_layer_off", videoDim.visibility == View.GONE || videoDim.alpha < 0.05f,
            "videoDim.visibility=" + videoDim.visibility + " alpha=" + videoDim.alpha)

        // ---- 菜单键短按 = 呼出; 长按 = 打开设置页 ----
        a.onKeyDown(KeyEvent.KEYCODE_MENU, key(KeyEvent.KEYCODE_MENU))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        a.onKeyUp(KeyEvent.KEYCODE_MENU, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))
        shadowOf(Looper.getMainLooper()).idle()
        probe("menu_short_press_shows_panel", menuRoot.visibility == View.VISIBLE, "menuRoot.visibility=" + menuRoot.visibility)
        probe("panel_dim_layer_on", videoDim.visibility == View.VISIBLE && videoDim.alpha > 0.30f,
            "videoDim.visibility=" + videoDim.visibility + " alpha=" + videoDim.alpha)

        a.onKeyDown(KeyEvent.KEYCODE_MENU, key(KeyEvent.KEYCODE_MENU))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        shadowOf(Looper.getMainLooper()).idle()
        val nxt = shadowOf(a).nextStartedActivity
        probe("menu_long_press_opens_settings", nxt != null && (nxt.component?.className ?: "").contains("SettingsActivity"),
            "nextStartedActivity=" + (nxt?.component?.className))
        a.onKeyUp(KeyEvent.KEYCODE_MENU, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))

        // ---- v1.9 需求2: 上下移动光标不切台, 必须按 OK 才切 ----
        // 观察量: playChannel() 会写 Prefs["last_ch"]; 纯移动光标绝不写它
        val lastBefore = Prefs.get(ctx).getInt("last_ch", -1)
        val chCount = list.adapter?.count ?: 0
        val target = if (chCount > 5) 5 else 0
        list.setSelection(target)
        shadowOf(Looper.getMainLooper()).idle()
        val lastAfterMove = Prefs.get(ctx).getInt("last_ch", -1)
        probe("cursor_move_no_switch", lastAfterMove == lastBefore,
            "移动光标到 $target 后 last_ch=$lastAfterMove (期望保持 $lastBefore, 不切台)")
        list.onItemClickListener!!.onItemClick(list, null, target, target.toLong())
        shadowOf(Looper.getMainLooper()).idle()
        probe("ok_click_switches", Prefs.get(ctx).getInt("last_ch", -1) == target,
            "按 OK 后 last_ch=" + Prefs.get(ctx).getInt("last_ch", -1) + " (期望 $target)")

        // ---- v1.9 Bug 修复: 「暂时无法播放」覆盖层不得挡住菜单, 且切台/播放成功即撤层 ----
        val statusOv = a.findViewById<View>(R.id.statusBox)
        // 先把面板状态归零(上一段长按菜单后 panelVisible 可能仍为 true)
        a.onKeyDown(KeyEvent.KEYCODE_BACK, key(KeyEvent.KEYCODE_BACK))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        statusOv.visibility = View.VISIBLE            // 模拟失败覆盖层残留
        menuRoot.visibility = View.GONE
        a.onKeyDown(KeyEvent.KEYCODE_MENU, key(KeyEvent.KEYCODE_MENU))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        a.onKeyUp(KeyEvent.KEYCODE_MENU, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))
        shadowOf(Looper.getMainLooper()).idle()
        probe("error_overlay_menu_reachable",
            menuRoot.visibility == View.VISIBLE && statusOv.visibility == View.GONE,
            "menuRoot=" + menuRoot.visibility + " statusBox=" + statusOv.visibility)
        a.onKeyDown(KeyEvent.KEYCODE_BACK, key(KeyEvent.KEYCODE_BACK))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        statusOv.visibility = View.VISIBLE            // 再模拟一次残留
        val t2 = if (chCount > 2) 2 else 0
        list.onItemClickListener!!.onItemClick(list, null, t2, t2.toLong())
        shadowOf(Looper.getMainLooper()).idle()
        probe("error_overlay_cleared_on_switch", statusOv.visibility == View.GONE,
            "切台后 statusBox=" + statusOv.visibility + " (期望 GONE)")

        // ---- v1.9 需求1: 空收藏组右栏留白(无占位项), 左栏收藏组仍常驻 ----
        nav.onItemSelectedListener!!.onItemSelected(nav, null, 0, 0L)   // 确保先不在收藏组
        nav.onItemSelectedListener!!.onItemSelected(nav, null, 1, 1L)   // 显式进入收藏组
        shadowOf(Looper.getMainLooper()).idle()
        val favRows = list.adapter?.count ?: -1
        val navCntAfter = nav.adapter?.count ?: 0
        probe("empty_fav_group_blank", favRows == 0 && navCntAfter == 7,
            "收藏组 channelCount=$favRows (期望 0, 无占位项) navCount=$navCntAfter (期望 7)")

        srv.close()
        // 收尾: 销毁 Activity 并清空状态, 避免污染同 JVM 的其他测试类
        try { controller.pause().stop().destroy() } catch (e: Exception) { }
        Prefs.get(ctx).edit().clear().commit()
        assertEquals("UI实测失败项: " + fails, 0, fails.size)
    }
}
