package com.cyj265.iptvplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cyj265.iptvplayer.R
import com.cyj265.iptvplayer.data.Channel
import com.cyj265.iptvplayer.databinding.ItemChannelBinding
import com.cyj265.iptvplayer.databinding.ItemGroupHeaderBinding

/**
 * 频道列表适配器：二级分组体验。
 *
 * 一级 = 分组头（分组名 + 频道数 + 展开箭头，可聚焦点击）；
 * 二级 = 分组内频道项（组名 + 当前 EPG 节目 + 收藏星标）。
 * 未手动折叠过时默认全部收起（v1.7.0 起），列表只显示分组，展开才见频道，
 * 避免几百个频道平铺太长找不到台。
 */
class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onCollapsedChanged: (Set<String>) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHANNEL = 1
    }

    data class Row(val isHeader: Boolean, val group: String = "", val channel: Channel? = null)

    private val rows = ArrayList<Row>()
    private val collapsedGroups = HashSet<String>()
    private var groups = listOf<String>()

    /** 用户从未折叠过时默认全部收起（二级列表）。由 MainActivity 按偏好设置。 */
    private var defaultCollapsed = true

    /** 当前 EPG 节目文本：channelId -> "正在播放: xxx"（用于频道项副行） */
    var epgNow: Map<String, String> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var favorites: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    private var selectedChannelId: String? = null

    fun setDefaultCollapsed(value: Boolean) {
        defaultCollapsed = value
    }

    /** 恢复折叠状态；collapsed 为空且 defaultCollapsed 时按全收起重建。 */
    fun setCollapsedGroups(collapsed: Set<String>) {
        collapsedGroups.clear()
        collapsedGroups.addAll(collapsed)
        rebuild()
    }

    fun collapsedState(): Set<String> = HashSet(collapsedGroups)

    private val groupCache = HashMap<String, List<Channel>>()

    /** 入口：先存全量，再重建分组缓存 */
    fun submitChannels(channels: List<Channel>) {
        groupCache.clear()
        val byGroup = LinkedHashMap<String, MutableList<Channel>>()
        for (c in channels) {
            byGroup.getOrPut(c.group) { ArrayList() }.add(c)
        }
        for ((g, list) in byGroup) {
            groupCache[g] = list
        }
        groups = byGroup.keys.toList()
        rebuild()
    }

    private fun rebuild() {
        rows.clear()
        for (g in groups) {
            val collapsed = if (collapsedGroups.isEmpty() && defaultCollapsed) {
                true
            } else {
                collapsedGroups.contains(g)
            }
            rows.add(Row(isHeader = true, group = g))
            if (!collapsed) {
                for (c in groupCache[g] ?: emptyList()) {
                    rows.add(Row(isHeader = false, channel = c))
                }
            }
        }
        notifyDataSetChanged()
    }

    fun setSelected(channelId: String?) {
        selectedChannelId = channelId
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (rows[position].isHeader) TYPE_HEADER else TYPE_CHANNEL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemGroupHeaderBinding.inflate(inflater, parent, false))
        } else {
            ChannelHolder(ItemChannelBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (holder is HeaderHolder) {
            val count = groupCache[row.group]?.size ?: 0
            val collapsed = if (collapsedGroups.isEmpty() && defaultCollapsed) {
                true
            } else {
                collapsedGroups.contains(row.group)
            }
            holder.binding.groupTitle.text = row.group
            holder.binding.groupCount.text = "$count 个频道"
            holder.binding.groupArrow.text = if (collapsed) "▸" else "▾"
            holder.binding.root.setOnClickListener {
                toggleGroup(row.group)
            }
        } else if (holder is ChannelHolder) {
            val ch = row.channel ?: return
            holder.binding.tvChannelName.text = ch.name
            holder.binding.tvEpgLine.text = epgNow[ch.id] ?: ch.group
            val isFav = favorites.contains(ch.url)
            holder.binding.ivFavorite.visibility = if (isFav) View.VISIBLE else View.GONE
            holder.binding.ivFavorite.setColorFilter(
                if (isFav) ContextCompat.getColor(holder.binding.root.context, R.color.accent) else 0
            )
            holder.binding.root.isSelected = ch.id == selectedChannelId
            holder.binding.root.setOnClickListener { onChannelClick(ch) }
        }
    }

    private fun toggleGroup(group: String) {
        if (collapsedGroups.contains(group)) {
            collapsedGroups.remove(group)
        } else {
            collapsedGroups.add(group)
        }
        rebuild()
        onCollapsedChanged(collapsedState())
    }

    class HeaderHolder(val binding: ItemGroupHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    class ChannelHolder(val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root)
}
