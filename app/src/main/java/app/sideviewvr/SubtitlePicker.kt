// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

/**
 * Chooses audio and subtitle tracks for the user's preferred language. Pure logic (no
 * Android or mpv calls), so it can be tested against track lists from a real library.
 *
 * Smart mode ("pref" = the preferred language, English by default):
 * - If the default audio is in another language but a pref audio track exists, switch
 *   to it (many dual-audio files default to a dub); see [pickAudio].
 * - Audio not in pref → full pref subtitles (plain before SDH, never commentary).
 * - Audio in pref → only "forced" pref subtitles (foreign-language parts), else none.
 * - Audio language untagged → pref, unless the audio title or the file name names
 *   another language (e.g. "Hindi", "KOREAN", "MYN").
 */
object SubtitlePicker {
    const val MODE_OFF = 0
    const val MODE_SMART = 1
    const val MODE_ALWAYS = 2
    const val MODE_FILE = 3
    val modeNames = listOf("Off", "Smart", "Always subtitles", "File default")

    /** A language: ISO 639-1 code, then other codes and names used in tags and file names. */
    class Lang(val code: String, val name: String, vararg aliases: String) {
        val tags: Set<String> = setOf(code, name.lowercase()) + aliases
    }

    val LANGUAGES = listOf(
        Lang("en", "English", "eng"), Lang("es", "Spanish", "spa", "castilian", "latino", "espanol"),
        Lang("fr", "French", "fre", "fra", "francais"), Lang("de", "German", "ger", "deu", "deutsch"),
        Lang("it", "Italian", "ita", "italiano"), Lang("pt", "Portuguese", "por", "brazilian", "portugues"),
        Lang("nl", "Dutch", "dut", "nld"), Lang("sv", "Swedish", "swe"), Lang("no", "Norwegian", "nor", "nob", "nno", "nb"),
        Lang("da", "Danish", "dan"), Lang("fi", "Finnish", "fin"), Lang("is", "Icelandic", "ice", "isl"),
        Lang("pl", "Polish", "pol"), Lang("cs", "Czech", "cze", "ces"), Lang("sk", "Slovak", "slo", "slk"),
        Lang("hu", "Hungarian", "hun"), Lang("ro", "Romanian", "rum", "ron"), Lang("bg", "Bulgarian", "bul"),
        Lang("hr", "Croatian", "hrv"), Lang("sr", "Serbian", "srp"), Lang("sl", "Slovenian", "slv"),
        Lang("el", "Greek", "gre", "ell"), Lang("tr", "Turkish", "tur"), Lang("ru", "Russian", "rus"),
        Lang("uk", "Ukrainian", "ukr"), Lang("et", "Estonian", "est"), Lang("lv", "Latvian", "lav"), Lang("lt", "Lithuanian", "lit"),
        Lang("ar", "Arabic", "ara"), Lang("he", "Hebrew", "heb"), Lang("fa", "Persian", "per", "fas", "farsi"),
        Lang("hi", "Hindi", "hin"), Lang("bn", "Bengali", "ben"), Lang("pa", "Punjabi", "pan"), Lang("mr", "Marathi", "mar"),
        Lang("ur", "Urdu", "urd"), Lang("ta", "Tamil", "tam"), Lang("te", "Telugu", "tel"), Lang("ml", "Malayalam", "mal"),
        Lang("kn", "Kannada", "kan"), Lang("th", "Thai", "tha"), Lang("vi", "Vietnamese", "vie"),
        Lang("id", "Indonesian", "ind"), Lang("ms", "Malay", "may", "msa"), Lang("tl", "Tagalog", "tgl", "fil", "filipino"),
        Lang("zh", "Chinese", "chi", "zho", "mandarin", "cantonese", "yue", "cmn"), Lang("ja", "Japanese", "jpn"),
        Lang("ko", "Korean", "kor"), Lang("ca", "Catalan", "cat"), Lang("eu", "Basque", "baq", "eus"), Lang("gl", "Galician", "glg"),
        Lang("myn", "Mayan"),
    )

    fun langName(code: String) = LANGUAGES.firstOrNull { it.code == code }?.name ?: code

