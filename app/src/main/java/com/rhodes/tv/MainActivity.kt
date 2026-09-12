package com.rhodes.tv

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.AdapterView
import android.widget.BaseAdapter
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

/**
 * 罗德岛TV 主界面 v1.6 —— 纯视频背景 + 双栏菜单。
 *
 * 视觉：左导航栏贴边整块半透明深色，频道卡悬浮(圆角+阴影)半透明深色（不做模糊）；
 * 呼出菜单时视频先轻微压暗 + 轻微缩放，双栏从左侧滑入；收起时反向。
 * 交互：菜单键(短按)呼出/收起，返回键收起；数字键按「频道号」直接跳转；
 * 上下选台、左右在分栏间移动(到头即切线路)；长按OK收藏；左栏末项/长按菜单键进设置。
 * 注意：播放时不再显示左上角频道名大字，也不再有底部 EPG 条（节目名合入频道行）。
 */
@UnstableApi
class MainActivity : Activity(), AdapterView.OnItemSelectedListener, AdapterView.OnItemClickListener {

    companion object {
        /** 左栏「我的收藏」分组的固定下标：紧跟「全部频道」，恒显示（空组只留空列表，不放占位项）。 */
        private const val FAV_GROUP = 1
    }

    private lateinit var playerView: PlayerView
    private lateinit var videoDim: View
    private lateinit var menuRoot: View
    private lateinit var navPanel: View
    private lateinit var navList: ListView
    private lateinit var tvBrand: TextView
    private lateinit var cardPanel: View
    private lateinit var tvCardTitle: TextView
    private lateinit var tvHintBar: TextView
    private lateinit var list: ListView
    private lateinit var tvToast: TextView
    private lateinit var statusBox: View
    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView

    private lateinit var player: PlayerController
    private var server: ConfigServer? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    private var all: MutableList<Channel> = ArrayList()
    private var shown: List<Channel> = ArrayList()
    private var groupNames: List<String> = ArrayList()
    private var curGroup = 0
    private var curPos = -1                 // 「正在播放」的频道在 shown 中的下标（-1 = 没有正在播放的）
    private var selectedPos = -1            // 「光标/高亮」位置，与正在播放解耦：上下键只动它，不切台
    private var playingName: String? = null // 正在播放频道的名字：换组时用它把光标定位回正在播放那一行
    private var suppressCursor = false      // true = 接下来的 onItemSelected 是程序化定位，不当作用户移动
    private var curLine = 1
    private var panelVisible = false
    private var loading = false
    private var menuW = 0
    private var numBuf = 0
    private var menuLongFired = false

    private val hideTask = Runnable { setPanel(false) }
    private val clearSuppress = Runnable { suppressCursor = false }
    private val toastTask = Runnable { hideToast() }
    private val numTask = Runnable { jumpTo(numBuf) }
    private val menuLongTask = Runnable { menuLongFired = true; openSettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        videoDim = findViewById(R.id.videoDim)
        menuRoot = findViewById(R.id.menuRoot)
        navPanel = findViewById(R.id.navPanel)
        navList = findViewById(R.id.navList)
        tvBrand = findViewById(R.id.tvBrand)
        cardPanel = findViewById(R.id.cardPanel)
        tvCardTitle = findViewById(R.id.tvCardTitle)
        tvHintBar = findViewById(R.id.tvHintBar)
        list = findViewById(R.id.channelList)
        tvToast = findViewById(R.id.tvToast)
        statusBox = findViewById(R.id.statusBox)
        tvStatus = findViewById(R.id.tvStatus)
        tvHint = findViewById(R.id.tvHint)

        menuW = resources.getDimensionPixelSize(R.dimen.menu_width)
        menuRoot.translationX = -menuW.toFloat()

        // 启动即后台实测本机 IPv6（结果缓存 10 分钟，供 PlayerController 过滤 v6 线路）
        NetInfo.refreshAsync(this)

        list.setOnItemSelectedListener(this)
        list.setOnItemClickListener(this)
        list.setOnItemLongClickListener { _, _, position, _ ->
            toggleFavAt(position)
            true
        }
        list.isFocusable = true
        list.setOnKeyListener { v, keyCode, event -> onListKey(v, keyCode, event) }

        navList.isFocusable = true
        navList.setOnKeyListener { v, keyCode, event -> onListKey(v, keyCode, event) }
        navList.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                scheduleHide()
                if (position < groupNames.size && position != curGroup) selectGroup(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        })
        navList.setOnItemClickListener { _, _, position, _ ->
            if (position >= groupNames.size) {
                openSettings()
            } else {
                if (position != curGroup) selectGroup(position)
                if (shown.isEmpty()) {
                    navList.requestFocus()      // 空组（如无收藏）焦点留在左栏，别困进空列表
                } else {
                    list.requestFocus()
                    silentSelect(if (selectedPos < 0) 0 else selectedPos)
                }
            }
        }

