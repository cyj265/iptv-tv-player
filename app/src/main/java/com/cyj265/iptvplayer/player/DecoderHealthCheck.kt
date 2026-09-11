package com.cyj265.iptvplayer.player

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * 解码器健康自检与免重启修复。
 *
 * 斐讯 T1（Amlogic S912 / Android 7）的 HEVC 硬解组件
 * （OMX.amlogic.hevc.decoder.awesome）在盒子长时间运行后会进入"坏状态"：
 * MediaCodecList 仍上报支持（format_supported=YES），但 create/start 解码器
 * 会失败（Decoder init failed，崩溃日志可见）。此时重启盒子能恢复。
 *
 * 本类提供不重启盒子的修复链：
 * 1. isHardwareHevcHealthy()：真实创建并 start 一个 1080P HEVC 硬解实例探测好坏；
 * 2. tryRepair()：
 *    a. 等待数秒重试（组件可能只是瞬时占用/未回收）；
 *    b. 有 root 时 killall mediaserver（Android 7 解码器宿主进程，
 *       杀掉后 init 自动拉起新进程，解码器随之重置）——仅在探测到坏状态时执行；
 *    c. 都失败则返回 FAILED，由上层提示用户重启盒子。
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

    /**
     * 免重启修复链（阻塞调用，必须在后台线程执行）。
     * 总耗时最坏约 9 秒：2 次等待重试（6s）+ mediaserver 重启（3s）。
     */
    fun tryRepair(): RepairResult {
        // 第一步：先确认确实处于坏状态
        if (isHardwareHevcHealthy()) return RepairResult.HEALTHY

        // 第二步：等 3 秒重试两次（组件可能瞬时占用，稍后自动释放）
        for (i in 1..2) {
            Thread.sleep(3000)
            if (isHardwareHevcHealthy()) return RepairResult.FIXED_BY_WAIT
        }

        // 第三步：尝试重启 mediaserver（需 root；杀后 init 自动拉起新进程）
        if (restartMediaServer()) {
            Thread.sleep(3000)
            if (isHardwareHevcHealthy()) return RepairResult.FIXED_BY_MEDIA_RESTART
        }

        return RepairResult.FAILED
    }

    /** 尝试用 root 杀掉 mediaserver（Android 7 解码器宿主进程）。 */
    private fun restartMediaServer(): Boolean {
        val cmds = listOf(
            arrayOf("su", "-c", "killall mediaserver"),
            arrayOf("su", "-c", "pkill mediaserver"),
            arrayOf("su", "0", "killall mediaserver")
        )
        for (cmd in cmds) {
            try {
                val p = Runtime.getRuntime().exec(cmd)
                if (p.waitFor() == 0) return true
            } catch (ignored: Exception) {
            }
        }
        return false
    }

    enum class RepairResult {
        HEALTHY,                 // 本来就正常，无需修复
        FIXED_BY_WAIT,           // 等待数秒后自愈（瞬时占用已释放）
        FIXED_BY_MEDIA_RESTART,  // 重启 mediaserver 后恢复
        FAILED                   // 免重启修复失败，需用户重启盒子
    }
}
