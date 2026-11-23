/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.engine.runtime.UserWord

/**
 * The user's words read the same, tones and all, as stretches of what voice heard (婧 is never in the beam): where
 * one may go in ([candidates], [allowed]) and whether it does ([pick]). Not where it is a word of theirs, holds a
 * dictionary word read otherwise, or is covered by a longer dictionary word or crosses a common one. BMP words of
 * two characters or more; the text keeps its length. Made on the engine's thread, read on any.
 */
class VoiceHomophones internal constructor(
    private val words: List<Word>,
    // every word of the user's: never replaced, nor what is inside one
    private val own: Set<String>,
    private val chars: (Char) -> IntArray?,
    private val added: Int = 0,
) {
    /** [bonus]: what the word model's log10 odds against it are outweighed by; [ALWAYS] goes in unasked. */
    class Word(val text: String, val readings: List<IntArray>, val bonus: Float = ADDED_BONUS)

    /** [at]: where [run] starts in the text. */
    class Span(val run: String, val start: Int, val end: Int, val word: Word, val at: Int = 0) {
        val text: String get() = run.substring(start, end)

        internal val from: Int get() = at + start
        internal val to: Int get() = at + end
    }

    /** On the engine's thread (EngineAssets). */
    interface Dictionary {
        fun isWord(text: String): Boolean

        fun isCommon(text: String): Boolean

        /** One character a reading, null if the dictionary has no such word. */
        fun wordReading(text: String): IntArray?
    }

    private val byFirst: Map<Int, List<Word>> = HashMap<Int, MutableList<Word>>().also { map ->
        for (w in words) for (r in w.readings[0]) map.getOrPut(r) { ArrayList() }.let { if (w !in it) it += w }
    }

    private val rank: Map<Word, Int> = words.withIndex().associate { (i, w) -> w to i }

    val isEmpty: Boolean get() = words.isEmpty()

    /** The same words with an added word's bonus [added] and a learned one's [learned], for tuning them (`ime-eval voice-post --bonus`). */
    fun withBonus(added: Float, learned: Float): VoiceHomophones = VoiceHomophones(
        words.map { w -> Word(w.text, w.readings, if (w.bonus == ADDED_BONUS) added else if (w.bonus == LEARNED_BONUS) learned else w.bonus) },
        own, chars, this.added,
    )

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

    /** With [text] added by hand after the others added, at [bonus]; the rest not read again. */
    internal fun withAdded(text: String, readings: List<IntArray>, bonus: Float = ADDED_BONUS): VoiceHomophones {
        val i = words.indexOfFirst { it.text == text }
        if (i in 0 until added && words[i].bonus == bonus) return this
        val all = words.filterTo(ArrayList()) { it.text != text }
        val before = if (i in 0 until added) added - 1 else added
        all.add(before, Word(text, readings, bonus))
        return VoiceHomophones(all, own + text, chars, before + 1)
    }

    /**
     * [text] with the user's words put in where the word model, after [before], takes them: a way of writing it wins
     * over [text] when its log10 probability plus each word's [Word.bonus] is higher. [spans] are those [allowed];
     * [ALWAYS] words go in first, unasked. Singles, then pairs and so on, [MAX_CHOICES] at most; none that makes a
     * [blocked] word. [logProbs] scores whole texts, the first [text] with the [ALWAYS] ones in.
     */
    fun pick(text: String, spans: List<Span>, blocked: List<String>, logProbs: (List<String>) -> FloatArray): String {
        val base = text.toCharArray()
        val hard = ArrayList<Span>()
        for (span in spans) {
            if (span.word.bonus != ALWAYS || hard.any { it.overlaps(span) }) continue
            val out = base.copyOf()
            span.word.text.toCharArray(out, span.from)
            if (!makesBlocked(String(out), listOf(span), blocked)) {
                span.word.text.toCharArray(base, span.from)
                hard += span
            }
        }
        val first = String(base)
        val free = spans.filter { it.word.bonus != ALWAYS && hard.none { h -> h.overlaps(it) } }.sortedBy { it.from }
        val choices = ArrayList<Pair<String, Float>>()
        for (taken in combinations(free)) {
            val out = base.copyOf()
            for (span in taken) span.word.text.toCharArray(out, span.from)
            val written = String(out)
            if (!makesBlocked(written, taken, blocked)) choices += written to taken.sumOf { it.word.bonus.toDouble() }.toFloat()
        }
        if (choices.isEmpty()) return first
        val lp = logProbs(listOf(first) + choices.map { it.first })
        var best = first
        var bestScore = lp[0]
        for ((i, choice) in choices.withIndex()) {
            val score = lp[i + 1] + choice.second
            if (score > bestScore) {
                best = choice.first
                bestScore = score
            }
        }
        return best
    }

    // a word put in may join what is around it into a blocked one
    private fun makesBlocked(text: String, taken: List<Span>, blocked: List<String>): Boolean = blocked.any { b ->
        b.isNotEmpty() && taken.any { span ->
            var i = text.indexOf(b, maxOf(0, span.from - b.length + 1))
            var hit = false
            while (i in 0 until span.to && !hit) {
                hit = i + b.length > span.from
                i = text.indexOf(b, i + 1)
            }
            hit
        }
    }

    companion object {
        val NONE = VoiceHomophones(emptyList(), emptySet(), { null })

        const val LEARNED_MIN = 3f

        /** Added by hand or with 「记住」: past this the model's doubt lets wrong ones in, short stretches and names most. */
        const val ADDED_BONUS = 1.5f

        /** Learned by typing, never asked for in voice: the fewest wrongly put in. */
        const val LEARNED_BONUS = 1f

        /** 「总是换」: the model is not asked. */
        const val ALWAYS = Float.POSITIVE_INFINITY

        /** Ways of writing one text scored at most: a sentence of many stretches costs a bounded time. */
        const val MAX_CHOICES = 16

        private fun Span.overlaps(o: Span) = from < o.to && o.from < to

        /** Of [spans] by where they start: those not overlapping, singles first, then by how many, [MAX_CHOICES] at most. */
        internal fun combinations(spans: List<Span>): List<List<Span>> {
            val out = ArrayList<List<Span>>()
            val taken = ArrayList<Span>()
            fun walk(from: Int, k: Int) {
                if (taken.size == k) {
                    out += taken.toList()
                    return
                }
                for (i in from until spans.size) {
                    if (out.size >= MAX_CHOICES) return
                    if (taken.isNotEmpty() && spans[i].from < taken[taken.size - 1].to) continue
                    taken += spans[i]
                    walk(i + 1, k)
                    taken.removeAt(taken.size - 1)
                }
            }
            for (k in 1..spans.size) {
                val had = out.size
                walk(0, k)
                // none of k: none of more
                if (out.size == had || out.size >= MAX_CHOICES) break
            }
            return out
        }

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
            // a table's remembered word is never put in (its code is no reading), yet is the user's own, never replaced
            val taking = mine.filter { it.table.isEmpty() }.sortedWith(compareBy<UserWord> { it.kind != UserWord.Kind.ADDED }.thenByDescending { it.count }).asSequence()
                .filter { it.kind == UserWord.Kind.ADDED || it.count > LEARNED_MIN }
                .filter { w -> w.text.length >= 2 && w.text.all(::isHan) }
                .distinctBy { it.text }
                .mapNotNull { w -> readingsOf(w)?.takeIf { r -> r.size == w.text.length && r.all { it.isNotEmpty() } }?.let { Word(w.text, it, bonusOf(w)) } }
                .toList()
            if (taking.isEmpty()) return NONE
            val added = mine.filter { it.kind == UserWord.Kind.ADDED }.mapTo(HashSet()) { it.text }
            return VoiceHomophones(taking, mine.mapTo(HashSet()) { it.text }, charReadings, taking.count { it.text in added })
        }

        private fun bonusOf(w: UserWord) = when {
            w.kind == UserWord.Kind.LEARNED -> LEARNED_BONUS
            w.voiceAlways -> ALWAYS
            else -> ADDED_BONUS
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