        player = PlayerController(this, playerView)
        player.onLineChanged = { no, total ->
            curLine = no
            tvHintBar.text = hintText()
            if (total > 1) showToast("线路 " + no + "/" + total)
        }
        player.onRetry = { sec ->
            tvHintBar.text = hintText()
            showToast("全部线路都失败了，" + sec + " 秒后自动重试")
        }
        player.onAllFailed = { msg ->
            tvHint.text = "当前频道所有线路都播不了（" + msg + "）\n请按「菜单」键呼出菜单，在左栏「设置」里换个源，或在手机上刷新直播源"
            statusBox.visibility = View.VISIBLE
            tvStatus.text = "暂时无法播放"
            TvState.onError(msg)
        }
        // 播放成功 → 撤掉状态覆盖层；否则失败后再切到能播的频道，「暂时无法播放」会永久残留
        player.onReady = {
            // onReady 由 ExoPlayer 在应用主线程回调，保险起见统一切回 UI 线程
            runOnUiThread { statusBox.visibility = View.GONE }
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
        showStatus("正在加载频道…", "")
        UpdateWorker.schedule(this)
        refreshChannels(true)
    }

    // ---------- 数据加载 ----------

    private fun refreshChannels(first: Boolean) {
        if (loading) return
        loading = true
        if (all.isEmpty()) showStatus("正在加载频道…", "")
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
                showStatus("没有频道数据",
                    "请按「菜单」键呼出菜单，在左栏「设置」里添加直播源后保存刷新\n（" + (err ?: "") + "）")
                return@launch
            }
            buildGroups()
            val last = Prefs.get(this@MainActivity).getInt("last_ch", 0)
            if (shown.isEmpty()) {
                navList.requestFocus()      // 空组（无收藏）：别把焦点放进空列表，否则遥控器像失灵
            } else {
                val start = if (last in 0 until shown.size) last else 0
                playChannel(start)          // playChannel 内部会撤掉状态覆盖层
                silentSelect(start)         // 光标静默同步到「正在播放那一行」，不触发额外切台
                list.requestFocus()
            }
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
            tvHintBar.text = hintText()
            (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }
    }

    // ---------- 分组 / 数据 ----------

    private fun buildGroups() {
        val hidden = Prefs.hiddenGroups(this)
        val raw = LinkedHashSet<String>()
        for (c in all) if (!hidden.contains(c.group)) raw.add(c.group)
        val order = Prefs.groupOrder(this)
        val ordered = ArrayList<String>()
        for (g in order) if (raw.contains(g)) ordered.add(g)
        // 用户没自定义过的分组 → 按内置固定顺序（央视频道…其他频道）排在后面，刷新后顺序稳定
        for (g in raw.filter { !ordered.contains(it) }.sortedBy { GroupRules.orderOf(it) }) ordered.add(g)

        val names = ArrayList<String>()
        names.add("全部频道")
        // 「我的收藏」恒定占 index 1（紧跟「全部频道」）：空也显示，不随收藏数量增减而隐藏/移动
        names.add(Prefs.FAV_GROUP)
        names.addAll(ordered)
        groupNames = names

        navList.adapter = NavAdapter()
        val sel = if (curGroup in names.indices) curGroup else 0
        navList.setSelection(sel)
        selectGroup(sel)
    }

    private fun selectGroup(index: Int) {
        if (index < 0 || index >= groupNames.size) return
        curGroup = index
        val hidden = Prefs.hiddenGroups(this)
        shown = when {
            index == 0 -> all.filter { !hidden.contains(it.group) }
            index == FAV_GROUP -> favChannels()      // 空收藏 → 空集合，不放「暂无收藏」占位项
            else -> all.filter { it.group == groupNames[index] }
        }
        // 组内排序：分组顺序 → 频道号（CCTV-1≈1…CCTV-5+≈18、卫视 101+）→ 名称
        // 云端下发的规范顺序优先，用户一眼就能找到「央视 1 套」，不用自己数
        shown = GroupRules.sortChannels(shown)
        curPos = playingIndex()          // 「正在播放」的频道在本组的下标（不在本组 = -1）
        (list.adapter as? ChAdapter)?.notifyDataSetChanged() ?: run { list.adapter = ChAdapter() }
        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
        (navList.adapter as? BaseAdapter)?.notifyDataSetChanged()
        tvCardTitle.text = groupTitle()
        tvHintBar.text = hintText()
        if (shown.isEmpty()) {
            // 空组不留占位项：焦点直接交回左栏，避免「焦点困在空列表 → 遥控器像失灵」
            selectedPos = -1
            navList.requestFocus()
        } else {
            // 进组后光标停在「正在播放的那一行」（不在本组则退化为第一行）；静默定位，不触发切台
            silentSelect(playingIndex().coerceAtLeast(0))
        }
    }

    /** 正在播放的频道在当前 shown 中的下标（不在本组 / 尚未播放 → -1）。 */
    private fun playingIndex(): Int {
        val name = playingName ?: return -1
        return shown.indexOfFirst { GroupRules.favSame(it.name, name) }
    }

    /**
     * 程序化定位光标：只动高亮位置，不当作「用户移动」，也就不会触发切台。
     * suppressCursor 由 onItemSelected 消费；若该次 setSelection 没产生回调（位置没变），
     * 用 400ms 的兜底任务清掉标志，避免误吞用户以后的真实移动。
     */
    private fun silentSelect(pos: Int) {
        val p = if (pos < 0) 0 else pos
        selectedPos = p
        suppressCursor = true
        list.setSelection(p)
        handler.removeCallbacks(clearSuppress)
        handler.postDelayed(clearSuppress, 400)
    }

    /**
     * 收藏列表（「我的收藏」分组，永远置顶在左栏第二位；空组也显示，只是列表为空集合，不放占位项）。
     *
     * ① 用归一化名匹配：v1.7 起频道名改成云端规范名（CCTV-1 综合），而历史收藏里存的是上游旧名
     *    （CCTV1综合 / CCTV1 综合高清），精确比较会让老收藏看起来「消失」，这里两者都算命中。
     * ② 已经从源里消失的收藏项（改名/下架）用占位频道保留并灰显，方便长按取消，不静默丢数据。
     */
    private fun favChannels(): List<Channel> {
        val favs = Prefs.favSet(this)
        if (favs.isEmpty()) return emptyList()
        val hit = all.filter { c -> favs.any { GroupRules.favSame(it, c.name) } }
        val got = hit.map { GroupRules.normName(it.name) }.toHashSet()
        val ghosts = favs.filter { GroupRules.normName(it) !in got }.map {
            Channel(
                key = "fav:" + GroupRules.normName(it),
                name = it,
                group = Prefs.FAV_GROUP,
                dead = true,
                lines = mutableListOf(StreamLine(Channel.DEAD_SCHEME + it))
            )
        }
        return hit + ghosts
    }

    private fun groupTitle(): String = groupNames.getOrNull(curGroup) ?: "全部频道"

    private fun hintText(): String {
        val line = if (curLine > 1) "  ｜  " + curLine + "线" else ""
        return shown.size.toString() + " 个频道" + line + "   ｜   上下 移动光标 · OK 播放 · 长按OK 收藏 · ≪ 回导航 · ≫ 切线路 · 数字键 跳频道   ｜   菜单 呼出/长按 设置"
    }

    private fun toggleFavAt(position: Int) {
        val ch = shown.getOrNull(position) ?: return
        // 归一化匹配：兼容历史收藏里的旧频道名（上游名 → 云端规范名），并顺带清理重复的等价收藏
        val added = Prefs.toggleFavChannel(this, ch.name)
        showToast(if (added) "已收藏 " + ch.name else "已取消收藏 " + ch.name)
        // 收藏集合变化 → 重建左栏：「我的收藏」恒在 index 1（空也保留、不隐藏）
        val wasFavGroup = curGroup == FAV_GROUP
        buildGroups()
        if (wasFavGroup && shown.isNotEmpty()) {
            playChannel(0)          // 被取消的项已从收藏组消失 → 自动顶到新的第一项
            silentSelect(0)
        } else if (wasFavGroup) {
            navList.requestFocus()  // 取消掉最后一个收藏 → 收藏组变空，焦点交回左栏
        }
    }

    private fun playChannel(pos: Int) {
        if (pos < 0 || pos >= shown.size) return
        statusBox.visibility = View.GONE   // 任何切台动作先撤掉「暂时无法播放」等覆盖层
        curPos = pos
        selectedPos = pos                   // 「正在播放」与光标保持一致（光标移动本身不触发这里）
        playingName = shown[pos].name
        val ch = shown[pos]
        curLine = 1
        if (!ch.playable) {                     // 云端已标记「暂无线路」的占位频道：不切台、只提示
            showToast("「" + ch.name + "」暂无可用线路")
            return
        }
        // -1 = 走「线路记忆」：优先用该频道上次播放成功的线路，没有记忆则从第 1 条开始
        player.play(ch, -1)
        Prefs.get(this).edit().putInt("last_ch", pos).apply()
        TvState.onPlay(ch, pos)
        tvHintBar.text = hintText()
        handler.removeCallbacks(hideTask)
        scheduleHide()
    }

    private fun jumpTo(n: Int) {
        numBuf = 0
        if (n <= 0 || n > shown.size) {
            showToast("没有频道号 " + n)
            return
        }
        playChannel(n - 1)
        list.requestFocus()
        silentSelect(n - 1)     // 数字键直达：直接播放，并静默同步光标与 selectedPos
    }

    private fun showToast(msg: String) {
        tvToast.text = msg
        tvToast.visibility = View.VISIBLE
        tvToast.animate().cancel()
        tvToast.alpha = 0f
        tvToast.animate().alpha(1f).setDuration(140).start()
        handler.removeCallbacks(toastTask)
        handler.postDelayed(toastTask, 1600)
    }

    private fun hideToast() {
        tvToast.animate().alpha(0f).setDuration(240).withEndAction { tvToast.visibility = View.GONE }.start()
    }

    private fun showStatus(title: String, sub: String) {
        statusBox.visibility = View.VISIBLE
        tvStatus.text = title
        tvHint.text = sub
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ---------- 按键 / 焦点 ----------

    private fun onListKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (v === navList) {
                    if (shown.isEmpty()) return true   // 空组：别把焦点放进空列表
                    list.requestFocus()
                    silentSelect(if (selectedPos < 0) 0 else selectedPos)
                    return true
                }
                if (v === list) {
                    if (player.lineTotal() > 1) {
                        player.switchLine(1)
                        showToast("切换线路")
                    } else {
                        showToast("该频道只有一条线路")
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (v === list) {
                    navList.requestFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return false
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.repeatCount == 0) {
                menuLongFired = false
                handler.postDelayed(menuLongTask, 900)
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 分层退出：状态覆盖层 > 面板 > 交回系统
            if (statusBox.visibility == View.VISIBLE) {
                statusBox.visibility = View.GONE
                return true
            }
            if (panelVisible) {
                setPanel(false)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            val d = keyCode - KeyEvent.KEYCODE_0
            numBuf = if (numBuf > 99) d else numBuf * 10 + d
            handler.removeCallbacks(numTask)
            handler.postDelayed(numTask, 800)
            showToast("跳到 " + numBuf + " 号")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            handler.removeCallbacks(menuLongTask)
            if (!menuLongFired) setPanel(!panelVisible)
            menuLongFired = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * 上下键只移动光标：这里绝不切台，只记录光标位置（ListView 的 listSelector 跟随 selection，
     * 因此更新 selectedPos 即完成高亮刷新）。
     */
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        scheduleHide()
        if (suppressCursor) {   // 程序化定位（进组/数字键/同步）产生的回调，不当作用户移动
            suppressCursor = false
            handler.removeCallbacks(clearSuppress)
        }
        selectedPos = position
    }

    /** OK 键 / 点击：唯一真正的播放入口。 */
    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (position == curPos) showToast("正在播放 " + shown[position].name) else playChannel(position)
        scheduleHide()
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {}

    // ---------- 面板显隐 ----------

    private fun setPanel(visible: Boolean) {
        if (panelVisible == visible) return
        panelVisible = visible
        if (visible) {
            statusBox.visibility = View.GONE   // 菜单一定看得见：先撤掉可能残留的状态覆盖层
            menuRoot.visibility = View.VISIBLE
            if (menuRoot.translationX != 0f) {
                menuRoot.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            }
            videoDim.visibility = View.VISIBLE
            videoDim.animate().alpha(0.38f).setDuration(220).start()
            playerView.animate().scaleX(0.985f).scaleY(0.985f).setDuration(220).start()
            if (selectedPos >= 0 && list.adapter != null && shown.isNotEmpty()) list.requestFocus() else navList.requestFocus()
            scheduleHide()
        } else {
            menuRoot.animate().translationX(-menuW.toFloat()).setDuration(180)
                .setInterpolator(DecelerateInterpolator()).withEndAction { menuRoot.visibility = View.GONE }.start()
            videoDim.animate().alpha(0f).setDuration(180)
                .withEndAction { videoDim.visibility = View.GONE }.start()
            playerView.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            handler.removeCallbacks(hideTask)
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideTask)
        if (panelVisible) handler.postDelayed(hideTask, 15000)
    }

    // ---------- 适配器 ----------

    private inner class NavAdapter : BaseAdapter() {
        override fun getCount(): Int = groupNames.size + 1
        override fun getItem(position: Int): Any? = null
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_nav, parent, false)
            val name = v.findViewById<TextView>(R.id.tvNavName)
            val mark = v.findViewById<View>(R.id.navMark)
            val arrow = v.findViewById<TextView>(R.id.tvNavArrow)
            if (position >= groupNames.size) {
                name.text = "设置"
                name.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.dim))
                mark.visibility = View.INVISIBLE
                arrow.visibility = View.GONE
            } else {
                name.text = groupNames[position]
                name.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nav_fg))
                mark.visibility = if (position == curGroup) View.VISIBLE else View.INVISIBLE
                arrow.visibility = if (position == curGroup) View.VISIBLE else View.INVISIBLE
            }
            return v
        }
    }

    private fun shownAllCount(): Int {
        val hidden = Prefs.hiddenGroups(this)
        return all.count { !hidden.contains(it.group) }
    }

    private fun countOf(group: String): Int = all.count { it.group == group }

    private inner class ChAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any? = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_channel, parent, false)
            val no = v.findViewById<TextView>(R.id.tvNo)
            val name = v.findViewById<TextView>(R.id.tvName)
            val sub = v.findViewById<TextView>(R.id.tvProg)
            val ch = shown[position]
            val dead = !ch.playable
            val cFg = ContextCompat.getColor(this@MainActivity, R.color.fg)
            val cDim = ContextCompat.getColor(this@MainActivity, R.color.dim)
            // 左侧数字方块 = 频道号（云端规范库下发）；没有频道号的上游频道用列表序号兜底
            no.text = ch.chno?.toString() ?: (position + 1).toString()
            name.text = ch.name
            no.setTextColor(if (dead) cDim else cFg)
            name.setTextColor(if (dead) cDim else cFg)
            var s = if (dead) "暂无可用线路 · 云端体检后自动恢复" else ""
            if (!dead) {
                val nn = EpgStore.nowNext(ch)
                if (nn.first != null) s = nn.first?.title ?: ""
                if (s.isEmpty()) s = if (ch.lines.size > 1) "共 " + ch.lines.size + " 条线路" else ""
            }
            sub.text = s
            sub.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
            return v
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.coroutineContext[Job]?.cancel()
        player.release()
        super.onDestroy()
    }
}
