package com.cyj265.iptvplayer.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 简单的网络加载器，负责下载 M3U / EPG。
 */
object HttpLoader {

    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 30000
    private const val MAX_REDIRECTS = 5

    private val UA =
        "Mozilla/5.0 (Linux; Android 7.0; TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    fun fetch(url: String): String {
        val bytes = fetchBytes(url)
        return decodeText(bytes)
    }

    fun fetchBytes(url: String): ByteArray {
        var current = url
        var redirects = 0
        while (true) {
            val conn = open(current)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location == null) throw IOException("Redirect without Location")
                    if (++redirects > MAX_REDIRECTS) throw IOException("Too many redirects")
                    current = if (location.startsWith("http")) location else resolve(current, location)
                    continue
                }
                if (code != 200) throw IOException("HTTP $code")
                return conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        return conn
    }

    private fun resolve(base: String, location: String): String {
        return try {
            URL(URL(base), location).toString()
        } catch (e: Exception) {
            location
        }
    }

    /** M3U 可能是 UTF-8 或 GBK 编码，依次尝试。 */
    private fun decodeText(bytes: ByteArray): String {
        // UTF-8 无 BOM 时检查有效性
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        // 尝试 GBK，同样检查是否含替换字符（乱码标志）
        return try {
            val gbk = String(bytes, Charset.forName("GBK"))
            if (!gbk.contains('\uFFFD')) gbk else utf8
        } catch (e: Exception) {
            utf8
        }
    }
}
