package com.cyj265.iptvplayer.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 播放列表与偏好存储。
 * 所有 SharedPreferences 读取都带异常兜底：盒子存储异常导致 SP 文件损坏时，
 * 自动重置为空数据，保证应用能正常启动（否则会"启动即崩、打不开"）。
 */
class PlaylistRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)

    private fun safeGetString(key: String, def: String?): String? {
        return try {
            prefs.getString(key, def)
        } catch (e: Throwable) {
            resetPrefs()
            def
        }
    }

    private fun safeGetBoolean(key: String, def: Boolean): Boolean {
        return try {
            prefs.getBoolean(key, def)
        } catch (e: Throwable) {
            resetPrefs()
            def
        }
    }

    private fun safeGetStringSet(key: String): MutableSet<String> {
        return try {
            prefs.getStringSet(key, HashSet())!!.toMutableSet()
        } catch (e: Throwable) {
            resetPrefs()
            HashSet()
        }
    }

    private fun resetPrefs() {
        try {
            prefs.edit().clear().commit()
        } catch (ignored: Exception) {
        }
    }

    private fun safeApply(block: SharedPreferences.Editor.() -> Unit) {
        try {
            val editor = prefs.edit()
            block(editor)
            editor.apply()
        } catch (ignored: Exception) {
        }
    }

    var playlistUrl: String?
        get() = safeGetString("playlist_url", null)
        set(value) = safeApply { putString("playlist_url", value) }

    var epgUrl: String?
        get() = safeGetString("epg_url", null)
        set(value) = safeApply { putString("epg_url", value) }

    // ---------- 播放偏好 ----------

    /** 画面比例：fit / fill / zoom / 16:9 / 4:3 */
    var aspectRatio: String
        get() = safeGetString("aspect_ratio", "fit") ?: "fit"
        set(value) = safeApply { putString("aspect_ratio", value) }

    /** 打开应用时自动恢复上次频道 */
    var autoResume: Boolean
        get() = safeGetBoolean("auto_resume", true)
        set(value) = safeApply { putBoolean("auto_resume", value) }

    /** 顶部信息条/底部控制条无操作自动隐藏 */
    var autoHideOverlay: Boolean
        get() = safeGetBoolean("auto_hide_overlay", true)
        set(value) = safeApply { putBoolean("auto_hide_overlay", value) }

    /** 上次播放的频道 id */
    var lastChannelId: String?
        get() = safeGetString("last_channel_id", null)
        set(value) = safeApply { putString("last_channel_id", value) }

    // ---------- 收藏 ----------

    fun getFavorites(): MutableSet<String> = safeGetStringSet("favorites")

    fun setFavorites(favorites: Set<String>) {
        safeApply { putStringSet("favorites", favorites) }
    }

    // ---------- 分组折叠状态 ----------

    fun getCollapsedGroups(): MutableSet<String> = safeGetStringSet("collapsed_groups")

    fun saveCollapsedGroups(groups: Set<String>) {
        safeApply { putStringSet("collapsed_groups", groups) }
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
