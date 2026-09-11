package com.rhodes.tv

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object Prefs {
    private const val NAME = "rhodes"
    const val KEY_SUBS = "subs"
    const val KEY_EPG = "epg"
    const val KEY_UA = "ua"

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun get(c: Context): SharedPreferences = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun list(c: Context, key: String): MutableList<String> =
        get(c).getString(key, "")!!.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    fun save(c: Context, key: String, items: List<String>) {
        get(c).edit().putString(key, items.joinToString("\n")).apply()
    }

    fun ua(c: Context): String = get(c).getString(KEY_UA, DEFAULT_UA) ?: DEFAULT_UA

    // ---------- 收藏 ----------

    const val KEY_FAV = "fav"
    /** 收藏分组固定名称（永远置顶，无收藏时不显示） */
    const val FAV_GROUP = "\u2b50 \u6536\u85cf"

    /** 收藏集合：以「频道名」为键（与多线路聚合后的 key 一致，源刷新后不失效） */
    fun favSet(c: Context): MutableSet<String> =
        HashSet(get(c).getStringSet(KEY_FAV, emptySet()) ?: emptySet())

    fun isFav(c: Context, name: String): Boolean = favSet(c).contains(name)

    /** 切换收藏，返回 true=已收藏 / false=已取消 */
    fun toggleFav(c: Context, name: String): Boolean {
        val s = favSet(c)
        val added = if (s.contains(name)) {
            s.remove(name); false
        } else {
            s.add(name); true
        }
        get(c).edit().putStringSet(KEY_FAV, s).apply()
        return added
    }

    // ---------- 分组管理 ----------

    const val KEY_GROUP_ORDER = "group_order"
    const val KEY_GROUP_HIDDEN = "group_hidden"

    /** 用户自定义的分组顺序（只含用户调整过的；未列出的按源内出现顺序补在后） */
    fun groupOrder(c: Context): MutableList<String> = list(c, KEY_GROUP_ORDER)

    fun saveGroupOrder(c: Context, order: List<String>) = save(c, KEY_GROUP_ORDER, order)

    /** 被隐藏（不在主界面标签栏显示）的分组 */
    fun hiddenGroups(c: Context): MutableSet<String> =
        HashSet(get(c).getStringSet(KEY_GROUP_HIDDEN, emptySet()) ?: emptySet())

    fun isGroupHidden(c: Context, name: String): Boolean = hiddenGroups(c).contains(name)

    /** 切换分组显示/隐藏，返回 true=已隐藏 */
    fun toggleGroupHidden(c: Context, name: String): Boolean {
        val s = hiddenGroups(c)
        val hidden = if (s.contains(name)) {
            s.remove(name); false
        } else {
            s.add(name); true
        }
        get(c).edit().putStringSet(KEY_GROUP_HIDDEN, s).apply()
        return hidden
    }
}

object ChannelStore {

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun cacheFile(c: Context) = File(c.filesDir, "channels.json")

    fun getText(c: Context, url: String, ua: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP " + resp.code)
            val body = resp.body ?: throw RuntimeException("empty body")
            var stream = body.byteStream()
            val gz = url.endsWith(".gz") || (resp.header("Content-Encoding")?.contains("gzip") == true)
            if (gz) stream = GZIPInputStream(stream)
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }

    // ---------- 内置源：首次安装零配置即可看电视 ----------

    private const val ASSET_SOURCES = "default_sources.txt"
    private const val ASSET_SEED = "seed.m3u"

    /** 内置备用镜像：与默认订阅是同一份清单（不同 CDN 线路），主源全挂时逐个尝试 */
    private val MIRRORS = listOf(
        "https://cdn.jsdelivr.net/gh/na1ve7/RhodesTV-sources@main/dist/playable.m3u",
        "https://raw.githack.com/na1ve7/RhodesTV-sources/main/dist/playable.m3u",
        "https://raw.githubusercontent.com/na1ve7/RhodesTV-sources/main/dist/playable.m3u"
    )

    private fun assetText(c: Context, name: String): String =
        try {
            c.assets.open(name).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }

    /**
     * 首次启动（订阅为空）时自动填入内置默认源，用户无需手动添加。
     * 用户在设置页改过订阅后不会再覆盖。
     */
    fun ensureDefaults(c: Context) {
        try {
            if (Prefs.list(c, Prefs.KEY_SUBS).isNotEmpty()) return
            val urls = assetText(c, ASSET_SOURCES).split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            if (urls.isNotEmpty()) Prefs.save(c, Prefs.KEY_SUBS, urls)
        } catch (e: Exception) {
        }
    }