    data class Track(
        val id: Int,
        val type: String,               // "audio" / "sub" / "video"
        val lang: String? = null,
        val title: String? = null,
        val codec: String? = null,
        val forced: Boolean = false,
        val default: Boolean = false,
        val hearingImpaired: Boolean = false,
        val external: Boolean = false,
        val externalFilename: String? = null,
        val selected: Boolean = false,
    )

    /** Preferred language (ISO 639-1) for the current call. Calls come from one thread. */
    @Volatile private var pref = "en"
    private val prefTags get() = LANGUAGES.firstOrNull { it.code == pref }?.tags ?: setOf(pref)
    private fun isOtherLangTag(w: String) = w !in prefTags && LANGUAGES.any { it.code != pref && w in it.tags && (w.length > 2 || w == it.code) }

    /** Preferred-language audio to switch to (smart modes), or null to keep the current audio. */
    fun pickAudio(tracks: List<Track>, mode: Int, videoName: String? = null, lang: String = "en"): Int? {
        pref = lang
        if (mode == MODE_FILE || mode == MODE_OFF) return null
        val cur = currentAudio(tracks) ?: return null
        if (audioLanguage(cur, videoName, lang) != AudioLang.OTHER) return null
        return tracks.filter { it.type == "audio" && normLang(it.lang) == pref && !isCommentary(it) && !isDescription(it) }
            .minByOrNull { if (it.default) 0 else 1 }?.id
    }

    private fun currentAudio(tracks: List<Track>) = tracks.firstOrNull { it.type == "audio" && it.selected }
        ?: tracks.firstOrNull { it.type == "audio" && it.default }
        ?: tracks.firstOrNull { it.type == "audio" }

    /** What to do: a subtitle id, 0 = subtitles off, null = leave the player's choice. */
    fun pick(tracks: List<Track>, mode: Int, videoName: String? = null, audioId: Int? = null, lang: String = "en"): Int? {
        pref = lang
        stem = videoName?.substringAfterLast('/')?.substringAfterLast('\\')?.substringBeforeLast('.')
        if (mode == MODE_FILE) return null
        if (mode == MODE_OFF) return 0
        val subs = tracks.filter { it.type == "sub" }
        val mine = subs.filter { isPreferred(it) && !isCommentary(it) }
        val full = mine.filter { !isForced(it) }
        val forced = mine.filter { isForced(it) }
        if (mode == MODE_ALWAYS) return best(full)?.id ?: best(forced)?.id ?: 0

        val audio = audioId?.let { id -> tracks.firstOrNull { it.type == "audio" && it.id == id } } ?: currentAudio(tracks)
        return when (audioLanguage(audio, videoName, lang)) {
            AudioLang.OTHER -> best(full)?.id ?: best(forced)?.id ?: 0
            else -> best(forced)?.id ?: 0
        }
    }

    /** PREFERRED = the preferred language (named ENGLISH-agnostic). */
    enum class AudioLang { PREFERRED, OTHER, UNKNOWN }

    fun audioLanguage(a: Track?, videoName: String? = null, lang: String = pref): AudioLang {
        pref = lang
        if (a == null) return AudioLang.UNKNOWN
        val l = normLang(a.lang)
        if (l == pref) return AudioLang.PREFERRED
        if (l != null) return AudioLang.OTHER
        // Untagged: look for a language in the track title, then in the file name.
        val t = words(a.title)
        if (t.any { it in prefTags }) return AudioLang.PREFERRED
        if (t.any { isOtherLangTag(it) && it.length > 2 }) return AudioLang.OTHER
        val f = words(videoName?.substringAfterLast('/')?.substringAfterLast('\\'))
        if (f.any { isFileHint(it) || it == "subbed" } &&
            f.none { (it in prefTags && it.length > 2) || it == "dual" || it == "multi" }) return AudioLang.OTHER
        return AudioLang.UNKNOWN
    }

    /** Release-name words that name a language: full names, plus codes that are not also English words. */
    private val SAFE_CODES = setOf("hin", "tam", "tel", "kor", "jpn", "myn", "ita", "ger", "fre", "spa", "rus", "chi", "eng", "jap")
    private fun isFileHint(w: String) = w !in prefTags && ((w.length > 3 && isOtherLangTag(w)) || (w in SAFE_CODES && isOtherLangTag(w)))

