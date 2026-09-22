package de.heute.nachrichten

import de.heute.nachrichten.data.ZdfClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline parser tests against captured fixtures — no network, runs in CI.
 * Locks in the port of zdf_heute_url.py, including the documented "don't bleed a clip teaser's
 * title across to a later EPISODE node" bug.
 */
class ZdfClientTest {

    private fun resource(name: String): String =
        javaClass.getResource(name)!!.readText()

    private val html by lazy { resource("/page.html") }
    private val ptmd by lazy { JSONObject(resource("/ptmd.json")) }

    @Test
    fun extractsApiToken() {
        assertEquals("TESTtoken123", ZdfClient.extractApiToken(html))
    }

    @Test
    fun findsEpisodesNewestFirstSkippingClip() {
        val episodes = ZdfClient.findEpisodes(html)
        assertEquals(2, episodes.size)

        val first = episodes[0]
        // NOT the clip teaser that precedes the episodes
        assertEquals("heute-19-uhr-200", first.canonical)
        assertEquals("heute 19:00 Uhr", first.title)
        assertEquals("2026-06-16T19:00:00.000+02:00", first.date)
        assertEquals("https://www.zdf.de/share/ep200", first.sharingUrl)

        assertEquals("heute-19-uhr-199", episodes[1].canonical)
    }

    @Test
    fun picksDefaultVariantNotDgs() {
        val first = ZdfClient.findEpisodes(html).first()
        assertEquals("DEFAULT", first.vodMediaType)
        assertEquals("/tmd/2/{playerId}/vod/ptmd/mediathek/ep200/1", first.ptmdTemplate)
    }

    @Test
    fun honorsDgsRequest() {
        val first = ZdfClient.findEpisodes(html, wantDgs = true).first()
        assertEquals("DGS", first.vodMediaType)
        assertEquals("/tmd/2/{playerId}/vod/ptmd/mediathek/ep200/dgs", first.ptmdTemplate)
    }

    @Test
    fun honorsLimit() {
        assertEquals(1, ZdfClient.findEpisodes(html, limit = 1).size)
    }

    @Test
    fun collectStreamsKeepsMainGermanAudioOnly() {
        val streams = ZdfClient.collectStreams(ptmd)
        // the audio-description ("ad") track is dropped
        assertTrue(streams.none { it.klass == "ad" })
        assertTrue(streams.all { it.klass == "main" })
    }

    @Test
    fun picksBestProgressiveMp4() {
        val streams = ZdfClient.collectStreams(ptmd)
        // mp4 preferred over webm; fhd outranks high
        assertEquals("https://zdf.example/fhd.mp4", ZdfClient.pickBestProgressive(streams)?.uri)
    }

    @Test
    fun picksPreferredQualityWhenAvailable() {
        val streams = ZdfClient.collectStreams(ptmd)
        // hd is offered but ranks below fhd -- an explicit preference still wins over "best"
        assertEquals(
            "https://zdf.example/hd.mp4",
            ZdfClient.pickBestProgressive(streams, preferred = "hd")?.uri,
        )
    }

    @Test
    fun fallsBackToBestWhenPreferredQualityUnavailable() {
        val streams = ZdfClient.collectStreams(ptmd)
        // "low" isn't offered for this fixture -- a tap must still resolve to something
        assertEquals(
            "https://zdf.example/fhd.mp4",
            ZdfClient.pickBestProgressive(streams, preferred = "low")?.uri,
        )
    }

    @Test
    fun picksHlsAutoMaster() {
        val streams = ZdfClient.collectStreams(ptmd)
        assertEquals("https://zdf.example/auto.m3u8", ZdfClient.pickBestHls(streams)?.uri)
    }

    @Test
    fun emptyPtmdYieldsNoStreams() {
        val streams = ZdfClient.collectStreams(JSONObject("{}"))
        assertTrue(streams.isEmpty())
        assertNull(ZdfClient.pickBestProgressive(streams))
        assertFalse(streams.isNotEmpty())
    }
}
