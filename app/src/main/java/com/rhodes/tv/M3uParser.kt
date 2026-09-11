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
                val rawName = line.substringAfterLast(',', "").trim()
                    .ifEmpty { attrs["tvg-name"] ?: "未知频道" }
                // 去掉「高清/HD/线路N」等后缀，避免同一频道因写法不同被拆成好几个
                val name = GroupRules.cleanName(rawName)
                // 分组不再直接采用上游 group-title（多而杂乱、组内关系不大）：按名称规则重分类为 12 大类
                val group = GroupRules.classify(name, attrs["group-title"] ?: "")
                val tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                // key 用归一化频道名：同一频道的多条线路（不同画质/后缀）在此合并为一条多线路频道
                val key = GroupRules.normName(rawName)
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
                // 同一频道内完全相同的 url 只保留一条（多线路去重，避免白等一次换源）
                pending?.let { ch -> if (ch.lines.none { it.url == line }) ch.lines.add(StreamLine(line, ua, ref)) }
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
