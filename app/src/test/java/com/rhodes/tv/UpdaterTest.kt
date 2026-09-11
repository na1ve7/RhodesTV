package com.rhodes.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 「应用内一键更新」逻辑测试。
 * 网络层可注入（Robolectric 下真实 socket 不可用于此场景），
 * 覆盖：清单解析 / 版本比对 / 多镜像回退 / 无效清单跳过 / 全失败抛错 / sha256 校验 / 节流。
 */
@RunWith(RobolectricTestRunner::class)
class UpdaterTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        Prefs.save(ctx, Updater.KEY_UPDATE_URLS, emptyList<String>())
        Prefs.get(ctx).edit().remove(Updater.KEY_LAST_CHECK).apply()
    }

    private fun setUrls(vararg u: String) = Prefs.save(ctx, Updater.KEY_UPDATE_URLS, u.toList())

    private fun json(code: Int, name: String = "9.9", apk: String = "https://x/a.apk", note: String = "") =
        """{"versionCode":$code,"versionName":"$name","apkUrl":"$apk","note":"$note"}"""

    /** 1) 清单解析：字段完整 */
    @Test
    fun parsesManifest() {
        val i = Updater.parseManifest(
            """{"versionCode":42,"versionName":"4.2","apkUrl":"https://x/b.apk","sha256":"AB","note":"修复"}"""
        )
        assertNotNull(i)
        assertEquals(42, i!!.versionCode)
        assertEquals("4.2", i.versionName)
        assertEquals("https://x/b.apk", i.apkUrl)
        assertEquals("修复", i.note)
        assertEquals("AB", i.sha256)
    }

    /** 2) 清单缺 apkUrl → 视为无效 */
    @Test
    fun rejectsManifestWithoutApkUrl() {
        assertNull(Updater.parseManifest("""{"versionCode":42,"versionName":"4.2"}"""))
    }

    /** 3) 版本比对：更大才算新 */
    @Test
    fun versionCompare() {
        assertTrue(Updater.isNewer(3, 2))
        assertTrue(!Updater.isNewer(2, 2))
        assertTrue(!Updater.isNewer(1, 2))
    }

    /** 4) 有新版（远程 9999）→ 返回信息，且记录检查时间 */
    @Test
    fun detectsNewVersion() {
        setUrls("http://mirror1/update.json")
        Updater.manifestFetcher = { _ -> json(9999, "9.9", "https://x/a.apk", "测试更新") }
        val info = Updater.check(ctx)
        assertNotNull("应检测到新版本", info)
        assertEquals(9999, info!!.versionCode)
        assertEquals("测试更新", info.note)
        assertTrue("应记录检查时间", Prefs.get(ctx).getLong(Updater.KEY_LAST_CHECK, 0L) > 0)
    }

    /** 5) 远程版本 <= 当前 → null（不提示更新） */
    @Test
    fun noUpdateWhenNotNewer() {
        val cur = Updater.currentCode(ctx)
        setUrls("http://mirror1/update.json")
        Updater.manifestFetcher = { _ -> json(cur) }
        assertNull("同版本不应提示", Updater.check(ctx))
        Updater.manifestFetcher = { _ -> json(cur - 1) }
        assertNull("旧版本不应提示", Updater.check(ctx))
    }

    /** 6) 第一个镜像失败 → 自动回退第二个镜像 */
    @Test
    fun fallsBackToSecondMirror() {
        setUrls("http://bad/update.json", "http://good/update.json")
        var calls = 0
        Updater.manifestFetcher = { u ->
            calls++
            if (u.contains("bad")) throw java.io.IOException("connection refused")
            json(8888, "8.8")
        }
        val info = Updater.check(ctx)
        assertNotNull("应回退到可用镜像", info)
        assertEquals(8888, info!!.versionCode)
        assertEquals("应尝试两次", 2, calls)
    }

    /** 7) 第一个镜像返回无效清单（无 apkUrl）→ 继续下一个镜像 */
    @Test
    fun skipsBrokenManifest() {
        setUrls("http://bad/update.json", "http://good/update.json")
        Updater.manifestFetcher = { u ->
            if (u.contains("bad")) """{"versionCode":10}""" else json(7777, "7.7")
        }
        val info = Updater.check(ctx)
        assertNotNull("无效清单应被跳过", info)
        assertEquals(7777, info!!.versionCode)
    }

    /** 8) 全部镜像失败 → 抛异常（UI 显示"检查失败"） */
    @Test
    fun allMirrorsFailThrows() {
        setUrls("http://bad1/update.json", "http://bad2/update.json")
        Updater.manifestFetcher = { throw java.io.IOException("timeout") }
        try {
            Updater.check(ctx)
            fail("全部镜像失败应抛异常")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("timeout"))
        }
    }

    /** 9) 下载 + sha256 正确 → 临时文件转正、内容一致、进度回调触发 */
    @Test
    fun downloadAndVerifyOk() {
        val payload = ByteArray(200 * 1024) { (it % 253).toByte() }
        val pctList = ArrayList<Int>()
        Updater.fileDownloader = { _, tmp, cb ->
            tmp.writeBytes(payload); cb(50); cb(100)
        }
        val info = UpdateInfo(777, "7.7", "https://x/a.apk", "", sha256Of(payload))
        val out = Updater.download(ctx, info) { pctList.add(it) }
        assertTrue("最终文件应存在", out.exists())
        assertEquals(payload.size.toLong(), out.length())
        assertEquals(sha256Of(payload), Updater.sha256(out))
        assertTrue("应有进度回调", pctList.contains(100))
        assertTrue("临时文件应清理", !File(ctx.cacheDir, out.name + ".part").exists())
    }

    /** 10) sha256 不匹配 → 抛"校验失败"并删除残留文件 */
    @Test
    fun downloadRejectsBadSha256() {
        Updater.fileDownloader = { _, tmp, _ -> tmp.writeBytes(ByteArray(1024) { 7 }) }
        val bad = "0".repeat(64)
        try {
            Updater.download(ctx, UpdateInfo(666, "6.6", "https://x/a.apk", "", bad)) { }
            fail("校验失败应抛异常")
        } catch (e: Exception) {
            assertTrue("应是校验失败信息: " + e.message, (e.message ?: "").contains("校验失败"))
        }
        assertTrue("损坏文件必须被删除", !File(ctx.cacheDir, "RhodesTV-update-666.apk.part").exists())
        assertTrue("不得产出最终文件", !File(ctx.cacheDir, "RhodesTV-update-666.apk").exists())
    }

    /** 11) 自动检查节流：6 小时内不重复自动检查 */
    @Test
    fun autoCheckThrottled() {
        Prefs.get(ctx).edit().putLong(Updater.KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        assertTrue("刚查过应节流", !Updater.shouldAutoCheck(ctx, 6))
        Prefs.get(ctx).edit()
            .putLong(Updater.KEY_LAST_CHECK, System.currentTimeMillis() - 7 * 3600_000L).apply()
        assertTrue("超过6小时应允许自动检查", Updater.shouldAutoCheck(ctx, 6))
    }

    private fun sha256Of(b: ByteArray): String {
        val f = File(ctx.cacheDir, "tmp_sha.bin")
        f.writeBytes(b)
        return Updater.sha256(f)
    }
}
