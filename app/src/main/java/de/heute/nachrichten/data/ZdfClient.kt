package de.heute.nachrichten.data

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Raised for any recoverable failure with a user-facing message (mirror of the script's ExtractError). */
class ZdfException(message: String) : RuntimeException(message)

/** One broadcast on a ZDF magazine page. */
data class Episode(
    val title: String,
    val canonical: String,
    val sharingUrl: String?,
    val ptmdTemplate: String,
    val vodMediaType: String,
    val date: String? = null,
)

/** One playable stream variant out of a PTMD response. */
data class Stream(
    val mimeType: String?,
    val quality: String?,
    val klass: String?,
    val language: String?,
    val uri: String?,
)

/**
 * Kotlin port of `zdf_heute_url.py`. Resolves a ZDF Mediathek magazine page to playable
 * stream URLs using only page-embedded data and the public PTMD API — no headless browser.
 *
 * Stdlib only: [HttpURLConnection] for HTTP, bundled `org.json` for the PTMD response, and
 * Kotlin regex over the server-rendered (escaped) JSON in the page HTML. The regexes are raw
 * ports of the Python patterns: `\\?` matches an optional literal backslash, exactly as the
 * Python raw strings do.
 */
object ZdfClient {

    const val DEFAULT_PAGE = "https://www.zdf.de/magazine/heute-19-uhr-102"
    const val DEFAULT_PLAYER_ID = "android_native_5" // returns the widest set of formats
    private const val API_BASE = "https://api.zdf.de"

    /** Progressive qualities to offer in the format-selection UI, best first. */
    val PROGRESSIVE_QUALITIES = listOf("fhd", "hd", "veryhigh", "high", "low")
    private const val UA =
        "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"

    // progressive video quality, best first
    private val QUALITY_ORDER =
        listOf("uhd", "fhd", "hd", "veryhigh", "high", "med", "low", "auto")

    private val apiTokenRegex = Regex("""apiToken\\?":\\?"([A-Za-z0-9]+)""")

    // Every node on the page (real episode or clip teaser) opens with this header.
    private val episodeRegex = Regex(
        """\\"id\\":\\"([0-9a-f-]{36})\\",\\"canonical\\":\\"([^\\]+)\\",""" +
            """\\"title\\":\\"([^\\]+)\\""""
    )
    // A real broadcast (as opposed to a clip teaser) carries a populated episodeInfo;
    // teasers have "seasonNumber":null,"episodeNumber":null. This marker can sit behind
    // other closed sub-objects within the node, so the block can't be bounded by "next }"
    // (that's why episodeRegex above no longer tries to reach a node-closing marker itself —
    // blocks are bounded by "next header's start" instead, in findEpisodes).
    private val episodeMarkerRegex = Regex(
        """\\"episodeInfo\\":\{\\"seasonNumber\\":\d+,\\"episodeNumber\\":\d+"""
    )
    private val sharingUrlRegex = Regex("""sharingUrl\\":\\"([^\\]+)""")
    private val ptmdTemplateRegex = Regex("""ptmdTemplate\\":\\"([^\\]+)""")
    private val vodMediaTypeRegex = Regex("""vodMediaType\\":\\"([^\\]+)""")
    private val editorialDateRegex = Regex("""editorialDate\\":\\"([^\\]+)""")

    // --- HTTP -------------------------------------------------------------

    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw ZdfException("HTTP $code fetching $urlStr")
            return conn.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            throw ZdfException("Network error fetching $urlStr: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    fun fetchPage(url: String): String = String(httpGet(url), Charsets.UTF_8)

    // --- Page scraping ----------------------------------------------------

    /** Pull the short-lived videoToken.apiToken out of the embedded (escaped) JSON. */
    fun extractApiToken(html: String): String =
        apiTokenRegex.find(html)?.groupValues?.get(1)
            ?: throw ZdfException(
                "Could not find apiToken in page (ZDF may have changed the layout). " +
                    "Page length: ${html.length} bytes."
            )

    /**
     * Locate the most recent real broadcast nodes (newest first, clip teasers skipped) and
     * return up to [limit] of them. Generalizes the Python `find_first_episode`. Picks the
     * DEFAULT media variant (or the first available if there is no DEFAULT). Candidates
     * without a populated episodeInfo (clip teasers) or without any playable media (e.g.
     * today's broadcast before it has aired) are skipped.
     */
    fun findEpisodes(html: String, limit: Int = 5, wantDgs: Boolean = false): List<Episode> {
        val matches = episodeRegex.findAll(html).toList()
        if (matches.isEmpty()) throw ZdfException("No id/canonical/title headers found on the page.")

        val wantedType = if (wantDgs) "DGS" else "DEFAULT"
        var markerHits = 0
        val episodes = mutableListOf<Episode>()
        for ((i, m) in matches.withIndex()) {
            if (episodes.size >= limit) break
            val start = m.range.first
            val end =
                if (i + 1 < matches.size) matches[i + 1].range.first
                else minOf(m.range.last + 1 + 12_000, html.length)
            val block = html.substring(start, end)

            if (!episodeMarkerRegex.containsMatchIn(block)) continue
            markerHits++

            // A media node looks like {"__typename":"VodMedia","ptmdTemplate":Y,…,"vodMediaType":X},
            // i.e. vodMediaType FOLLOWS ptmdTemplate inside the same node. Pair each ptmdTemplate
            // with the vodMediaType in its own node — read forward only up to the next `}` (node
            // boundary) so we never borrow a neighbouring node's type; fall back to preceding/UNKNOWN.
            val variants = ptmdTemplateRegex.findAll(block).map { pm ->
                val node = block.substring(pm.range.last + 1).substringBefore('}')
                val type = vodMediaTypeRegex.find(node)?.groupValues?.get(1)
                    ?: vodMediaTypeRegex.findAll(block.substring(0, pm.range.first))
                        .lastOrNull()?.groupValues?.get(1)
                    ?: "UNKNOWN"
                type to pm.groupValues[1]
            }.toList()
            if (variants.isEmpty()) continue

            val chosen = variants.firstOrNull { it.first == wantedType } ?: variants.first()
            episodes.add(
                Episode(
                    title = m.groupValues[3],
                    canonical = m.groupValues[2],
                    sharingUrl = sharingUrlRegex.find(block)?.groupValues?.get(1),
                    ptmdTemplate = chosen.second,
                    vodMediaType = chosen.first,
                    date = editorialDateRegex.find(block)?.groupValues?.get(1),
                )
            )
        }
        if (episodes.isEmpty()) {
            throw ZdfException(
                "No playable episode found on the page (${matches.size} node header(s) found, " +
                    "$markerHits matched the episode marker, none had a ptmdTemplate)."
            )
        }
        return episodes
    }

    // --- PTMD resolution --------------------------------------------------

    fun fetchPtmd(ptmdTemplate: String, token: String, playerId: String = DEFAULT_PLAYER_ID): JSONObject {
        val url = API_BASE + ptmdTemplate.replace("{playerId}", playerId)
        val raw = httpGet(url, mapOf("Api-Auth" to "Bearer $token"))
        return try {
            JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw ZdfException("PTMD response was not valid JSON: ${e.message}")
        }
    }

    /** Flatten the PTMD into streams, keeping the main German audio track (skip audio-description). */
    fun collectStreams(ptmd: JSONObject): List<Stream> {
        val all = mutableListOf<Stream>()
        val priorityList = ptmd.optJSONArray("priorityList") ?: return emptyList()
        for (i in 0 until priorityList.length()) {
            val pl = priorityList.optJSONObject(i) ?: continue
            val formitaeten = pl.optJSONArray("formitaeten") ?: continue
            for (j in 0 until formitaeten.length()) {
                val fmt = formitaeten.optJSONObject(j) ?: continue
                val mime = fmt.optStringOrNull("mimeType")
                val qualities = fmt.optJSONArray("qualities") ?: continue
                for (k in 0 until qualities.length()) {
                    val q = qualities.optJSONObject(k) ?: continue
                    val quality = q.optStringOrNull("quality")
                    val tracks = q.optJSONObject("audio")?.optJSONArray("tracks") ?: continue
                    for (t in 0 until tracks.length()) {
                        val track = tracks.optJSONObject(t) ?: continue
                        all.add(
                            Stream(
                                mimeType = mime,
                                quality = quality,
                                klass = track.optStringOrNull("class"),
                                language = track.optStringOrNull("language"),
                                uri = track.optStringOrNull("uri"),
                            )
                        )
                    }
                }
            }
        }
        val main = all.filter { it.klass == "main" && (it.language == null || it.language == "deu") }
        return main.ifEmpty { all }
    }

    private fun qualityRank(quality: String?): Int =
        QUALITY_ORDER.indexOf(quality).let { if (it >= 0) it else QUALITY_ORDER.size }

    /**
     * Best progressive file: prefer mp4 over webm, then best quality. If [preferred] is given
     * and a stream of that exact quality exists, it wins regardless of rank, so a chosen
     * quality is honored when available; otherwise falls back to the best-first pick so a tap
     * never fails to open just because that quality isn't offered for this episode.
     */
    fun pickBestProgressive(streams: List<Stream>, preferred: String? = null): Stream? {
        val progressive = streams.filter { it.mimeType == "video/mp4" || it.mimeType == "video/webm" }
        if (preferred != null) {
            progressive.filter { it.quality == preferred }
                .minByOrNull { it.mimeType != "video/mp4" }
                ?.let { return it }
        }
        return progressive.sortedWith(compareBy({ it.mimeType != "video/mp4" }, { qualityRank(it.quality) }))
            .firstOrNull()
    }

    /** Best HLS: the 'auto' master playlist (full adaptive set) first, then best quality. */
    fun pickBestHls(streams: List<Stream>): Stream? =
        streams.filter { it.mimeType == "application/x-mpegURL" }
            .sortedWith(compareBy({ it.quality != "auto" }, { qualityRank(it.quality) }))
            .firstOrNull()
}

/** org.json's optString returns "" for a missing key; we want null so callers can default cleanly. */
private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name) || !has(name)) null else optString(name)
