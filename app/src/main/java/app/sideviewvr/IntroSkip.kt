// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

/**
 * Finds intros (and recaps) to skip: from chapter names in the file, and from what the viewer
 * skipped by hand in earlier episodes of the same season (same folder).
 */
object IntroSkip {
    data class Chapter(val title: String, val time: Double)
    data class Range(val start: Double, val end: Double, val kind: String, val confident: Boolean)

    private val INTRO = Regex("""(?i)\b(intro|opening|op|title sequence|main titles|theme song|opening credits)\b""")
    private val RECAP = Regex("""(?i)\b(recap|previously)\b""")

    /** Intro/recap ranges from chapter titles (each runs to the next chapter). */
    fun fromChapters(chapters: List<Chapter>, duration: Double): List<Range> {
        val out = ArrayList<Range>()
        val sorted = chapters.sortedBy { it.time }
        for ((i, c) in sorted.withIndex()) {
            val kind = when {
                RECAP.containsMatchIn(c.title) -> "recap"
                INTRO.containsMatchIn(c.title) -> "intro"
                else -> continue
            }
            val end = sorted.getOrNull(i + 1)?.time ?: continue
            // Only near the start, and never most of the episode.
            if (c.time > 900 || end - c.time > 300 || (!duration.isNaN() && end - c.time > duration * 0.4)) continue
            if (end - c.time >= 5) out += Range(c.time, end, kind, confident = true)
        }
        return out
    }

    /** A forward jump the viewer made early in an episode that looks like skipping an intro. */
    fun looksLikeManualSkip(from: Double, to: Double): Boolean =
        from >= 0 && from <= 600 && to - from in 20.0..240.0

    /** Stored form for a learned range: "start,end,times seen". */
    fun encode(start: Double, end: Double, seen: Int) = "%.1f,%.1f,%d".format(java.util.Locale.ROOT, start, end, seen)

    fun decode(s: String?): Range? {
        val p = s?.split(',') ?: return null
        if (p.size < 3) return null
        val a = p[0].toDoubleOrNull() ?: return null
        val b = p[1].toDoubleOrNull() ?: return null
        val n = p[2].toIntOrNull() ?: return null
        // Learned from one episode: offer it; seen twice (within a few seconds): skip it automatically.
        return Range(a, b, "intro", confident = n >= 2)
    }

    /** Merges a new manual skip with the stored one for the season. */
    fun learn(stored: String?, from: Double, to: Double): String {
        val old = decode(stored)
        return if (old != null && kotlin.math.abs(old.start - from) < 8 && kotlin.math.abs(old.end - to) < 8) {
            val seen = (stored!!.split(',')[2].toIntOrNull() ?: 1) + 1
            encode((old.start + from) / 2, (old.end + to) / 2, seen)
        } else encode(from, to, 1)
    }

    /** The range the playback position is in (with a little slack), if any. */
    fun active(ranges: List<Range>, pos: Double): Range? =
        ranges.firstOrNull { pos >= it.start - 1 && pos < it.end - 3 }

    /** Episodes of one season share a folder. */
    fun seasonKey(ref: String): String = "intro:" + ref.substringBeforeLast('/')
}
