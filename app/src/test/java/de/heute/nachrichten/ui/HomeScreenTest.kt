package de.heute.nachrichten.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.ZoneId

class HomeScreenTest {

    @Test
    fun isRecentBroadcast_episodeFrom30MinutesAgo_returnsTrue() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        val isoDate = "2026-06-30T19:30:00+02:00"
        assertTrue(isRecentBroadcast(isoDate, now))
    }

    @Test
    fun isRecentBroadcast_episodeFrom6HoursAgo_returnsTrue() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        val isoDate = "2026-06-30T14:00:00+02:00"
        assertTrue(isRecentBroadcast(isoDate, now))
    }

    @Test
    fun isRecentBroadcast_episodeFrom12HoursAgo_returnsTrue() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        val isoDate = "2026-06-30T08:00:00+02:00"
        assertTrue(isRecentBroadcast(isoDate, now))
    }

    @Test
    fun isRecentBroadcast_episodeFrom12HoursAnd1MinuteAgo_returnsFalse() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        val isoDate = "2026-06-30T07:59:00+02:00"
        assertFalse(isRecentBroadcast(isoDate, now))
    }

    @Test
    fun isRecentBroadcast_episodeFrom24HoursAgo_returnsFalse() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        val isoDate = "2026-06-29T20:00:00+02:00"
        assertFalse(isRecentBroadcast(isoDate, now))
    }

    @Test
    fun isRecentBroadcast_malformedDate_returnsFalse() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        val isoDate = "not-a-date"
        assertFalse(isRecentBroadcast(isoDate, now))
    }

    @Test
    fun isRecentBroadcast_emptyDate_returnsFalse() {
        val now = LocalDateTime.of(2026, 6, 30, 20, 0, 0)
        assertFalse(isRecentBroadcast("", now))
    }
}
