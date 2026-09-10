package com.cyj265.iptvplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 播放列表与偏好存储。
 */
class PlaylistRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)

    var playlistUrl: String?
        get() = prefs.getString("playlist_url", null)
        set(value) = prefs.edit().putString("playlist_url", value).apply()

    var epgUrl: String?
        get() = prefs.getString("epg_url", null)
        set(value) = prefs.edit().putString("epg_url", value).apply()

    // ---------- 播放偏好 ----------

    /** 画面比例：fit / fill / zoom / 16:9 / 4:3 */
    var aspectRatio: String
        get() = prefs.getString("aspect_ratio", "fit")!!
        set(value) = prefs.edit().putString("aspect_ratio", value).apply()

    /** 打开应用时自动恢复上次频道 */
    var autoResume: Boolean
        get() = prefs.getBoolean("auto_resume", true)
        set(value) = prefs.edit().putBoolean("auto_resume", value).apply()

    /** 顶部信息条/底部控制条无操作自动隐藏 */
    var autoHideOverlay: Boolean
        get() = prefs.getBoolean("auto_hide_overlay", true)
        set(value) = prefs.edit().putBoolean("auto_hide_overlay", value).apply()

    /** 上次播放的频道 id */
    var lastChannelId: String?
        get() = prefs.getString("last_channel_id", null)
        set(value) = prefs.edit().putString("last_channel_id", value).apply()

    // ---------- 收藏 ----------

    fun getFavorites(): MutableSet<String> {
        return prefs.getStringSet("favorites", HashSet())!!.toMutableSet()
    }

    fun setFavorites(favorites: Set<String>) {
        prefs.edit().putStringSet("favorites", favorites).apply()
    }

    // ---------- 分组折叠状态 ----------

    fun getCollapsedGroups(): MutableSet<String> {
        return prefs.getStringSet("collapsed_groups", HashSet())!!.toMutableSet()
    }

    fun saveCollapsedGroups(groups: Set<String>) {
        prefs.edit().putStringSet("collapsed_groups", groups).apply()
    }

    // ---------- 频道缓存（离线快速启动 / 网络失败兜底） ----------

    private val cacheFile: File
        get() = File(context.cacheDir, "playlist_cache.json")

    fun saveChannels(channels: List<Channel>) {
        try {
            val arr = JSONArray()
            for (c in channels) {
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("url", c.url)
                        .put("group", c.group)
                        .put("logo", c.logo)
                        .put("tvgId", c.tvgId)
                )
            }
            cacheFile.writeText(arr.toString(), Charsets.UTF_8)
        } catch (ignored: Exception) {
        }
    }

    fun loadCachedChannels(): List<Channel>? {
        return try {
            if (!cacheFile.exists()) return null
            val arr = JSONArray(cacheFile.readText(Charsets.UTF_8))
            val list = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Channel(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        url = o.optString("url"),
                        group = o.optString("group"),
                        logo = o.optString("logo"),
                        tvgId = o.optString("tvgId")
                    )
                )
            }
            list
        } catch (e: Exception) {
            null
        }
    }
}
