package no.frodolini.podspelar

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

// Denne fila er rein Kotlin/Java utan Android-klassar, slik at ho kan testast på PC-en.

const val USER_AGENT = "Podspelar/0.1 (Android)"

data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String,
    val artUrl: String,
)

data class Episode(
    val id: String,            // stabil nøkkel, laga av feed-adressa og guid
    val feedUrl: String,
    val podcastTitle: String,
    val title: String,
    val pubMs: Long,           // utgjevingstid, 0 om ukjend
    val durationSec: Int,      // 0 om ukjend
    val audioUrl: String,
    val description: String,
    val artUrl: String,
)

class Feed(val podcast: Podcast, val episodes: List<Episode>)

/** Opnar ei adresse og følgjer vidaresendingar sjølv, også mellom http og https. */
fun openUrl(url: String, rangeFrom: Long = 0): HttpURLConnection {
    var current = url
    repeat(10) {
        val c = URL(current).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
        val code = c.responseCode
        if (code in 300..399) {
            val loc = c.getHeaderField("Location") ?: throw IOException("Vidaresending utan adresse")
            c.disconnect()
            current = URL(URL(current), loc).toString()
        } else if (code in 200..299) {
            return c
        } else {
            c.disconnect()
            throw IOException("Tenaren svara $code")
        }
    }
    throw IOException("For mange vidaresendingar")
}

/** Søk i Apple sin opne podkastkatalog. Krev ingen nøkkel. */
fun searchPodcasts(term: String): List<Podcast> {
    val q = URLEncoder.encode(term.trim(), "UTF-8")
    val c = openUrl("https://itunes.apple.com/search?media=podcast&limit=40&term=$q")
    val text = try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    return parseSearch(text)
}

fun parseSearch(json: String): List<Podcast> {
    val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
    val out = ArrayList<Podcast>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val feed = o.optString("feedUrl")
        if (feed.isBlank()) continue
        out += Podcast(
            feedUrl = feed,
            title = o.optString("collectionName").ifBlank { o.optString("trackName") },
            author = o.optString("artistName"),
            artUrl = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") },
        )
    }
    return out.distinctBy { it.feedUrl }
}

fun fetchFeed(feedUrl: String): Feed {
    val c = openUrl(feedUrl)
    try {
        return c.inputStream.use { parseFeed(it, feedUrl) }
    } finally {
        c.disconnect()
    }
}

fun parseFeed(input: InputStream, feedUrl: String): Feed {
    val p = XmlPullParserFactory.newInstance().newPullParser()
    // Mange feedar har HTML-teikn som &nbsp; utan å definera dei. «relaxed» tolererer det.
    runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
    p.setInput(input, null)

    var title = ""
    var author = ""
    var art = ""
    val items = ArrayList<Episode>()

    var ev = p.eventType
    var inChannel = false
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG) {
            val name = p.name.lowercase()
            if (!inChannel) {
                if (name == "channel") inChannel = true
            } else when (name) {
                "item" -> readItem(p, feedUrl)?.let { items += it }
                "title" -> { val t = readText(p); if (title.isEmpty()) title = t }
                "itunes:author" -> author = readText(p)
                "itunes:image" -> { p.getAttributeValue(null, "href")?.let { art = it }; skip(p) }
                "image" -> { val u = readImageUrl(p); if (art.isEmpty()) art = u }
                else -> skip(p)
            }
        }
        ev = p.next()
    }
    val pod = Podcast(feedUrl, title.trim(), author.trim(), art.trim())
    val eps = items.map {
        it.copy(podcastTitle = pod.title, artUrl = it.artUrl.ifBlank { pod.artUrl })
    }.distinctBy { it.id }.sortedByDescending { it.pubMs }   // nokre feedar har same guid to gonger
    return Feed(pod, eps)
}

