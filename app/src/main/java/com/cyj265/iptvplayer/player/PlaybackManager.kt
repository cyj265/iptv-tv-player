package com.cyj265.iptvplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 基于 Media3 ExoPlayer 的播放内核。
 * 自动识别 HLS / 渐进式流，与 TiviMate 同源的内核家族，对标准 HLS 支持最好。
 *
 * 解码策略（应对 Android 7 / 老电视盒子解码器差异）：
 * 1. 默认使用平台硬解 + 解码器回退（硬解失败会尝试其他可用 MediaCodec）；
 * 2. 若仍报 DECODER_INIT_FAILED（如 H.265 Main10、特殊音频格式硬解不支持），
 *    自动切换到 FFmpeg 软解模式重试，保证能出画面。
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
    private var softwareMode = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            listener.onPlaybackStateChanged(player?.isPlaying == true)
        }

        override fun onPlayerError(error: PlaybackException) {
            val url = currentUrl
            if (url == null) {
                listener.onPlaybackError(error.errorCodeName)
                return
            }
            // 硬解失败 -> 自动降级软解，只降级一次
            if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && !softwareMode) {
                softwareMode = true
                listener.onPlaybackError("硬解失败，已自动切换软解重试")
                switchToSoftwareDecoder(url)
            } else {
                listener.onPlaybackError(error.errorCodeName)
            }
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
        playerView.player = buildPlayer(forceSoftware = false)
        player = playerView.player as ExoPlayer
        player?.addListener(playerListener)
    }

    /** 构建播放器；forceSoftware=true 时仅用 FFmpeg 软解（media3-decoder-ffmpeg）。 */
    private fun buildPlayer(forceSoftware: Boolean): ExoPlayer {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("ExoPlayer/IPTVPlayer")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        if (forceSoftware) {
            renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    private fun switchToSoftwareDecoder(url: String) {
        try {
            player?.release()
            player = null
            playerView?.player = null
            val p = buildPlayer(forceSoftware = true)
            p.addListener(playerListener)
            playerView?.player = p
            player = p
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMediaId(currentChannelName ?: "")
                .build()
            p.setMediaItem(mediaItem)
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) {
            listener.onPlaybackError("软解切换失败：" + (e.message ?: "未知错误"))
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
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaId(channelName)
            .build()
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
        player?.release()
        player = null
        playerView = null
    }
}
