package com.rhodes.tv

import android.content.Context
import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * DoH（DNS over HTTPS）接入 —— 只做两件事：构造一个可回退的 [Dns]，交给两个全局 OkHttpClient。
 *
 * 设计要点：
 * 1. **自举防死循环**：DoH 请求本身走独立的 bootstrapClient（系统 DNS）+ bootstrapDnsHosts(硬编码 IP)，
 *    绝不让 DoH 去解析 DoH 自己的域名（否则网络一断就递归到超时）。
 * 2. **必须自己写回退**：OkHttp 的 Dns.lookup 抛异常不会自动回落系统 DNS。
 * 3. 拿不到 Context / 开关关闭 / 构造异常 → 一律 [Dns.SYSTEM]，保持 v1.9 行为不变。
 *
 * Context 来源：全局 OkHttpClient 是 lazy 单例、没有 Context 参数，因此这里接收可空 Context，
 * 由调用方传 Prefs.appContext()（Prefs.get() 首次被调用时自动记下的 applicationContext）。
 */
object Doh {

    private const val TAG = "RhodesDoh"

    /** 默认端点名（与 Prefs.dohEndpoint 的默认值一致） */
    const val DEFAULT_ENDPOINT = "doh.pub"

    /** 一个 DoH 端点：展示名 + 请求地址 + 自举 IP（用于解析端点自身域名） */
    data class Endpoint(val name: String, val url: String, val bootstrap: List<String>)

    val ENDPOINTS: List<Endpoint> = listOf(
        Endpoint("doh.pub", "https://doh.pub/dns-query", listOf("119.29.29.29", "1.12.12.12")),
        Endpoint("alidns", "https://dns.alidns.com/dns-query", listOf("223.5.5.5", "223.6.6.6")),
        Endpoint("360", "https://doh.360.cn/dns-query", listOf("101.226.4.6"))
    )

    /** 设置页可选的端点名（顺序固定） */
    val NAMES: List<String> = ENDPOINTS.map { it.name }

    /** 按名字取端点，未知名字回落默认端点（doh.pub） */
    fun endpoint(name: String?): Endpoint =
        ENDPOINTS.firstOrNull { it.name == name } ?: ENDPOINTS[0]

    /**
     * 构造要挂到 OkHttpClient.Builder 上的 Dns。
     * 返回 [FallbackDns]（DoH 优先、系统 DNS 兜底）；任何前置条件不满足都返回 [Dns.SYSTEM]。
     */
    fun dns(ctx: Context?): Dns {
        if (ctx == null) return Dns.SYSTEM
        val enabled = try {
            Prefs.dohEnabled(ctx)
        } catch (e: Exception) {
            false
        }
        if (!enabled) return Dns.SYSTEM

        val ep = try {
            endpoint(Prefs.dohEndpoint(ctx))
        } catch (e: Exception) {
            ENDPOINTS[0]
        }

        return try {
            // 自举客户端：只用系统 DNS，绝不能再挂 DoH（否则解析 DoH 域名会递归）
            val bootstrapClient = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()

            val hosts = ep.bootstrap.mapNotNull { ip ->
                try {
                    InetAddress.getByName(ip)   // 字面量 IP，不做 DNS 查询
                } catch (e: Exception) {
                    null
                }
            }

            val builder = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(ep.url.toHttpUrl())
            if (hosts.isNotEmpty()) builder.bootstrapDnsHosts(hosts)

            FallbackDns(builder.build())
        } catch (t: Throwable) {
            Log.w(TAG, "DoH init failed, fallback to system DNS: " + t.message)
            Dns.SYSTEM
        }
    }
}

/**
 * 先试 [primary]（DoH），异常时回退 [fallback]（默认系统 DNS）。
 *
 * 熔断：连续失败 [MAX_FAILS] 次进入 [COOLDOWN_MS] 冷却，冷却期内直接走 fallback（不再等 DoH 超时）；
 * 任一成功立刻清零计数。计数用 [AtomicInteger] + `@Volatile` 时间戳，线程安全。
 */
class FallbackDns(
    private val primary: Dns,
    private val fallback: Dns = Dns.SYSTEM
) : Dns {

    private val fails = AtomicInteger(0)

    @Volatile
    private var cooldownUntil = 0L

    override fun lookup(hostname: String): List<InetAddress> {
        if (System.currentTimeMillis() < cooldownUntil) return fallback.lookup(hostname)
        return try {
            val r = primary.lookup(hostname)
            if (r.isEmpty()) throw UnknownHostException("empty result: $hostname")
            fails.set(0)
            r
        } catch (e: Exception) {
            if (fails.incrementAndGet() >= MAX_FAILS) {
                cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                fails.set(0)
            }
            fallback.lookup(hostname)
        }
    }

    companion object {
        /** 连续失败阈值 */
        const val MAX_FAILS = 3

        /** 熔断冷却时长：5 分钟 */
        const val COOLDOWN_MS = 5 * 60 * 1000L
    }
}
