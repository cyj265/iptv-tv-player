package com.cyj265.iptvplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 基于 Media3 ExoPlayer 的播放内核。
 * 自动识别 HLS / 渐进式流，与 TiviMate 同源的内核家族，对标准 HLS 支持最好。
 */
class PlaybackManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onPlaybackReady(channelName: String)
        fun onPlaybackError(message: String)
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var currentUrl: String? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            listener.onPlaybackStateChanged(player?.isPlaying == true)
        }

        override fun onPlayerError(error: PlaybackException) {
            listener.onPlaybackError(error.errorCodeName)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener.onPlaybackStateChanged(isPlaying)
        }
    }

    fun attach(playerView: PlayerView) {
        if (player != null) return
        this.playerView = playerView
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("ExoPlayer/IPTVPlayer")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)

        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        p.addListener(playerListener)
        playerView.player = p
        player = p
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
