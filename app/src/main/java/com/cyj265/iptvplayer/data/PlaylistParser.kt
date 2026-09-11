package com.cyj265.iptvplayer.data

import java.util.regex.Pattern

/**
 * 解析 M3U / M3U8 播放列表，或 TXT 播放列表（自动识别）。
 *
 * v1.7.1 起：解析完成后按（分组, 频道名）合并同名单频道为多线路，
 * 播放列表里重复出现的同名频道（如多个 CCTV1 源）只显示一个条目。
 */
object PlaylistParser {

    private val EXTINF_PATTERN = Pattern.compile(
        "#EXTINF:([^,]*),(.*)"
    )

    fun parseAuto(content: String): List<Channel> {
        val trimmed = content.trimStart()
        val raw = if (trimmed.startsWith("#EXTM3U") || trimmed.startsWith("#EXTINF")) {
            parseRaw(content)
        } else {
            parseTxtRaw(content)
        }
        return mergeChannels(raw)
    }

    /**
     * 解析 TXT 播放列表。常见格式：
     *  频道名,http://...
     *  频道名，http://...（中文逗号）
     *  纯 URL 行（自动用序号命名）
     */
    private fun parseTxtRaw(content: String): List<Channel> {
        val channels = ArrayList<Channel>()
        var index = 0
        for (raw in content.split("\n", "\r\n")) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            var name: String
            var url: String

            val enIdx = line.lastIndexOf(',')
            val cnIdx = line.lastIndexOf('，')
            val sepIdx = if (cnIdx > enIdx) cnIdx else enIdx

            if (sepIdx > 0) {
                name = line.substring(0, sepIdx).trim()
                url = line.substring(sepIdx + 1).trim()
                if (!url.startsWith("http")) {
                    // 分隔符右边不是 URL，可能是 "url,name" 格式
                    val u = name
                    val n = url
                    if (u.startsWith("http")) {
                        name = if (n.isEmpty()) "频道 ${index + 1}" else n
                        url = u
                    } else {
                        continue
                    }
                }
            } else if (line.startsWith("http")) {
                url = line
                name = "频道 ${index + 1}"
            } else {
                // 尝试空格/Tab 分隔：name url
                val spaceIdx = line.indexOf(' ')
                val tabIdx = line.indexOf('\t')
                val sIdx = when {
                    spaceIdx < 0 -> tabIdx
                    tabIdx < 0 -> spaceIdx
                    else -> minOf(spaceIdx, tabIdx)
                }
                if (sIdx > 0) {
                    val u = line.substring(sIdx + 1).trim()
                    if (u.startsWith("http")) {
                        name = line.substring(0, sIdx).trim()
                        url = u
                    } else {
                        continue
                    }
                } else {
                    continue
                }
            }

            if (!url.startsWith("http")) continue
            if (name.isEmpty()) name = "频道 ${index + 1}"

            channels.add(
                Channel.from(
                    m3uIndex = index++,
                    name = name,
                    url = url,
                    group = "本地列表",
                    logo = "",
                    tvgId = name
                )
            )
        }
        return channels
    }

    private fun parseRaw(content: String): List<Channel> {
        val channels = ArrayList<Channel>()
        val lines = content.split("\n", "\r\n")
        var pending: Channel? = null
        var index = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF")) {
                val m = EXTINF_PATTERN.matcher(line)
                if (m.matches()) {
                    val attrs = m.group(1) ?: ""
                    val name = m.group(2) ?: ""
                    val group = extractAttr(attrs, "group-title")
                    val logo = extractAttr(attrs, "tvg-logo")
                    val tvgId = extractAttr(attrs, "tvg-id")
                    pending = Channel(
                        id = "",
                        name = name,
                        url = "",
                        group = group,
                        logo = logo,
                        tvgId = tvgId
                    )
                }
            } else if (!line.startsWith("#")) {
                // 频道流地址
                val info = pending
                if (info != null && line.startsWith("http")) {
                    val tvgId = if (info.tvgId.isEmpty()) info.name else info.tvgId
                    channels.add(
                        Channel.from(
                            m3uIndex = index++,
                            name = if (info.name.isEmpty()) line else info.name,
                            url = line,
                            group = if (info.group.isEmpty()) "未分组" else info.group,
                            logo = info.logo,
                            tvgId = tvgId
                        )
                    )
                }
                pending = null
            }
            // 其他 # 开头的行（#EXTM3U / #EXTVLCOPT 等）直接忽略
        }
        return channels
    }

    /**
     * 按（分组, 频道名）合并同名单频道，URL 列表按出现顺序保留，
     * 并生成稳定的频道 id（基于分组+名称），保证收藏/缓存跨版本可用。
     */
    private fun mergeChannels(raw: List<Channel>): List<Channel> {
        if (raw.isEmpty()) return raw
        val urlsByKey = LinkedHashMap<Pair<String, String>, MutableList<String>>()
        val metaByKey = LinkedHashMap<Pair<String, String>, Channel>()

        for (c in raw) {
            val key = c.group to c.name
            val urls = urlsByKey.getOrPut(key) { ArrayList() }
            if (!urls.contains(c.url)) urls.add(c.url)
            if (!metaByKey.containsKey(key)) metaByKey[key] = c
        }

        val out = ArrayList<Channel>(urlsByKey.size)
        for ((key, urls) in urlsByKey) {
            val base = metaByKey[key] ?: continue
            val stableId = "ch-" + key.first.hashCode().toString(16) + "-" + key.second.hashCode().toString(16)
            out.add(
                Channel(
                    id = stableId,
                    name = key.second,
                    url = urls[0],
                    group = key.first,
                    logo = base.logo,
                    tvgId = base.tvgId,
                    sources = urls.toList()
                )
            )
        }
        return out
    }

    private fun extractAttr(attrText: String, key: String): String {
        val pattern = Pattern.compile("$key\\s*=\\s*\"([^\"]*)\"")
        val m = pattern.matcher(attrText)
        return if (m.find()) m.group(1) else ""
    }
}
