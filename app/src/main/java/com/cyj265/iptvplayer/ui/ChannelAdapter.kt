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
 * 频道列表适配器：可折叠分组头 + 频道项。
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

    var favorites: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    private var selectedChannelId: String? = null

    /** 恢复折叠状态 */
    fun setCollapsedGroups(collapsed: Set<String>) {
        collapsedGroups.clear()
        collapsedGroups.addAll(collapsed)
        rebuild()
    }

    fun collapsedState(): Set<String> = HashSet(collapsedGroups)

    fun setChannels(channels: List<Channel>) {
        groups = channels.map { it.group }.distinct()
        rebuild()
    }

    private fun rebuild() {
        rows.clear()
        for (g in groups) {
            val list = groupCache[g] ?: emptyList()
            rows.add(Row(isHeader = true, group = g))
            if (!collapsedGroups.contains(g)) {
                for (c in list) {
                    rows.add(Row(isHeader = false, channel = c))
                }
            }
        }
        notifyDataSetChanged()
    }

    private val groupCache = HashMap<String, List<Channel>>()
    private var allChannels: List<Channel> = emptyList()

    /** 入口：先存全量，再重建分组缓存 */
    fun submitChannels(channels: List<Channel>) {
        allChannels = channels
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
            holder.binding.groupTitle.text = row.group + "  (" + count + ")"
            holder.binding.groupArrow.text = if (collapsedGroups.contains(row.group)) "▸" else "▾"
            holder.binding.root.setOnClickListener {
                toggleGroup(row.group)
            }
        } else if (holder is ChannelHolder) {
            val ch = row.channel ?: return
            holder.binding.tvChannelName.text = ch.name
            holder.binding.tvEpgLine.text = ch.group
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
