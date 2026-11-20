// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import app.sideviewvr.SubtitlePicker.MODE_ALWAYS
import app.sideviewvr.SubtitlePicker.MODE_SMART
import app.sideviewvr.SubtitlePicker.Track
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SubtitlePickerTest {
    private fun a(lang: String?, sel: Boolean = true) = Track(1, "audio", lang = lang, selected = sel)
    private fun s(id: Int, lang: String?, title: String? = null, forced: Boolean = false, def: Boolean = false,
                  hi: Boolean = false, codec: String = "subrip", ext: String? = null) =
        Track(id, "sub", lang, title, codec, forced, def, hi, ext != null, ext)

    @Test fun englishAudioGetsForcedOnly() {
        assertEquals(2, SubtitlePicker.pick(listOf(a("eng"), s(1, "eng"), s(2, "eng", "Forced"), s(3, "spa")), MODE_SMART))
        assertEquals(0, SubtitlePicker.pick(listOf(a("eng"), s(1, "eng", def = true), s(3, "spa")), MODE_SMART))
    }
    @Test fun foreignAudioGetsFullEnglishPlainBeforeSdh() {
        assertEquals(3, SubtitlePicker.pick(listOf(a("ita"), s(1, "eng", "SDH"), s(2, "eng", "Forced"), s(3, "eng", "English"), s(4, "ita")), MODE_SMART))
        assertEquals(1, SubtitlePicker.pick(listOf(a("jpn"), s(1, "eng", "English (SDH)"), s(2, "spa")), MODE_SMART))
    }
    @Test fun neverCommentaryOrForeign() {
        assertEquals(0, SubtitlePicker.pick(listOf(a("kor"), s(1, "eng", "Commentary"), s(2, "spa")), MODE_SMART))
    }
    @Test fun textBeatsImage() {
        assertEquals(2, SubtitlePicker.pick(listOf(a("fre"), s(1, "eng", codec = "hdmv_pgs_subtitle"), s(2, "eng")), MODE_SMART))
    }
    @Test fun externalFileNames() {
        assertEquals(1, SubtitlePicker.pick(listOf(a("eng"), s(1, null, ext = "It (2017).en.forced.srt")), MODE_SMART))
        assertEquals(0, SubtitlePicker.pick(listOf(a("eng"), s(1, null, ext = "It (2017).en.srt")), MODE_SMART))
        assertEquals(1, SubtitlePicker.pick(listOf(a("spa"), s(1, null, ext = "It (2017).srt")), MODE_SMART, "It (2017).mkv"))
        assertEquals(0, SubtitlePicker.pick(listOf(a("spa"), s(1, null, ext = "Movie.es.srt")), MODE_SMART))
        assertEquals(2, SubtitlePicker.pick(listOf(a("spa"), s(1, null, ext = "Movie.sdh.en.srt"), s(2, null, ext = "Movie.English.srt")), MODE_ALWAYS))
    }
    @Test fun untaggedAudio() {
        // Untagged audio is English unless the file name says otherwise.
        assertEquals(0, SubtitlePicker.pick(listOf(a("und"), s(1, "eng", def = true)), MODE_SMART, "Batman.Begins.2005.mkv"))
        assertEquals(1, SubtitlePicker.pick(listOf(a(null), s(1, "eng")), MODE_SMART, "Apocalypto (2006) AC3 5.1 MYN 1080p.mkv"))
        assertEquals(0, SubtitlePicker.pick(listOf(a(null), s(1, "eng")), MODE_SMART, "Movie ITA ENG.mkv"))
    }
    @Test fun dualAudioSwitchesToEnglish() {
        val t = listOf(Track(1, "audio", "ita", default = true, selected = true), Track(2, "audio", "eng"),
            s(1, "ita"), s(2, "eng", "English"), s(3, "eng", "Forced", forced = true))
        assertEquals(2, SubtitlePicker.pickAudio(t, MODE_SMART))
        assertEquals(3, SubtitlePicker.pick(t, MODE_SMART, null, 2))
    }
    @Test fun otherPreferredLanguage() {
        // Spanish speaker: English film → full Spanish subs; Spanish dub available → switch audio, forced only.
        val t = listOf(Track(1, "audio", "eng", default = true, selected = true), s(1, "eng"), s(2, "spa", "Latin American"), s(3, "spa", "Forzados", forced = true))
        assertEquals(2, SubtitlePicker.pick(t, MODE_SMART, null, null, "es"))
        val d = listOf(Track(1, "audio", "eng", default = true, selected = true), Track(2, "audio", "spa"), s(1, "spa", "Forced", forced = true), s(2, "spa"))
        assertEquals(2, SubtitlePicker.pickAudio(d, MODE_SMART, null, "es"))
        assertEquals(1, SubtitlePicker.pick(d, MODE_SMART, null, 2, "es"))
    }
    @Test fun englishWordsInNamesAreNotLanguages() {
        assertEquals(0, SubtitlePicker.pick(listOf(a(null), s(1, "eng")), MODE_SMART, "The.Cat.in.the.Hat.2003.1080p.mkv"))
        assertEquals(0, SubtitlePicker.pick(listOf(a(null), s(1, "eng")), MODE_SMART, "Ice.Age.2002.mkv"))
    }
    @Test fun sdhFlaggedForcedIsNotForced() {
        assertEquals(0, SubtitlePicker.pick(listOf(a("eng"), s(1, "eng", "English (SDH)", forced = true, def = true)), MODE_SMART))
    }

    /** Runs over a real library dump (tools: tracks.tsv) when SV_TRACKS is set; writes SV_REPORT. */
    @Test fun library() {
        val src = System.getenv("SV_TRACKS") ?: return
        val out = File(System.getenv("SV_REPORT") ?: "build/sub-report.tsv").printWriter()
        var file = ""; var tracks = ArrayList<Track>()
        fun flush() {
            if (file.isEmpty()) return
            val aid = SubtitlePicker.pickAudio(tracks, MODE_SMART, file)
            val audio = tracks.firstOrNull { it.type == "audio" && (if (aid != null) it.id == aid else it.selected) }
            val pick = SubtitlePicker.pick(tracks, MODE_SMART, file, aid)
            val st = tracks.firstOrNull { it.type == "sub" && it.id == pick }
            val desc = st?.let { "${it.lang}|${it.title}|${it.codec}|${if (SubtitlePicker.isForced(it)) "forced" else "full"}${if (SubtitlePicker.isSdh(it)) "+sdh" else ""}${if (it.external) "|ext:" + it.externalFilename else ""}" } ?: "none"
            val subs = tracks.filter { it.type == "sub" }.joinToString(";") { "${it.id}:${it.lang}:${it.title}:${if (it.forced) "F" else ""}${if (it.default) "D" else ""}${if (it.hearingImpaired) "H" else ""}${it.externalFilename ?: ""}" }
            out.println(listOf(SubtitlePicker.audioLanguage(audio, file), (if (aid != null) "switched:" else "") + audio?.lang, desc, file, subs).joinToString("\t"))
        }
        File(src).forEachLine { l ->
            val c = l.split('\t')
            if (c[0] == "F") { flush(); file = c[1]; tracks = ArrayList() }
            else tracks += Track(c[1].toInt(), c[2], c[3].ifEmpty { null }, c[4].ifEmpty { null }, c[5], c[6] == "1", c[7] == "1",
                c[8] == "1", c[9] == "1", c[10].ifEmpty { null }, c[11] == "1")
        }
        flush(); out.close()
    }
}
