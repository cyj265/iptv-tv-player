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
 * 基于 Media3 ExoPlayer 的播放内核。
 * 自动识别 HLS / 渐进式流，与 TiviMate 同源的内核家族，对标准 HLS 支持最好。
 *
 * 解码策略（设置中可切换）：
 * - auto：硬解优先，解码器初始化失败自动降级软解（默认，影视仓同款行为）；
 * - hardware：只允许硬件解码器；
 * - software：只允许软件解码器。
 *
 * 直播缓冲：起播 1.5s、重缓冲 3s、持续 15s、上限 45s——秒开且能吸收网络抖动。
 *
 * 失败自动重试：解码/网络瞬时错误自动重播（最多 2 次），避免偶尔抽风直接报错。
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

    private var retryCount = 0
    private val retryHandler = Handler(Looper.getMainLooper())

    /** 当前频道是否已从硬解自动降级到软解（每个频道重置一次） */
    private var degradedToSoftware = false

    /** 解码方式：auto / hardware / software */
    private var decoderMode: String =
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .getString("decoder_mode", "auto") ?: "auto"

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
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
            // 硬解解码器初始化/解码失败时，自动降级到软解重试（影视仓同款行为）：
            // Amlogic 老硬解对个别 HEVC 流会 init failed，Media3 的 fallback 只覆盖
            // 解码器查询阶段，configure 阶段失败不会自动换软解，这里手动补上。
            val url = currentUrl
            if (!degradedToSoftware && decoderMode == "auto" && url != null &&
                (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED)
            ) {
                degradedToSoftware = true
                val name = currentChannelName
                listener.onPlaybackError("硬解失败，正在切换到软件解码…")
                retryHandler.postDelayed({ degradeToSoftware(url, name ?: url) }, 800)
                return
            }
            // 自动重试最多 2 次，间隔递增（1.5s / 3s）
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
        val url = currentUrl
        val name = currentChannelName
        player?.removeListener(playerListener)
        player?.release()
        pv.player = null
        player = buildPlayer()
        pv.player = player
        player?.addListener(playerListener)
        if (url != null) {
            retryCount = 0
            play(url, name ?: url)
        }
    }

    fun currentDecoderMode(): String = decoderMode

    /**
     * 硬解解码器初始化/解码失败时，把播放器重建为软件解码模式续播（影视仓同款兜底）。
     * 单独抽成方法：避免在 playerListener 初始化期间被 lambda 引用自身，
     * 触发 Kotlin "Type checking has run into a recursive problem"。
     */
    private fun degradeToSoftware(url: String, channelName: String) {
        val pv = playerView ?: return
        decoderMode = "software"
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .edit().putString("decoder_mode", "software").apply()
        player?.removeListener(playerListener)
        player?.release()
        pv.player = null
        player = buildPlayer()
        pv.player = player
        player?.addListener(playerListener)
        retryCount = 0
        play(url, channelName)
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
            // 强制同步 MediaCodec 队列：Amlogic（斐讯 T1 S912）Android 7 的
            // 硬件解码器对异步模式支持不佳，Media3 默认 async 优先会导致
            // HEVC 解码器初始化失败（DECODER_INIT_FAILED）。同步模式最稳。
            .forceDisableMediaCodecAsynchronousQueueing()
        when (decoderMode) {
            "hardware" -> {
                // 仅硬件解码：硬解失败直接报错，不回退软解（避免软解 1080p 卡死）
                renderersFactory.setEnableDecoderFallback(false)
                renderersFactory.setMediaCodecSelector(HardwareOnlySelector)
            }
            "software" -> {
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
        // setExceedRendererCapabilitiesIfNecessary：Amlogic 解码器能力上报不全
        // （MediaCodecInfo 把 4K 判为不支持），默认会因此降档选 1080P/720P，
        // 这里强制超出上报能力选择，让解码器实际去解（硬解本身支持 4K）。
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

    /** 只保留非软件解码器（硬件/系统专用解码器） */
    private object HardwareOnlySelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> {
            return MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                .filter { !it.softwareOnly }
        }
    }

    /** 只保留软件解码器（兼容性最好） */
    private object SoftwareOnlySelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> {
            return MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                .filter { it.softwareOnly }
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

    fun play(url: String, channelName: String) {
        val p = player ?: return
        currentUrl = url
        currentChannelName = channelName
        retryCount = 0
        degradedToSoftware = false
        p.setMediaSource(buildMediaSource(url, channelName))
        p.prepare()
        p.playWhenReady = true
        listener.onPlaybackReady(channelName)
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
        player?.release()
        player = null
        playerView = null
    }
}
