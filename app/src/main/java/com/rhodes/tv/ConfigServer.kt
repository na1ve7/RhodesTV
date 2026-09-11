package com.rhodes.tv

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 极简局域网配置页：手机浏览器访问 http://<电视IP>:8765/ 修改订阅地址 */
class ConfigServer(private val ctx: Context) {

    private var ss: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile
    var running = false
        private set

    val port = 8765

    /** 由 MainActivity 注入：POST /api/refresh 时触发电视端刷新 */
    @Volatile
    var refreshHook: (() -> Unit)? = null

    fun start() {
        if (running) return
        try {
            val s = ServerSocket(port)
            ss = s
            running = true
            thread = Thread {
                while (running) {
                    try {
                        val c = s.accept()
                        try {
                            handle(c)
                        } catch (e: Exception) {
                        } finally {
                            try {
                                c.close()
                            } catch (e: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                        if (!running) break
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            running = false
        }
    }

    fun stop() {
        running = false
        try {
            ss?.close()
        } catch (e: Exception) {
        }
        ss = null
    }

    private fun handle(s: Socket) {
        s.soTimeout = 8000
        val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val requestLine = input.readLine() ?: return
        var contentLength = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val k = line.substringBefore(':').trim().lowercase()
            if (k == "content-length") contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }

        val parts = requestLine.split(' ')
        val method = parts.getOrElse(0) { "GET" }.uppercase()
        val path = parts.getOrElse(1) { "/" }
        val out = s.getOutputStream()

        var raw = ""
        if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            raw = String(buf, 0, read)
        }

        if (path.startsWith("/api/")) {
            respond(out, 200, "application/json; charset=utf-8", api(method, path, raw))
            return
        }

        if (method == "POST") {
            val body = raw
            val params = HashMap<String, String>()
            for (kv in body.split('&')) {
                if (kv.isEmpty()) continue
                val i = kv.indexOf('=')
                if (i <= 0) continue
                params[URLDecoder.decode(kv.substring(0, i), "UTF-8")] =
                    URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }
            params["subs"]?.let { Prefs.save(ctx, Prefs.KEY_SUBS, splitLines(it)) }
            params["epg"]?.let { Prefs.save(ctx, Prefs.KEY_EPG, splitLines(it)) }
            params["pat"]?.let { if (it.trim().isNotEmpty()) Prefs.saveGhPat(ctx, it.trim()) }
            respond(out, 302, "text/plain; charset=utf-8", "", "Location: /?ok=1\r\n")
            return
        }

        val ok = path.contains("ok=1")
        respond(out, 200, "text/html; charset=utf-8", page(ok))
    }

    private fun splitLines(s: String): List<String> =
        s.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }

    private fun respond(out: OutputStream, code: Int, type: String, body: String, extra: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 " + code + " " + (if (code == 200) "OK" else "Found") + "\r\n" +
            "Content-Type: " + type + "\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Cache-Control: no-store\r\n" + extra + "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(ok: Boolean): String {
        val subs = esc(Prefs.list(ctx, Prefs.KEY_SUBS).joinToString("\n"))
        val epg = esc(Prefs.list(ctx, Prefs.KEY_EPG).joinToString("\n"))
        val n = ChannelStore.load(ctx).size
        val banner = if (ok) "<p style='color:#2e7d32;font-weight:bold'>已保存 ✓ 电视端下次启动或刷新即生效</p>" else ""
        val patState = if (Prefs.ghPat(ctx).isEmpty()) "未配置（粘贴 GitHub Token 后点保存）" else "已配置，留空保持不变"
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>罗德岛TV · 设置</title><style>" +
            "body{font-family:-apple-system,Roboto,sans-serif;margin:0;padding:18px;background:#f5f6f8}" +
            "h1{font-size:22px;margin:6px 0 14px}p{color:#555}" +
            "label{display:block;margin:16px 0 6px;font-weight:bold}" +
            "textarea{width:100%;box-sizing:border-box;font-size:15px;padding:10px;border:1px solid #ccc;" +
            "border-radius:8px;min-height:150px}" +
            "button{width:100%;margin-top:20px;padding:16px;font-size:18px;color:#fff;background:#1e88e5;" +
            "border:0;border-radius:10px}" +
            ".tip{background:#fff;border-radius:10px;padding:12px;font-size:14px;color:#666;margin-top:18px}" +
            ".card{background:#fff;border-radius:10px;padding:12px;margin-top:18px}" +
            ".row{display:flex;justify-content:space-between;align-items:center;gap:10px;padding:8px 0;" +
            "border-bottom:1px solid #eee;font-size:15px}.mono{font-size:13px;color:#888}" +
            "input.search{width:100%;box-sizing:border-box;padding:10px;font-size:15px;border:1px solid #ccc;" +
            "border-radius:8px}.mini{width:auto;margin:0;padding:8px 14px;font-size:15px;background:#e53935;" +
            "border-radius:8px}</style></head><body>" +
            "<h1>罗德岛TV · 设置</h1>" + banner +
            "<form method='post' action='/save'>" +
            "<label>直播源订阅地址（每行一条，m3u 或 txt）</label>" +
            "<textarea name='subs'>" + subs + "</textarea>" +
            "<label>EPG 节目单地址（可留空）</label>" +
            "<textarea name='epg'>" + epg + "</textarea>" +
            "<label>GitHub Token（可选，仅用于手机端「重体检单个频道」，只存电视本机）</label>" +
            "<input class='search' type='password' name='pat' placeholder='" + patState + "'>" +
            "<button type='submit'>保存</button></form>" +
            "<div class='tip'>当前电视端已缓存频道数：" + n +
            "<br>保存后回到电视，按「菜单」→「保存并刷新」或重启 App 生效。</div>" +
            "<div class='card'><label>频道维护 · 单个频道实时换源（不用等 6 小时）</label>" +
            "<input class='search' id='kw' placeholder='搜索频道名，例如 湖南卫视' oninput='rcSearch()'>" +
            "<div id='rclist'></div><p class='mono' id='rcmsg'>点频道右侧「重体检」→ 云端只重跑这个台的线路 → 再点「刷新电视端频道」。</p>" +
            "<button class='mini' onclick='rfGo()'>刷新电视端频道</button></div>" +
            "<script>" +
            "function ef(s){var t=String(s);t=t.split('&').join('&amp;');t=t.split('<').join('&lt;');return t;}" +
            "window.__rc=[];" +
            "async function rcSearch(){var kw=document.getElementById('kw').value.trim();" +
            "var box=document.getElementById('rclist');if(!kw){box.innerHTML='';return;}" +
            "var r=await fetch('/api/channels?q='+encodeURIComponent(kw)+'&limit=30');" +
            "var j=await r.json();var a=j.channels||[];window.__rc=a;var h='';" +
            "for(var i=0;i<a.length;i++){var c=a[i];" +
            "h+='<div class=row><span>'+ef(c.group)+' · '+ef(c.name)+(c.dead?'（暂无线路）':'')+'</span>'" +
            "+'<button class=mini onclick=rcGo('+i+')>重体检</button></div>';}" +
            "box.innerHTML=h||'<p class=mono>没有匹配的频道</p>';}" +
            "async function rcGo(i){var c=window.__rc[i];if(!c)return;" +
            "var m=document.getElementById('rcmsg');m.textContent=c.name+'：正在提交云端重体检…';" +
            "var r=await fetch('/api/recheck?ch='+encodeURIComponent(c.key),{method:'POST'});" +
            "var j=await r.json();" +
            "m.textContent=j.ok?(c.name+'：'+(j.tip||'已提交')):('失败：'+(j.error||'未知错误'));}" +
            "function rfGo(){var m=document.getElementById('rcmsg');m.textContent='正在通知电视端刷新…';" +
            "fetch('/api/refresh',{method:'POST'}).then(function(r){return r.json()}).then(function(j){" +
            "m.textContent=j.ok?'已通知电视端刷新，稍等几秒回电视看新线路':'刷新失败';});}" +
            "</script>" +
            "</body></html>"
    }

    // ---------- JSON 接口（供手机 App 调用） ----------

    private fun decodePairs(src: String, into: HashMap<String, String>) {
        for (kv in src.split('&')) {
            if (kv.isEmpty()) continue
            val i = kv.indexOf('=')
            if (i <= 0) continue
            try {
                into[URLDecoder.decode(kv.substring(0, i), "UTF-8")] =
                    URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            } catch (e: Exception) {
            }
        }
    }

    private fun api(method: String, path: String, raw: String): String {
        val q = HashMap<String, String>()
        decodePairs(path.substringAfter('?', ""), q)
        if (raw.isNotEmpty()) decodePairs(raw, q)
        val route = path.substringBefore('?')
        return try {
            when (route) {
                "/api/status" -> statusJson()
                "/api/channels" -> channelsJson(q)
                "/api/groups" -> groupsJson()
                "/api/fav", "/api/favorite" -> if (method == "POST") favPost(q) else favJson()
                "/api/group" -> if (method == "POST") groupPost(q) else groupsJson()
                "/api/refresh" -> refreshPost()
                "/api/update" -> updateJson()
                "/api/recheck" -> recheckPost(q)
                "/api/ping" -> JSONObject().put("ok", true).put("app", "RhodesTV")
                    .put("ts", System.currentTimeMillis()).toString()
                else -> JSONObject().put("ok", false).put("error", "unknown api " + route).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error").toString()
        }
    }

    /** 手机端「这个台卡了 → 立刻重体检」：调源仓库 Actions workflow_dispatch，只重跑该频道的线路 */
    private fun recheckPost(q: HashMap<String, String>): String {
        val o = JSONObject()
        val key = (q["ch"] ?: q["key"] ?: q["channel"] ?: "").trim()
        if (key.isEmpty()) {
            o.put("ok", false)
            o.put("error", "缺少频道参数 ch")
            return o.toString()
        }
        val pat = Prefs.ghPat(ctx)
        if (pat.isEmpty()) {
            o.put("ok", false)
            o.put("error", "还没配置 GitHub Token：在本页底部填一次即可")
            return o.toString()
        }
        return try {
            val body = JSONObject()
                .put("ref", "main")
                .put("inputs", JSONObject().put("channel", key).put("skip_gitee", "true"))
                .toString()
            val req = Request.Builder()
                .url("https://api.github.com/repos/na1ve7/RhodesTV-sources/actions/workflows/refresh.yml/dispatches")
                .header("Authorization", "Bearer " + pat)
                .header("Accept", "application/vnd.github+json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            ChannelStore.http.newCall(req).execute().use { r ->
                o.put("ok", r.isSuccessful)
                o.put("code", r.code)
                if (!r.isSuccessful) o.put("error", "GitHub 返回 " + r.code)
            }
            o.put("channel", key)
            o.put("tip", "云端已开始重体检，约 1-2 分钟后点「刷新频道」生效")
            o.toString()
        } catch (e: Exception) {
            o.put("ok", false)
            o.put("error", e.message ?: "请求失败")
            o.toString()
        }
    }

    /** 检查是否有新版本（手机端可远程查看，返回当前版本与最新版本信息） */
    private fun updateJson(): String {
        val o = JSONObject()
        o.put("ok", true)
        o.put("app", "RhodesTV")
        o.put("currentCode", Updater.currentCode(ctx))
        o.put("currentName", Updater.currentName(ctx))
        try {
            val info = Updater.check(ctx)
            o.put("hasUpdate", info != null)
            if (info != null) {
                o.put("versionCode", info.versionCode)
                o.put("versionName", info.versionName)
                o.put("apkUrl", info.apkUrl)
                o.put("note", info.note)
            }
        } catch (e: Exception) {
            o.put("hasUpdate", false)
            o.put("error", e.message ?: "check failed")
        }
        o.put("ts", System.currentTimeMillis())
        return o.toString()
    }

    private fun groupOf(ch: Channel): String =
        if (ch.group.isNullOrBlank()) "\u672a\u5206\u7ec4" else ch.group

    private fun groupNames(): List<String> {
        val raw = ArrayList<String>()
        for (ch in ChannelStore.load(ctx)) {
            val g = groupOf(ch)
            if (!raw.contains(g)) raw.add(g)
        }
        val out = ArrayList<String>()
        for (g in Prefs.groupOrder(ctx)) if (raw.contains(g) && !out.contains(g)) out.add(g)
        for (g in raw) if (!out.contains(g)) out.add(g)
        return out
    }

    private fun statusJson(): String {
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        val o = JSONObject()
        o.put("ok", true)
        o.put("app", "RhodesTV")
        o.put("proto", 1)
        o.put("device", android.os.Build.MODEL ?: "AndroidTV")
        o.put("android", android.os.Build.VERSION.RELEASE ?: "")
        o.put("version", ver)
        o.put("ip", localIp())
        o.put("port", port)
        o.put("favGroup", Prefs.FAV_GROUP)
        val now = JSONObject()
        now.put("channel", TvState.currentName)
        now.put("group", TvState.currentGroup)
        now.put("index", TvState.currentIndex)
        now.put("playing", TvState.playing)
        now.put("line", TvState.currentLine)
        now.put("lines", TvState.totalLines)
        now.put("lastError", TvState.lastError)
        now.put("lastPlayAt", TvState.lastPlayAt)
        o.put("now", now)
        o.put("channels", ChannelStore.load(ctx).size)
        o.put("groups", groupNames().size)
        o.put("favs", Prefs.favSet(ctx).size)
        o.put("subs", Prefs.list(ctx, Prefs.KEY_SUBS).size)
        o.put("epgChannels", EpgStore.count())
        o.put("lastRefreshAt", TvState.lastRefreshAt)
        o.put("lastRefreshResult", TvState.lastRefreshResult)
        o.put("cacheTime", ChannelStore.cacheTime(ctx))
        o.put("uptimeSec", TvState.uptimeSec())
        o.put("ts", System.currentTimeMillis())
        return o.toString()
    }

    private fun groupsJson(): String {
        val counts = HashMap<String, Int>()
        for (ch in ChannelStore.load(ctx)) {
            val g = groupOf(ch)
            counts[g] = (counts[g] ?: 0) + 1
        }
        val hidden = Prefs.hiddenGroups(ctx)
        val arr = JSONArray()
        for (g in groupNames()) {
            val o = JSONObject()
            o.put("name", g)
            o.put("channels", counts[g] ?: 0)
            o.put("hidden", hidden.contains(g))
            arr.put(o)
        }
        val res = JSONObject()
        res.put("ok", true)
        res.put("count", arr.length())
        res.put("groups", arr)
        return res.toString()
    }

    private fun channelsJson(q: HashMap<String, String>): String {
        val all = ChannelStore.load(ctx)
        val favs = Prefs.favSet(ctx)
        val g = q["group"]
        val kw = q["q"]?.lowercase()
        val onlyFav = q["fav"] == "1"
        val limit = (q["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 2000)
        val offset = (q["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val arr = JSONArray()
        var total = 0
        for ((i, ch) in all.withIndex()) {
            val cg = groupOf(ch)
            if (!g.isNullOrBlank() && cg != g) continue
            if (onlyFav && !favs.contains(ch.name)) continue
            if (kw != null && !ch.name.lowercase().contains(kw)) continue
            total++
            if (total <= offset) continue
            if (arr.length() >= limit) continue
            val o = JSONObject()
            o.put("no", ch.chno ?: (i + 1))
            o.put("name", ch.name)
            o.put("group", cg)
            o.put("key", ch.key)
            o.put("chno", ch.chno ?: 0)
            o.put("dead", !ch.playable)
            o.put("fav", favs.contains(ch.name))
            o.put("lines", ch.lines.size)
            arr.put(o)
        }
        val res = JSONObject()
        res.put("ok", true)
        res.put("total", total)
        res.put("count", arr.length())
        res.put("offset", offset)
        res.put("limit", limit)
        res.put("channels", arr)
        return res.toString()
    }

    private fun favJson(): String {
        val arr = JSONArray()
        val names = ArrayList(Prefs.favSet(ctx))
        names.sort()
        for (n in names) arr.put(n)
        val res = JSONObject()
        res.put("ok", true)
        res.put("count", arr.length())
        res.put("favs", arr)
        return res.toString()
    }

    private fun favPost(q: HashMap<String, String>): String {
        val name = q["name"]
        if (name.isNullOrBlank()) {
            return JSONObject().put("ok", false).put("error", "missing name").toString()
        }
        val action = q["action"] ?: "toggle"
        val has = Prefs.favSet(ctx).contains(name)
        val want = when (action) {
            "add" -> true
            "remove", "del", "delete" -> false
            else -> !has
        }
        if (want != has) Prefs.toggleFav(ctx, name)
        val o = JSONObject()
        o.put("ok", true)
        o.put("name", name)
        o.put("fav", want)
        o.put("favs", Prefs.favSet(ctx).size)
        return o.toString()
    }

    private fun groupPost(q: HashMap<String, String>): String {
        // order=新闻,央视,地方  → 保存用户自定义分组顺序（幂等）
        val orderRaw = q["order"]
        if (orderRaw != null) {
            val list = ArrayList<String>()
            for (g in orderRaw.split(',', '\u3001')) {
                val t = g.trim()
                if (t.isNotEmpty() && !list.contains(t)) list.add(t)
            }
            Prefs.saveGroupOrder(ctx, list)
        }
        // hidden=地方,购物  → 覆盖式设置隐藏集合（空串=全部显示）；toggle=地方 → 切换单个
        val hiddenRaw = q["hidden"]
        if (hiddenRaw != null) {
            val want = HashSet<String>()
            for (g in hiddenRaw.split(',', '\u3001')) {
                val t = g.trim()
                if (t.isNotEmpty()) want.add(t)
            }
            for (g in ArrayList(Prefs.hiddenGroups(ctx))) if (!want.contains(g)) Prefs.toggleGroupHidden(ctx, g)
            for (g in want) if (!Prefs.hiddenGroups(ctx).contains(g)) Prefs.toggleGroupHidden(ctx, g)
        }
        val toggle = q["toggle"]
        if (!toggle.isNullOrEmpty()) Prefs.toggleGroupHidden(ctx, toggle)

        val arr = JSONArray()
        for (g in Prefs.groupOrder(ctx)) arr.put(g)
        val arrH = JSONArray()
        for (g in Prefs.hiddenGroups(ctx)) arrH.put(g)
        return JSONObject().put("ok", true).put("order", arr).put("hidden", arrH)
            .put("ts", System.currentTimeMillis()).toString()
    }

    private fun refreshPost(): String {
        val hook = refreshHook
        if (hook == null) {
            return JSONObject().put("ok", false)
                .put("error", "refresh unavailable: open RhodesTV app first").toString()
        }
        return try {
            hook.invoke()
            JSONObject().put("ok", true).put("msg", "refresh started").toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "error").toString()
        }
    }

    companion object {
        @Volatile
        private var inst: ConfigServer? = null

        /** 全局单例：电视端常驻，手机可随时查询状态 */
        fun shared(ctx: Context): ConfigServer {
            val cur = inst
            if (cur != null && cur.running) return cur
            synchronized(this) {
                val again = inst
                if (again != null && again.running) return again
                val sv = ConfigServer(ctx.applicationContext)
                sv.start()
                inst = sv
                return sv
            }
        }

        fun localIp(): String {
            try {
                val ifs = NetworkInterface.getNetworkInterfaces()
                while (ifs.hasMoreElements()) {
                    val ni = ifs.nextElement()
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) {
                        if (!a.isLoopbackAddress && a is Inet4Address) {
                            val ip = a.hostAddress ?: continue
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip
                        }
                    }
                }
            } catch (e: Exception) {
            }
            return "127.0.0.1"
        }
    }
}