private fun readItem(p: XmlPullParser, feedUrl: String): Episode? {
    var title = ""
    var guid = ""
    var pub = 0L
    var dur = 0
    var audio = ""
    var desc = ""
    var summary = ""
    var art = ""
    val depth = p.depth
    while (true) {
        val ev = p.next()
        if (ev == XmlPullParser.END_DOCUMENT) break
        if (ev == XmlPullParser.END_TAG && p.depth == depth) break
        if (ev != XmlPullParser.START_TAG) continue
        when (p.name.lowercase()) {
            "title" -> title = readText(p)
            "guid" -> guid = readText(p)
            "pubdate" -> pub = parseDate(readText(p))
            "itunes:duration" -> dur = parseDuration(readText(p))
            "enclosure" -> {
                val url = p.getAttributeValue(null, "url").orEmpty()
                val type = p.getAttributeValue(null, "type").orEmpty()
                if (url.isNotBlank() && (audio.isEmpty() || type.startsWith("audio"))) audio = url
                skip(p)
            }
            "description" -> desc = readText(p)
            "itunes:summary" -> summary = readText(p)
            "itunes:image" -> { p.getAttributeValue(null, "href")?.let { art = it }; skip(p) }
            else -> skip(p)
        }
    }
    if (audio.isBlank()) return null
    val key = guid.trim().ifBlank { audio }
    return Episode(
        id = sha1("$feedUrl|$key").take(20),
        feedUrl = feedUrl,
        podcastTitle = "",
        title = title.trim().ifBlank { "Utan tittel" },
        pubMs = pub,
        durationSec = dur,
        audioUrl = audio.trim(),
        description = htmlToText(desc.ifBlank { summary }).take(4000),
        artUrl = art.trim(),
    )
}

/** Les all tekst fram til sluttaggen, også om det ligg taggar inni. */
private fun readText(p: XmlPullParser): String {
    val depth = p.depth
    val sb = StringBuilder()
    while (true) {
        val ev = p.next()
        if (ev == XmlPullParser.END_DOCUMENT) break
        if (ev == XmlPullParser.END_TAG && p.depth == depth) break
        if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT || ev == XmlPullParser.ENTITY_REF) {
            p.text?.let { sb.append(it) }
        }
    }
    return sb.toString().trim()
}

private fun skip(p: XmlPullParser) {
    if (p.eventType != XmlPullParser.START_TAG) return
    val depth = p.depth
    while (true) {
        val ev = p.next()
        if (ev == XmlPullParser.END_DOCUMENT) break
        if (ev == XmlPullParser.END_TAG && p.depth == depth) break
    }
}

private fun readImageUrl(p: XmlPullParser): String {
    var url = ""
    val depth = p.depth
    while (true) {
        val ev = p.next()
        if (ev == XmlPullParser.END_DOCUMENT) break
        if (ev == XmlPullParser.END_TAG && p.depth == depth) break
        if (ev == XmlPullParser.START_TAG) {
            if (p.name.lowercase() == "url") url = readText(p) else skip(p)
        }
    }
    return url
}

private val dateFormats = listOf(
    "EEE, d MMM yyyy HH:mm:ss Z",
    "EEE, d MMM yyyy HH:mm:ss zzz",
    "EEE, d MMM yyyy HH:mm Z",
    "d MMM yyyy HH:mm:ss Z",
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
)

fun parseDate(s: String): Long {
    val t = s.trim()
    if (t.isEmpty()) return 0
    for (f in dateFormats) {
        try {
            val df = SimpleDateFormat(f, Locale.US)
            df.isLenient = true
            return df.parse(t)?.time ?: continue
        } catch (_: Exception) {
        }
    }
    return 0
}

/** «1:02:03», «62:03» eller berre sekund. */
fun parseDuration(s: String): Int {
    val t = s.trim()
    if (t.isEmpty()) return 0
    val parts = t.split(":")
    return try {
        when (parts.size) {
            1 -> t.toDouble().toInt()
            2 -> parts[0].toInt() * 60 + parts[1].toDouble().toInt()
            else -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toDouble().toInt()
        }
    } catch (_: Exception) {
        0
    }
}

private val entities = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
    "rdquo" to "”", "ldquo" to "“",
)

/** Gjer om HTML i episodeomtalen til vanleg tekst. */
fun htmlToText(html: String): String {
    var s = html
    s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
    s = s.replace(Regex("(?i)</(p|div|li|h[1-6])>"), "\n")
    s = s.replace(Regex("(?i)<li[^>]*>"), "• ")
    s = s.replace(Regex("<[^>]+>"), "")
    s = s.replace(Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")) { m ->
        val e = m.groupValues[1]
        try {
            when {
                e.startsWith("#x") || e.startsWith("#X") -> String(Character.toChars(e.substring(2).toInt(16)))
                e.startsWith("#") -> String(Character.toChars(e.substring(1).toInt()))
                else -> entities[e] ?: m.value
            }
        } catch (_: Exception) {
            m.value
        }
    }
    s = s.replace(Regex("[ \\t]+"), " ")
    s = s.replace(Regex(" ?\\n ?"), "\n")
    s = s.replace(Regex("\\n{3,}"), "\n\n")
    return s.trim()
}

fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
