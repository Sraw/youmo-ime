/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.engine.runtime.UserWord

/**
 * The user's words put in for what voice input heard read the same (060-voice-userdict.md, the
 * owner's choice 2026-11-10): a rare character of theirs the recognizer never hears (徐婧 heard
 * 徐静, its hotword no help, 婧 never in the beam). A stretch of Chinese whose every character reads
 * as the word's does there, tones and all, becomes that word, unless
 * - it is a word of the user's itself (they typed 徐静 too), or inside a longer one of theirs;
 * - it is, or has in it, a common word of the dictionary ([Dictionary.isCommon]: 公式, never 攻势), or is
 *   the dictionary's word read otherwise ([Dictionary.wordReading]);
 * - a longer word of the dictionary covers it (徐静 in 徐静雨), or a common one crosses its edge
 *   (和瑞 of 和瑞典).
 *
 * Only words of two characters or more, every one in the BMP; added ones at once, learned ones
 * typed more than [LEARNED_MIN] times. Of several reading the same, the first of [words]: added
 * before learned, then the most typed. The text keeps its length: a character for a character.
 *
 * Made once a hold (VoiceEngine.userHotwords, beside the hotwords) on the engine's thread; what
 * the dictionary says of a stretch ([allowed]) is asked of the engine only for one that reads as a
 * word ([candidates]) and remembered for the hold ([Hold]).
 */
