package com.cyj265.iptvplayer.data

/**
 * EPG 节目条目。
 */
data class EpgProgram(
    val channelId: String,
    val start: Long,
    val end: Long,
    val title: String,
    val description: String
)
