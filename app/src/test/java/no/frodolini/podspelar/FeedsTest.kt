package no.frodolini.podspelar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeedsTest {
    private val sample = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/">
  <channel>
    <title>Testpodden</title>
    <image><url>https://example.org/gammalt.jpg</url><title>Biletetittel</title></image>
    <itunes:author>Frode</itunes:author>
    <itunes:image href="https://example.org/omslag.jpg"/>
    <item>
      <title>Eldst</title>
      <guid isPermaLink="false">a-1</guid>
      <pubDate>Mon, 02 Nov 2026 06:00:00 +0000</pubDate>
      <itunes:duration>1:02:03</itunes:duration>
      <description><![CDATA[<p>Fyrste&nbsp;avsnitt &amp; meir.</p><p>Andre avsnitt.</p>]]></description>
      <enclosure url="https://example.org/a1.mp3?x=1" length="123" type="audio/mpeg"/>
    </item>
    <item>
      <title>Nyast &amp; best</title>
      <guid>a-2</guid>
      <pubDate>Thu, 12 Nov 2026 23:02:01 GMT</pubDate>
      <itunes:duration>754</itunes:duration>
      <itunes:image href="https://example.org/episode.jpg"/>
      <description>Rein tekst&nbsp;her</description>
      <enclosure url="https://example.org/a2.m4a" type="audio/x-m4a"/>
    </item>
    <item><title>Utan lyd</title><guid>a-3</guid></item>
  </channel>
</rss>"""

    @Test
    fun parsesFeed() {
        val feed = parseFeed(sample.byteInputStream(), "https://example.org/feed")
        assertEquals("Testpodden", feed.podcast.title)
        assertEquals("Frode", feed.podcast.author)
        assertEquals("https://example.org/omslag.jpg", feed.podcast.artUrl)
        assertEquals(2, feed.episodes.size)            // episoden utan lyd fell bort
        val (new, old) = feed.episodes                 // nyaste først
        assertEquals("Nyast & best", new.title)
        assertEquals(754, new.durationSec)
        assertEquals("https://example.org/episode.jpg", new.artUrl)
        assertEquals("Testpodden", new.podcastTitle)
        assertEquals("Eldst", old.title)
        assertEquals(3723, old.durationSec)
        assertEquals("https://example.org/omslag.jpg", old.artUrl)
        assertEquals("Fyrste avsnitt & meir.\nAndre avsnitt.", old.description)
        assertTrue(new.pubMs > old.pubMs && old.pubMs > 0)
        assertTrue(new.id != old.id && new.id.length == 20)
    }

    @Test
    fun parsesDurationsAndDates() {
        assertEquals(62 * 60 + 3, parseDuration("62:03"))
        assertEquals(0, parseDuration("tull"))
        assertEquals(1794525600000L, parseDate("Thu, 12 Nov 2026 23:20:00 +0000"))
        assertEquals(0L, parseDate(""))
    }

    @Test
    fun parsesSearch() {
        val json = """{"resultCount":2,"results":[
            {"collectionName":"Hard Fork","artistName":"NYT","feedUrl":"https://f/1","artworkUrl100":"https://a/100.jpg","artworkUrl600":"https://a/600.jpg"},
            {"collectionName":"Utan feed","artistName":"X"}]}"""
        val r = parseSearch(json)
        assertEquals(1, r.size)
        assertEquals(Podcast("https://f/1", "Hard Fork", "NYT", "https://a/600.jpg"), r[0])
    }

    /** Køyrer mot ekte feedar om mappa er oppgitt: -Dfeeds=/sti/til/mappe med .xml-filer */
    @Test
    fun parsesRealFeeds() {
        val dir = System.getProperty("feeds")?.let(::File) ?: return
        for (f in dir.listFiles { x -> x.extension == "xml" }.orEmpty()) {
            val feed = f.inputStream().use { parseFeed(it, f.name) }
            val e = feed.episodes.first()
            println("${f.name}: «${feed.podcast.title}» av ${feed.podcast.author}, ${feed.episodes.size} episodar, " +
                "utan dato ${feed.episodes.count { it.pubMs == 0L }}, utan lengd ${feed.episodes.count { it.durationSec == 0 }}, " +
                "omslag ${feed.podcast.artUrl.take(40)}\n   nyast: ${e.title} | ${java.util.Date(e.pubMs)} | ${e.durationSec}s | ${e.description.take(80).replace('\n', ' ')}")
            assertTrue(feed.podcast.title.isNotBlank() && feed.episodes.isNotEmpty())
        }
    }
}