    /** 随安装包内置的离线快照：没网 / 全部源都失败时也能出频道 */
    fun fetchSeed(c: Context): List<Channel> {
        val t = assetText(c, ASSET_SEED)
        if (t.isBlank()) return emptyList()
        return try {
            M3uParser.parse(t)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** jsDelivr 等 CDN 对 @main 有最长 12 小时缓存：加时间戳强制取到最新清单 */
    private fun bust(url: String, minutes: Long = System.currentTimeMillis() / 60000): String =
        if (url.contains("jsdelivr")) url + (if (url.contains("?")) "&" else "?") + "t=" + minutes else url

    fun fetchAll(c: Context): List<Channel> {
        ensureDefaults(c)
        val subs = Prefs.list(c, Prefs.KEY_SUBS)
        val ua = Prefs.ua(c)
        val all = ArrayList<Channel>()
        var ok = 0
        var lastErr: Exception? = null
        for (u in subs) {
            try {
                val list = M3uParser.parse(getText(c, bust(u), ua))
                if (list.isNotEmpty()) {
                    M3uParser.merge(all, list)
                    ok++
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        if (ok == 0) {
            // 主源全挂 → 内置镜像（与主源同一份清单，只取第一个成功的，避免同一地址重复成多条线路）
            for (u in MIRRORS) {
                try {
                    val list = M3uParser.parse(getText(c, bust(u), ua))
                    if (list.isNotEmpty()) {
                        M3uParser.merge(all, list)
                        ok++
                        break
                    }
                } catch (e: Exception) {
                    lastErr = e
                }
            }
        }
        if (ok == 0) {
            // 连 CDN 都不可达 → 用随包内置的离线快照，至少保证能看电视
            val seed = fetchSeed(c)
            if (seed.isNotEmpty()) return seed
            throw (lastErr ?: RuntimeException("no subscription"))
        }
        return all
    }

    fun save(c: Context, list: List<Channel>) {
        val arr = JSONArray()
        for (ch in list) {
            val o = JSONObject()
            o.put("name", ch.name)
            o.put("group", ch.group)
            o.put("key", ch.key)
            o.put("logo", ch.logo ?: "")
            o.put("tvgId", ch.tvgId ?: "")
            val ls = JSONArray()
            for (l in ch.lines) {
                val lo = JSONObject()
                lo.put("url", l.url)
                lo.put("ua", l.ua ?: "")
                lo.put("ref", l.referer ?: "")
                ls.put(lo)
            }
            o.put("lines", ls)
            arr.put(o)
        }
        try {
            cacheFile(c).writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
        }
    }

    fun load(c: Context): List<Channel> {
        val f = cacheFile(c)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ls = ArrayList<StreamLine>()
                val la = o.optJSONArray("lines") ?: JSONArray()
                for (j in 0 until la.length()) {
                    val lo = la.getJSONObject(j)
                    ls.add(StreamLine(lo.optString("url"),
                        lo.optString("ua").ifBlank { null },
                        lo.optString("ref").ifBlank { null }))
                }
                out.add(Channel(
                    key = o.optString("key"),
                    name = o.optString("name"),
                    group = o.optString("group"),
                    logo = o.optString("logo").ifBlank { null },
                    tvgId = o.optString("tvgId").ifBlank { null },
                    lines = ls
                ))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun cacheTime(c: Context): Long = cacheFile(c).lastModified()
}

object EpgStore {

    @Volatile
    private var map: MutableMap<String, MutableList<EpgProgramme>> = HashMap()

    private fun file(c: Context) = File(c.filesDir, "epg.tsv")

    fun count(): Int = map.size

    fun load(c: Context): Int {
        val urls = Prefs.list(c, Prefs.KEY_EPG)
        if (urls.isEmpty()) return 0
        val ua = Prefs.ua(c)
        val m = HashMap<String, MutableList<EpgProgramme>>()
        var any = false
        for (u in urls) {
            try {
                val req = Request.Builder().url(u).header("User-Agent", ua).build()
                ChannelStore.http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body
                        if (body != null) {
                            var stream = body.byteStream()
                            if (u.endsWith(".gz")) stream = GZIPInputStream(stream)
                            for (pg in XmltvParser.parse(stream)) {
                                m.getOrPut(pg.channelId) { ArrayList() }.add(pg)
                            }
                            any = true
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
        if (!any) return 0
        for (v in m.values) v.sortBy { it.start }
        map = m
        saveCache(c)
        return m.size
    }

    private fun saveCache(c: Context) {
        try {
            val sb = StringBuilder()
            for ((k, v) in map) {
                for (p in v) {
                    sb.append(k).append('\t').append(p.start).append('\t').append(p.stop)
                        .append('\t').append(p.title.replace('\t', ' ').replace('\n', ' ')).append('\n')
                }
            }
            file(c).writeText(sb.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
        }
    }

    fun loadCache(c: Context): Int {
        val f = file(c)
        if (!f.exists()) return 0
        return try {
            val m = HashMap<String, MutableList<EpgProgramme>>()
            f.readLines(Charsets.UTF_8).forEach { ln ->
                val parts = ln.split('\t')
                if (parts.size >= 4) {
                    val st = parts[1].toLongOrNull() ?: 0L
                    val sp = parts[2].toLongOrNull() ?: 0L
                    if (sp > st) m.getOrPut(parts[0]) { ArrayList() }
                        .add(EpgProgramme(parts[0], parts[3], st, sp))
                }
            }
            for (v in m.values) v.sortBy { it.start }
            map = m
            m.size
        } catch (e: Exception) {
            0
        }
    }

    private fun lookup(ch: Channel): List<EpgProgramme>? {
        ch.tvgId?.let { id -> map[id]?.let { return it } }
        map[ch.name]?.let { return it }
        for ((k, v) in map) {
            if (k.isNotBlank() && (k.contains(ch.name) || ch.name.contains(k))) return v
        }
        return null
    }

    fun nowNext(ch: Channel): Pair<EpgProgramme?, EpgProgramme?> {
        val list = lookup(ch) ?: return null to null
        val now = System.currentTimeMillis()
        var cur: EpgProgramme? = null
        var next: EpgProgramme? = null
        for (p in list) {
            if (now >= p.start && now < p.stop) cur = p
            if (p.start > now) {
                next = p
                break
            }
        }
        return cur to next
    }
}
