package com.rhodes.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 应用内「一键远程更新」。
 *
 * 流程：读取远程版本清单 update.json（多个镜像依次尝试）
 *  → 版本号大于当前则提示
 *  → 下载 APK 到 cache（可选 sha256 校验）
 *  → 通过 FileProvider 拉起系统安装器（首次需允许「安装未知应用」）。
 *
 * 清单格式（dist/update.json）：
 * {
 *   "versionCode": 3,
 *   "versionName": "1.2",
 *   "apkUrl": "https://cdn.jsdelivr.net/gh/na1ve7/RhodesTV@main/dist/RhodesTV.apk",
 *   "sha256": "…",
 *   "note": "本次更新内容…"
 * }
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val note: String,
    val sha256: String
)

object Updater {

    const val KEY_UPDATE_URLS = "update_urls"
    const val KEY_AUTO_CHECK = "auto_update_check"
    const val KEY_LAST_CHECK = "last_update_check"

    /**
     * 默认清单地址。第一项是 GitHub Contents API —— 它不做 CDN 缓存，永远返回仓库真实内容，
     * 实测国内可直连；后面的 jsDelivr / GitHub 代理都带缓存（曾出现「发了新版却检测不到」）。
     */
    val DEFAULT_URLS = listOf(
        "https://api.github.com/repos/na1ve7/RhodesTV/contents/dist/update.json?ref=main",
        "https://cdn.jsdelivr.net/gh/na1ve7/RhodesTV@main/dist/update.json",
        "https://gh-proxy.com/https://raw.githubusercontent.com/na1ve7/RhodesTV/main/dist/update.json",
        "https://ghfast.top/https://raw.githubusercontent.com/na1ve7/RhodesTV/main/dist/update.json"
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .dns(Doh.dns(Prefs.appContext()))
        .build()

    fun urls(c: Context): List<String> = Prefs.list(c, KEY_UPDATE_URLS).ifEmpty { DEFAULT_URLS }

    /** 当前已安装版本号 */
    fun currentCode(c: Context): Int = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        PackageInfoCompat.getLongVersionCode(pi).toInt()
    } catch (e: Exception) {
        0
    }

    fun currentName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    fun currentLabel(c: Context): String = "v" + currentName(c) + " (" + currentCode(c) + ")"

