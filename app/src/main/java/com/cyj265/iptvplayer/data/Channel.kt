package com.cyj265.iptvplayer.data

/**
 * 单个电视频道。
 */
data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val group: String,
    val logo: String,
    val tvgId: String
) {
    companion object {
        fun from(m3uIndex: Int, name: String, url: String, group: String, logo: String, tvgId: String): Channel {
            return Channel(
                id = "ch-$m3uIndex-${url.hashCode()}",
                name = name.trim(),
                url = url.trim(),
                group = group.trim(),
                logo = logo.trim(),
                tvgId = tvgId.trim()
            )
        }
    }
}
