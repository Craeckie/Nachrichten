package de.heute.nachrichten.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates the two-phase resolve, lazily:
 *  - [loadEpisodes] does ONE page fetch (cheap) and caches the short-lived player token.
 *  - [resolveStreamUrl] only hits the PTMD API when an episode is actually tapped.
 *
 * All work runs on [Dispatchers.IO]. The token (~2 days TTL) is never persisted; it lives only
 * for the session and is refreshed on demand if missing.
 */
class ZdfRepository {

    @Volatile
    private var apiToken: String? = null

    suspend fun loadEpisodes(
        pageUrl: String = ZdfClient.DEFAULT_PAGE,
        limit: Int = 5,
    ): List<Episode> = withContext(Dispatchers.IO) {
        val html = ZdfClient.fetchPage(pageUrl)
        apiToken = ZdfClient.extractApiToken(html)
        ZdfClient.findEpisodes(html, limit)
    }

    suspend fun resolveStreamUrl(
        episode: Episode,
        playerId: String = ZdfClient.DEFAULT_PLAYER_ID,
        preferredQuality: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val token = apiToken ?: ZdfClient.extractApiToken(ZdfClient.fetchPage(ZdfClient.DEFAULT_PAGE))
            .also { apiToken = it }
        val streams = ZdfClient.collectStreams(ZdfClient.fetchPtmd(episode.ptmdTemplate, token, playerId))
        if (streams.isEmpty()) throw ZdfException("PTMD contained no stream URLs.")
        val best = ZdfClient.pickBestProgressive(streams, preferredQuality)
            ?: ZdfClient.pickBestHls(streams)
            ?: throw ZdfException("No usable stream found.")
        best.uri ?: throw ZdfException("Selected stream has no URL.")
    }
}
