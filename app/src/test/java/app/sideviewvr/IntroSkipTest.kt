// SPDX-License-Identifier: GPL-3.0-or-later
package app.sideviewvr

import app.sideviewvr.IntroSkip.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroSkipTest {
    @Test fun introChapter() {
        val r = IntroSkip.fromChapters(listOf(Chapter("Prologue", 0.0), Chapter("Opening", 95.0), Chapter("Part A", 185.0)), 1420.0)
        assertEquals(1, r.size)
        assertEquals(95.0, r[0].start, 0.01); assertEquals(185.0, r[0].end, 0.01)
        assertTrue(r[0].confident)
    }

    @Test fun recapAndIntro() {
        val r = IntroSkip.fromChapters(listOf(Chapter("Recap", 0.0), Chapter("Intro", 60.0), Chapter("Episode", 120.0)), 2600.0)
        assertEquals(listOf("recap", "intro"), r.map { it.kind })
    }

    @Test fun ignoresLongOrLateChapters() {
        assertTrue(IntroSkip.fromChapters(listOf(Chapter("Intro", 0.0), Chapter("End", 1000.0)), 1200.0).isEmpty())
        assertTrue(IntroSkip.fromChapters(listOf(Chapter("Opening", 1500.0), Chapter("B", 1590.0)), 3000.0).isEmpty())
        assertTrue(IntroSkip.fromChapters(listOf(Chapter("Chapter 1", 0.0), Chapter("Chapter 2", 90.0)), 3000.0).isEmpty())
        // "op" as a word only, not inside other words
        assertTrue(IntroSkip.fromChapters(listOf(Chapter("Stop", 0.0), Chapter("Go", 60.0)), 3000.0).isEmpty())
    }

    @Test fun learnsFromManualSkips() {
        assertTrue(IntroSkip.looksLikeManualSkip(32.0, 112.0))
        assertFalse(IntroSkip.looksLikeManualSkip(32.0, 42.0))          // a 10 s nudge
        assertFalse(IntroSkip.looksLikeManualSkip(1500.0, 1600.0))      // late in the episode
        val first = IntroSkip.learn(null, 30.0, 110.0)
        assertFalse(IntroSkip.decode(first)!!.confident)
        val second = IntroSkip.learn(first, 33.0, 112.0)
        val r = IntroSkip.decode(second)!!
        assertTrue(r.confident)
        assertEquals(31.5, r.start, 0.01)
        // A different range starts over.
        assertFalse(IntroSkip.decode(IntroSkip.learn(second, 300.0, 380.0))!!.confident)
    }

    @Test fun activeRange() {
        val rs = listOf(IntroSkip.Range(95.0, 185.0, "intro", true))
        assertNotNull(IntroSkip.active(rs, 100.0))
        assertNull(IntroSkip.active(rs, 183.0))
        assertNull(IntroSkip.active(rs, 50.0))
    }

    @Test fun seasonKeyIsFolder() {
        assertEquals("intro:smb://nas.local/media/TV/Show/Season 01",
            IntroSkip.seasonKey("smb://nas.local/media/TV/Show/Season 01/Show - S01E02.mkv"))
    }
}
