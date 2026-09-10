package com.cyj265.iptvplayer.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 解析 XMLTV (EPG) 数据。
 */
object EpgParser {

    private val DATE_FORMATS = arrayOf(
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US),
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    )

    data class EpgData(
        val channelNames: Map<String, String>,
        val programs: List<EpgProgram>
    )

    fun parse(content: String): EpgData {
        val channelNames = HashMap<String, String>()
        val programs = ArrayList<EpgProgram>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(content))

            var event = parser.eventType
            var currentChannelId: String? = null
            var currentStart = 0L
            var currentEnd = 0L
            var currentTitle = ""
            var currentDesc = ""
            var inProgramme = false
            var inTitle = false
            var inDesc = false

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "channel" -> {
                                currentChannelId = parser.getAttributeValue(null, "id")
                            }
                            "display-name" -> {
                                if (currentChannelId != null) {
                                    val text = parser.nextText()
                                    if (!channelNames.containsKey(currentChannelId)) {
                                        channelNames[currentChannelId!!] = text
                                    }
                                }
                            }
                            "programme" -> {
                                inProgramme = true
                                val ch = parser.getAttributeValue(null, "channel") ?: ""
                                currentChannelId = ch
                                currentStart = parseDate(parser.getAttributeValue(null, "start"))
                                currentEnd = parseDate(parser.getAttributeValue(null, "stop"))
                            }
                            "title" -> if (inProgramme) { inTitle = true }
                            "desc" -> if (inProgramme) { inDesc = true }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inTitle) currentTitle += parser.text ?: ""
                        if (inDesc) currentDesc += parser.text ?: ""
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "title" -> inTitle = false
                            "desc" -> inDesc = false
                            "programme" -> {
                                if (currentChannelId != null && currentStart > 0) {
                                    programs.add(
                                        EpgProgram(
                                            channelId = currentChannelId!!,
                                            start = currentStart,
                                            end = currentEnd,
                                            title = currentTitle.trim(),
                                            description = currentDesc.trim()
                                        )
                                    )
                                }
                                inProgramme = false
                                currentChannelId = null
                                currentTitle = ""
                                currentDesc = ""
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (ignored: Exception) {
            // 解析失败返回已收集部分
        }

        return EpgData(channelNames, programs)
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val s = raw.trim()
        for (fmt in DATE_FORMATS) {
            try {
                return fmt.parse(s)?.time ?: 0L
            } catch (ignored: Exception) {
            }
        }
        // 尝试把 UTC 时区后缀处理一下
        try {
            val fixed = s.replace(Regex("\\s*\\+0000$"), "")
            DATE_FORMATS[1].timeZone = TimeZone.getTimeZone("UTC")
            return DATE_FORMATS[1].parse(fixed)?.time ?: 0L
        } catch (ignored: Exception) {
        }
        return 0L
    }
}
