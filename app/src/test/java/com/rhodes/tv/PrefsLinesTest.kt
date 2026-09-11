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
