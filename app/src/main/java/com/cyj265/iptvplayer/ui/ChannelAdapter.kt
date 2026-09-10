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
 * 频道列表适配器：分组头 + 频道项。
 */
class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHANNEL = 1
    }

    data class Row(val isHeader: Boolean, val group: String = "", val channel: Channel? = null)

    private val rows = ArrayList<Row>()
    var favorites: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    private var selectedChannelId: String? = null

    fun setChannels(channels: List<Channel>) {
        rows.clear()
        val byGroup = LinkedHashMap<String, MutableList<Channel>>()
        for (c in channels) {
            byGroup.getOrPut(c.group) { ArrayList() }.add(c)
        }
        for ((group, list) in byGroup) {
            rows.add(Row(isHeader = true, group = group))
            for (c in list) {
                rows.add(Row(isHeader = false, channel = c))
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
            holder.binding.groupTitle.text = row.group
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

    class HeaderHolder(val binding: ItemGroupHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    class ChannelHolder(val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root)
}
