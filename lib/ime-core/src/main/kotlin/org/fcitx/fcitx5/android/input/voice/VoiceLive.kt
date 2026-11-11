/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What one talk shows and types as streaming recognition goes (060-voice-tagger.md §4.2, §5.1):
 * the segments the recognizer's endpoints closed, each its chosen text ([VoiceChoice]), then the
 * one being said, joined with nothing between them ([join]: a space between two English words or
 * numbers, the recognizer's own marks kept), polished as it streams ([polish]: marks, numbers in digits; none if the tagger did not load,
 * the text then typed as recognised), then cleaned ([VoiceText.clean]: no spaces in Chinese). The field's end mark ([VoiceEnd]) is
 * the caller's. The rules come after the tagger: it was trained and measured on the recognizer's text.
 * Where a pause has no mark is the tagger's to say (its after and number heads), not a rule's: the
 * owner's decision 2026-11-10. Its training data (voice-eval `fusion_exp.py`'s `joined`) still puts
 * a 「，」 there; training it on segments joined with nothing is a later task.
 *
 * Not thread-safe: one thread, the recognizer's, as the tagger runs in [heard] and [closed].
 */
class VoiceLive(private val polish: VoicePolishStream? = null) {

    private var said = ""
    private var hearing = ""

    // the segment being said as the recognizer wrote it, before [gone] is left out of it
    private var hearingRaw = ""

    // segments closed so far, so a [Mark] tells whether the one being said then has closed since
    private var closedCount = 0

    // whether each closed segment went into [said] (one of a sound only does not), by its number
    private val closedKept = mutableListOf<Boolean>()

    // what of the segment being said went with the last utterance ([restart]), as the recognizer
    // wrote it: left out of it as it goes on
    private var gone = ""

    /** how many times the talk [restart]ed: what [Heard] tells, so one from before a restart is told apart */
    var utterance = 0
        private set

    /**
     * Where the talk was when a [Heard] was made: the closed segments joined ([said]), the one being
     * said ([hearing] as kept, [hearingRaw] as the recognizer wrote it), and how many had closed.
     * A [restart] leaves out that much, not what was heard after it.
     */
    class Mark internal constructor(val said: String, val hearing: String, val hearingRaw: String, val closed: Int)

    /** Where the talk is now ([Mark]). */
    fun mark() = Mark(said, hearing, hearingRaw, closedCount)

    /**
     * What was said up to [upTo] (the last [Heard] the field showed) was taken (the app sent it,
     * cleared the field): what is said next is a new utterance, nothing of the last joined to it
     * nor typed again. What was heard after [upTo] stays, the start of the new one: it never
     * showed. The segment being said goes on in the recognizer: what of it was heard by [upTo] is
     * left out of it, matched as [cut] does. [upTo] null: none of this utterance showed, nothing
     * left out. To [to], however many restarts were asked for meanwhile; nothing if there already.
     */
    fun restart(to: Int, upTo: Mark?) {
        if (to <= utterance) return
        utterance = to
        if (upTo == null) return
        // the segments closed by then; a later one may have taken the mark that ended them (join)
        val base = upTo.said.trimEnd { it in MARKS }
        var rest = if (said.startsWith(base)) said.substring(base.length) else ""
        if (closedCount > upTo.closed) {
            // the segment being said then has closed since: the start of what follows them, if it
            // went into said at all (not a sound only, nor nothing)
            if (closedKept[upTo.closed]) rest = cut(upTo.hearing, rest)
        } else {
            gone = upTo.hearingRaw
            hearing = fresh(hearingRaw)
        }
        said = rest.trimStart { it in MARKS || it.isWhitespace() }
    }

    // [text] of the segment being said, what of it went with the last utterance left out
    private fun fresh(text: String): String = if (gone.isEmpty()) text else cut(gone, text)

    /** The segments closed and the one being said, as the recognizer wrote them, joined. */
    val raw: String
        get() = join(said, hearing)

    /**
     * What one step of the talk shows: [whole], the text so far before [VoiceText.clean] (the
     * frozen part polished, the rest as heard); [text], it cleaned, as it shows; and [settled], how
     * many characters of [whole] stay as they are whatever is said next, so they can be typed as
     * the talk goes on (tap to talk, [VoiceTalk]). [fallback]: the polish failed on this step and
     * [whole] is the raw text instead, not in step with what was polished before. [utterance]: the
     * [VoiceLive.utterance] it is of; [upTo], where the talk was ([restart]).
     */
    class Heard(val whole: String, val settled: Int, val fallback: Boolean = false, val utterance: Int = 0, val upTo: Mark? = null) {
        val text: String = VoiceText.clean(whole).orEmpty()
    }

    /** The segment being said is [raw] so far; what to show. */
    fun heard(raw: String): Heard {
        hearingRaw = raw.trim()
        hearing = fresh(hearingRaw)
        return shown()
    }

    /**
     * The segment being said closed at an endpoint, [raw] its chosen text; what to show. A segment
     * of a sound only ([VoiceText.clean]'s 「嗯。」, 「咳咳。」) is left out: the recognizer writes one
     * for a noise as often as for a word.
     */
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
        return Heard(whole, settled(whole, fixed), utterance = utterance, upTo = mark())
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

        /**
         * [after] following [before], one segment after another, with nothing between: a mark at a
         * pause is the tagger's. Two English words or numbers keep a space, or they would be one
         * word (「hello」「world」, 「2026」「11」).
         */
        fun join(before: String, after: String): String = when {
            before.isEmpty() -> after
            after.isEmpty() -> before
            // the recognizer's context outlives its endpoint: the next segment may start with the
            // mark that ends this one, its own say over where this one ends (，， and ，。 otherwise)
            after.first() in MARKS -> before.trimEnd { it in MARKS } + after
            before.last() in MARKS -> before + after
            before.last().isAsciiWord() && after.first().isAsciiWord() -> "$before $after"
            else -> before + after
        }

        /**
         * [text] after what of it was [prefix]: the end of [text]'s start closest to [prefix] (the
         * fewest characters put in, left out or changed; the longest of those as close), then the
         * marks after it. The recognizer may write a character of [prefix] otherwise as it goes on
         * (its n-best choice at the endpoint, 吗 then 嘛): that one goes too, not typed again.
         */
        fun cut(prefix: String, text: String): String {
            if (prefix.isEmpty()) return text
            // the recognizer took back some of what was sent and has nothing new: nothing to type
            if (prefix.startsWith(text)) return ""
            // the edit distance from prefix to each start of text, a row at a time; [last] the row
            // before prefix's last character, to tell an end on a changed character
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
                // as close and longer: not if it ends on a changed character (that may be a new
                // word's, as likely as the sent one's written otherwise): a character typed twice
                // is better than a new word lost
                val changed = prefix.last() != text[j - 1] && row[j] == last[j - 1] + 1
                if (row[j] < row[end] || (row[j] == row[end] && !changed)) end = j
            }
            // nothing much like what was sent (the recognizer wrote it all otherwise): all of it kept
            if (row[end] * 2 > prefix.length) return text
            return text.substring(end).trimStart { it in MARKS || it.isWhitespace() }
        }

        private fun Char.isAsciiWord() = this < '\u0080' && isLetterOrDigit()
    }
}