class VoiceHomophones internal constructor(
    // those that take part, preferred first
    private val words: List<Word>,
    // every word of the user's (added, learned however often): never replaced, nor what is inside one
    private val own: Set<String>,
    // each character's readings ([reading]), all of them; those no word has match nothing
    private val chars: Map<Char, IntArray>,
) {
    /** A user's word, the readings each of its characters may have ([reading]). */
    class Word(val text: String, val readings: List<IntArray>)

    /** [word] read in [run] (a stretch of Chinese) at [start] until [end], [at] where [run] starts in the text. */
    class Span(val run: String, val start: Int, val end: Int, val word: Word, val at: Int = 0) {
        val text: String get() = run.substring(start, end)

        internal val key: String get() = "$run\u0000$start\u0000${word.text}"
    }

    /** What the dictionary says of a stretch, on the engine's thread (EngineAssets). */
    interface Dictionary {
        /** whether [text] is a word of the dictionary */
        fun isWord(text: String): Boolean

        /** whether [text] is a word of the dictionary used too often to be put in for */
        fun isCommon(text: String): Boolean

        /** [text]'s reading ([reading], one a character) as the dictionary's word, null if it has no such word */
        fun wordReading(text: String): IntArray?
    }

    /** What the engine answers of [Span]s ([allowed] each), null if it cannot say in time. */
    fun interface Lexicon {
        suspend fun allowed(spans: List<Span>): BooleanArray?
    }

    private val byFirst: Map<Int, List<Word>> = HashMap<Int, MutableList<Word>>().also { map ->
        for (w in words) for (r in w.readings[0]) map.getOrPut(r) { ArrayList() }.let { if (w !in it) it += w }
    }

    private val rank: Map<Word, Int> = words.withIndex().associate { (i, w) -> w to i }

    val isEmpty: Boolean get() = words.isEmpty()

    /**
     * Every stretch of [text] that reads as one of [words] and is neither it nor a word of the user's
     * or inside one: by where it starts, the longest word first, then as [words] has them.
     */
    fun candidates(text: String): List<Span> {
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Span>()
        var i = 0
        while (i < text.length) {
            if (!isHan(text[i])) {
                i++
                continue
            }
            var end = i
            while (end < text.length && isHan(text[end])) end++
            if (end - i >= 2) candidatesIn(text.substring(i, end), i, out)
            i = end
        }
        return out
    }

    private fun candidatesIn(run: String, at: Int, out: MutableList<Span>) {
        for (start in 0 until run.length - 1) {
            val firsts = chars[run[start]] ?: continue
            val found = LinkedHashSet<Word>()
            for (r in firsts) byFirst[r]?.let(found::addAll)
            val spans = found.filter { w -> start + w.text.length <= run.length && reads(run, start, w) }
                .sortedWith(compareByDescending<Word> { it.text.length }.thenBy { rank.getValue(it) })
                .map { Span(run, start, start + it.text.length, it, at) }
                .filter { !mine(it) }
            out += spans
        }
    }

    // [run] from [start] reads as [w], character by character, and is not [w]
    private fun reads(run: String, start: Int, w: Word): Boolean {
        if (run.regionMatches(start, w.text, 0, w.text.length)) return false
        for (k in w.text.indices) {
            val readings = chars[run[start + k]] ?: return false
            if (w.readings[k].none { it in readings }) return false
        }
        return true
    }

    // the stretch a word of the user's, or inside a longer one of theirs in its run
    private fun mine(span: Span): Boolean {
        if (span.text in own) return true
        for (from in maxOf(0, span.end - MAX_WORD)..span.start) {
            for (to in span.end..minOf(span.run.length, from + MAX_WORD)) {
                if (to - from > span.end - span.start && span.run.substring(from, to) in own) return true
            }
        }
        return false
    }

    /** One hold's replacing ([hold]): made for each listener, on its decoder's thread alone. */
    fun hold() = Hold(this)

    /**
     * What one hold put in, kept for all of it: what showed and was typed as it streamed stays so
     * whatever comes later. A stretch put in once (its text and word) is put in again wherever it
     * is heard in this hold, the engine asked or not: the raw text the polish failing falls back
     * on splits otherwise, and a word typed must not turn back (VoiceTalk retyping what follows).
     * The engine not answering once ([Lexicon.allowed] null: busy, a timeout), it is not asked
     * again this hold: what it has not answered stays as heard.
     */
    class Hold internal constructor(private val homophones: VoiceHomophones) {
        // [allowed] as answered, by [Span.key]: a stretch is asked once as each step of it streams
        private val answered = HashMap<String, Boolean>()

        // stretches put in, as heard and as put in: "徐静\u0000徐婧"
        private val putIn = HashSet<String>()

        private var silent = false

        /**
         * [text] with each stretch [candidates] finds and the engine [allowed] made the word (or one
         * this hold put in already, where the engine has not answered for it): from the start, a stretch overlapping one already put in left
         * as it is.
         */
        suspend fun replace(text: String, lexicon: Lexicon): String {
            val spans = homophones.candidates(text)
            if (spans.isEmpty()) return text
            val ask = spans.filter { it.key !in answered }.distinctBy { it.key }
            if (ask.isNotEmpty() && !silent) {
                val said = lexicon.allowed(ask)
                if (said == null) silent = true else ask.forEachIndexed { i, span -> answered[span.key] = said.getOrElse(i) { false } }
            }
            val out = text.toCharArray()
            var until = 0
            for (span in spans) {
                val from = span.at + span.start
                // the engine's answer for this stretch where it is, asked of every place while it answers,
                // never overturned; one put in this hold elsewhere only for a place it never answered
                // (silent since), so what was typed does not turn back
                val yes = answered[span.key] ?: (pair(span) in putIn)
                if (from < until || !yes) continue
                answered[span.key] = true
                span.word.text.toCharArray(out, from)
                putIn += pair(span)
                until = span.at + span.end
            }
            return String(out)
        }

        /** [heard] with its text [replace]d: the same length, so what of it is settled stays so. */
        suspend fun replace(heard: VoiceLive.Heard, lexicon: Lexicon): VoiceLive.Heard {
            val whole = replace(heard.whole, lexicon)
            return if (whole == heard.whole) heard else VoiceLive.Heard(whole, heard.settled, heard.fallback, heard.utterance, heard.upTo)
        }

        private fun pair(span: Span) = span.text + "\u0000" + span.word.text
    }

    companion object {
        /** none of the user's words: nothing replaced */
        val NONE = VoiceHomophones(emptyList(), emptySet(), emptyMap())

        /** a learned word takes part once typed more than this many times (the owner's 2026-11-10) */
        const val LEARNED_MIN = 3f

        // as long as a word of the user's may be (VoiceHotwords' spelling's)
        private const val MAX_WORD = 12

        /** A reading as [Word] and [Dictionary] have it: a syllable's id and its tone (1–5). */
        fun reading(syllable: Int, tone: Int): Int = syllable * 8 + tone

        private fun isHan(c: Char): Boolean = Character.isIdeographic(c.code)

        /**
         * Of the user's [words] (Engines.userWords' order: added, learned most typed first,
         * blocked), [readingsOf] each that takes part (null: not known, it takes no part), each
         * character's readings, all of them, by [charReadings].
         */
        fun of(
            words: List<UserWord>,
            readingsOf: (UserWord) -> List<IntArray>?,
            charReadings: () -> Map<Char, IntArray>,
        ): VoiceHomophones {
            val mine = words.filter { it.kind == UserWord.Kind.ADDED || it.kind == UserWord.Kind.LEARNED }
            // added first, then learned most typed first, whatever order they came in
            val taking = mine.sortedWith(compareBy<UserWord> { it.kind != UserWord.Kind.ADDED }.thenByDescending { it.count }).asSequence()
                .filter { it.kind == UserWord.Kind.ADDED || it.count > LEARNED_MIN }
                .filter { w -> w.text.length >= 2 && w.text.all(::isHan) }
                .distinctBy { it.text }
                .mapNotNull { w -> readingsOf(w)?.takeIf { r -> r.size == w.text.length && r.all { it.isNotEmpty() } }?.let { Word(w.text, it) } }
                .toList()
            if (taking.isEmpty()) return NONE
            return VoiceHomophones(taking, mine.mapTo(HashSet()) { it.text }, charReadings())
        }

        /**
         * Whether [span] may become its word, as [dictionary] has it: no common word, nor one in it, not the
         * dictionary's word read otherwise (its tones; one not known taken as any), no longer word
         * of it covering the stretch in its run.
         */
        fun allowed(span: Span, dictionary: Dictionary): Boolean {
            val text = span.text
            // a common word in it too: 是那个 is not made the user's 是哪个, nor 和瑞典 何芮典
            for (from in 0 until text.length - 1) {
                for (to in from + 2..text.length) if (dictionary.isCommon(text.substring(from, to))) return false
            }
            dictionary.wordReading(text)?.let { read ->
                if (read.size != text.length) return false
                for (k in read.indices) {
                    val tone = read[k] % 8
                    val ok = span.word.readings[k].any { if (tone == 0) it / 8 == read[k] / 8 else it == read[k] }
                    if (!ok) return false
                }
            }
            // a word of the run over its edge: any covering it (徐静 in 徐静雨), a common one cut by it
            // (和瑞 of 和瑞典, never the user's 何芮)
            for (from in maxOf(0, span.start - MAX_WORD + 1) until span.end) {
                for (to in maxOf(from + 2, span.start + 1)..minOf(span.run.length, from + MAX_WORD)) {
                    if (from >= span.start && to <= span.end) continue
                    val word = span.run.substring(from, to)
                    val covers = from <= span.start && to >= span.end
                    if (if (covers) dictionary.isWord(word) else dictionary.isCommon(word)) return false
                }
            }
            return true
        }
    }
}
