package com.cyj265.iptvplayer.data

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 直播源健康度检测器
 * 通过 HTTP HEAD 请求测试各源的连接延迟和可用性
 */
class SourceHealthChecker {

    data class SourceHealth(
        val url: String,
        val available: Boolean,
        val latencyMs: Long,        // -1 表示不可用
        val status: Status,
        val testedAt: Long = System.currentTimeMillis()
    ) {
        enum class Status { GOOD, SLOW, UNAVAILABLE, UNTESTED }

        val statusText: String
            get() = when (status) {
                Status.GOOD -> "正常"
                Status.SLOW -> "较慢"
                Status.UNAVAILABLE -> "不可用"
                Status.UNTESTED -> "未检测"
            }

        val statusColor: Int
            get() = when (status) {
                Status.GOOD -> 0xFF4CAF50.toInt()   // 绿
                Status.SLOW -> 0xFFFFC107.toInt()   // 黄
                Status.UNAVAILABLE -> 0xFFF44336.toInt() // 红
                Status.UNTESTED -> 0xFF9E9E9E.toInt() // 灰
            }
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(3)

    /** 测试单个源的延迟（HTTP HEAD，超时 5 秒） */
    fun checkSource(url: String, timeoutMs: Int = 5000): SourceHealth {
        if (url.isBlank()) {
            return SourceHealth(url, false, -1, SourceHealth.Status.UNAVAILABLE)
        }
        // 本地文件不检测
        if (url.startsWith("file://") || url.startsWith("/")) {
            return SourceHealth(url, true, 0, SourceHealth.Status.GOOD)
        }
        val start = System.currentTimeMillis()
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "LanXingTV/1.0")
            val code = conn.responseCode
            val latency = System.currentTimeMillis() - start
            conn.disconnect()
            val available = code in 200..399
            val status = when {
                !available -> SourceHealth.Status.UNAVAILABLE
                latency <= 1000 -> SourceHealth.Status.GOOD
                latency <= 3000 -> SourceHealth.Status.SLOW
                else -> SourceHealth.Status.UNAVAILABLE
            }
            SourceHealth(url, available, latency, status)
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            SourceHealth(url, false, -1, SourceHealth.Status.UNAVAILABLE)
        }
    }

    /** 批量测试所有源（异步，回调在主线程调用） */
    fun checkAllSources(
        urls: List<String>,
        timeoutMs: Int = 5000,
        onProgress: ((Int, Int, SourceHealth) -> Unit)? = null,
        onComplete: ((List<SourceHealth>) -> Unit)? = null
    ) {
        if (urls.isEmpty()) {
            onComplete?.invoke(emptyList())
            return
        }
        executor.execute {
            val results = mutableListOf<SourceHealth>()
            urls.forEachIndexed { index, url ->
                val health = checkSource(url, timeoutMs)
                results.add(health)
                onProgress?.invoke(index + 1, urls.size, health)
            }
            onComplete?.invoke(results)
        }
    }

    /** 从健康度列表中找出最快的可用源索引 */
    fun findFastestAvailable(healthList: List<SourceHealth>): Int {
        var bestIndex = -1
        var bestLatency = Long.MAX_VALUE
        healthList.forEachIndexed { index, health ->
            if (health.available && health.latencyMs in 0 until bestLatency) {
                bestLatency = health.latencyMs
                bestIndex = index
            }
        }
        return bestIndex
    }

    fun shutdown() {
        try {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (ignored: Exception) {
        }
    }
}
