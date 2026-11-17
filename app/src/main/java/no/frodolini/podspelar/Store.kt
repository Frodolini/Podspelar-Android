package no.frodolini.podspelar

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class PodApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

/** Kor langt ein er komen i ein episode. */
data class Pos(val posMs: Long, val durMs: Long, val done: Boolean)

/**
 * Alt appen hugsar: favorittar, episodelister, framdrift og nedlastingar.
 * Ligg som små JSON-filer i appen si eiga mappe. Tilstanden blir berre endra på hovudtråden.
 */
object Store {
    private lateinit var dir: File
    lateinit var downloadDir: File
        private set
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val favorites = mutableStateListOf<Podcast>()
    val episodes = mutableStateMapOf<String, List<Episode>>()   // feed-adresse -> episodar
    val positions = mutableStateMapOf<String, Pos>()            // episode-id -> framdrift
    val downloads = mutableStateMapOf<String, Episode>()        // ferdig nedlasta
    val progress = mutableStateMapOf<String, Float>()           // nedlasting i gang, 0..1
    val playlist = mutableStateListOf<Episode>()                // spelelista, i den rekkjefølgja ho skal spelast
    var last by mutableStateOf<Episode?>(null)                  // sist spela episode
    var speed by mutableStateOf(1f)
    var askedNotifications by mutableStateOf(false)
    var lastRefreshMs = 0L

    fun init(ctx: Context) {
        dir = ctx.filesDir
        downloadDir = (ctx.getExternalFilesDir("nedlasta") ?: File(ctx.filesDir, "nedlasta")).apply { mkdirs() }

        readArray("favorittar.json")?.let { a ->
            for (i in 0 until a.length()) favorites += podcastFromJson(a.getJSONObject(i))
        }
        readObject("framdrift.json")?.let { o ->
            for (k in o.keys()) {
                val p = o.getJSONObject(k)
                positions[k] = Pos(p.optLong("p"), p.optLong("d"), p.optBoolean("f"))
            }
        }
        readArray("nedlasta.json")?.let { a ->
            for (i in 0 until a.length()) {
                val e = episodeFromJson(a.getJSONObject(i))
                if (fileFor(e).exists()) downloads[e.id] = e
            }
        }
        readArray("speleliste.json")?.let { a ->
            for (i in 0 until a.length()) playlist += episodeFromJson(a.getJSONObject(i))
        }
        readObject("innstillingar.json")?.let { o ->
            speed = o.optDouble("fart", 1.0).toFloat()
            askedNotifications = o.optBoolean("spurtVarsel")
            lastRefreshMs = o.optLong("sistOppdatert")
            o.optJSONObject("sist")?.let { last = episodeFromJson(it) }
        }
        // Rydd bort halvferdige nedlastingar frå førre gong
        downloadDir.listFiles()?.filter { it.name.endsWith(".del") }?.forEach { it.delete() }

        // Episodelistene kan vera store, så dei blir lesne i bakgrunnen.
        val feeds = favorites.map { it.feedUrl }
        io.execute {
            for (f in feeds) {
                val list = runCatching {
                    val a = JSONArray(File(dir, feedFile(f)).readText())
                    List(a.length()) { episodeFromJson(a.getJSONObject(it)) }
                }.getOrNull() ?: continue
                main.post { if (episodes[f] == null) episodes[f] = list }
            }
        }
    }

    // ---- Favorittar ----

    fun isFavorite(feedUrl: String) = favorites.any { it.feedUrl == feedUrl }

    fun toggleFavorite(p: Podcast) {
        val i = favorites.indexOfFirst { it.feedUrl == p.feedUrl }
        if (i >= 0) {
            favorites.removeAt(i)
            val f = File(dir, feedFile(p.feedUrl))
            io.execute { f.delete() }
        } else {
            favorites.add(0, p)
            episodes[p.feedUrl]?.let { saveEpisodes(p.feedUrl, it) }
        }
        saveFavorites()
    }

    /** Ny episodeliste henta frå nettet. */
    fun setFeed(feed: Feed) {
        val url = feed.podcast.feedUrl
        episodes[url] = feed.episodes
        val i = favorites.indexOfFirst { it.feedUrl == url }
        if (i >= 0) {
            // Oppdater tittel og omslag om podden har endra dei
            val old = favorites[i]
            val fresh = old.copy(
                title = feed.podcast.title.ifBlank { old.title },
                author = feed.podcast.author.ifBlank { old.author },
                artUrl = old.artUrl.ifBlank { feed.podcast.artUrl },
            )
            if (fresh != old) { favorites[i] = fresh; saveFavorites() }
            saveEpisodes(url, feed.episodes)
        }
    }

    private fun saveFavorites() {
        val a = JSONArray()
        favorites.forEach { a.put(podcastToJson(it)) }
        write("favorittar.json", a.toString())
    }

    private fun saveEpisodes(feedUrl: String, list: List<Episode>) {
        val name = feedFile(feedUrl)
        io.execute {
            val a = JSONArray()
            list.forEach { a.put(episodeToJson(it)) }
            runCatching { File(dir, name).writeText(a.toString()) }
        }
    }

