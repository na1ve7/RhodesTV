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

        // ---- 本地 m3u 真源: 60 个频道, 3 个分组 ----
        val sb = StringBuilder("#EXTM3U\n")
        for (i in 1..60) {
            val g = if (i <= 20) "新闻" else if (i <= 40) "央视" else "地方"
            sb.append("#EXTINF:-1 tvg-id=\"c$i\" group-title=\"$g\",频道$i\n")
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
        probe("groups_loaded", navCount >= 4, "navList.adapter.count=" + navCount + " (全部+3组+设置=5)")

        // ---- 长按 OK = 收藏 ----
        val lcl = list.onItemLongClickListener
        probe("longclick_wired", lcl != null, "onItemLongClickListener!=null -> " + (lcl != null))
        lcl?.onItemLongClick(list, list.getChildAt(0), 0, 0L)
        shadowOf(Looper.getMainLooper()).idle()
        probe("long_click_dpad_center_maps_toggleFav", Prefs.isFav(ctx, "频道1"), "Prefs.isFav(频道1)=" + Prefs.isFav(ctx, "频道1"))
        val navCount2 = nav.adapter?.count ?: 0
        val firstRow = nav.adapter?.getView(1, null, nav)
        val navName1 = firstRow?.findViewById<TextView>(R.id.tvNavName)?.text?.toString() ?: ""
        probe("fav_group_pinned", navCount2 >= 5 && navName1.contains("收藏"),
            "navCount=" + navCount2 + " nav[1]='" + navName1 + "'")

        // 再长按一次 = 取消收藏
        lcl?.onItemLongClick(list, list.getChildAt(0), 0, 0L)
        shadowOf(Looper.getMainLooper()).idle()
        probe("long_click_idempotent_untoggle", !Prefs.isFav(ctx, "频道1"), "第二次长按后 isFav=" + Prefs.isFav(ctx, "频道1"))

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

        srv.close()
        // 收尾: 销毁 Activity 并清空状态, 避免污染同 JVM 的其他测试类
        try { controller.pause().stop().destroy() } catch (e: Exception) { }
        Prefs.get(ctx).edit().clear().commit()
        assertEquals("UI实测失败项: " + fails, 0, fails.size)
    }
}
