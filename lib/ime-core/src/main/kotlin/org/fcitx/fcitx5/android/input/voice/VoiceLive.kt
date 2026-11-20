/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What one talk shows and types as recognition streams: the closed segments, then the one being said, joined
 * with nothing between ([join]), polished ([polish]; none if the tagger did not load), then cleaned. Rules come
 * after the tagger, which was trained on the recognizer's text; where a pause gets a mark is the tagger's call.
 * One thread, the recognizer's.
 */
class VoiceLive(private val polish: VoicePolishStream? = null) {

    private var said = ""
    private var hearing = ""

    private var hearingRaw = ""

    private var closedCount = 0

    // whether each closed segment went into [said] (a sound only does not), by its number
    private val closedKept = mutableListOf<Boolean>()

    // what of the segment being said went with the last utterance ([restart]): left out as it goes on
    private var gone = ""

    /** Tells a [Heard] from before a restart apart. */
    var utterance = 0
        private set

    /** Where the talk was when a [Heard] was made; a [restart] leaves out that much, not what came after. */
    class Mark internal constructor(val said: String, val hearing: String, val hearingRaw: String, val closed: Int)

    fun mark() = Mark(said, hearing, hearingRaw, closedCount)

    /**
     * What was said up to [upTo] was taken (the app sent it): what follows is a new utterance, nothing joined to
     * it nor typed again; what was heard after [upTo] never showed and starts the new one. Restarts to [to] at once.
     */
    fun restart(to: Int, upTo: Mark?) {
        if (to <= utterance) return
        utterance = to
        if (upTo == null) return
        // a later segment may have taken the mark that ended them (join)
        val base = upTo.said.trimEnd { it in MARKS }
        var rest = if (said.startsWith(base)) said.substring(base.length) else ""
        if (closedCount > upTo.closed) {
            // the segment being said then has closed since: what follows it, if it went into said at all
            if (closedKept[upTo.closed]) rest = cut(upTo.hearing, rest.trimStart { it in MARKS || it.isWhitespace() })
        } else {
            gone = upTo.hearingRaw
            hearing = fresh(hearingRaw)
        }
        said = rest.trimStart { it in MARKS || it.isWhitespace() }
    }

    private fun fresh(text: String): String = if (gone.isEmpty()) text else cut(gone, text)

    val raw: String
        get() = join(said, hearing)

    /**
     * [whole]: the text before [VoiceText.clean]; [settled]: how many of its characters stay whatever is said next,
     * so they can be typed as the talk goes on. [fallback]: the polish failed and [whole] is the raw text.
     */
    class Heard(val whole: String, val settled: Int, val fallback: Boolean = false, val utterance: Int = 0, val upTo: Mark? = null) {
        val text: String = VoiceText.clean(whole).orEmpty()
    }

    fun heard(raw: String): Heard {
        hearingRaw = raw.trim()
        hearing = fresh(hearingRaw)
        return shown()
    }

    /** A segment of a sound only (「嗯。」) is left out: the recognizer writes one for a noise as often as for a word. */
    fun closed(raw: String): Heard {
        hearing = ""
        hearingRaw = ""
        val text = fresh(raw.trim())
        gone = ""
        closedCount++
        val kept = VoiceText.clean(text) != null
        closedKept += kept
        if (kept) said = join(said, text)
        return shown()
    }

    /** Before [VoiceText.clean]. */
    fun finished(): String {
        val text = raw
        return polish?.finish(text) ?: text
    }

    /** Null if nothing worth typing. */
    fun finish(): String? = VoiceText.clean(finished())

    private fun shown(): Heard {
        val text = raw
        // the closed segments but their end marks (the next may bring its own); the one being said may change anywhere
        val stable = said.trimEnd { it in MARKS || it.isWhitespace() }
        val shown = polish?.update(text)
        val whole = shown?.let { it.polished + it.rest } ?: text
        val fixed = polish?.frozenPrefix(VoicePolishTags.norm(stable).size) ?: stable
        return Heard(whole, settled(whole, fixed), utterance = utterance, upTo = mark())
    }

    companion object {
        private const val MARKS = VoiceMarks.JOINING

        internal const val ENDS = VoiceMarks.LIVE_ENDS

        /**
         * Up to its last mark with a non-mark character after it. Not a mark at its end: the next may change it
         * (「，」 before 「。」 drops), and clean may join across a cut with no mark. 0 if none.
         */
        fun settledEnd(fixed: String): Int {
            for (i in fixed.length - 1 downTo 1) {
                val c = fixed[i]
                if (c in MARKS || c.isWhitespace()) continue
                if (fixed[i - 1] in ENDS) return i
            }
            return 0
        }

        /** 0 if [whole] does not start with that much of [fixed]: nothing typed beats the wrong thing. */
        fun settled(whole: String, fixed: String): Int {
            val end = settledEnd(fixed)
            return if (whole.regionMatches(0, fixed, 0, end)) end else 0
        }

        /** A mark at a pause is the tagger's; two English words or numbers keep a space (「2026」「11」). */
        fun join(before: String, after: String): String = when {
            before.isEmpty() -> after
            after.isEmpty() -> before
            // the next segment may start with the mark that ends this one (，， and ，。 otherwise)
            after.first() in MARKS -> before.trimEnd { it in MARKS } + after
            before.last() in MARKS -> before + after
            before.last().isAsciiWord() && after.first().isAsciiWord() -> "$before $after"
            else -> before + after
        }

        /**
         * [text] after the start closest to [prefix] by edit distance, then its marks. A cut ending on a character the
         * recognizer rewrote (吗 then 嘛) keeps it: typed twice beats a new word lost. Nothing like [prefix]: all kept.
         */
        fun cut(prefix: String, text: String): String {
            if (prefix.isEmpty()) return text
            // the recognizer took back some of what was sent and has nothing new: nothing to type
            if (prefix.startsWith(text)) return ""
            // [last]: the row before prefix's last character, to tell an end on a changed character
            var last = IntArray(text.length + 1) { it }
            var row = IntArray(text.length + 1)
            for (i in 1..prefix.length) {
                row[0] = i
                for (j in 1..text.length) {
                    val change = last[j - 1] + if (prefix[i - 1] == text[j - 1]) 0 else 1
                    row[j] = minOf(change, last[j] + 1, row[j - 1] + 1)
                }
                if (i < prefix.length) last = row.also { row = last }
            }
            var end = 0
            for (j in 1..text.length) {
                // as close and longer: not if it ends on a changed character
                val changed = prefix.last() != text[j - 1] && row[j] == last[j - 1] + 1
                if (row[j] < row[end] || (row[j] == row[end] && !changed)) end = j
            }
            if (row[end] * 2 > prefix.length) return text
            return text.substring(end).trimStart { it in MARKS || it.isWhitespace() }
        }

        private fun Char.isAsciiWord() = this < '\u0080' && isLetterOrDigit()
    }
}
