package com.rhodes.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「按名称重分类」规则单测：期望值全部由同源原型脚本 classify_proto.py 在真实源上跑出来后抄来。 */
class GroupRulesTest {

    @Test
    fun classify_typical_real_world_names() {
        val cases = listOf(
            Triple("CCTV-13 新闻", "", "央视频道"),
            Triple("CCTV-5体育", "", "央视频道"),
            Triple("CGTN纪录", "", "央视频道"),
            Triple("CCTV-14少儿", "", "央视频道"),
            Triple("CCTV-6电影", "", "央视频道"),
            Triple("湖南卫视", "", "卫视频道"),
            Triple("东方卫视", "", "卫视频道"),
            Triple("厦门卫视", "", "卫视频道"),
            Triple("Dragon TV International", "卫视", "卫视频道"),
            Triple("凤凰中文台", "", "港澳台频道"),
            Triple("翡翠台", "", "港澳台频道"),
            Triple("NOW NEWS", "", "港澳台频道"),
            Triple("纬来体育", "", "港澳台频道"),
            Triple("FOX News", "", "国际频道"),
            Triple("辽宁频道", "", "地方频道"),
            Triple("萧山生活频道", "", "地方频道"),
            Triple("Lanzhou Comprehensive News Channel", "", "地方频道"),
            Triple("鬼吹灯之精绝古城", "电视剧频道", "影视综艺"),
            Triple("环球旅游", "", "纪录人文"),
            Triple("金鹰卡通", "", "少儿动画"),
            Triple("AMC音乐", "", "音乐戏曲"),
            Triple("车迷频道", "", "其他频道")
        )
        for ((n, up, want) in cases) assertEquals("$n (上游组=$up)", want, GroupRules.classify(n, up))
    }

    @Test
    fun upstream_group_used_when_name_has_no_keyword() {
        val cases = listOf(
            Triple("某某某", "卫视", "其他频道"),
            Triple("某某某", "电视剧频道", "影视综艺"),
            Triple("某某某", "直播中国", "纪录人文"),
            Triple("某某某", "电影频道", "影视综艺"),
            Triple("某某某", "随便写写", "其他频道"),
            Triple("某某某", "", "其他频道"),
        )
        for ((n, up, want) in cases) assertEquals("$n (上游组=$up)", want, GroupRules.classify(n, up))
    }

    @Test
    fun parse_merges_same_channel_into_multi_lines() {
        val m3u = listOf(
            "#EXTM3U",
            "#EXTINF:-1 tvg-id=\"cctv1\" group-title=\"乱组A\",CCTV-1 综合",
            "http://h/1",
            "#EXTINF:-1 tvg-id=\"cctv1\" group-title=\"乱组B\",CCTV1综合(1080P)",
            "http://h/2",
            "#EXTINF:-1 tvg-id=\"cctv1\" group-title=\"乱组C\",CCTV-1 综合 (1080p)",
            "http://h/3",
            "#EXTINF:-1 tvg-id=\"hunan\" group-title=\"乱组D\",湖南卫视",
            "http://h/4"
        ).joinToString("\n")
        val chs = M3uParser.parse(m3u)
        assertEquals(2, chs.size)
        assertEquals("同频道 3 条线路应合并", 3, chs[0].lines.size)
        assertEquals("央视频道", chs[0].group)
        assertEquals("卫视频道", chs[1].group)
    }

    @Test
    fun parse_dedups_identical_url() {
        val m3u = listOf(
            "#EXTM3U",
            "#EXTINF:-1 group-title=\"x\",CCTV-1 综合",
            "http://h/same",
            "#EXTINF:-1 group-title=\"x\",CCTV1综合",
            "http://h/same"
        ).joinToString("\n")
        val chs = M3uParser.parse(m3u)
        assertEquals(1, chs.size)
        assertEquals(1, chs[0].lines.size)
    }

    /** v1.8：云端规范库的频道号(tvg-chno)与占位标记(rhodes-dead)解析、组内排序、占位不可播 */
    @Test
    fun v18_chno_parse_and_sort_with_dead_placeholder() {
        val m3u = listOf(
            "#EXTM3U",
            "#EXTINF:-1 tvg-id=\"CCTV5P\" tvg-chno=\"18\" group-title=\"乱组X\",CCTV-5+ 体育赛事",
            "http://h/18",
            "#EXTINF:-1 tvg-id=\"CCTV1\" tvg-chno=\"1\" group-title=\"乱组Y\",CCTV-1 综合",
            "http://h/1",
            "#EXTINF:-1 tvg-id=\"CCTV16\" tvg-chno=\"16\" group-title=\"乱组Z\" rhodes-dead=\"1\",CCTV-16 奥林匹克",
            "dead://CCTV16"
        ).joinToString("\n")
        val chs = M3uParser.parse(m3u)
        assertEquals(3, chs.size)
        val dead = chs.first { it.tvgId == "CCTV16" }
        assertEquals(16, dead.chno)
        assertEquals(true, dead.dead)
        assertEquals("dead:// 占位不算可用线路", false, dead.playable)
        assertEquals(true, chs.first { it.tvgId == "CCTV1" }.playable)
        // 组内排序按频道号：1 → 16(占位灰显) → 18，与上游给的乱组顺序无关
        val sorted = GroupRules.sortChannels(chs)
        assertEquals(listOf(1, 16, 18), sorted.map { it.chno })
        assertEquals("CCTV-1 综合", sorted[0].name)
    }

    /** v1.8 修复回归：收藏必须兼容历史旧频道名，否则老收藏会「消失」在收藏分组里 */
    @Test
    fun v18_fav_same_normalizes_legacy_names() {
        // 上游旧名 → 云端规范名
        assertEquals(true, GroupRules.favSame("CCTV-1 综合", "CCTV1综合"))
        assertEquals(true, GroupRules.favSame("CCTV-1 综合", "CCTV1 综合高清"))
        assertEquals(true, GroupRules.favSame("CCTV-1 综合", "CCTV-1 综合 (1080p)"))
        assertEquals(true, GroupRules.favSame("CCTV-5体育", "cctv5 体育 高清"))
        assertEquals(true, GroupRules.favSame("湖南卫视", "湖南卫视"))
        // 不同频道绝不能被归一化成同一个
        assertEquals(false, GroupRules.favSame("CCTV-1 综合", "CCTV-2 财经"))
        assertEquals(false, GroupRules.favSame("CCTV-5体育", "CCTV-5+ 体育赛事"))
        assertEquals(false, GroupRules.favSame("湖南卫视", "湖南经视"))
        // 关键回归：v1.6 时代存的旧名要能被 v1.8 的规范名命中
        assertEquals(GroupRules.favNorm("CCTV-1 综合"), GroupRules.favNorm("CCTV1 综合高清"))
    }
}
