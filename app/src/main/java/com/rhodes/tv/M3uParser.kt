package com.rhodes.tv

object M3uParser {

    private val ATTR = Regex("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")

    fun parse(text: String): List<Channel> {
        val out = LinkedHashMap<String, Channel>()
        var pending: Channel? = null
        var ua: String? = null
        var ref: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF")) {
                val attrs = HashMap<String, String>()
                for (m in ATTR.findAll(line)) attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
                val name = line.substringAfterLast(',', "").trim()
                    .ifEmpty { attrs["tvg-name"] ?: "未知频道" }
                val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "其他"
                val tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                val key = name + "|" + group
                pending = out.getOrPut(key) {
                    Channel(key = key, name = name, group = group,
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }, tvgId = tvgId)
                }
                ua = null
                ref = null
            } else if (line.startsWith("#EXTVLCOPT") || line.startsWith("#KODIPROP")) {
                val body = line.substringAfter(':', "")
                val k = body.substringBefore('=').trim().lowercase()
                val v = body.substringAfter('=', "").trim().ifBlank { null }
                if (v != null) {
                    if (k.contains("user-agent") || k.contains("useragent")) ua = v
                    else if (k.contains("referrer") || k.contains("referer")) ref = v
                }
            } else if (line.startsWith("#")) {
                // ignore other comments
            } else {
                pending?.lines?.add(StreamLine(line, ua, ref))
                pending = null
            }
        }
        return out.values.filter { it.lines.isNotEmpty() }
    }

    fun merge(dst: MutableList<Channel>, incoming: List<Channel>) {
        val idx = HashMap<String, Channel>()
        for (c in dst) idx[c.key] = c
        for (c in incoming) {
            val exist = idx[c.key]
            if (exist == null) {
                dst.add(c)
                idx[c.key] = c
            } else {
                val seen = exist.lines.map { it.url }.toHashSet()
                for (l in c.lines) if (seen.add(l.url)) exist.lines.add(l)
            }
        }
    }
}
