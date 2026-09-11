package com.rhodes.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class MainActivity : Activity(), AdapterView.OnItemSelectedListener, AdapterView.OnItemClickListener {

    private lateinit var playerView: PlayerView
    private lateinit var leftPanel: View
    private lateinit var groupBar: LinearLayout
    private lateinit var list: ListView
    private lateinit var osd: View
    private lateinit var tvOsdName: TextView
    private lateinit var tvOsdLine: TextView
    private lateinit var epgBar: View
    private lateinit var tvEpgNow: TextView
    private lateinit var tvEpgNext: TextView
    private lateinit var statusBox: View
    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvPanelFoot: TextView

    private lateinit var player: PlayerController
    private var server: ConfigServer? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    private var all: MutableList<Channel> = ArrayList()
    private var shown: List<Channel> = ArrayList()
    private var groupNames: List<String> = ArrayList()
    private var favGroupIndex = -1
    private var curGroup = 0
    private var curPos = -1
    private var curLine = 1
    private var panelVisible = true
    private var loading = false

    private val hideTask = Runnable { setPanel(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        leftPanel = findViewById(R.id.leftPanel)
        groupBar = findViewById(R.id.groupBar)
        list = findViewById(R.id.channelList)
        osd = findViewById(R.id.osd)
        tvOsdName = findViewById(R.id.tvOsdName)
        tvOsdLine = findViewById(R.id.tvOsdLine)
        epgBar = findViewById(R.id.epgBar)
        tvEpgNow = findViewById(R.id.tvEpgNow)
        tvEpgNext = findViewById(R.id.tvEpgNext)
        statusBox = findViewById(R.id.statusBox)
        tvStatus = findViewById(R.id.tvStatus)
        tvHint = findViewById(R.id.tvHint)
        tvPanelFoot = findViewById(R.id.tvPanelFoot)

        list.setOnItemSelectedListener(this)
        list.setOnItemClickListener(this)
        list.setOnItemLongClickListener { _, _, position, _ ->
            toggleFavAt(position)
            true
        }
        list.isFocusable = true

        player = PlayerController(this, playerView)
        player.onLineChanged = { no, total ->
            curLine = no
            tvOsdLine.text = if (total > 1) "\u7ebf\u8def " + no + "/" + total else ""
            tvPanelFoot.text = footText()
        }
        player.onAllFailed = { msg ->
            tvHint.text = "\u5f53\u524d\u9891\u9053\u6240\u6709\u7ebf\u8def\u90fd\u64ad\u4e0d\u4e86\uff08" + msg + "\uff09\n\u8bf7\u7528\u300c\u83dc\u5355\u300d\u952e\u6362\u4e2a\u6e90\uff0c\u6216\u5728\u624b\u673a\u4e0a\u5237\u65b0\u76f4\u64ad\u6e90"
            statusBox.visibility = View.VISIBLE
            tvStatus.text = "\u6682\u65f6\u65e0\u6cd5\u64ad\u653e"
            TvState.onError(msg)
        }
        server = ConfigServer.shared(this)
        server?.let { srv ->
            srv.refreshHook = {
                TvState.lastRefreshAt = System.currentTimeMillis()
                TvState.lastRefreshResult = "requested by phone"
                runOnUiThread { refreshChannels(true) }
            }
        }
        UdpBeacon.shared(this)
        player.init()
        setPanel(true)
        showStatus("\u6b63\u5728\u52a0\u8f7d\u9891\u9053\u2026", "")
        UpdateWorker.schedule(this)
        refreshChannels(true)
    }

    // ---------- 数据加载 ----------

    private fun refreshChannels(first: Boolean) {
        if (loading) return
        loading = true
        if (all.isEmpty()) showStatus("\u6b63\u5728\u52a0\u8f7d\u9891\u9053\u2026", "")
        scope.launch {
            val (data, err) = withContext(Dispatchers.IO) {
                try {
                    val d = ChannelStore.fetchAll(this@MainActivity)
                    ChannelStore.save(this@MainActivity, d)
                    d to null
                } catch (e: Exception) {
                    val c = ChannelStore.load(this@MainActivity)
                    if (c.isEmpty()) ArrayList<Channel>() to (e.message ?: "unknown")
                    else c to null
                }
            }
            loading = false
            all = ArrayList(data)
            if (all.isEmpty()) {
                showStatus("\u6ca1\u6709\u9891\u9053\u6570\u636e",
                    "\u8bf7\u6309\u300c\u83dc\u5355\u300d\u952e\u8fdb\u5165\u8bbe\u7f6e\uff0c\u6dfb\u52a0\u76f4\u64ad\u6e90\u540e\u4fdd\u5b58\u5237\u65b0\n\uff08" + (err ?: "") + "\uff09")
                return@launch
            }
            buildGroups()
            val last = Prefs.get(this@MainActivity).getInt("last_ch", 0)
            curPos = -1
            val start = if (last in 0 until shown.size) last else 0
            list.setSelection(start)
            statusBox.visibility = View.GONE
            playChannel(start)
            list.requestFocus()
            loadEpgAsync()
            scheduleHide()
        }
    }

    private fun loadEpgAsync() {
        if (Prefs.list(this, Prefs.KEY_EPG).isEmpty()) return
        if (EpgStore.count() > 0) return
        EpgStore.loadCache(this)
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    EpgStore.load(this@MainActivity)
                } catch (e: Exception) {
                }
            }
            player.channel()?.let { updateEpg(it) }
        }
    }

    // ---------- 分组与列表 ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildGroups() {
        rebuildGroups(keep = false)
    }

    /** 重建分组标签栏：全部 + ⭐收藏（有收藏时置顶）+ 按用户顺序排列且未被隐藏的分组 */
    private fun rebuildGroups(keep: Boolean) {
        val keepGroup = if (keep) groupNames.getOrNull(curGroup) else null
        val keepName = if (keep) shown.getOrNull(curPos)?.name else null
        groupBar.removeAllViews()

        val favs = Prefs.favSet(this)
        val raw = LinkedHashSet<String>()
        for (c in all) raw.add(c.group)
        val hidden = Prefs.hiddenGroups(this)
        val ordered = ArrayList<String>()
        for (g in Prefs.groupOrder(this)) if (raw.contains(g) && !hidden.contains(g)) ordered.add(g)
        for (g in raw) if (!ordered.contains(g) && !hidden.contains(g)) ordered.add(g)

        val names = ArrayList<String>()
        names.add("\u5168\u90e8")
        favGroupIndex = if (favs.isEmpty()) -1 else 1
        if (favGroupIndex > 0) names.add(Prefs.FAV_GROUP)
        names.addAll(ordered)
        groupNames = names

        for (i in names.indices) {
            val tv = TextView(this)
            tv.text = names[i]
            tv.textSize = 19f
            tv.setPadding(dp(24), dp(12), dp(24), dp(12))
            tv.background = getDrawable(R.drawable.sel_chip)
            tv.isFocusable = true
            tv.isClickable = true
            tv.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.fg))
            tv.setOnFocusChangeListener { v, has ->
                if (has) {
                    selectGroup(i)
                    v.isSelected = true
                } else {
                    v.isSelected = i == curGroup
                }
            }
            tv.setOnClickListener {
                list.requestFocus()
                if (list.childCount > 0) list.setSelection(0)
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(10)
            groupBar.addView(tv, lp)
        }

        val gi = if (keepGroup != null) names.indexOf(keepGroup) else 0
        selectGroup(if (gi >= 0) gi else if (names.size > 1) 1 else 0)
        if (keepName != null) {
            val idx = shown.indexOfFirst { it.name == keepName }
            if (idx >= 0) {
                list.setSelection(idx)
                playChannel(idx)
            }
        }
    }

    private fun selectGroup(i: Int) {
        curGroup = i
        val favs = Prefs.favSet(this)
        shown = when {
            i <= 0 || i >= groupNames.size -> all
            favGroupIndex > 0 && i == favGroupIndex -> all.filter { favs.contains(it.name) }
            else -> all.filter { it.group == groupNames[i] }
        }
        list.adapter = ChAdapter(shown)
        list.setSelection(0)
        curPos = -1
        if (shown.isNotEmpty()) playChannel(0)
    }

    /** 长按 OK：加入 / 取消收藏（按频道名，源刷新后依然有效） */
    private fun toggleFavAt(pos: Int) {
        if (pos < 0 || pos >= shown.size) return
        val name = shown[pos].name
        val added = Prefs.toggleFav(this, name)
        val msg = (if (added) "\u5df2\u6536\u85cf\uff1a" else "\u5df2\u53d6\u6d88\u6536\u85cf\uff1a") + name
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        rebuildGroups(keep = true)
        val idx = shown.indexOfFirst { it.name == name }
        if (idx >= 0) {
            list.setSelection(idx)
            playChannel(idx)
        }
        tvPanelFoot.text = footText()
    }

    private inner class ChAdapter(private val data: List<Channel>) : BaseAdapter() {
        override fun getCount(): Int = data.size
        override fun getItem(position: Int): Any = data[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_channel, parent, false)
            v.findViewById<TextView>(R.id.tvNo).text = (position + 1).toString()
            val nm = data[position].name
            val star = if (Prefs.isFav(this@MainActivity, nm)) " \u2b50" else ""
            v.findViewById<TextView>(R.id.tvName).text = nm + star
            return v
        }
    }

    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        scheduleHide()
        if (position != curPos) playChannel(position)
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        playChannel(position)
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
    }

    private fun playChannel(pos: Int) {
        if (pos < 0 || pos >= shown.size) return
        curPos = pos
        val ch = shown[pos]
        player.play(ch, 0)
        TvState.onPlay(ch, pos)
        tvOsdName.text = ch.name
        tvOsdLine.text = ""
        osd.visibility = if (panelVisible) View.GONE else View.VISIBLE
        Prefs.get(this).edit().putInt("last_ch", pos).apply()
        statusBox.visibility = View.GONE
        updateEpg(ch)
        tvPanelFoot.text = footText()
    }

    private fun updateEpg(ch: Channel) {
        val (now, next) = EpgStore.nowNext(ch)
        if (now == null && next == null) {
            epgBar.visibility = View.GONE
            return
        }
        epgBar.visibility = View.VISIBLE
        tvEpgNow.text = "\u6b63\u5728\u64ad\uff1a" + (now?.title ?: "\u2014") + "   " + range(now)
        tvEpgNext.text = "\u63a5\u4e0b\u6765\uff1a" + (next?.title ?: "\u2014") + "   " + range(next)
    }

    private fun range(p: EpgProgramme?): String {
        if (p == null) return ""
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        return f.format(Date(p.start)) + "-" + f.format(Date(p.stop))
    }

    private fun footText(): String {
        val t = ChannelStore.cacheTime(this)
        val when_ = if (t <= 0) "\u5c1a\u672a\u66f4\u65b0" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))
        val n = if (curPos in shown.indices) (curPos + 1) else 0
        return "\u5171 " + shown.size + " \u4e2a\u9891\u9053 \u00b7 \u7b2c " + n + " \u4e2a\n\u66f4\u65b0\uff1a" + when_ +
            "\n\u7ebf\u8def\uff1a" + curLine + "/" + player.lineTotal() +
            "\n\u83dc\u5355\u952e = \u8bbe\u7f6e    \u8fd4\u56de\u952e = \u9690\u85cf\u5217\u8868\n\u5de6\u53f3\u952e\u5207\u7ebf\u8def    \u957f\u6309OK = \u6536\u85cf"
    }

    private fun showStatus(title: String, hint: String) {
        statusBox.visibility = View.VISIBLE
        tvStatus.text = title
        tvHint.text = hint
    }

    // ---------- 显示控制 ----------

    private var numBuf = 0
    private val numTask = Runnable { jumpTo(numBuf) }

    private fun jumpTo(n: Int) {
        numBuf = 0
        if (n <= 0) return
        if (n <= shown.size) {
            val p = n - 1
            list.setSelection(p)
            playChannel(p)
        } else {
            tvPanelFoot.text = "没有第 " + n + " 个频道（共 " + shown.size + " 个）"
        }
    }

    private fun setPanel(visible: Boolean) {
        panelVisible = visible
        leftPanel.visibility = if (visible) View.VISIBLE else View.GONE
        osd.visibility = if (visible) View.GONE else View.VISIBLE
        if (visible) {
            if (curPos >= 0) list.requestFocus()
            scheduleHide()
        } else {
            handler.removeCallbacks(hideTask)
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideTask)
        if (panelVisible) handler.postDelayed(hideTask, 25000)
    }

    // ---------- 按键 ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                setPanel(!panelVisible)
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!panelVisible) {
                    setPanel(true)
                    return true
                }
                player.switchLine(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!panelVisible) {
                    setPanel(true)
                    return true
                }
                player.switchLine(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!panelVisible) {
                    setPanel(true)
                    list.setSelection(if (curPos < 0) 0 else curPos)
                    return true
                }
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                step(1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                step(-1)
                return true
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9 -> {
                val d = keyCode - KeyEvent.KEYCODE_0
                numBuf = if (numBuf > 999) d else numBuf * 10 + d
                if (!panelVisible) setPanel(true)
                tvPanelFoot.text = "跳转到 " + numBuf + " …"
                handler.removeCallbacks(numTask)
                handler.postDelayed(numTask, 1500)
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                setPanel(!panelVisible)
                return true
            }
        }
        scheduleHide()
        return super.onKeyDown(keyCode, event)
    }

    private fun step(delta: Int) {
        if (shown.isEmpty()) return
        val p = ((curPos + delta) % shown.size + shown.size) % shown.size
        list.setSelection(p)
        playChannel(p)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(hideTask)
    }

    override fun onResume() {
        super.onResume()
        if (all.isNotEmpty()) rebuildGroups(keep = true)
        if (panelVisible) scheduleHide()
        if (curPos >= 0) {
            updateEpg(shown[curPos])
            tvPanelFoot.text = footText()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.coroutineContext[Job]?.cancel()
        player.release()
        super.onDestroy()
    }
}
