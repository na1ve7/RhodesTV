package com.rhodes.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * IPv6 能力判据单测（纯函数 + 缓存策略）。
 *
 * 硬约束：**绝不能误伤**——未检测/未知 时一律视为「可用」，让 v1.9 的候选行为保持不变；
 * 只有本机实测确认无 v6 时才过滤 v6 字面量线路，且剔空时不做剔除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetInfoTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun v6_literal_urls_are_detected() {
        assertTrue(NetInfo.isV6Literal("http://[2400:3200::1]:8080/live.m3u8"))
        assertTrue(NetInfo.isV6Literal("http://2400:3200::1/live.m3u8"))
        assertTrue(NetInfo.isV6Literal("https://[240e:945:1:0:3::7d]/x"))
        assertTrue(NetInfo.isV6Literal("rtsp://[2409:8a00::1]:554/x"))
    }

    @Test
    fun v4_and_domain_urls_are_not_v6() {
        assertFalse(NetInfo.isV6Literal("http://1.2.3.4:8080/x.m3u8"))
        assertFalse(NetInfo.isV6Literal("https://example.com/live.m3u8"))
        assertFalse(NetInfo.isV6Literal("http://live.example.com:8080/a.ts"))
        assertFalse(NetInfo.isV6Literal(""))
        assertFalse(NetInfo.isV6Literal("not-a-url"))
    }

    @Test
    fun indicesToKeep_drops_only_v6_literals() {
        val urls = listOf("http://[2400::1]/a.m3u8", "http://1.2.3.4/b.m3u8", "https://x.com/c.m3u8")
        assertEquals(listOf(1, 2), NetInfo.indicesToKeep(urls))
    }

    @Test
    fun indicesToKeep_never_empties_the_list() {
        val allV6 = listOf("http://[2400::1]/a.m3u8", "http://[2409::2]/b.m3u8")
        assertEquals("全是 v6 时不得剔除（宁可试也不无台可播）", listOf(0, 1), NetInfo.indicesToKeep(allV6))
        val allV4 = listOf("http://1.1.1.1/a", "http://2.2.2.2/b")
        assertEquals(listOf(0, 1), NetInfo.indicesToKeep(allV4))
        assertEquals(emptyList<Int>(), NetInfo.indicesToKeep(emptyList()))
    }

    @Test
    fun unknown_v6_state_never_filters() {
        val c = ctx()
        Prefs.get(c).edit().remove(Prefs.KEY_V6_OK).remove(Prefs.KEY_V6_AT).apply()
        assertTrue("从未检测 → 视为可用（不得误过滤 v6 线路）", NetInfo.ipv6AvailableCached(c))
    }

    @Test
    fun cached_false_filters_and_expires_back_to_true() {
        val c = ctx()
        Prefs.setV6(c, false)
        assertFalse(NetInfo.ipv6AvailableCached(c))
        // 模拟「11 分钟前的检测结果」→ 过期 → 回到未知(=true)，给用户翻盘机会
        Prefs.get(c).edit().putLong(Prefs.KEY_V6_AT, System.currentTimeMillis() - 11 * 60 * 1000L).apply()
        assertTrue(NetInfo.ipv6AvailableCached(c))
    }

    @Test
    fun cached_true_keeps_v6_lines() {
        val c = ctx()
        Prefs.setV6(c, true)
        assertTrue(NetInfo.ipv6AvailableCached(c))
        assertTrue(Prefs.v6CheckedAt(c) > 0L)
    }
}