    private fun feedFile(feedUrl: String) = "feed_" + sha1(feedUrl).take(16) + ".json"

    // ---- Framdrift ----

    fun setPos(id: String, posMs: Long, durMs: Long, done: Boolean) {
        val old = positions[id]
        val dur = if (durMs > 0) durMs else old?.durMs ?: 0
        positions[id] = Pos(posMs.coerceAtLeast(0), dur, done)
        val o = JSONObject()
        for ((k, v) in positions) o.put(k, JSONObject().put("p", v.posMs).put("d", v.durMs).put("f", v.done))
        write("framdrift.json", o.toString())
    }

    // ---- Innstillingar ----

    fun rememberLast(e: Episode) { last = e; saveSettings() }
    fun rememberSpeed(s: Float) { speed = s; saveSettings() }
    fun rememberAsked() { askedNotifications = true; saveSettings() }
    fun rememberRefresh() { lastRefreshMs = System.currentTimeMillis(); saveSettings() }

    private fun saveSettings() {
        val o = JSONObject().put("fart", speed.toDouble()).put("spurtVarsel", askedNotifications)
            .put("sistOppdatert", lastRefreshMs)
        last?.let { o.put("sist", episodeToJson(it)) }
        write("innstillingar.json", o.toString())
    }

    // ---- Nedlastingar ----

    fun fileFor(e: Episode): File {
        val ext = e.audioUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
        val ok = if (ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "mp4")) ext else "mp3"
        return File(downloadDir, "${e.id}.$ok")
    }

    fun isDownloaded(e: Episode) = downloads.containsKey(e.id)

    fun addDownload(e: Episode) {
        progress.remove(e.id)
        downloads[e.id] = e
        saveDownloads()
    }

    fun deleteDownload(e: Episode) {
        downloads.remove(e.id)
        val f = fileFor(e)
        io.execute { f.delete() }
        saveDownloads()
    }

    private fun saveDownloads() {
        val a = JSONArray()
        downloads.values.forEach { a.put(episodeToJson(it)) }
        write("nedlasta.json", a.toString())
    }

    // ---- Spelelista ----

    fun inPlaylist(id: String) = playlist.any { it.id == id }

    /** Legg episoden sist i spelelista, eller tek han ut om han alt ligg der. */
    fun togglePlaylist(e: Episode) {
        val i = playlist.indexOfFirst { it.id == e.id }
        if (i >= 0) playlist.removeAt(i) else playlist.add(e)
        savePlaylist()
    }

    fun removeFromPlaylist(id: String) {
        if (playlist.removeAll { it.id == id }) savePlaylist()
    }

    /** Flyttar ein episode eitt hakk opp (-1) eller ned (+1). */
    fun movePlaylist(index: Int, delta: Int) {
        val to = index + delta
        if (index !in playlist.indices || to !in playlist.indices) return
        playlist.add(to, playlist.removeAt(index))
        savePlaylist()
    }

    fun clearPlaylist() {
        playlist.clear()
        savePlaylist()
    }

    /**
     * Ein episode er spela ferdig. Låg han i spelelista, blir han teken ut,
     * og den som stod etter han blir returnert slik at han kan spelast vidare.
     */
    fun nextAfterFinished(id: String): Episode? {
        val i = playlist.indexOfFirst { it.id == id }
        if (i < 0) return null
        playlist.removeAt(i)
        savePlaylist()
        return playlist.getOrNull(i)
    }

    private fun savePlaylist() {
        val a = JSONArray()
        playlist.forEach { a.put(episodeToJson(it)) }
        write("speleliste.json", a.toString())
    }

    // ---- Filer og JSON ----

    private fun write(name: String, text: String) {
        io.execute {
            runCatching {
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(text)
                tmp.renameTo(File(dir, name))
            }
        }
    }

    private fun readObject(name: String) = runCatching { JSONObject(File(dir, name).readText()) }.getOrNull()
    private fun readArray(name: String) = runCatching { JSONArray(File(dir, name).readText()) }.getOrNull()
}

fun podcastToJson(p: Podcast): JSONObject =
    JSONObject().put("feed", p.feedUrl).put("title", p.title).put("author", p.author).put("art", p.artUrl)

fun podcastFromJson(o: JSONObject) =
    Podcast(o.optString("feed"), o.optString("title"), o.optString("author"), o.optString("art"))

fun episodeToJson(e: Episode): JSONObject = JSONObject()
    .put("id", e.id).put("feed", e.feedUrl).put("pod", e.podcastTitle).put("title", e.title)
    .put("pub", e.pubMs).put("dur", e.durationSec).put("audio", e.audioUrl)
    .put("desc", e.description).put("art", e.artUrl)

fun episodeFromJson(o: JSONObject) = Episode(
    id = o.optString("id"), feedUrl = o.optString("feed"), podcastTitle = o.optString("pod"),
    title = o.optString("title"), pubMs = o.optLong("pub"), durationSec = o.optInt("dur"),
    audioUrl = o.optString("audio"), description = o.optString("desc"), artUrl = o.optString("art"),
)
