package com.rhodes.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : Activity() {

    private lateinit var etSubs: EditText
    private lateinit var etEpg: EditText
    private lateinit var etUa: EditText
    private lateinit var tvState: TextView
    private lateinit var tvIp: TextView
    private lateinit var server: ConfigServer
    private lateinit var tvVersion: TextView
    private var pending: UpdateInfo? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etSubs = findViewById(R.id.etSubs)
        etEpg = findViewById(R.id.etEpg)
        etUa = findViewById(R.id.etUa)
        tvState = findViewById(R.id.tvState)
        tvIp = findViewById(R.id.tvLocalIp)

        etSubs.setText(Prefs.list(this, Prefs.KEY_SUBS).joinToString("\n"))
        etEpg.setText(Prefs.list(this, Prefs.KEY_EPG).joinToString("\n"))
        etUa.setText(Prefs.get(this).getString(Prefs.KEY_UA, "") ?: "")

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
            refresh("正在刷新频道…")
        }
        findViewById<Button>(R.id.btnSaveOnly).setOnClickListener {
            save()
            tvState.text = "已保存"
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnReloadEpg).setOnClickListener {
            save()
            refreshEpg()
        }

        findViewById<Button>(R.id.btnManageGroups).setOnClickListener {
            save()
            startActivity(Intent(this, GroupManageActivity::class.java))
        }

        server = ConfigServer.shared(this)
        tvIp.text = "http://" + ConfigServer.localIp() + ":" + server.port + "/"
        tvState.text = "已缓存频道：" + ChannelStore.load(this).size + " 个"

        // ---------- 一键远程更新 ----------
        tvVersion = findViewById(R.id.tvVersion)
        tvVersion.text = "当前版本 " + Updater.currentLabel(this)
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            save()
            checkUpdate(true)
        }
        findViewById<Button>(R.id.btnDoUpdate).setOnClickListener { doUpdate() }
        // 进入设置页时自动检查（默认开启，6 小时节流，不打扰观看）
        if (Prefs.get(this).getBoolean(Updater.KEY_AUTO_CHECK, true) &&
            Updater.shouldAutoCheck(this, 6)
        ) {
            checkUpdate(false)
        }
    }

    private fun save() {
        Prefs.save(this, Prefs.KEY_SUBS, etSubs.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() })
        Prefs.save(this, Prefs.KEY_EPG, etEpg.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() })
        Prefs.get(this).edit().putString(Prefs.KEY_UA, etUa.text.toString().trim()).apply()
    }

    private fun refresh(title: String) {
        tvState.text = title
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val d = ChannelStore.fetchAll(this@SettingsActivity)
                    ChannelStore.save(this@SettingsActivity, d)
                    "刷新成功：共 " + d.size + " 个频道"
                } catch (e: Exception) {
                    "刷新失败：" + (e.message ?: "")
                }
            }
            tvState.text = res
        }
    }

    private fun refreshEpg() {
        tvState.text = "正在刷新节目单…"
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    EpgStore.load(this@SettingsActivity)
                } catch (e: Exception) {
                    0
                }
            }
            tvState.text = "节目单已更新：" + n + " 个频道有节目信息"
        }
    }

    /** @param manual 手动点击：任何结果都写状态栏；自动检查：仅在有新版本时提示 */
    private fun checkUpdate(manual: Boolean) {
        if (manual) tvState.text = "正在检查更新…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    Updater.check(this@SettingsActivity)
                } catch (e: Exception) {
                    "ERR:" + (e.message ?: "未知错误")
                }
            }
            when {
                r is UpdateInfo -> {
                    pending = r
                    tvState.text = "发现新版本 v" + r.versionName + "（当前 " +
                        Updater.currentName(this@SettingsActivity) + "）\n" +
                        (if (r.note.isNotEmpty()) r.note + "\n" else "") +
                        "按【一键更新】下载并安装"
                }
                r == null -> if (manual) tvState.text = "已是最新版本 " + Updater.currentLabel(this@SettingsActivity)
                else -> if (manual) tvState.text = "检查更新失败：" + r.toString().removePrefix("ERR:")
            }
        }
    }

    private fun doUpdate() {
        val info = pending
        if (info == null) {
            tvState.text = "请先按【检查更新】"
            checkUpdate(true)
            return
        }
        tvState.text = "正在下载 v" + info.versionName + " … 0%"
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                try {
                    Updater.download(this@SettingsActivity, info) { pct ->
                        runOnUiThread {
                            tvState.text = "正在下载 v" + info.versionName + " … " +
                                (if (pct >= 0) pct.toString() + "%" else "…")
                        }
                    }
                } catch (e: Exception) {
                    null to (e.message ?: "下载失败")
                }
            }
            @Suppress("UNCHECKED_CAST")
            val file = f as? java.io.File
            if (file == null) {
                tvState.text = "下载失败：" + (f as? Pair<*, *>)?.second
                return@launch
            }
            tvState.text = "下载完成，正在调起安装器…"
            val ok = Updater.install(this@SettingsActivity, file)
            tvState.text = if (ok) "已在安装界面：请用遥控器选择【安装】完成更新"
            else "请先允许本应用「安装未知应用」，返回后再按一次【一键更新】"
        }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