    fun isDescription(t: Track): Boolean = words(t.title).let { w -> w.any { it == "description" || it == "descriptive" || it == "ad" } }

    /** Ranking among equally suitable tracks: plain before SDH, text before images, the file's default, embedded. */
    private fun best(list: List<Track>): Track? = list.minByOrNull {
        (if (isSdh(it)) 8 else 0) + (if (isImage(it)) 4 else 0) + (if (it.default) 0 else 2) + (if (it.external) 1 else 0)
    }

    // "cpe" (English-based creole) shows up on English shows by mistake: treat it as untagged.
    private val UNKNOWN = setOf("", "und", "unk", "mul", "zxx", "mis", "unknown", "xx", "cpe")

    /** ISO 639-1 code (e.g. "en", "pt-BR" → "pt"), the raw tag for languages not in the table, null when unknown. */
    fun normLang(lang: String?): String? {
        val l = lang?.trim()?.lowercase() ?: return null
        if (l in UNKNOWN) return null
        val base = l.substringBefore('-').substringBefore('_')
        return LANGUAGES.firstOrNull { base == it.code || base in it.tags }?.code ?: base
    }

    private fun words(s: String?) = s?.lowercase()?.split(Regex("[^a-z0-9]+"))?.filter { it.isNotEmpty() } ?: emptyList()

    private fun titleSaysPreferred(t: String?) = words(t).any { it in prefTags && it.length > 2 }

    private val FLAGS = setOf("forced", "foreign", "signs", "sdh", "cc", "hi", "hoh", "default", "full")

    /** Video file name without extension, used to strip it from external subtitle names. */
    @Volatile private var stem: String? = null

    /**
     * Tags of an external subtitle's file name, with the video's own name removed:
     * "Signs (2002).en.forced.srt" → [en, forced]; "Subs/2_English.srt" → [2, english].
     */
    private fun fileTags(t: Track): List<String> {
        if (!t.external) return emptyList()
        var name = t.externalFilename?.substringAfterLast('/')?.substringAfterLast('\\')?.substringBeforeLast('.') ?: return emptyList()
        name = java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8")
        val st = stem
        if (st != null && st.isNotEmpty() && name.startsWith(st, ignoreCase = true)) name = name.substring(st.length)
        else if (st != null && words(name).let { w -> w.size > 3 && words(st).take(3) == w.take(3) }) name = words(name).drop(words(st).size).joinToString(".")
        return words(name)
    }

    fun isPreferred(t: Track): Boolean {
        val l = normLang(t.lang)
        if (l != null) return l == pref
        if (!t.external) return titleSaysPreferred(t.title)
        val tags = fileTags(t)
        if (tags.any { it in prefTags && it !in FLAGS }) return true
        if (tags.any { it !in FLAGS && isOtherLangTag(it) }) return false
        if (titleSaysPreferred(t.title)) return true
        // No language anywhere: assume the library's own language, which is usually the preferred one.
        return true
    }

    /** Kept for tests and callers that mean English. */
    fun isEnglish(t: Track): Boolean { val p = pref; pref = "en"; return isPreferred(t).also { pref = p } }

    private fun flagWords(t: Track) = if (t.external) fileTags(t) else words(t.title)

    fun isForced(t: Track): Boolean {
        // Some files flag full SDH tracks as forced; the title wins.
        if (t.forced) return !(isSdh(t) || words(t.title).any { it == "full" || it == "complete" || it == "completos" })
        val w = flagWords(t)
        return w.any { it == "forced" || it == "foreign" || it == "signs" || it == "forzados" } ||
            (!t.external && t.title?.lowercase()?.let { it.contains("non-english") || it.contains("non english") } == true)
    }

    fun isSdh(t: Track): Boolean {
        if (t.hearingImpaired) return true
        val w = flagWords(t)
        return w.any { it == "sdh" || it == "cc" || it == "hi" || it == "hoh" } || (!t.external && t.title?.lowercase()?.contains("hearing") == true)
    }

    fun isCommentary(t: Track): Boolean = words(t.title).any { it.startsWith("comment") }

    fun isImage(t: Track): Boolean = t.codec?.lowercase()?.let {
        it.contains("pgs") || it.contains("dvd_sub") || it.contains("dvdsub") || it.contains("vobsub") || it.contains("hdmv")
    } == true
}
