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
                // 频道号：云端规范库下发（CCTV-1=1 … CCTV-5+=18 … 卫视 101+ …），电视端左侧数字方块与组内排序都用它
                val chno = attrs["tvg-chno"]?.trim()?.toIntOrNull()
                // 占位频道标记：云端体检后该频道当前无任何可用线路，仍下发以便列表保持固定顺序（灰显不可播）
                val isDead = attrs["rhodes-dead"] == "1"
                // key 优先用云端规范 id（跨源一致，线路归并最准），退回归一化频道名
                val key = tvgId ?: GroupRules.normName(rawName)
                val ch = out.getOrPut(key) {
                    Channel(key = key, name = name, group = group,
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }, tvgId = tvgId,
                        chno = chno, dead = isDead)
                }
                pending = ch
                if (chno != null) ch.chno = chno
                if (isDead) ch.dead = true
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
                // 后到的源若带来了真实线路，覆盖「占位/暂无线路」状态；频道号也以有值的为准
                if (c.lines.any { !it.url.startsWith(Channel.DEAD_SCHEME) }) exist.dead = false
                if (exist.chno == null) exist.chno = c.chno
            }
        }
    }
}
