package com.rhodes.tv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object XmltvParser {

    private fun parseTime(s: String?): Long {
        if (s == null) return 0L
        return try {
            val t = s.trim()
            if (t.length < 14) return 0L
            var tz = "GMT+8"
            if (t.length > 14) {
                val rest = t.substring(14).trim()
                if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                    tz = "GMT" + rest.substring(0, 3) + ":" + rest.substring(3, 5)
                }
            }
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone(tz)
            fmt.parse(t.substring(0, 14))?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun parse(input: InputStream): List<EpgProgramme> {
        val out = ArrayList<EpgProgramme>()
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            var ev = p.eventType
            var inProg = false
            var ch: String? = null
            var start = 0L
            var stop = 0L
            var title: String? = null
            var desc: String? = null
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "programme" -> {
                            inProg = true
                            ch = p.getAttributeValue(null, "channel")
                            start = parseTime(p.getAttributeValue(null, "start"))
                            stop = parseTime(p.getAttributeValue(null, "stop"))
                            title = null
                            desc = null
                        }
                        "title" -> if (inProg) title = safeText(p)
                        "desc" -> if (inProg) desc = safeText(p)
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (p.name == "programme" && inProg) {
                        val c = ch
                        val t = title
                        if (c != null && !t.isNullOrBlank() && stop > start) {
                            out.add(EpgProgramme(c, t, start, stop, desc))
                        }
                        inProg = false
                    }
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            // return partial
        }
        return out
    }

    private fun safeText(p: XmlPullParser): String? = try {
        p.nextText().trim()
    } catch (e: Exception) {
        null
    }
}
