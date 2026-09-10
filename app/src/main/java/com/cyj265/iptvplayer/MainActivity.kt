package com.cyj265.iptvplayer

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.cyj265.iptvplayer.data.Channel
import com.cyj265.iptvplayer.data.EpgParser
import com.cyj265.iptvplayer.data.EpgProgram
import com.cyj265.iptvplayer.data.HttpLoader
import com.cyj265.iptvplayer.data.PlaylistParser
import com.cyj265.iptvplayer.data.PlaylistRepository
import com.cyj265.iptvplayer.databinding.ActivityMainBinding
import com.cyj265.iptvplayer.player.PlaybackManager
import com.cyj265.iptvplayer.ui.ChannelAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面：左侧频道列表，右侧播放器。
 */
class MainActivity : AppCompatActivity(), PlaybackManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: PlaylistRepository
    private lateinit var playback: PlaybackManager
    private lateinit var adapter: ChannelAdapter

    private var allChannels: List<Channel> = emptyList()
    private var favorites: MutableSet<String> = HashSet()
    private var showFavoritesOnly = false
    private var currentChannel: Channel? = null
    private var epgPrograms: Map<String, List<EpgProgram>> = emptyMap()

    private val settingsLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val action = result.data?.getStringExtra(SettingsActivity.EXTRA_ACTION)
            when (action) {
                SettingsActivity.ACTION_PLAYLIST -> reloadPlaylist()
                SettingsActivity.ACTION_DIRECT -> {
                    val url = result.data?.getStringExtra(SettingsActivity.EXTRA_URL)
                    val name = result.data?.getStringExtra(SettingsActivity.EXTRA_NAME)
                    if (!url.isNullOrEmpty()) {
                        playDirect(url, name ?: url)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = PlaylistRepository(this)
        favorites = repository.getFavorites().toMutableSet()

        playback = PlaybackManager(this, this)
        playback.attach(binding.playerView)

        adapter = ChannelAdapter { channel -> onChannelClick(channel) }
        adapter.favorites = favorites
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter

        setupSearch()
        setupButtons()
        setupKeys()

        // 启动时：有缓存先显示缓存，再尝试刷新
        val cached = repository.loadCachedChannels()
        if (!cached.isNullOrEmpty()) {
            onChannelsLoaded(cached)
        }
        if (!repository.playlistUrl.isNullOrEmpty()) {
            reloadPlaylist()
        }
        loadEpgIfConfigured()
    }

    // ---------- UI 初始化 ----------

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })
    }

    private fun setupButtons() {
        binding.btnSettings.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        binding.btnFavorites.setOnClickListener {
            showFavoritesOnly = !showFavoritesOnly
            applyFilter()
            binding.tvStatus.text = if (showFavoritesOnly) getString(R.string.favorites) else getString(R.string.channel_list)
        }
        binding.btnPrev.setOnClickListener { switchChannel(-1) }
        binding.btnNext.setOnClickListener { switchChannel(1) }
        binding.btnPlayPause.setOnClickListener { playback.togglePlayPause() }
        binding.btnStop.setOnClickListener { playback.stop() }
        binding.btnFavoriteCurrent.setOnClickListener { toggleFavoriteCurrent() }
    }

    private fun setupKeys() {
        binding.channelList.isFocusable = true
        binding.channelList.isFocusableInTouchMode = true
    }

    // ---------- 播放列表加载 ----------

    private fun reloadPlaylist() {
        val url = repository.playlistUrl
        if (url.isNullOrEmpty()) {
            val cached = repository.loadCachedChannels()
            if (!cached.isNullOrEmpty()) onChannelsLoaded(cached)
            return
        }
        binding.tvStatus.text = getString(R.string.loading_playlist)
        Thread {
            try {
                val content = HttpLoader.fetch(url)
                val channels = PlaylistParser.parseAuto(content)
                if (channels.isNotEmpty()) {
                    repository.saveChannels(channels)
                }
                runOnUiThread { onChannelsLoaded(channels) }
            } catch (e: Exception) {
                val cached = repository.loadCachedChannels()
                runOnUiThread {
                    if (!cached.isNullOrEmpty()) {
                        onChannelsLoaded(cached)
                        binding.tvStatus.text = getString(R.string.load_failed) + "（已用缓存）"
                    } else {
                        binding.tvStatus.text = getString(R.string.load_failed)
                        Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun onChannelsLoaded(channels: List<Channel>) {
        allChannels = channels
        binding.tvStatus.text = getString(R.string.channel_list) + " · " + channels.size + " 个频道"
        applyFilter()
        if (channels.isEmpty()) {
            binding.tvChannelName.text = getString(R.string.no_channels)
        }
        loadEpgIfConfigured()
    }

    // ---------- 频道筛选 ----------

    private fun applyFilter() {
        val query = binding.searchInput.text.toString().trim().lowercase(Locale.getDefault())
        val filtered = allChannels.filter { ch ->
            val matchQuery = query.isEmpty() || ch.name.lowercase(Locale.getDefault()).contains(query)
            val matchFav = !showFavoritesOnly || favorites.contains(ch.url)
            matchQuery && matchFav
        }
        adapter.setChannels(filtered)
        adapter.setSelected(currentChannel?.id)
    }

    // ---------- 播放 ----------

    private fun onChannelClick(channel: Channel) {
        currentChannel = channel
        adapter.setSelected(channel.id)
        playback.play(channel.url, channel.name)
        updateNowPlaying()
    }

    private fun playDirect(url: String, name: String) {
        val channel = Channel(
            id = "direct-${url.hashCode()}",
            name = name,
            url = url,
            group = "直接播放",
            logo = "",
            tvgId = name
        )
        currentChannel = channel
        adapter.setSelected(null)
        playback.play(url, name)
        updateNowPlaying()
    }

    private fun switchChannel(delta: Int) {
        if (allChannels.isEmpty()) return
        val current = currentChannel
        val idx = allChannels.indexOfFirst { it.id == current?.id }
        var next = if (idx < 0) 0 else (idx + delta + allChannels.size) % allChannels.size
        // 如果处于收藏筛选模式，只在收藏里切换
        if (showFavoritesOnly) {
            val favChannels = allChannels.filter { favorites.contains(it.url) }
            if (favChannels.isEmpty()) return
            val favIdx = favChannels.indexOfFirst { it.id == current?.id }
            next = if (favIdx < 0) 0 else (favIdx + delta + favChannels.size) % favChannels.size
            onChannelClick(favChannels[next])
            return
        }
        onChannelClick(allChannels[next])
    }

    private fun toggleFavoriteCurrent() {
        val ch = currentChannel ?: return
        if (favorites.contains(ch.url)) {
            favorites.remove(ch.url)
        } else {
            favorites.add(ch.url)
        }
        repository.setFavorites(favorites)
        adapter.favorites = favorites
        updateFavoriteIcon()
    }

    private fun updateFavoriteIcon() {
        val ch = currentChannel ?: return
        binding.btnFavoriteCurrent.alpha = if (favorites.contains(ch.url)) 1f else 0.4f
    }

    // ---------- EPG ----------

    private fun loadEpgIfConfigured() {
        val epgUrl = repository.epgUrl
        if (epgUrl.isNullOrEmpty()) {
            updateNowPlaying()
            return
        }
        Thread {
            try {
                val content = HttpLoader.fetch(epgUrl)
                val data = EpgParser.parse(content)
                epgPrograms = indexEpg(data)
                runOnUiThread { updateNowPlaying() }
            } catch (ignored: Exception) {
            }
        }.start()
    }

    private fun indexEpg(data: EpgParser.EpgData): Map<String, List<EpgProgram>> {
        val map = HashMap<String, List<EpgProgram>>()
        val byName = HashMap<String, MutableList<EpgProgram>>()
        for (p in data.programs) {
            byName.getOrPut(p.channelId) { ArrayList() }.add(p)
        }
        // 按频道 tvgId 和名称做两层映射
        for (ch in allChannels) {
            val list = byName[ch.tvgId] ?: byName[ch.name] ?: continue
            map[ch.id] = list.sortedBy { it.start }
        }
        return map
    }

    private fun updateNowPlaying() {
        val ch = currentChannel
        if (ch == null) {
            binding.tvChannelName.text = getString(R.string.no_channels)
            binding.tvEpgNow.text = getString(R.string.no_epg)
            return
        }
        binding.tvChannelName.text = ch.name
        val programs = epgPrograms[ch.id] ?: emptyList()
        val now = System.currentTimeMillis()
        val current = programs.firstOrNull { now in it.start until it.end }
        val next = programs.firstOrNull { it.start >= now }
        val sb = StringBuilder()
        if (current != null) {
            sb.append("▶ ").append(current.title)
        } else {
            sb.append(getString(R.string.no_epg))
        }
        if (next != null) {
            sb.append("  |  下一个: ").append(next.title).append(" ").append(formatTime(next.start))
        }
        binding.tvEpgNow.text = sb.toString()
        updateFavoriteIcon()
    }

    private fun formatTime(ts: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    }

    // ---------- PlaybackManager.Listener ----------

    override fun onPlaybackReady(channelName: String) {
        binding.tvChannelName.text = channelName
        updateNowPlaying()
    }

    override fun onPlaybackError(message: String) {
        binding.tvEpgNow.text = "播放失败: $message"
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    // ---------- 遥控器按键 ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                switchChannel(1); true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                switchChannel(-1); true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playback.togglePlayPause(); true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                playback.stop(); true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                switchChannel(1); true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                switchChannel(-1); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playback.release()
    }
}
