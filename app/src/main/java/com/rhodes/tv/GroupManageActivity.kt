package com.rhodes.tv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/** 分组管理：调整分组顺序（左/右键）、显示或隐藏分组（确认键） */
class GroupManageActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var tvTip: TextView
    private val names = ArrayList<String>()
    private val counts = HashMap<String, Int>()
    private var adapter: GroupAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_manage)
        list = findViewById(R.id.listGroups)
        tvTip = findViewById(R.id.tvGroupTip)

        for (ch in ChannelStore.load(this)) {
            val g = groupOf(ch)
            counts[g] = (counts[g] ?: 0) + 1
        }
        names.addAll(orderedNames())

        val a = GroupAdapter()
        adapter = a
        list.adapter = a
        list.setOnItemClickListener { _, _, pos, _ -> toggle(pos) }
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                view?.isSelected = true
                updateTip()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        list.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) false
            else when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    move(list.selectedItemPosition, -1); true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    move(list.selectedItemPosition, 1); true
                }
                else -> false
            }
        }
        list.requestFocus()
        updateTip()
        if (names.isEmpty()) toast("还没有频道，请先在设置里刷新直播源")
    }

    private fun groupOf(ch: Channel): String =
        if (ch.group.isNullOrBlank()) "\u672a\u5206\u7ec4" else ch.group

    private fun orderedNames(): List<String> {
        val raw = ArrayList<String>()
        for (ch in ChannelStore.load(this)) {
            val g = groupOf(ch)
            if (!raw.contains(g)) raw.add(g)
        }
        val out = ArrayList<String>()
        for (g in Prefs.groupOrder(this)) if (raw.contains(g) && !out.contains(g)) out.add(g)
        for (g in raw) if (!out.contains(g)) out.add(g)
        return out
    }

    private fun move(pos: Int, d: Int) {
        if (pos < 0 || pos >= names.size) return
        val to = pos + d
        if (to < 0 || to >= names.size) {
            toast(if (d < 0) "已经在最前面了" else "已经在最后面了")
            return
        }
        val n = names.removeAt(pos)
        names.add(to, n)
        Prefs.saveGroupOrder(this, names)
        adapter?.notifyDataSetChanged()
        list.setSelection(to)
        toast((if (d < 0) "上移：" else "下移：") + n)
    }

    private fun toggle(pos: Int) {
        val name = names.getOrNull(pos) ?: return
        val hidden = Prefs.toggleGroupHidden(this, name)
        toast(if (hidden) "已隐藏分组：" + name else "已显示分组：" + name)
        adapter?.notifyDataSetChanged()
        updateTip()
    }

    private fun updateTip() {
        val pos = list.selectedItemPosition
        val hiddenCount = names.count { Prefs.isGroupHidden(this, it) }
        val head = "共 " + names.size + " 个分组，其中隐藏 " + hiddenCount + " 个"
        val cur = names.getOrNull(pos)
        tvTip.text = if (cur == null) head
        else head + "　|　当前：" + cur + "（" + (counts[cur] ?: 0) + " 个频道，" +
                (if (Prefs.isGroupHidden(this, cur)) "已隐藏" else "显示中") + "）"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private inner class GroupAdapter : BaseAdapter() {
        override fun getCount(): Int = names.size
        override fun getItem(position: Int): Any = names[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_group, parent, false)
            val name = names[position]
            val hidden = Prefs.isGroupHidden(this@GroupManageActivity, name)
            val tvName = v.findViewById<TextView>(R.id.tvGroupName)
            val tvState = v.findViewById<TextView>(R.id.tvGroupState)
            tvName.text = name
            tvName.alpha = if (hidden) 0.45f else 1f
            tvState.text = (counts[name] ?: 0).toString() + " 个频道 · " + (if (hidden) "已隐藏" else "显示中")
            tvState.setTextColor(ContextCompat.getColor(this@GroupManageActivity,
                if (hidden) R.color.dim else R.color.ok))
            return v
        }
    }
}