    /** 是否允许安装未知来源应用（Android 8+ 需用户授权） */
    fun canInstall(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.packageManager.canRequestPackageInstalls() else true

    /**
     * 检查更新。返回 null = 已是最新；非 null = 有新版本。
     * 全部镜像都失败时抛异常（附最后一次错误）。
     */
    /** 可注入：拉取清单文本（便于测试，默认走 OkHttp） */
    var manifestFetcher: (String) -> String = { u -> httpGet(u) }

    /** 可注入：APK 下载实现（便于测试，默认走 OkHttp） */
    var fileDownloader: (String, File, (Int) -> Unit) -> Unit = { u, tmp, cb -> httpDownload(u, tmp, cb) }

    /** 解析清单文本；缺少 apkUrl 视为无效清单 */
    fun parseManifest(body: String): UpdateInfo? {
        val j = JSONObject(body)
        val apk = j.optString("apkUrl", "")
        if (apk.isEmpty()) return null
        return UpdateInfo(
            j.optInt("versionCode", 0),
            j.optString("versionName", ""),
            apk,
            j.optString("note", ""),
            j.optString("sha256", "")
        )
    }

    /** 远程版本是否比当前新 */
    fun isNewer(remoteCode: Int, currentCode: Int): Boolean = remoteCode > currentCode

    /**
     * 检查更新。返回 null = 已是最新；非 null = 有新版本。
     * 全部镜像都失败时抛异常（附最后一次错误）。
     */
    fun check(c: Context): UpdateInfo? {
        var lastErr: Exception? = null
        var best: UpdateInfo? = null
        var gotAny = false
        for (u in urls(c)) {
            try {
                val info = parseManifest(manifestFetcher(u)) ?: continue
                gotAny = true
                // 多个镜像可能因 CDN 缓存给出不同版本：取最大的那个，避免"漏更新"
                if (isNewer(info.versionCode, currentCode(c)) &&
                    (best == null || info.versionCode > best!!.versionCode)
                ) best = info
            } catch (e: Exception) {
                lastErr = e
            }
        }
        if (gotAny) {
            Prefs.get(c).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            return best
        }
        throw lastErr ?: IllegalStateException("没有可用的更新地址")
    }

    /** jsDelivr 对 @main 有最长 12h 缓存：加时间戳强制取最新（也是"清单不更新"的元凶） */
    /**
     * jsDelivr 对 @main 缓存最长 12 小时，且加 ?t= 时间戳无效（实测仍返回旧文件）。
     * 唯一可靠办法：先请求官方 purge 接口强制回源，再正常下载（purge 失败不影响主流程）。
     */
    fun bust(u: String): String {
        if (u.contains("cdn.jsdelivr.net")) {
            try {
                val path = u.substringAfter("cdn.jsdelivr.net/").substringBefore("?")
                client.newCall(Request.Builder().url("https://purge.jsdelivr.net/" + path).build())
                    .execute().use { it.body?.string() }
            } catch (e: Exception) {
            }
        }
        return u
    }

    /**
     * GitHub Contents API 返回的是 JSON（文件内容 base64 编码）。
     * 之所以用它：CDN（jsDelivr/githack/gh-proxy）全都带缓存，实测能返旧文件；
     * api.github.com 每次都返回仓库真实内容，国内可直连（匿名额度 60 次/小时，够用）。
     */
    private fun decodeApiBody(url: String, text: String): String {
        if (!url.contains("api.github.com")) return text
        return try {
            val c = org.json.JSONObject(text).optString("content", "")
            if (c.isEmpty()) text
            else String(android.util.Base64.decode(c.replace("\n", ""), android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            text
        }
    }

    /** 拉取文本（清单） */
    private fun httpGet(url: String): String {
        val req = Request.Builder().url(bust(url))
            .header("User-Agent", Prefs.DEFAULT_UA)
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
            return decodeApiBody(url, resp.body?.string() ?: throw IllegalStateException("空响应"))
        }
    }

    /** 下载 APK 到 cacheDir，返回文件。onProgress 回调 0..100（未知长度时 -1） */
    fun download(c: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val out = File(c.cacheDir, "RhodesTV-update-" + info.versionCode + ".apk")
        val tmp = File(c.cacheDir, out.name + ".part")
        if (tmp.exists()) tmp.delete()
        // 主地址可能是被 CDN 缓存的旧包 → sha256 校验失败就换下一个镜像重试
        var lastErr: Exception? = null
        val cands = (listOf(info.apkUrl) + urls(c).map { it.replace("update.json", "RhodesTV.apk") })
            .filter { !it.contains("api.github.com") }   // Contents API 传大二进制会截断，不用它下包
            .distinct()
        for (u in cands) {
            try {
                if (tmp.exists()) tmp.delete()
                fileDownloader(u, tmp, onProgress)
                return finalizeDownload(tmp, out, info.sha256)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("下载失败：所有镜像都不可用")
    }

    /** 校验 sha256 后把临时文件转正；校验失败抛异常并清理残留 */
    fun finalizeDownload(tmp: File, out: File, sha256: String): File {
        if (sha256.isNotEmpty()) {
            val got = sha256(tmp)
            if (!got.equals(sha256, ignoreCase = true)) {
                tmp.delete()
                throw IllegalStateException("校验失败：文件可能损坏（" + got.take(12) + "…）")
            }
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        return out
    }

    /** 真实下载（OkHttp → 临时文件），带进度回调 */
    private fun httpDownload(url: String, tmp: File, onProgress: (Int) -> Unit) {
        val req = Request.Builder().url(bust(url))
            .header("User-Agent", Prefs.DEFAULT_UA)
            // GitHub Contents API 需要 raw 头才是文件本体（默认返回 base64 JSON）；它不做 CDN 缓存，最可靠
            .header("Accept", if (url.contains("api.github.com")) "application/vnd.github.raw" else "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("下载失败 HTTP " + resp.code)
            val body = resp.body ?: throw IllegalStateException("下载失败：空响应")
            val total = body.contentLength()
            body.byteStream().use { ins ->
                tmp.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -2
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        os.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 拉起系统安装器。若未授予「安装未知应用」权限，则先跳转授权页并返回 false。
     */
    fun install(a: Activity, f: File): Boolean {
        if (!canInstall(a)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    a.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + a.packageName)
                        )
                    )
                } catch (e: Exception) {
                }
            }
            return false
        }
        val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        a.startActivity(i)
        return true
    }

    /** 自动检查节流：距上次检查不足 [hours] 小时则跳过 */
    fun shouldAutoCheck(c: Context, hours: Int): Boolean {
        val last = Prefs.get(c).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > hours * 3600_000L
    }
}
