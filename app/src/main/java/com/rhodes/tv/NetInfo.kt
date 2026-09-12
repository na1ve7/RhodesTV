package com.rhodes.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/**
 * IPv6 可用性探测（本机实测，不依赖云端）。
 *
 * 背景：GitHub Actions 云端没有 IPv6，v6 线路能否播放只能由电视端自己实测；
 * 因此本机实测结果缓存到 [Prefs]，播放器构建候选线路时据此过滤 v6 线路。
 *
 * 判定「可用」需同时满足：
 *  1. 本机存在全局 IPv6 地址（排除回环 / 链路本地 / 通配）；
 *  2. 能对 [TCP_TARGETS] 中任一目标建立 TCP 连接（任一成功即算通过，
 *     避免单个目标被墙/下线被误判为「本机无 v6」）。
 *
 * 设计约束：所有函数均不做 DNS 解析（目标用 IPv6 字面量），
 * 未知/从未检测 → 一律视为「可用」，绝不误过滤线路。
 */
object NetInfo {

    /** 实测结果缓存有效期：10 分钟 */
    const val TTL_MS = 10 * 60 * 1000L

    /**
     * TCP 连通性探测目标（IPv6 字面量，不做 DNS）。
     * 前 4 个为国内主流 CDN 的 IPv6 地址（公开 AAAA，接受 TCP 80），
     * 末位放一个海外地址兜底（被墙也无妨，只要有一个成功即通过）。
     */
    private val TCP_TARGETS = listOf(
        "240e:945:1:0:3::7d",                 // 淘宝 CDN
        "240e:ff:e020:99b:0:ff:b099:cff1",    // 百度
        "240e:e1:a800:120::76",               // 腾讯
        "240e:946:6000::1b",                  // 爱奇艺
        "2606:4700:4700::1111"                // Cloudflare 1.1.1.1（兜底）
    )

    private const val TCP_PORT = 80
    /** 单目标连接超时（需求：≤1500ms） */
    private const val TCP_TIMEOUT_MS = 1500

    // ------------------------------------------------------------------ 纯函数

    /**
     * URL 的 host 是否为 IPv6 字面量。
     * 例：`http://[2400:3200::1]:8080/live.m3u8` → true；普通域名 / IPv4 → false。
     * 纯函数，无网络请求，可单测。
     */
    fun isV6Literal(url: String): Boolean {
        val a = authority(url) ?: return false
        if (a.startsWith("[")) return true                 // [2400:...]:8080
        val host = a.replace(Regex(":\\d+$"), "")          // 去掉 :port
        return host.contains(':')                          // 无方括号的裸 v6：2400:3200::1
    }

    /** 截取 URL 的 authority（去掉 scheme / userinfo / path），失败返回 null */
    private fun authority(url: String): String? {
        if (url.isEmpty()) return null
        val i = url.indexOf("://")
        val rest = if (i >= 0) url.substring(i + 3) else url
        if (rest.isEmpty()) return null
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val auth = (if (end >= 0) rest.substring(0, end) else rest).substringAfterLast('@')
        return auth.ifEmpty { null }
    }

    /**
     * 过滤后应保留的下标（供播放器用：本机确认无 v6 时剔除 v6 字面量线路）。
     * 若过滤后一个都不剩（整组线路都是 v6 字面量）→ 不做剔除，宁可试也不让用户无台可播。
     * 纯函数，可单测。
     */
    fun indicesToKeep(urls: List<String>): List<Int> {
        val keep = urls.indices.filter { !isV6Literal(urls[it]) }
        return if (keep.isEmpty()) urls.indices.toList() else keep
    }

    // ------------------------------------------------------------------ 检测

    /** 本机是否存在全局 IPv6 地址 */
    fun hasGlobalV6(): Boolean {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return false
            for (nif in Collections.list(ifs)) {
                if (!nif.isUp) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet6Address &&
                        !addr.isLoopbackAddress &&
                        !addr.isLinkLocalAddress &&
                        !addr.isAnyLocalAddress
                    ) return true
                }
            }
        } catch (_: Throwable) {
            // 权限 / 异常一律视为「无 v6」
        }
        return false
    }

    /** 完整检测：有全局 v6 地址 且 能对任一目标建立 TCP 连接。网络请求，调用方需放后台线程 */
    fun ipv6Available(ctx: Context): Boolean {
        if (!hasGlobalV6()) return false
        for (t in TCP_TARGETS) {
            if (tcpConnect(t)) return true
        }
        return false
    }

    private fun tcpConnect(host: String): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, TCP_PORT), TCP_TIMEOUT_MS)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------ 缓存

    /**
     * 带缓存的快速判断（供播放器同步调用）：
     * 缓存有效（TTL 10 分钟内）→ 返回实测值；
     * 缓存缺失 / 过期 → 视为「未知」，返回 true（绝不误过滤线路）。
     */
    fun ipv6AvailableCached(ctx: Context): Boolean {
        val at = Prefs.v6CheckedAt(ctx)
        val fresh = at > 0L && System.currentTimeMillis() - at < TTL_MS
        if (!fresh) return true
        return Prefs.v6Ok(ctx)
    }

    /** 后台线程实测并写缓存；[onDone] 回调在主线程（可空） */
    fun refreshAsync(ctx: Context, onDone: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            val ok = try {
                ipv6Available(app)
            } catch (_: Throwable) {
                false
            }
            Prefs.setV6(app, ok)
            if (onDone != null) {
                Handler(Looper.getMainLooper()).post { onDone(ok) }
            }
        }.start()
    }
}
