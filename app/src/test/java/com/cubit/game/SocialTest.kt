package com.cubit.game

import org.junit.Assert.assertEquals
import org.junit.Test

class SocialTest {
    @Test fun quickMessagesKeepTheirIndexes() {
        // The server checks indexes against this count (quickMessages in server/social.go); sent ones must keep meaning.
        assertEquals(12, QUICK_MESSAGES.size)
        assertEquals("GG!", QUICK_MESSAGES[0])
        assertEquals("Rematch?", QUICK_MESSAGES[3])
    }

    @Test fun timeAgoReadsNaturally() {
        assertEquals("just now", timeAgo(1000, 1030))
        assertEquals("5 min ago", timeAgo(1000, 1000 + 5 * 60))
        assertEquals("3 h ago", timeAgo(0, 3 * 3600 + 59))
        assertEquals("yesterday", timeAgo(0, 30 * 3600))
        assertEquals("4 days ago", timeAgo(0, 4 * 86_400))
        assertEquals("just now", timeAgo(2000, 1000)) // a clock a little ahead never says "-5 min"
    }

    @Test fun feedLines() {
        assertEquals("cleared Level 12 in 0:37 · 655 pts", feedText(FeedItem("C", "Simon", "run", "Level 12", 655, 37_400, 0, 0)))
        assertEquals("cleared Daily 2026-11-26 in 1:42 · 1,840 pts", feedText(FeedItem("C", "Mira", "run", "Daily 2026-11-26", 1840, 102_000, 0, 0)))
        assertEquals("reached LV 7", feedText(FeedItem("C", "Jonas", "level", "", 0, 0, 7, 0)))
    }

    @Test fun leagueTimeLeft() {
        assertEquals("3d 4h", timeLeft(3 * 86_400 + 4 * 3600 + 10, 0))
        assertEquals("5h 12m", timeLeft(5 * 3600 + 12 * 60, 0))
        assertEquals("12m", timeLeft(12 * 60 + 30, 0))
        assertEquals("0m", timeLeft(0, 100))
    }
}
