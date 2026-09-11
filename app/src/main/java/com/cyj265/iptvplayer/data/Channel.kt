package com.cyj265.iptvplayer.data

/**
 * 单个电视频道。
 *
 * v1.7.1 起支持多线路（sources）：同一个频道名在播放列表里出现多次时
 * （如 gansu.m3u 里"本地列表/CCTV1综合"出现 7 次），解析时自动合并为
 * 一个频道条目，URL 列表按出现顺序保存，播放时可手动/超时自动切换线路。
 */
data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val group: String,
    val logo: String,
    val tvgId: String,
    val sources: List<String> = emptyList()
) {
    /** 线路数量（无 sources 时按 1 条处理） */
    val sourceCount: Int
        get() = if (sources.isEmpty()) 1 else sources.size

    /** 取第 index 条线路地址（越界回绕） */
    fun sourceAt(index: Int): String {
        if (sources.isEmpty()) return url
        return sources[((index % sources.size) + sources.size) % sources.size]
    }

    companion object {
        fun from(m3uIndex: Int, name: String, url: String, group: String, logo: String, tvgId: String): Channel {
            return Channel(
                id = "ch-$m3uIndex-${url.hashCode()}",
                name = name.trim(),
                url = url.trim(),
                group = group.trim(),
                logo = logo.trim(),
                tvgId = tvgId.trim(),
                sources = listOf(url.trim())
            )
        }
    }
}
