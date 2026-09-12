package com.rhodes.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 线路记忆 / 坏线路黑名单 的存储逻辑单测（失败即切的记忆基础）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrefsLinesTest {

    /**
     * v1.8 修复回归：收藏用归一化名匹配。
     * 历史收藏里存的是上游旧名（CCTV1 综合高清），云端规范名是「CCTV-1 综合」，
     * 旧实现用 names.contains(name) 精确比较，导致收藏分组里空无一物（用户看到「收藏没了」）。
     */
    @Test
    fun fav_matches_legacy_channel_names() {
        val ctx = RuntimeEnvironment.getApplication()
        Prefs.get(ctx).edit().clear().commit()

        Prefs.toggleFav(ctx, "CCTV1 综合高清")            // 模拟 v1.6 时代收藏的旧名
        assertTrue("旧名收藏应被新规范名命中", Prefs.isFavChannel(ctx, "CCTV-1 综合"))
        assertTrue("收藏分组不应是空的", Prefs.favSet(ctx).isNotEmpty())

        // 在收藏分组里按新名取消收藏 → 要原地移除旧记录，不能变成两条
        assertEquals("按新名取消收藏应命中旧记录", false, Prefs.toggleFavChannel(ctx, "CCTV-1 综合"))
        assertEquals("等价项应被移除且不新增", 0, Prefs.favSet(ctx).size)

        // 收藏 / 取消 往返
        assertEquals(true, Prefs.toggleFavChannel(ctx, "CCTV-1 综合"))
        assertEquals(listOf("CCTV-1 综合"), Prefs.favSet(ctx).toList())

        Prefs.get(ctx).edit().clear().commit()
    }

    @Test
    fun line_memory_save_and_read_back() {
        val ctx = RuntimeEnvironment.getApplication()
        Prefs.get(ctx).edit().clear().commit()

        assertEquals("无记忆应为 -1", -1, Prefs.lineMem(ctx, "CCTV-1 综合"))

        Prefs.saveLineMem(ctx, "CCTV-1 综合", 2)
        assertEquals(2, Prefs.lineMem(ctx, "CCTV-1 综合"))
        assertEquals(-1, Prefs.lineMem(ctx, "别的频道"))

        Prefs.saveLineMem(ctx, "CCTV-1 综合", 1)
        assertEquals("重复保存同一频道应覆盖而不是追加", 1, Prefs.lineMem(ctx, "CCTV-1 综合"))

        Prefs.get(ctx).edit().clear().commit()
    }

    @Test
    fun bad_lines_mark_and_clear() {
        val ctx = RuntimeEnvironment.getApplication()
        Prefs.get(ctx).edit().clear().commit()

        assertTrue(Prefs.badLines(ctx).isEmpty())

        Prefs.markBad(ctx, "http://a/1")
        Prefs.markBad(ctx, "http://b/2")
        assertTrue(Prefs.badLines(ctx).contains("http://a/1"))
        assertTrue(Prefs.badLines(ctx).contains("http://b/2"))

        Prefs.clearBad(ctx, "http://a/1")
        assertTrue(!Prefs.badLines(ctx).contains("http://a/1"))
        assertTrue(Prefs.badLines(ctx).contains("http://b/2"))

        Prefs.get(ctx).edit().clear().commit()
    }
}
