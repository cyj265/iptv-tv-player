package com.cyj265.iptvplayer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面：全屏播放 + 悬浮频道列表（OK 唤出）+ 右侧设置面板（菜单键唤出）。
 * 遥控器：上下键换台、OK 频道列表、菜单键设置、CH+/CH- 换台。
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

    private var videoW = 0
    private var videoH = 0
    private var currentSettingsTab = 0

    // 覆盖层自动隐藏：4 秒无操作淡出顶部信息条与底部控制条（TiviMate 风格）
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayHideRunnable = Runnable { hideOverlay() }

    // 右上角时钟（柠檬TV 风格）
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            try {
                binding.tvClock.text =
                    SimpleDateFormat("yyyy/MM/dd\nHH:mm:ss", Locale.getDefault()).format(Date())
            } catch (ignored: Throwable) {
            }
            clockHandler.postDelayed(this, 1000L)
        }
    }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importLocalFile(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        try {
            initApp()
        } catch (t: Throwable) {
            reportStartupCrash(t)
        }
    }

    /** 启动主体：任何异常都会走 reportStartupCrash 弹窗显示，不再静默闪退。 */
    private fun initApp() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showLastCrashIfAny()

        repository = PlaylistRepository(this)
        favorites = repository.getFavorites().toMutableSet()

        playback = PlaybackManager(this, this)
        playback.attach(binding.playerView)
        playback.setAspectRatio(repository.aspectRatio)

        adapter = ChannelAdapter(
            onChannelClick = { channel -> onChannelClick(channel) },
            onCollapsedChanged = { groups -> repository.saveCollapsedGroups(groups) }
        )
        adapter.favorites = favorites
        adapter.setCollapsedGroups(repository.getCollapsedGroups())
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter

        setupSearch()
        setupButtons()
        setupSettingsPanel()

        // 启动：先显示缓存，再尝试刷新
        val cached = repository.loadCachedChannels()
        if (!cached.isNullOrEmpty()) {
            onChannelsLoaded(cached)
        }
        if (!repository.playlistUrl.isNullOrEmpty()) {
            reloadPlaylist()
        }
        loadEpgIfConfigured()

        // 自动恢复上次频道
        if (repository.autoResume) {
            resumeLastChannel()
        }

        // 启动时显示覆盖层，随后自动淡出
        showOverlay()
    }

    /** 启动失败：记录日志并弹窗显示堆栈，方便在电视上直接截图反馈。 */
    private fun reportStartupCrash(t: Throwable) {
        try {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            crashFile().writeText(
                "startup=" + System.currentTimeMillis() + "\n" + sw.toString(),
                Charsets.UTF_8
            )
        } catch (ignored: Exception) {
        }
        try {
            AlertDialog.Builder(this)
                .setTitle("启动失败（已记录日志）")
                .setMessage(
                    "请把此弹窗截图发给开发者，或在设置→调试中查看完整日志：\n\n" +
                        Log.getStackTraceString(t)
                )
                .setPositiveButton("知道了", null)
                .setCancelable(false)
                .show()
        } catch (ignored: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockRunnable)
        super.onPause()
    }

    // ---------- 崩溃日志（真机定位用） ----------

    private fun crashFile(): File = File(filesDir, "crash.log")

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                crashFile().writeText(
                    "time=" + System.currentTimeMillis() + "\nthread=" + thread.name +
                        "\n" + sw.toString(),
                    Charsets.UTF_8
                )
            } catch (ignored: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun showLastCrashIfAny() {
        val f = crashFile()
        if (!f.exists()) return
        val log = try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return
        }
        val brief = log.lineSequence().take(6).joinToString("\n")
        runOnUiThread {
            Toast.makeText(this, "上次运行崩溃：\n$brief", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- 覆盖层自动隐藏 ----------

    private fun showOverlay() {
        overlayHandler.removeCallbacks(overlayHideRunnable)
        val bar = binding.nowPlayingBar
        val ctrl = binding.controlBar
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            bar.animate().cancel()
            bar.alpha = 0f
            bar.animate().alpha(1f).setDuration(200).start()
        }
        if (ctrl.visibility != View.VISIBLE) {
            ctrl.visibility = View.VISIBLE
            ctrl.animate().cancel()
            ctrl.alpha = 0f
            ctrl.animate().alpha(1f).setDuration(200).start()
        }
        overlayHandler.removeCallbacks(overlayHideRunnable)
        if (repository.autoHideOverlay) {
            overlayHandler.postDelayed(overlayHideRunnable, 4000L)
        }
    }

    private fun hideOverlay() {
        val bar = binding.nowPlayingBar
        val ctrl = binding.controlBar
        if (bar.visibility == View.VISIBLE) {
            bar.animate().cancel()
            bar.animate().alpha(0f).setDuration(300)
                .withEndAction { bar.visibility = View.GONE }
        }
        if (ctrl.visibility == View.VISIBLE) {
            ctrl.animate().cancel()
            ctrl.animate().alpha(0f).setDuration(300)
                .withEndAction { ctrl.visibility = View.GONE }
        }
    }

    // ---------- 面板显隐 ----------

    private val isChannelPanelVisible: Boolean
        get() = binding.channelPanel.visibility == View.VISIBLE

    private val isSettingsPanelVisible: Boolean
        get() = binding.settingsPanel.visibility == View.VISIBLE

    private fun showChannelPanel() {
        overlayHandler.removeCallbacks(overlayHideRunnable)
        binding.settingsPanel.visibility = View.GONE
        binding.channelPanel.visibility = View.VISIBLE
        binding.channelPanel.alpha = 0f
        binding.channelPanel.animate().alpha(1f).setDuration(160).start()
        binding.channelList.requestFocus()
    }

    private fun hideChannelPanel() {
        binding.channelPanel.visibility = View.GONE
        showOverlay()
    }

    private fun showSettingsPanel() {
        overlayHandler.removeCallbacks(overlayHideRunnable)
        binding.channelPanel.visibility = View.GONE
        binding.settingsPanel.visibility = View.VISIBLE
        binding.settingsPanel.alpha = 0f
        binding.settingsPanel.animate().alpha(1f).setDuration(160).start()
        val navs = settingsNavs()
        navs[currentSettingsTab].requestFocus()
    }

    private fun hideSettingsPanel() {
        binding.settingsPanel.visibility = View.GONE
        showOverlay()
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
        binding.btnSettings.setOnClickListener { showSettingsPanel() }
        binding.btnList.setOnClickListener { showChannelPanel() }
        binding.btnFavorites.setOnClickListener {
            showFavoritesOnly = !showFavoritesOnly
            applyFilter()
            binding.tvStatus.text =
                if (showFavoritesOnly) getString(R.string.favorites) else getString(R.string.channel_list)
        }
        binding.btnPrev.setOnClickListener { switchChannel(-1) }
        binding.btnNext.setOnClickListener { switchChannel(1) }
        binding.btnPlayPause.setOnClickListener { playback.togglePlayPause() }
        binding.btnFavoriteCurrent.setOnClickListener { toggleFavoriteCurrent() }
    }

    private fun settingsNavs(): List<View> = listOf(
        binding.navSource, binding.navPlayer, binding.navUi,
        binding.navFav, binding.navUpdate, binding.navDebug, binding.navAbout
    )

    private fun settingsSections(): List<View> = listOf(
        binding.sectionSource, binding.sectionPlayer, binding.sectionUi,
        binding.sectionFav, binding.sectionUpdate, binding.sectionDebug, binding.sectionAbout
    )

    private fun selectSettingsTab(index: Int) {
        currentSettingsTab = index
        val navs = settingsNavs()
        val sections = settingsSections()
        sections.forEachIndexed { i, s ->
            s.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        navs.forEachIndexed { i, n ->
            if (i == index) {
                n.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_dim))
                (n as android.widget.TextView).setTextColor(Color.WHITE)
            } else {
                n.setBackgroundColor(Color.TRANSPARENT)
                (n as android.widget.TextView).setTextColor(
                    ContextCompat.getColor(this, R.color.text_secondary)
                )
            }
        }
    }

    private fun setupSettingsPanel() {
        setupSettingsTabs()

        binding.inputPlaylistUrl.setText(repository.playlistUrl.orEmpty())
        binding.inputEpgUrl.setText(repository.epgUrl.orEmpty())

        binding.btnImportFile.setOnClickListener {
            try {
                openDocument.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                // 部分精简电视系统没有文件管理器/文档提供者，会抛 ActivityNotFoundException
                Toast.makeText(
                    this,
                    "无法打开文件选择器（系统无文件管理），请改用播放列表 URL 加载",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        binding.btnLoadPlaylist.setOnClickListener { saveAndReload() }
        binding.btnPlayDirect.setOnClickListener { playDirectFromPanel() }
        binding.btnCloseSettings.setOnClickListener { hideSettingsPanel() }
        binding.btnClearFavorites.setOnClickListener {
            favorites.clear()
            repository.setFavorites(favorites)
            adapter.favorites = favorites
            updateFavCount()
            Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
        }

        binding.chkAutoResume.isChecked = repository.autoResume
        binding.chkAutoResume.setOnCheckedChangeListener { _, checked ->
            repository.autoResume = checked
        }

        binding.chkAutoHide.isChecked = repository.autoHideOverlay
        binding.chkAutoHide.setOnCheckedChangeListener { _, checked ->
            repository.autoHideOverlay = checked
            if (checked) showOverlay()
        }

        binding.btnAspectRatio.setOnClickListener { cycleAspectRatio() }
        updateAspectRatioLabel()

        binding.btnDecoderMode.setOnClickListener { cycleDecoderMode() }
        updateDecoderModeLabel()

        updateFavCount()

        binding.tvVersion.text = "v" + BuildConfig.VERSION_NAME
        binding.btnCheckUpdate.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/cyj265/iptv-tv-player/releases")
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
            }
        }

        refreshCrashLog()
        binding.btnClearCrashLog.setOnClickListener {
            crashFile().delete()
            binding.tvCrashLog.text = "无"
            Toast.makeText(this, R.string.cleared_crash_log, Toast.LENGTH_SHORT).show()
        }

        binding.tvAbout.text = getString(R.string.app_name) + " v" + BuildConfig.VERSION_NAME +
            "\n播放内核：libVLC（HEVC/H.265 软硬解自动切换）" +
            "\n代码仓库：https://github.com/cyj265/iptv-tv-player" +
            "\n开源许可：Apache-2.0 / MIT / GPL-2.0，来源致谢见仓库 README"
    }

    private fun setupSettingsTabs() {
        val navs = settingsNavs()
        navs.forEachIndexed { i, nav ->
            nav.setOnFocusChangeListener { _, focused ->
                if (focused) selectSettingsTab(i)
            }
        }
        selectSettingsTab(0)
    }

    private fun updateFavCount() {
        binding.tvFavCount.text = getString(R.string.fav_count, favorites.size)
    }

    private fun refreshCrashLog() {
        val f = crashFile()
        binding.tvCrashLog.text = if (f.exists()) {
            try {
                f.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                "读取失败"
            }
        } else {
            "无"
        }
    }

    // ---------- 画面比例 ----------

    private val ratioCycle = listOf("fit", "16:9", "4:3", "zoom", "fill")

    // ---------- 解码方式 ----------

    private val decoderModeCycle = listOf("auto", "hardware", "software")

    private fun cycleDecoderMode() {
        val current = playback.currentDecoderMode()
        val idx = decoderModeCycle.indexOf(current)
        val next = decoderModeCycle[(idx + 1 + decoderModeCycle.size) % decoderModeCycle.size]
        playback.applyDecoderMode(next)
        updateDecoderModeLabel()
        Toast.makeText(this, getString(R.string.decoder_mode) + "：" + labelForMode(next), Toast.LENGTH_SHORT).show()
    }

    private fun labelForMode(mode: String): String = when (mode) {
        "hardware" -> getString(R.string.decoder_hardware)
        "software" -> getString(R.string.decoder_software)
        else -> getString(R.string.decoder_auto)
    }

    private fun updateDecoderModeLabel() {
        binding.btnDecoderMode.text = labelForMode(playback.currentDecoderMode())
    }

    private fun cycleAspectRatio() {
        val current = repository.aspectRatio
        val idx = ratioCycle.indexOf(current)
        val next = ratioCycle[(idx + 1 + ratioCycle.size) % ratioCycle.size]
        repository.aspectRatio = next
        playback.setAspectRatio(next)
        updateAspectRatioLabel()
    }

    private fun updateAspectRatioLabel() {
        val label = when (repository.aspectRatio) {
            "16:9" -> getString(R.string.aspect_16_9)
            "4:3" -> getString(R.string.aspect_4_3)
            "zoom" -> getString(R.string.aspect_zoom)
            "fill" -> getString(R.string.aspect_fill)
            else -> getString(R.string.aspect_fit)
        }
        binding.btnAspectRatio.text = label
    }

    // ---------- 播放列表加载 ----------

    private fun saveAndReload() {
        val url = binding.inputPlaylistUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
            return
        }
        repository.playlistUrl = url
        repository.epgUrl = binding.inputEpgUrl.text.toString().trim().ifEmpty { null }
        reloadPlaylist()
        loadEpgIfConfigured()
        Toast.makeText(this, R.string.loading_playlist, Toast.LENGTH_SHORT).show()
    }

    private fun reloadPlaylist() {
        val url = repository.playlistUrl
        if (url.isNullOrEmpty()) {
            val cached = repository.loadCachedChannels()
            if (!cached.isNullOrEmpty()) onChannelsLoaded(cached)
            return
        }
        Thread {
            try {
                val content = HttpLoader.fetch(url)
                val channels = PlaylistParser.parseAuto(content)
                if (channels.isNotEmpty()) {
                    repository.saveChannels(channels)
                }
                runOnUiThread {
                    try {
                        onChannelsLoaded(channels)
                    } catch (e: Throwable) {
                        binding.tvStatus.text =
                            getString(R.string.load_failed) + "：" + (e.message ?: "列表渲染失败")
                    }
                }
            } catch (e: Throwable) {
                val cached = repository.loadCachedChannels()
                val detail = e.message ?: e.javaClass.simpleName
                runOnUiThread {
                    try {
                        if (!cached.isNullOrEmpty()) {
                            onChannelsLoaded(cached)
                            binding.tvStatus.text = getString(R.string.load_failed) + "（已用缓存）"
                        } else {
                            binding.tvStatus.text = getString(R.string.load_failed) + "：" + detail
                            Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e2: Throwable) {
                        binding.tvStatus.text =
                            getString(R.string.load_failed) + "：" + (e2.message ?: "未知错误")
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
        // 若启动时还没有播放（恢复失败/无缓存），自动播第一个频道
        if (currentChannel == null && channels.isNotEmpty() && repository.autoResume) {
            resumeLastChannel()
        }
    }

    // ---------- 本地文件导入 ----------

    private fun importLocalFile(uri: Uri) {
        Thread {
            try {
                val content = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (content == null) return@Thread
                var text = String(content, Charsets.UTF_8)
                if (text.contains('\uFFFD')) {
                    text = String(content, java.nio.charset.Charset.forName("GBK"))
                }
                val channels = PlaylistParser.parseAuto(text)
                if (channels.isEmpty()) {
                    runOnUiThread { Toast.makeText(this, R.string.no_channels, Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                repository.playlistUrl = null
                repository.saveChannels(channels)
                runOnUiThread {
                    try {
                        onChannelsLoaded(channels)
                        hideSettingsPanel()
                        Toast.makeText(this, R.string.importing, Toast.LENGTH_SHORT).show()
                    } catch (e: Throwable) {
                        Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun playDirectFromPanel() {
        val url = binding.inputDirectUrl.text.toString().trim()
        if (!url.startsWith("http")) {
            Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
            return
        }
        playDirect(url, url)
        hideSettingsPanel()
    }

    // ---------- 频道筛选 ----------

    private fun applyFilter() {
        try {
            val query = binding.searchInput.text.toString().trim().lowercase(Locale.getDefault())
            val filtered = allChannels.filter { ch ->
                val matchQuery = query.isEmpty() || ch.name.lowercase(Locale.getDefault()).contains(query)
                val matchFav = !showFavoritesOnly || favorites.contains(ch.url)
                matchQuery && matchFav
            }
            adapter.submitChannels(filtered)
            adapter.setSelected(currentChannel?.id)
        } catch (ignored: Throwable) {
        }
    }

    // ---------- 播放 ----------

    private fun onChannelClick(channel: Channel) {
        currentChannel = channel
        repository.lastChannelId = channel.id
        adapter.setSelected(channel.id)
        playback.play(channel.url, channel.name)
        updateNowPlaying()
        hideChannelPanel()
    }

    private fun resumeLastChannel() {
        val lastId = repository.lastChannelId ?: return
        val ch = allChannels.firstOrNull { it.id == lastId } ?: return
        currentChannel = ch
        adapter.setSelected(ch.id)
        playback.play(ch.url, ch.name)
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
        repository.lastChannelId = channel.id
        adapter.setSelected(null)
        playback.play(url, name)
        updateNowPlaying()
    }

    private fun switchChannel(delta: Int) {
        if (allChannels.isEmpty()) return
        val current = currentChannel
        if (showFavoritesOnly) {
            val favChannels = allChannels.filter { favorites.contains(it.url) }
            if (favChannels.isEmpty()) return
            val favIdx = favChannels.indexOfFirst { it.id == current?.id }
            val next = if (favIdx < 0) 0 else (favIdx + delta + favChannels.size) % favChannels.size
            onChannelClick(favChannels[next])
            return
        }
        val idx = allChannels.indexOfFirst { it.id == current?.id }
        val next = if (idx < 0) 0 else (idx + delta + allChannels.size) % allChannels.size
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
        updateFavCount()
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
                runOnUiThread {
                    try {
                        updateNowPlaying()
                    } catch (ignored: Throwable) {
                    }
                }
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
        for (ch in allChannels) {
            val list = byName[ch.tvgId] ?: byName[ch.name] ?: continue
            map[ch.id] = list.sortedBy { it.start }
        }
        return map
    }

    private fun updateNowPlaying() {
        try {
            val ch = currentChannel
            if (ch == null) {
                binding.tvChannelName.text = getString(R.string.no_channels)
                binding.tvEpgNow.text = getString(R.string.no_epg)
                binding.tvEpgNext.text = ""
                binding.tvChannelMeta.text = ""
                return
            }
            binding.tvChannelName.text = ch.name

            // 序号 / 总数 + 分辨率（柠檬TV 风格）
            val idx = allChannels.indexOfFirst { it.id == ch.id }
            val meta = StringBuilder()
            if (idx >= 0) {
                meta.append("第 ").append(idx + 1).append(" / ").append(allChannels.size).append(" 频道")
            }
            if (videoW > 0 && videoH > 0) {
                if (meta.isNotEmpty()) meta.append(" · ")
                meta.append("分辨率 ").append(videoW).append("×").append(videoH)
            }
            binding.tvChannelMeta.text = meta.toString()

            val programs = epgPrograms[ch.id] ?: emptyList()
            val now = System.currentTimeMillis()
            val current = programs.firstOrNull { now in it.start until it.end }
            val next = programs.firstOrNull { it.start >= now }
            binding.tvEpgNow.text =
                if (current != null) "正在播放: " + current.title else getString(R.string.no_epg)
            binding.tvEpgNext.text =
                if (next != null) "稍后播放: " + next.title + "  " + formatTime(next.start) else ""
            updateFavoriteIcon()
        } catch (ignored: Throwable) {
        }
    }

    private fun formatTime(ts: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    }

    // ---------- PlaybackManager.Listener ----------

    override fun onPlaybackReady(channelName: String) {
        try {
            binding.tvChannelName.text = channelName
            updateNowPlaying()
        } catch (ignored: Throwable) {
        }
    }

    override fun onPlaybackError(message: String) {
        try {
            binding.tvEpgNow.text = "播放失败: $message"
        } catch (ignored: Throwable) {
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        try {
            binding.btnPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        } catch (ignored: Throwable) {
        }
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
        try {
            videoW = width
            videoH = height
            updateNowPlaying()
        } catch (ignored: Throwable) {
        }
    }

    // ---------- 遥控器按键 ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 设置面板打开：OK/上下键交给面板内控件；BACK 关闭
        if (isSettingsPanelVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hideSettingsPanel(); true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }
        // 频道列表打开：OK 触发选中项（item 自带点击），BACK 关闭，上下键列表内导航
        if (isChannelPanelVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hideChannelPanel(); true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // 交给当前聚焦项处理（频道项点击 / 分组头折叠）
                    super.onKeyDown(keyCode, event)
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }
        // 全屏播放态：任何按键都重新显示覆盖层并重置自动隐藏计时
        showOverlay()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                switchChannel(-1); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                switchChannel(1); true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                showChannelPanel(); true
            }
            KeyEvent.KEYCODE_MENU -> {
                showSettingsPanel(); true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                switchChannel(1); true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                switchChannel(-1); true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playback.togglePlayPause(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        overlayHandler.removeCallbacks(overlayHideRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        super.onDestroy()
        playback.release()
    }
}
