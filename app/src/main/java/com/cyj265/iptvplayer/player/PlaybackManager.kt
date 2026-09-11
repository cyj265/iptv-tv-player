package com.cyj265.iptvplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/**
 * 基于 libVLC 的播放内核（TiviMate/Kodi 同族，HEVC 软硬解自动切换）。
 *
 * 为什么换 VLC：之前 Media3 ExoPlayer（1.4.1/1.8.0，同步/异步队列都试过）在
 * 斐讯 T1（S912/Android 7）上 HEVC 硬解反复失败——DECODER_INIT_FAILED 或
 * 只出声不出画；而影视仓 EXO、TiviMate 等 VLC 系在 T1 上 4K HEVC 全流畅。
 * libVLC 内置"硬解优先、失败自动回退软解"（--codec 默认 mediacodec 优先），
 * 不需要手动降级逻辑。
 *
 * 解码策略（设置中可切换）：
 * - auto：VLC 默认（mediacodec 硬解优先，失败自动回退软解）；
 * - hardware：--codec=mediacodec（仅硬件解码器）；
 * - software：--codec=avcodec（FFmpeg 软解，兼容性最好）。
 *
 * 直播低延迟：--live-caching=500 / --network-caching=500，起播快且能吸收抖动。
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

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private var currentUrl: String? = null
    private var currentChannelName: String? = null

    private var retryCount = 0
    private val retryHandler = Handler(Looper.getMainLooper())

    /** 解码方式：auto / hardware / software（映射 VLC --codec） */
    private var decoderMode: String =
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .getString("decoder_mode", "auto") ?: "auto"

    /** 画面比例：fit / 16:9 / 4:3 / zoom / fill */
    private var aspectRatio: String =
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .getString("aspect_ratio", "fit") ?: "fit"

    fun attach(layout: VLCVideoLayout) {
        if (mediaPlayer != null) return
        this.videoLayout = layout
        try {
            val vlc = LibVLC(context, vlcArgs())
            val mp = MediaPlayer(vlc)
            libVLC = vlc
            mediaPlayer = mp
            layout.setMediaPlayer(mp)
            mp.setEventListener { event -> onVlcEvent(event) }
        } catch (t: Throwable) {
            log("VLC 初始化失败: " + t)
            listener.onPlaybackError("播放内核初始化失败")
        }
    }

    /** VLC 全局参数（构造时固定，切换解码方式需重建 LibVLC） */
    private fun vlcArgs(): ArrayList<String> {
        val args = ArrayList<String>()
        // 直播低延迟：500ms 缓冲，起播快且能吸收甘肃移动网络的抖动
        args.add("--live-caching=500")
        args.add("--network-caching=500")
        when (decoderMode) {
            "hardware" -> args.add("--codec=mediacodec")
            "software" -> args.add("--codec=avcodec")
            // auto：VLC 默认 mediacodec 硬解优先，失败自动回退 avcodec 软解
        }
        return args
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                retryCount = 0
                listener.onPlaybackReady(currentChannelName ?: "")
                listener.onPlaybackStateChanged(true)
            }
            MediaPlayer.Event.Paused -> listener.onPlaybackStateChanged(false)
            MediaPlayer.Event.Stopped -> listener.onPlaybackStateChanged(false)
            MediaPlayer.Event.EndReached -> listener.onPlaybackStateChanged(false)
            MediaPlayer.Event.EncounteredError -> {
                log("VLC 播放错误: " + currentUrl)
                listener.onPlaybackStateChanged(false)
                val url = currentUrl
                if (retryCount < 2 && url != null) {
                    retryCount++
                    val delay = 1500L * retryCount
                    val name = currentChannelName
                    listener.onPlaybackError("播放失败，${retryCount} 秒后自动重试…")
                    retryHandler.postDelayed({ retryPlay(url, name ?: url) }, delay)
                } else {
                    retryCount = 0
                    listener.onPlaybackError("播放失败（编码不支持或源不可用）")
                }
            }
            MediaPlayer.Event.Vout -> {
                // 视频尺寸变化（首次出画面 / 分辨率切换）
                if (event.width > 0 && event.height > 0) {
                    listener.onVideoSizeChanged(event.width, event.height)
                }
            }
        }
    }

    fun play(url: String, channelName: String) {
        val mp = mediaPlayer ?: return
        val vlc = libVLC ?: return
        currentUrl = url
        currentChannelName = channelName
        retryCount = 0
        try {
            // 释放旧 media
            val old = mp.media
            mp.media = null
            old?.release()
            mp.stop()

            val media = Media(vlc, Uri.parse(url))
            media.addOption(":network-caching=500")
            when (aspectRatio) {
                "16:9" -> media.addOption(":aspect-ratio=16:9")
                "4:3" -> media.addOption(":aspect-ratio=4:3")
                else -> {}
            }
            mp.media = media
            applyTextureScale()
            mp.play()
            listener.onPlaybackReady(channelName)
        } catch (t: Throwable) {
            log("播放启动失败: $t")
            listener.onPlaybackError("播放失败：" + (t.message ?: "未知错误"))
        }
    }

    private fun retryPlay(url: String, channelName: String) {
        play(url, channelName)
    }

    /** 切换解码方式：重建 LibVLC（--codec 是构造参数），保留当前频道继续播放。 */
    fun applyDecoderMode(mode: String) {
        if (mode == decoderMode) return
        decoderMode = mode
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .edit().putString("decoder_mode", mode).apply()
        val layout = videoLayout ?: return
        val url = currentUrl
        val name = currentChannelName
        try {
            mediaPlayer?.release()
            libVLC?.release()
            val vlc = LibVLC(context, vlcArgs())
            val mp = MediaPlayer(vlc)
            libVLC = vlc
            mediaPlayer = mp
            layout.setMediaPlayer(mp)
            mp.setEventListener { event -> onVlcEvent(event) }
        } catch (t: Throwable) {
            log("切换解码方式失败: $t")
            listener.onPlaybackError("切换解码方式失败")
            return
        }
        if (url != null) {
            retryCount = 0
            play(url, name ?: url)
        }
    }

    fun currentDecoderMode(): String = decoderMode

    /** 画面比例：fit / 16:9 / 4:3（VLC 原生）/ zoom / fill（缩放近似） */
    fun setAspectRatio(mode: String) {
        aspectRatio = mode
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .edit().putString("aspect_ratio", mode).apply()
        applyTextureScale()
        // 16:9 / 4:3 需要重建当前 media 以应用 :aspect-ratio option
        val url = currentUrl
        val name = currentChannelName
        if (url != null && (mode == "16:9" || mode == "4:3")) {
            retryCount = 0
            play(url, name ?: url)
        }
    }

    /** zoom/fill 通过放大画面实现（fill 视觉上接近无黑边），fit 恢复原始 */
    private fun applyTextureScale() {
        val layout = videoLayout ?: return
        val scale = when (aspectRatio) {
            "zoom" -> 1.12f
            "fill" -> 1.15f
            else -> 1f
        }
        layout.post {
            try {
                val tex = layout.getChildAt(0) ?: return@post
                tex.scaleX = scale
                tex.scaleY = scale
                tex.pivotX = tex.width / 2f
                tex.pivotY = tex.height / 2f
            } catch (ignored: Throwable) {
            }
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
        } else {
            mp.play()
        }
    }

    fun release() {
        retryHandler.removeCallbacksAndMessages(null)
        try {
            videoLayout?.mediaPlayer = null
        } catch (ignored: Throwable) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
        libVLC?.release()
        libVLC = null
        videoLayout = null
    }

    /** 诊断日志：写入 filesDir/crash.log（设置→调试 可查看） */
    private fun log(msg: String) {
        try {
            File(context.filesDir, "crash.log").appendText(
                "\n--- " + System.currentTimeMillis() + " ---\n" + msg + "\n"
            )
        } catch (ignored: Throwable) {
        }
    }
}
