/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What one talk shows and types as streaming recognition goes (060-voice-tagger.md §4.2, §5.1):
 * the segments the recognizer's endpoints closed, each its chosen text ([VoiceChoice]), then the
 * one being said, joined as the tagger was measured on (voice-eval `fusion_exp.py`'s `joined`: a
 * segment ending in no mark of its own gets a 「，」, except between two English words, which keep
 * a space), polished as it streams ([polish]: marks, numbers in digits; none if the tagger did not load,
 * the text then typed as recognised), then cleaned ([VoiceText.clean]: no spaces in Chinese). The field's end mark ([VoiceEnd]) is
 * the caller's. The rules come after the tagger: it was trained and measured on the recognizer's text.
 *
 * Not thread-safe: one thread, the recognizer's, as the tagger runs in [heard] and [closed].
 */
class VoiceLive(private val polish: VoicePolishStream? = null) {

    private var said = ""
    private var hearing = ""

    /** The segments closed and the one being said, as the recognizer wrote them, joined. */
    val raw: String
        get() = join(said, hearing)

    /**
     * What one step of the talk shows: [whole], the text so far before [VoiceText.clean] (the
     * frozen part polished, the rest as heard); [text], it cleaned, as it shows; and [settled], how
     * many characters of [whole] stay as they are whatever is said next, so they can be typed as
     * the talk goes on (tap to talk, [VoiceTalk]). [fallback]: the polish failed on this step and
     * [whole] is the raw text instead, not in step with what was polished before.
     */
    class Heard(val whole: String, val settled: Int, val fallback: Boolean = false) {
        val text: String = VoiceText.clean(whole).orEmpty()
    }

    /** The segment being said is [raw] so far; what to show. */
    fun heard(raw: String): Heard {
        hearing = raw.trim()
        return shown()
    }

    /**
     * The segment being said closed at an endpoint, [raw] its chosen text; what to show. A segment
     * of a sound only ([VoiceText.clean]'s 「嗯。」, 「咳咳。」) is left out: the recognizer writes one
     * for a noise as often as for a word.
     */
    fun closed(raw: String): Heard {
        hearing = ""
        if (VoiceText.clean(raw) != null) said = join(said, raw.trim())
        return shown()
    }

    /** What was said, polished, the segment being said taken as it is; before [VoiceText.clean]. */
    fun finished(): String {
        val text = raw
        return polish?.finish(text) ?: text
    }

    /** What was said, polished and cleaned, the segment being said taken as it is; null if nothing worth typing. */
    fun finish(): String? = VoiceText.clean(finished())

    private fun shown(): Heard {
        val text = raw
        // what no later segment changes: the closed ones, but for their end marks (the next may
        // bring its own, join). The one being said may yet change anywhere, as may its text once it
        // closes, chosen among the n-best (VoiceChoice); and the tagger's labels only once frozen
        val stable = said.trimEnd { it in MARKS || it.isWhitespace() }
        val shown = polish?.update(text)
        val whole = shown?.let { it.polished + it.rest } ?: text
        val fixed = polish?.frozenPrefix(VoicePolishTags.norm(stable).size) ?: stable
        return Heard(whole, settled(whole, fixed))
    }

    companion object {
        private const val MARKS = "，。？！、；：,.?!;:…"

        // the marks a talk is typed up to as it goes: not 「.」 (3.5, e.g.), 「：」 (what follows
        // belongs to it) nor 「…」
        internal const val ENDS = "。？！；，、?!;,"

        /**
         * How much of [fixed] (text that stays) may be typed now: up to its last mark with a
         * character after it, not a mark nor a space, in [fixed] too, so that what follows it now
         * follows it in the end. Not up to a mark at its end: one coming next may change it
         * ([VoicePolishTags.apply] drops 「，」 before 「。」), and the clean-up may join what is on
         * either side of a cut with no mark ([VoiceText.clean]: spelled letters). 0 if none.
         */
        fun settledEnd(fixed: String): Int {
            for (i in fixed.length - 1 downTo 1) {
                val c = fixed[i]
                if (c in MARKS || c.isWhitespace()) continue
                if (fixed[i - 1] in ENDS) return i
            }
            return 0
        }

        /**
         * How much of [whole] may be typed now, [fixed] what stays of it ([settledEnd]); 0 if [whole]
         * does not start with that much of [fixed] (the polish's frozen text and what it shows out of
         * step: it should not be), as nothing typed is better than the wrong thing.
         */
        fun settled(whole: String, fixed: String): Int {
            val end = settledEnd(fixed)
            return if (whole.regionMatches(0, fixed, 0, end)) end else 0
        }

        /** [after] following [before], as the tagger's input had segments one after another. */
        fun join(before: String, after: String): String = when {
            before.isEmpty() -> after
            after.isEmpty() -> before
            // the recognizer's context outlives its endpoint: the next segment may start with the
            // mark that ends this one, its own say over where this one ends (，， and ，。 otherwise)
            after.first() in MARKS -> before.trimEnd { it in MARKS } + after
            before.last() in MARKS -> before + after
            before.last().isAsciiWord() && after.first().isAsciiWord() -> "$before $after"
            else -> "$before，$after"
        }

        private fun Char.isAsciiWord() = this < '\u0080' && isLetterOrDigit()
    }
}
