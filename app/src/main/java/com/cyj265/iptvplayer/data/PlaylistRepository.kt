package com.cyj265.iptvplayer.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 播放列表与偏好存储。
 *
 * v1.7.0 起支持多个直播源：
 * - sources_json：全部源 URL 列表（JSON 数组，保序）；
 * - active_source_index：当前使用的源序号；
 * - 每个源独立的频道缓存文件（playlist_cache_<hash>.json）与最后更新时间。
 * 旧版单源数据（playlist_url）首次读取时自动迁移为首个源。
 *
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

    private fun safeGetLong(key: String, def: Long): Long {
        return try {
            prefs.getLong(key, def)
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

    // ---------- 多直播源 ----------

    /** 全部直播源 URL 列表（保序）。旧版单源自动迁移。 */
    fun getSources(): List<String> {
        try {
            val json = safeGetString("sources_json", null)
            if (json.isNullOrBlank()) {
                val old = safeGetString("playlist_url", null)
                if (!old.isNullOrBlank()) {
                    saveSources(listOf(old.trim()))
                    return listOf(old.trim())
                }
                return emptyList()
            }
            val arr = JSONArray(json)
            return (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    fun saveSources(sources: List<String>) {
        val arr = JSONArray()
        for (s in sources) arr.put(s)
        safeApply { putString("sources_json", arr.toString()) }
    }

    fun addSource(url: String): Boolean {
        val cur = getSources().toMutableList()
        if (cur.contains(url)) return false
        cur.add(url)
        saveSources(cur)
        return true
    }

    /** 删除指定源，返回被删除的 URL（null 表示越界）。 */
    fun removeSource(index: Int): String? {
        val cur = getSources().toMutableList()
        if (index < 0 || index >= cur.size) return null
        val removed = cur.removeAt(index)
        saveSources(cur)
        if (cur.isEmpty()) {
            activeSourceIndex = 0
        } else if (activeSourceIndex >= cur.size) {
            activeSourceIndex = cur.size - 1
        }
        deleteCacheFor(removed)
        return removed
    }

    /** 当前源序号 */
    var activeSourceIndex: Int
        get() {
            val v = safeGetLong("active_source_index", 0).toInt()
            val size = getSources().size
            return if (size == 0) 0 else v.coerceIn(0, size - 1)
        }
        set(value) = safeApply { putLong("active_source_index", value.toLong()) }

    /** 当前源 URL（null = 无源） */
    fun getActiveSource(): String? {
        val s = getSources()
        return s.getOrNull(activeSourceIndex)
    }

    /** 切换到下一个源，返回切换后的源 URL。 */
    fun switchToNextSource(): String? {
        val s = getSources()
        if (s.isEmpty()) return null
        activeSourceIndex = (activeSourceIndex + 1) % s.size
        return getActiveSource()
    }

    // ---------- EPG ----------

    var epgUrl: String?
        get() = safeGetString("epg_url", null)
        set(value) = safeApply { putString("epg_url", value) }

    /** EPG 上次成功加载的节目条数（0 = 未加载成功） */
    var epgProgramCount: Int
        get() = safeGetLong("epg_program_count", 0).toInt()
        set(value) = safeApply { putLong("epg_program_count", value.toLong()) }

    /** EPG 上次成功加载时间戳 */
    var epgUpdatedAt: Long
        get() = safeGetLong("epg_updated_at", 0)
        set(value) = safeApply { putLong("epg_updated_at", value) }

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

    /** 用户是否手动折叠过分组（未折叠过时默认全部收起，二级列表体验） */
    fun hasCollapsedPrefs(): Boolean {
        return try {
            prefs.contains("collapsed_groups")
        } catch (e: Throwable) {
            false
        }
    }

    fun getCollapsedGroups(): MutableSet<String> = safeGetStringSet("collapsed_groups")

    fun saveCollapsedGroups(groups: Set<String>) {
        safeApply { putStringSet("collapsed_groups", groups) }
    }

    // ---------- 频道缓存（每源独立） ----------

    private fun cacheFileFor(url: String): File {
        val hash = url.hashCode().toString(16)
        return File(context.cacheDir, "playlist_cache_$hash.json")
    }

    /** 记录某源最后成功更新时间戳 */
    fun markSourceUpdated(url: String) {
        safeApply { putLong("updated_at_" + url.hashCode().toString(16), System.currentTimeMillis()) }
    }

    fun getSourceUpdatedAt(url: String): Long {
        return safeGetLong("updated_at_" + url.hashCode().toString(16), 0)
    }

    fun saveChannels(channels: List<Channel>, sourceUrl: String) {
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
            cacheFileFor(sourceUrl).writeText(arr.toString(), Charsets.UTF_8)
            markSourceUpdated(sourceUrl)
        } catch (ignored: Exception) {
        }
    }

    fun loadCachedChannels(sourceUrl: String): List<Channel>? {
        return try {
            val f = cacheFileFor(sourceUrl)
            if (!f.exists()) return null
            val arr = JSONArray(f.readText(Charsets.UTF_8))
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

    private fun deleteCacheFor(url: String) {
        try {
            cacheFileFor(url).delete()
        } catch (ignored: Exception) {
        }
    }

    // ---------- 本地文件导入缓存（不算直播源，重启后仍在） ----------

    private val localCacheFile: File
        get() = File(context.cacheDir, "playlist_cache_local.json")

    fun saveLocalChannels(channels: List<Channel>) {
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
            localCacheFile.writeText(arr.toString(), Charsets.UTF_8)
        } catch (ignored: Exception) {
        }
    }

    fun loadLocalChannels(): List<Channel>? {
        return try {
            val f = localCacheFile
            if (!f.exists()) {
                // 兼容 v1.6.4 及更早的旧缓存文件（playlist_cache.json）
                val legacy = File(context.cacheDir, "playlist_cache.json")
                if (legacy.exists()) {
                    val arr = JSONArray(legacy.readText(Charsets.UTF_8))
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
                    return list
                }
                return null
            }
            val arr = JSONArray(f.readText(Charsets.UTF_8))
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
