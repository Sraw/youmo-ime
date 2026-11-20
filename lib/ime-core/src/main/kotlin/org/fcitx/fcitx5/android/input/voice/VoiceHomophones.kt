/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.engine.runtime.UserWord

/**
 * Puts the user's words in for what voice heard read the same, tones and all (婧 is never in the beam). Not
 * where it is a word of theirs, holds a dictionary word read otherwise, or is covered by a longer dictionary
 * word or crosses a common one; a common word read alike is replaced. BMP words of two characters or more; the text keeps its
 * length. Made once a hold on the engine's thread; the dictionary is asked only of stretches that read as a word.
 */
class VoiceHomophones internal constructor(
    private val words: List<Word>,
    // every word of the user's: never replaced, nor what is inside one
    private val own: Set<String>,
    private val chars: (Char) -> IntArray?,
    private val added: Int = 0,
) {
    class Word(val text: String, val readings: List<IntArray>)

    /** [at]: where [run] starts in the text. */
    class Span(val run: String, val start: Int, val end: Int, val word: Word, val at: Int = 0) {
        val text: String get() = run.substring(start, end)

        // only what [allowed] reads (MAX_WORD either side): a growing run is not asked again
        internal val key: String get() {
            val from = maxOf(0, start - MAX_WORD + 1)
            val to = minOf(run.length, end - 1 + MAX_WORD)
            return "${run.substring(from, to)}\u0000${start - from}\u0000${word.text}"
        }
    }

    /** On the engine's thread (EngineAssets). */
    interface Dictionary {
        fun isWord(text: String): Boolean

        fun isCommon(text: String): Boolean

        /** One character a reading, null if the dictionary has no such word. */
        fun wordReading(text: String): IntArray?
    }

    /** Null if the engine cannot say in time. */
    fun interface Lexicon {
        suspend fun allowed(spans: List<Span>): BooleanArray?
    }

    private val byFirst: Map<Int, List<Word>> = HashMap<Int, MutableList<Word>>().also { map ->
        for (w in words) for (r in w.readings[0]) map.getOrPut(r) { ArrayList() }.let { if (w !in it) it += w }
    }

    private val rank: Map<Word, Int> = words.withIndex().associate { (i, w) -> w to i }

    val isEmpty: Boolean get() = words.isEmpty()

    /** By where it starts, the longest word first, then as [words] has them. */
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
            val firsts = chars(run[start]) ?: continue
            val found = LinkedHashSet<Word>()
            for (r in firsts) byFirst[r]?.let(found::addAll)
            val spans = found.filter { w -> start + w.text.length <= run.length && reads(run, start, w) }
                .sortedWith(compareByDescending<Word> { it.text.length }.thenBy { rank.getValue(it) })
                .map { Span(run, start, start + it.text.length, it, at) }
                .filter { !mine(it) }
            out += spans
        }
    }

    private fun reads(run: String, start: Int, w: Word): Boolean {
        if (run.regionMatches(start, w.text, 0, w.text.length)) return false
        for (k in w.text.indices) {
            val readings = chars(run[start + k]) ?: return false
            if (w.readings[k].none { it in readings }) return false
        }
        return true
    }

    private fun mine(span: Span): Boolean {
        if (span.text in own) return true
        for (from in maxOf(0, span.end - MAX_WORD)..span.start) {
            for (to in span.end..minOf(span.run.length, from + MAX_WORD)) {
                if (to - from > span.end - span.start && span.run.substring(from, to) in own) return true
            }
        }
        return false
    }

    /** With [text] added by hand after the others added; the rest not read again. */
    internal fun withAdded(text: String, readings: List<IntArray>): VoiceHomophones {
        if (words.indexOfFirst { it.text == text } in 0 until added) return this
        val all = words.filterTo(ArrayList()) { it.text != text }.apply { add(added, Word(text, readings)) }
        return VoiceHomophones(all, own + text, chars, added + 1)
    }

    /** On its decoder's thread alone. */
    fun hold() = Hold(this)

    /**
     * What one hold put in stays so: a stretch put in once is put in again wherever the engine has not answered
     * for it, and one put in where it was typed already stays whatever the engine says later (typed words must
     * not turn back). The engine not answering once, it is not asked again this hold.
     */
    class Hold internal constructor(private val homophones: VoiceHomophones) {
        // by [Span.key]: asked again only as what is read around it changes
        private val answered = HashMap<String, Boolean>()

        // by where they start in the utterance's text
        private val pinned = HashMap<Int, String>()
        private var pinnedOf = -1

        // per typed character: kept while a text still starts with the heard stretch up to it, never by position alone
        private val pinnedChars = HashMap<Int, Pair<String, Char>>()

        // stretches put in, as heard and as put in: "徐静\u0000徐婧"
        private val putIn = HashSet<String>()

        private var silent = false

        /** From the start; a stretch overlapping one already put in is left as it is. */
        suspend fun replace(text: String, lexicon: Lexicon): String = replaceTyped(text, lexicon, 0)

        private suspend fun replaceTyped(text: String, lexicon: Lexicon, settled: Int): String {
            val spans = homophones.candidates(text)
            if (spans.isEmpty()) return keepTyped(text, text.toCharArray())
            val ask = spans.filter { it.key !in answered }.distinctBy { it.key }
            if (ask.isNotEmpty() && !silent) {
                val said = lexicon.allowed(ask)
                if (said == null) silent = true else ask.forEachIndexed { i, span -> answered[span.key] = said.getOrElse(i) { false } }
            }
            val out = text.toCharArray()
            var until = 0
            for (span in spans) {
                val from = span.at + span.start
                // one typed already stays; else the engine's answer, never overturned; else one put in here, only where it never answered
                val yes = pinned[from] == pair(span) || (answered[span.key] ?: (pair(span) in putIn))
                if (from < until || !yes) continue
                answered[span.key] = true
                span.word.text.toCharArray(out, from)
                putIn += pair(span)
                if (from < settled) {
                    pinned[from] = pair(span)
                    for (k in from until minOf(settled, span.at + span.end)) {
                        if (text[k] != out[k]) pinnedChars[k] = text.substring(0, k + 1) to out[k]
                    }
                }
                until = span.at + span.end
            }
            return keepTyped(text, out)
        }

        private fun keepTyped(text: String, out: CharArray): String {
            for ((k, pin) in pinnedChars) if (text.startsWith(pin.first)) out[k] = pin.second
            return String(out)
        }

        /** The same length, so what of it is settled stays so. */
        suspend fun replace(heard: VoiceLive.Heard, lexicon: Lexicon): VoiceLive.Heard {
            // a new utterance's text starts again: where the last one's were typed says nothing of it
            if (heard.utterance != pinnedOf) {
                pinned.clear()
                pinnedChars.clear()
                pinnedOf = heard.utterance
            }
            val whole = replaceTyped(heard.whole, lexicon, heard.settled)
            return if (whole == heard.whole) heard else VoiceLive.Heard(whole, heard.settled, heard.fallback, heard.utterance, heard.upTo)
        }

        private fun pair(span: Span) = span.text + "\u0000" + span.word.text
    }

    companion object {
        val NONE = VoiceHomophones(emptyList(), emptySet(), { null })

        const val LEARNED_MIN = 3f

        private const val MAX_WORD = 12

        /** A syllable's id and its tone (1–5). */
        fun reading(syllable: Int, tone: Int): Int = syllable * 8 + tone

        private fun isHan(c: Char): Boolean = Character.isIdeographic(c.code)

        /** [words] in Engines.userWords' order; [readingsOf] null: takes no part. */
        fun of(
            words: List<UserWord>,
            readingsOf: (UserWord) -> List<IntArray>?,
            charReadings: (Char) -> IntArray?,
        ): VoiceHomophones {
            val mine = words.filter { it.kind == UserWord.Kind.ADDED || it.kind == UserWord.Kind.LEARNED }
            val taking = mine.sortedWith(compareBy<UserWord> { it.kind != UserWord.Kind.ADDED }.thenByDescending { it.count }).asSequence()
                .filter { it.kind == UserWord.Kind.ADDED || it.count > LEARNED_MIN }
                .filter { w -> w.text.length >= 2 && w.text.all(::isHan) }
                .distinctBy { it.text }
                .mapNotNull { w -> readingsOf(w)?.takeIf { r -> r.size == w.text.length && r.all { it.isNotEmpty() } }?.let { Word(w.text, it) } }
                .toList()
            if (taking.isEmpty()) return NONE
            val added = mine.filter { it.kind == UserWord.Kind.ADDED }.mapTo(HashSet()) { it.text }
            return VoiceHomophones(taking, mine.mapTo(HashSet()) { it.text }, charReadings, taking.count { it.text in added })
        }

        /** No dictionary word in it read otherwise, no longer word covering it, no common word cut by its edge. */
        fun allowed(span: Span, dictionary: Dictionary): Boolean {
            val text = span.text
            // by the words in it, not each char's readings alone: 那 and 哪 share one, the word 那个 reads otherwise than 哪个
            for (from in 0 until text.length - 1) {
                for (to in from + 2..text.length) {
                    val read = dictionary.wordReading(text.substring(from, to)) ?: continue
                    if (read.size != to - from) return false
                    for (k in read.indices) {
                        val tone = read[k] % 8
                        val ok = span.word.readings[from + k].any { if (tone == 0) it / 8 == read[k] / 8 else it == read[k] }
                        if (!ok) return false
                    }
                }
            }
            // any word covering it (徐静 in 徐静雨), or a common one cut by its edge (和瑞 of 和瑞典)
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
