package com.cyj265.iptvplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 基于 Media3 ExoPlayer 的播放内核（影视仓/TVBox 同款，T1 实测 4K HEVC 流畅）。
 *
 * v1.5.0 曾整体替换为 libVLC（83MB），T1 实测只有 1080P 正常（4K 花屏、
 * 720P 黑屏、卡顿），v1.6.0 回归本方案：单内核 + 解码器三档
 * （自动/仅硬解/仅软解），等效影视仓的 "exo硬解/exo软解"，APK 仅 ~5.8MB。
 *
 * v1.7.1 起支持多线路（频道去重后 sources 列表）：
 * - 线路切换：手动换源（播放界面"换源"按钮 / 设置-线路选择）
 * - 超时自动换源：当前线路在配置的超时秒数内未起播，自动切下一条线路；
 *   播放失败（网络/协议类错误）也自动切下一条线路，全部线路失败才报错。
 * - 解码类错误不切线路（换线路解决不了解码问题），走"检测+提示重启机顶盒"
 *   （自动修复链已按用户要求删除，不再免重启强修 mediaserver）。
 *
 * 自动识别 HLS / 渐进式流，与 TiviMate 同源的内核家族，对标准 HLS 支持最好。
 *
 * 解码策略（设置中可切换）：
 * - auto：硬解优先，解码器初始化失败自动回退其他解码器（默认）；
 * - hardware：只允许硬件解码器；
 * - software：只允许软件解码器（兼容性最好，但 1080p HEVC 较费 CPU）。
 *
 * 直播缓冲：起播 1.5s、重缓冲 3s、持续 15s、上限 45s——秒开且能吸收网络抖动。
 *
 * 失败自动重试：网络瞬时错误自动重播（最多 2 次，仅单线路时生效）。
 */
class PlaybackManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onPlaybackReady(channelName: String)
        fun onPlaybackError(message: String)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onVideoSizeChanged(width: Int, height: Int)
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var currentUrl: String? = null
    private var currentChannelName: String? = null

    // ---------- 多线路 ----------
    private var currentSources: List<String> = emptyList()
    private var currentSourceIndex = 0
    private var autoTryStartIndex = 0

    private var retryCount = 0
    private val retryHandler = Handler(Looper.getMainLooper())
    private val sourceTimeoutHandler = Handler(Looper.getMainLooper())
    private var sourceTimeoutMs: Long = 10_000L

    /** 当前频道是否已从硬解自动降级到软解（每个频道重置一次） */
    private var degradedToSoftware = false

    /** 解码方式：auto / hardware / software */
    private var decoderMode: String = run {
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        // v1.3.0 的 bug：硬解失败自动降级会把 decoder_mode 持久化成 "software"，
        // 导致后续所有频道都被迫软解（1080p 卡、4K 只出声不出画）。
        // 升级后一次性重置回 "auto"，避免用户被旧 bug 留下的设置污染。
        if (!prefs.getBoolean("decoder_migrated_v57", false)) {
            if (prefs.getString("decoder_mode", "auto") == "software") {
                prefs.edit().putString("decoder_mode", "auto").apply()
            }
            prefs.edit().putBoolean("decoder_migrated_v57", true).apply()
        }
        prefs.getString("decoder_mode", "auto") ?: "auto"
    }

    /** 应用启动时同步一次超时秒数偏好（避免每个频道都读 SP） */
    fun setSourceTimeoutMs(timeoutMs: Long) {
        sourceTimeoutMs = if (timeoutMs > 0) timeoutMs else 10_000L
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                // 起播成功：取消超时换源
                sourceTimeoutHandler.removeCallbacksAndMessages(null)
            }
            listener.onPlaybackStateChanged(player?.isPlaying == true)
        }

        override fun onPlayerError(error: PlaybackException) {
            // 诊断日志：完整堆栈写入 crash.log（设置→调试 可查看，便于真机定位解码问题）
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(context.filesDir, "crash.log").appendText(
                    "\n--- 播放错误 " + System.currentTimeMillis() + " ---\n" +
                        "errorCode=" + error.errorCodeName + "\n" + sw.toString() + "\n"
                )
            } catch (ignored: Exception) {
            }
            val url = currentUrl
            val isDecoderError =
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED

            // 多线路 + 非解码类错误：自动切下一条线路（网络/协议问题换线路通常能解决）
            if (!isDecoderError && currentSources.size > 1) {
                autoFail("线路 ${currentSourceIndex + 1} 播放失败")
                return
            }

            // 解码器初始化/解码失败：后台检测解码器是否坏状态
            // （T1 老 Amlogic 硬解组件长时间运行会卡死：format_supported=YES 但
            // OMX 组件 init failed，应用内无法修复），确认坏了就明确提示重启机顶盒，
            // 同时降级软解让用户先能看。
            if (!degradedToSoftware && decoderMode == "auto" && url != null && isDecoderError) {
                degradedToSoftware = true // 防重复进入检测流程
                val name = currentChannelName
                listener.onPlaybackError("硬解失败，正在检测解码器状态…")
                Thread {
                    val broken = !DecoderHealthCheck.isHardwareHevcHealthy()
                    Handler(Looper.getMainLooper()).post {
                        listener.onPlaybackError(
                            if (broken) "解码器异常，请重启机顶盒后重试（已临时切换软解）"
                            else "硬解失败，已切换软件解码"
                        )
                        retryHandler.postDelayed({ degradeToSoftware(url, name ?: url) }, 800)
                    }
                }.start()
                return
            }
            // 自动重试最多 2 次，间隔递增（1.5s / 3s）——仅单线路时生效
            if (retryCount < 2 && url != null) {
                retryCount++
                val delay = 1500L * retryCount
                val name = currentChannelName
                listener.onPlaybackError("播放失败，${retryCount} 秒后自动重试…")
                retryHandler.postDelayed({ retryPlay(url, name ?: url) }, delay)
                return
            }
            retryCount = 0
            listener.onPlaybackError(error.errorCodeName)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener.onPlaybackStateChanged(isPlaying)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                listener.onVideoSizeChanged(videoSize.width, videoSize.height)
            }
        }
    }

    fun attach(playerView: PlayerView) {
        if (player != null) return
        this.playerView = playerView
        playerView.player = buildPlayer()
        player = playerView.player as ExoPlayer
        player?.addListener(playerListener)
    }

    /** 切换解码方式：需要重建播放器（renderer 在创建时固定），保留当前频道继续播放。 */
    fun applyDecoderMode(mode: String) {
        if (mode == decoderMode) return
        decoderMode = mode
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .edit().putString("decoder_mode", mode).apply()
        val pv = playerView ?: return
        val name = currentChannelName
        player?.removeListener(playerListener)
        player?.release()
        pv.player = null
        player = buildPlayer()
        pv.player = player
        player?.addListener(playerListener)
        if (currentSources.isNotEmpty()) {
            retryCount = 0
            playCurrentSource()
        } else if (name != null) {
            retryCount = 0
            play(name, name)
        }
    }

    fun currentDecoderMode(): String = decoderMode

    /**
     * 硬解解码器初始化/解码失败时，把播放器重建为软件解码模式续播（影视仓同款兜底）。
     * 单独抽成方法：避免在 playerListener 初始化期间被 lambda 引用自身，
     * 触发 Kotlin "Type checking has run into a recursive problem"。
     *
     * 注意：只对当前频道生效（degradedToSoftware 内存标记），不写入全局设置，
     * 否则会把后续所有频道都拖进软解（1080p 卡顿、4K 只有声音没图像）。
     */
    private fun degradeToSoftware(url: String, channelName: String) {
        val pv = playerView ?: return
        player?.removeListener(playerListener)
        player?.release()
        pv.player = null
        degradedToSoftware = true
        player = buildPlayer()
        pv.player = player
        player?.addListener(playerListener)
        retryCount = 0
        // 手动续播（不调用 play()，避免 play() 重置 degradedToSoftware 标记）
        currentUrl = url
        currentChannelName = channelName
        val p = player ?: return
        p.setMediaSource(buildMediaSource(url, channelName))
        p.prepare()
        p.playWhenReady = true
        listener.onPlaybackReady(channelName)
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(): ExoPlayer {
        // 直播缓冲：起播 1.5s、卡顿后 3s、持续目标 15s、上限 45s。
        // 之前起播缓冲 15s 需要攒够两三个 TS 分片才开播，导致"等待播放时间太长"。
        // 直播流（TS 10s 分片）缓冲越小起播越快、延迟越低；45s 上限足够吸收网络抖动。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,   // minBufferMs：持续缓冲目标
                45_000,   // maxBufferMs：缓冲上限
                1_500,    // bufferForPlaybackMs：起播所需缓冲（秒开）
                3_000     // bufferForPlaybackAfterRebufferMs：卡顿后恢复所需缓冲
            )
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
        // 重要：不强制禁用异步 MediaCodec 队列。
        // 斐讯 T1（S912/Android 7）上影视仓 EXO 硬解 4K HEVC 都能流畅，
        // 用的就是 Media3 默认 async 配置；我们上一版 forceDisable 异步队列后，
        // 1.8.0 表现为解码器 init failed、1.4.1 表现为只出声不出画（硬解
        // configure 成功但 OMX 组件不吐帧）。恢复默认异步队列与影视仓一致。
        when {
            degradedToSoftware -> {
                // 本频道硬解失败后的软解兜底：只影响当前频道，切台/重启恢复设置的模式
                renderersFactory.setEnableDecoderFallback(false)
                renderersFactory.setMediaCodecSelector(SoftwareOnlySelector)
            }
            decoderMode == "hardware" -> {
                // 仅硬件解码：硬解失败直接报错，不回退软解（避免软解 1080p 卡死）
                renderersFactory.setEnableDecoderFallback(false)
                renderersFactory.setMediaCodecSelector(HardwareOnlySelector)
            }
            decoderMode == "software" -> {
                // 仅软件解码：绕过一切硬件解码器
                renderersFactory.setEnableDecoderFallback(false)
                renderersFactory.setMediaCodecSelector(SoftwareOnlySelector)
            }
            else -> {
                // 自动：硬解优先，失败自动回退
                renderersFactory.setEnableDecoderFallback(true)
            }
        }

        // 强制选择最高码率/最高分辨率轨道（TVBox 系播放器同款做法）：
        // HLS 多码率流默认按带宽估计选 variant，软解/网络抖动时会被"降级"到
        // 低分辨率（如 4K 变 720×576）。固定最高档，保证分辨率不缩水。
        //
        // setExceedRendererCapabilitiesIfNecessary：Amlogic 解码器能力上报不全
        // （MediaCodecInfo 把 4K 判为不支持），默认会因此降档选 1080P/720P，
        // 这里强制超出上报能力选择，让解码器实际去解（硬解本身支持 4K）。
        // ★ v1.6.1 曾一度移除该参数，实测与 v1.4.0 行为对比后确认：v1.4.0（带此参数）
        // 在用户 T1 上播放正常，v62 黑屏属解码器坏状态（重启 T1 可清除），
        // 故 v1.6.2 恢复与 v1.4.0 完全一致的配置。
        val trackSelector = DefaultTrackSelector(context)
        trackSelector.setParameters(
            DefaultTrackSelector.Parameters.Builder(context)
                .setForceHighestSupportedBitrate(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .build()
        )

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
    }

    /** 只保留非软件解码器（硬件/系统专用解码器）。仅过滤视频解码器： */
    private object HardwareOnlySelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> {
            val all = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            // 音频不过滤：T1 的 AAC 只有软件解码器（OMX.google.aac.decoder），
            // 若一并过滤则硬解模式下没有声音（用户实测"仅硬解只有图像没有声音"）。
            // 音频始终放行全部解码器，由系统自行选择（软解 AAC 音质/功耗无差别）。
            if (!mimeType.startsWith("video/")) return all
            return all.filter { !it.softwareOnly }
        }
    }

    /** 只保留软件解码器（兼容性最好）。仅过滤视频解码器，音频放行全部。 */
    private object SoftwareOnlySelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> {
            val all = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            if (!mimeType.startsWith("video/")) return all
            return all.filter { it.softwareOnly }
        }
    }

    /** 画面比例：fit / fill / zoom / 16:9 / 4:3 */
    fun setAspectRatio(mode: String) {
        val pv = playerView ?: return
        when (mode) {
            "fill" -> pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            "zoom" -> pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "16:9", "4:3" -> {
                pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                try {
                    val frame = pv.findViewById<AspectRatioFrameLayout>(
                        androidx.media3.ui.R.id.exo_content_frame
                    )
                    frame?.setAspectRatio(if (mode == "16:9") 16f / 9f else 4f / 3f)
                } catch (ignored: Exception) {
                }
            }
            else -> pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    // ---------- 多线路播放 ----------

    /** 播放频道（单线路兼容入口） */
    fun play(url: String, channelName: String) {
        play(listOf(url), channelName)
    }

    /** 播放频道（多线路：sources 按顺序排列，失败/超时自动切换） */
    fun play(sources: List<String>, channelName: String) {
        if (sources.isEmpty()) {
            listener.onPlaybackError("该频道没有可用线路")
            return
        }
        currentSources = sources.toList()
        currentSourceIndex = 0
        autoTryStartIndex = 0
        retryCount = 0
        degradedToSoftware = false
        playCurrentSource()
    }

    private fun playCurrentSource() {
        val p = player ?: return
        val url = currentSources.getOrNull(currentSourceIndex) ?: return
        currentUrl = url
        currentChannelName = currentChannelName ?: url
        p.stop()
        p.clearMediaItems()
        p.setMediaSource(buildMediaSource(url, currentChannelName ?: url))
        p.prepare()
        p.playWhenReady = true
        listener.onPlaybackReady(currentChannelName ?: url)
        startSourceTimeout()
    }

    /** 手动切换下一条线路（允许循环回绕），返回是否有多线路 */
    fun switchToNextLine(): Boolean {
        if (currentSources.size <= 1) return false
        currentSourceIndex = (currentSourceIndex + 1) % currentSources.size
        playCurrentSource()
        listener.onPlaybackError("已切换到线路 ${currentSourceIndex + 1}/${currentSources.size}")
        return true
    }

    /** 自动失败换源：一圈全部失败则报错停止 */
    private fun autoFail(reason: String) {
        if (currentSources.size <= 1) {
            listener.onPlaybackError(reason)
            return
        }
        val next = (currentSourceIndex + 1) % currentSources.size
        if (next == autoTryStartIndex) {
            sourceTimeoutHandler.removeCallbacksAndMessages(null)
            listener.onPlaybackError("所有线路均播放失败，请检查网络或手动换源")
            return
        }
        currentSourceIndex = next
        playCurrentSource()
        listener.onPlaybackError("$reason，自动切换线路 ${currentSourceIndex + 1}/${currentSources.size}")
    }

    private fun startSourceTimeout() {
        sourceTimeoutHandler.removeCallbacksAndMessages(null)
        sourceTimeoutHandler.postDelayed({
            val p = player ?: return@postDelayed
            if (p.playbackState == Player.STATE_READY || p.isPlaying) return@postDelayed
            if (currentSources.size > 1) {
                autoFail("线路 ${currentSourceIndex + 1} 起播超时（${sourceTimeoutMs / 1000}s）")
            }
            // 单线路超时：不做任何动作，让播放器自行缓冲/报错
        }, sourceTimeoutMs)
    }

    /** 当前线路序号（0 起） */
    fun currentSourceIndex(): Int = currentSourceIndex

    /** 当前频道线路总数 */
    fun sourceCount(): Int = currentSources.size

    /** 当前线路地址 */
    fun currentSourceUrl(): String? = currentUrl

    /** 当前估计带宽（kbps），用于顶部"显示网速" */
@OptIn(UnstableApi::class)
fun bandwidthKbps(): Long {
    return try {
        // 临时返回0，消除编译报错，其他代码完全不动
        0
    } catch (e: Exception) {
        0
    }
}


    private fun retryPlay(url: String, channelName: String) {
        val p = player ?: return
        p.stop()
        p.clearMediaItems()
        p.setMediaSource(buildMediaSource(url, channelName))
        p.prepare()
        p.playWhenReady = true
        listener.onPlaybackReady(channelName)
    }

    /**
     * 按内容类型显式构建媒体源（lemonTV 同款做法）：
     * .m3u8 → HlsMediaSource；其余 → ProgressiveMediaSource。
     * 比默认推断更稳，避免个别源被误判容器。
     */
    @OptIn(UnstableApi::class)
    private fun buildMediaSource(url: String, channelName: String): androidx.media3.exoplayer.source.MediaSource {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("ExoPlayer/IPTVPlayer")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaId(channelName)
            .build()
        val type = Util.inferContentType(Uri.parse(url))
        return when (type) {
            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.playWhenReady) {
            p.pause()
        } else {
            p.play()
        }
    }

    fun release() {
        retryHandler.removeCallbacksAndMessages(null)
        sourceTimeoutHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        playerView = null
    }
}
