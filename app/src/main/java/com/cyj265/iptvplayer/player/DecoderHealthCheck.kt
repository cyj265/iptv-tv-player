package com.cyj265.iptvplayer.player

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * 解码器健康检测。
 *
 * 斐讯 T1（Amlogic S912 / Android 7）的 HEVC 硬解组件
 * （OMX.amlogic.hevc.decoder.awesome）在盒子长时间运行后会进入"坏状态"：
 * MediaCodecList 仍上报支持（format_supported=YES），但 create/start 解码器
 * 会失败（Decoder init failed，崩溃日志可见）。此状态应用内无法修复，
 * 只能重启机顶盒（或重启 mediaserver）恢复，因此这里只做检测：
 *
 * isHardwareHevcHealthy()：真实创建并 start 一个 1080P HEVC 硬解实例，
 * 能成功 = 正常；抛异常 = 坏状态，由上层提示用户重启机顶盒。
 */
object DecoderHealthCheck {

    private const val MIME_HEVC = "video/hevc"

    /** 探测 1080P HEVC 硬解是否可用（用户极清/高清均为该档）。true=正常。 */
    fun isHardwareHevcHealthy(): Boolean {
        return try {
            val codecName = findHardwareDecoder(MIME_HEVC) ?: return false
            val format = MediaFormat.createVideoFormat(MIME_HEVC, 1920, 1080)
            val codec = MediaCodec.createByCodecName(codecName)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                true
            } finally {
                try { codec.stop() } catch (ignored: Exception) {}
                try { codec.release() } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 找系统里 HEVC 硬件解码器（跳过 OMX.google.* 软件解码器）。 */
    private fun findHardwareDecoder(mime: String): String? {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder && info.supportedTypes.contains(mime)) {
                if (!info.name.startsWith("OMX.google.")) {
                    return info.name
                }
            }
        }
        return null
    }
}
