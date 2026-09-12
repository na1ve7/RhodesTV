package com.rhodes.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自定义 DNS（DoH）单测。
 *
 * 核心硬约束：**DoH 坏了也必须能上网**——订阅拉取、OTA 检查、播放地址解析都依赖 DNS，
 * 所以这里重点验证 FallbackDns 的「失败回退 + 连续失败熔断 + 成功清零」三件事，
 * 以及开关关闭时严格退回 Dns.SYSTEM（= v1.9 行为，绝不引入回归）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DohTest {

    /** 永远成功的假解析器（记录调用次数） */
    private class OkDns(private val ip: String = "1.2.3.4") : Dns {
        val calls = AtomicInteger(0)
        override fun lookup(hostname: String): List<InetAddress> {
            calls.incrementAndGet()
            return listOf(InetAddress.getByName(ip))
        }
    }

    /** 永远失败的假解析器 */
    private class BadDns : Dns {
        val calls = AtomicInteger(0)
        override fun lookup(hostname: String): List<InetAddress> {
            calls.incrementAndGet()
            throw UnknownHostException("boom")
        }
    }

    /** 返回空列表（语义同失败） */
    private class EmptyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> = emptyList()
    }

    /** 可通过 fail 开关手动控制失败/成功的假解析器 */
    private class ToggleDns : Dns {
        val calls = AtomicInteger(0)

        @Volatile
        var fail = true

        @Volatile
        var ip = "9.9.9.9"

        override fun lookup(hostname: String): List<InetAddress> {
            calls.incrementAndGet()
            if (fail) throw UnknownHostException("boom")
            return listOf(InetAddress.getByName(ip))
        }
    }

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun primary_success_is_used_and_fallback_untouched() {
        val ok = OkDns("1.1.1.1")
        val fb = OkDns("2.2.2.2")
        val r = FallbackDns(ok, fb).lookup("example.com")
        assertEquals("1.1.1.1", r[0].hostAddress)
        assertEquals(0, fb.calls.get())
    }

    @Test
    fun failure_falls_back_instead_of_breaking_network() {
        val bad = BadDns()
        val fb = OkDns("5.6.7.8")
        val r = FallbackDns(bad, fb).lookup("example.com")
        assertEquals("5.6.7.8", r[0].hostAddress)
        assertEquals(1, bad.calls.get())
        assertEquals(1, fb.calls.get())
    }

    @Test
    fun empty_result_counts_as_failure() {
        val fb = OkDns("7.7.7.7")
        assertEquals("7.7.7.7", FallbackDns(EmptyDns(), fb).lookup("x.com")[0].hostAddress)
        assertEquals(1, fb.calls.get())
    }

    @Test
    fun cooldown_after_three_consecutive_failures() {
        val bad = BadDns()
        val fb = OkDns()
        val dns = FallbackDns(bad, fb)
        repeat(3) { dns.lookup("h$it") }
        assertEquals("前 3 次应真的去试 primary", 3, bad.calls.get())
        repeat(5) { dns.lookup("h$it") }
        assertEquals("熔断冷却期内不得再调 primary", 3, bad.calls.get())
        assertEquals(8, fb.calls.get())
    }

    @Test
    fun success_resets_failure_counter_and_delays_trip() {
        val t = ToggleDns()
        val fb = OkDns("3.3.3.3")
        val dns = FallbackDns(t, fb)
        dns.lookup("h1")                                          // 失败 1
        dns.lookup("h2")                                          // 失败 2（未达阈值 3）
        t.fail = false
        assertEquals("成功后应直接用 primary 结果", "9.9.9.9", dns.lookup("h3")[0].hostAddress)
        t.fail = true
        dns.lookup("h4")
        dns.lookup("h5")
        dns.lookup("h6")                                          // 再连失败 3 次才该熔断
        assertEquals("成功后失败计数必须清零，否则这里会提前熔断", 6, t.calls.get())
        dns.lookup("h7")
        assertEquals("第 3 次连续失败后就该进冷却，不再打扰 primary", 6, t.calls.get())
        assertEquals(6, fb.calls.get())
    }

    @Test
    fun endpoint_table_and_defaults() {
        assertEquals("doh.pub", Doh.DEFAULT_ENDPOINT)
        assertEquals(3, Doh.ENDPOINTS.size)
        assertEquals(listOf("doh.pub", "alidns", "360"), Doh.NAMES)
        assertTrue(Doh.ENDPOINTS.all { it.url.startsWith("https://") && it.url.contains("dns-query") })
        assertTrue("每个端点都必须自带硬编码自举 IP，否则 DoH 会死循环", Doh.ENDPOINTS.all { it.bootstrap.isNotEmpty() })
        assertEquals("https://dns.alidns.com/dns-query", Doh.endpoint("alidns").url)
        assertEquals("未知端点名应回落到默认端点", Doh.DEFAULT_ENDPOINT, Doh.endpoint("不存在").name)
        assertEquals(Doh.DEFAULT_ENDPOINT, Doh.endpoint(null).name)
    }

    @Test
    fun switch_off_uses_system_dns_exactly_as_before() {
        val c = ctx()
        Prefs.setDohEnabled(c, false)
        assertSame("关闭时必须是 Dns.SYSTEM 本体（v1.9 行为）", Dns.SYSTEM, Doh.dns(c))
        Prefs.setDohEnabled(c, true)   // 复原，避免影响同 JVM 的其它测试
    }

    @Test
    fun switch_on_wraps_doh_with_fallback() {
        val c = ctx()
        Prefs.setDohEnabled(c, true)
        val dns = Doh.dns(c)
        assertTrue("开启时必须是带熔断回退的包装实现", dns is FallbackDns)
        assertFalse(dns === Dns.SYSTEM)
    }
}
