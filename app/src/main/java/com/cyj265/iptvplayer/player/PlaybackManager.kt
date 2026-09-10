package com.cyj265.iptvplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
 * - auto：硬解优先，解码器初始化失败自动回退其他解码器（默认）；
 * - hardware：只允许硬件解码器（斐讯 T1 的 H.265 硬解本身没问题，
 *   此模式可避免自动回退误选软解导致 1080p 卡顿/报错）；
 * - software：只允许软件解码器（兼容性最好，但 1080p HEVC 较费 CPU）。
 *
 * 直播缓冲调大：TS 分片 10s 一段，缓冲不足会导致一卡一卡，这里把起播缓冲
 * 提到 15s、卡顿重缓冲 30s，明显改善网络抖动下的流畅度。
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
            // 自动重试最多 2 次，间隔递增（1.5s / 3s）
            val url = currentUrl
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

    private fun buildPlayer(): ExoPlayer {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("ExoPlayer/IPTVPlayer")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)

        // 直播缓冲调大：起播 15s、重缓冲 30s、上限 180s，直播源网络抖动不再一卡一卡
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                60_000,   // minBufferMs：持续缓冲目标
                180_000,  // maxBufferMs：缓冲上限
                15_000,   // bufferForPlaybackMs：起播所需缓冲
                30_000    // bufferForPlaybackAfterRebufferMs：卡顿后恢复所需缓冲
            )
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
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

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
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
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaId(channelName)
            .build()
        p.setMediaItem(mediaItem)
        p.prepare()
        p.playWhenReady = true
        listener.onPlaybackReady(channelName)
    }

    private fun retryPlay(url: String, channelName: String) {
        val p = player ?: return
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaId(channelName)
            .build()
        p.stop()
        p.clearMediaItems()
        p.setMediaItem(mediaItem)
        p.prepare()
        p.playWhenReady = true
        listener.onPlaybackReady(channelName)
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
