/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What of a heard stretch is typed: the hypotheses reranked ([VoiceRerank]), the first with no blocked word standing in
 * it ([VoiceBlocking]); if each has one, the stretch searched again with them blocked. Before that, those with a
 * character 生僻字屏蔽 holds back are dropped, unless each has one.
 */
object VoiceChoice {

    /** Asked of the pinyin engine on its thread (VoiceEngine.language). */
    interface Language {
        /** Engines.wordBoundaries; none for some if it cannot say. */
        suspend fun boundaries(texts: List<String>): Map<String, IntArray>

        /** Engines.logProbs; null if it cannot say. */
        suspend fun logProbs(texts: List<String>): FloatArray?

        /** Engines.voicePick; null if it cannot say. */
        suspend fun pick(homophones: VoiceHomophones, text: String, before: String, blocked: List<String>): String? = null

        /** Engines.voiceStretches: a talk's stretches as kept, for 记住 to judge a word where voice did. */
        suspend fun stretches(texts: List<String>) {}
    }

    /** [scores] none for some; [text] the best as it writes it, the one hypothesis if it gives none. */
    class Heard(val nbest: List<String>, val scores: FloatArray, val text: String)

    /** [searchBlocked] hears the stretch again with [blocked] blocked in the search. */
    suspend fun choose(
        heard: Heard,
        blocked: List<String>,
        language: Language,
        held: (String) -> Boolean = { false },
        searchBlocked: suspend () -> Heard,
    ): String {
        val hypotheses = VoiceRerank.distinct(
            heard.nbest.indices.map { VoiceRerank.Hypothesis(heard.nbest[it], heard.scores.getOrElse(it) { 0f }) }
                .ifEmpty { listOf(VoiceRerank.Hypothesis(heard.text, 0f)) },
        )
        // the best first, the rest as the recognizer has them
        val candidates = VoiceRerank.candidates(hypotheses)
        val best = if (candidates.size < 2) hypotheses[0] else {
            VoiceRerank.best(candidates, language.logProbs(candidates.map { VoiceRerank.bare(it.text) }))
        }
        // held back whether or not the user blocked anything
        val texts = unheld(listOf(best.text) + hypotheses.filter { it !== best }.map { it.text }, held)
        if (blocked.isEmpty()) return texts[0]
        firstUnblocked(texts, blocked, language)?.let { return it }
        val again = searchBlocked()
        val nbest = unheld(again.nbest.ifEmpty { listOf(again.text) }, held)
        val first = again.text.takeUnless(held) ?: nbest[0]
        // the search blocks a word as the model spells it: written in other tokens it can still win
        return firstUnblocked(nbest, blocked, language, first = first, last = true) ?: first
    }

    /**
     * [text], a stretch [choose] chose, with the user's words put in where the word model after [before] takes them
     * ([VoiceHomophones.pick]); as it is if none reads alike or the engine cannot say: never put in unasked.
     */
    suspend fun pick(text: String, before: String, homophones: VoiceHomophones, blocked: List<String>, language: Language): String =
        if (homophones.candidates(text).isEmpty()) text else language.pick(homophones, text, before, blocked) ?: text

    /**
     * One talk's user words: each closed stretch [chosen] after the one kept before it, what shows meanwhile by
     * [shows] ([Live]), the stretches kept for 记住's judging ([stretches]).
     */
    class Talk(
        private val homophones: VoiceHomophones,
        private val blocked: List<String>,
        private val language: Language,
        launch: (suspend () -> Unit) -> Unit,
        show: suspend (String) -> Unit,
        /** Asked for what shows. */
        liveLanguage: Language = language,
        /** The words put in, closed or shown, for the polish to keep ([VoicePolishStream]). */
        private val putIn: MutableSet<String> = LinkedHashSet(),
    ) {
        private val live = Live(homophones, blocked, liveLanguage, launch, show, putIn)
        private var before = ""

        /** As kept, none empty. */
        val stretches = ArrayList<String>()

        suspend fun chosen(text: String): String = pick(text, before, homophones, blocked, language).also { putIn(homophones, text, it, putIn) }

        fun shows(text: String): String = live.pick(text, before)

        /** A stretch closed as it is typed: the next one's before; what showed of it no longer updated. */
        fun kept(text: String) {
            live.closed()
            if (text.isEmpty()) return
            before = text
            stretches += text
        }

        /** Nothing asked from here, no answer shown; what is known still shows. */
        fun stop() = live.stop()
    }

    /**
     * [pick] for what shows while a stretch is said, never waited for: each place a word may go is answered once for
     * the same few characters around it ([CONTEXT]) and the word, in a talk; a text with a place not answered shows
     * those known, the engine is asked on the side ([launch]: later, on the caller's one thread; one ask at a time, the
     * latest text only) and its answer [show]n if that text is still what shows. Once the engine could not say, none asked in
     * the talk.
     */
    class Live(
        private val homophones: VoiceHomophones,
        private val blocked: List<String>,
        private val language: Language,
        private val launch: (suspend () -> Unit) -> Unit,
        private val show: suspend (String) -> Unit,
        private val putIn: MutableSet<String> = LinkedHashSet(),
    ) {
        private var silent = false
        private var stopped = false
        private val answers = HashMap<String, Boolean>()
        // the stretch's last answer at a place, shown till its own comes: the word does not come and go as it grows
        private val lastAt = HashMap<Pair<Int, String>, Boolean>()
        private var current: Pair<String, String>? = null
        private var wanted: Pair<String, String>? = null
        private var asking = false

        fun pick(text: String, before: String): String {
            current = null
            val spans = homophones.candidates(text)
            if (spans.isEmpty()) return text
            val out = text.toCharArray()
            val put = ArrayList<VoiceHomophones.Span>()
            var unknown = false
            for (span in spans) {
                val known = answers[key(text, before, span)]
                if (known == null) unknown = true
                if ((known ?: lastAt[span.from to span.word.text]) == true && put.none { it.from < span.to && span.from < it.to }) {
                    span.word.text.toCharArray(out, span.from)
                    put += span
                    putIn += span.word.text
                }
            }
            // stopped or silent, what is known still shows: a word put in does not go back to what was heard
            if (unknown && !silent && !stopped) {
                current = text to before
                wanted = text to before
                if (!asking) {
                    asking = true
                    launch { ask() }
                }
            }
            return String(out)
        }

        fun closed() {
            current = null
            wanted = null
            lastAt.clear()
        }

        fun stop() {
            stopped = true
            current = null
            wanted = null
        }

        private suspend fun ask() {
            try {
                while (!stopped) {
                    val (text, before) = wanted ?: return
                    wanted = null
                    val said = language.pick(homophones, text, before, blocked)
                    if (said == null) {
                        silent = true
                        return
                    }
                    for (span in homophones.candidates(text)) {
                        val put = said.regionMatches(span.from, span.word.text, 0, span.word.text.length)
                        answers[key(text, before, span)] = put
                        if (current == text to before) lastAt[span.from to span.word.text] = put
                    }
                    if (!stopped && current == text to before) {
                        putIn(homophones, text, said, putIn)
                        show(said)
                    }
                }
            } finally {
                asking = false
            }
        }

        // the word model reads a place by what is around it: the same characters about it, the same answer
        private fun key(text: String, before: String, span: VoiceHomophones.Span): String {
            val tail = before.takeLast(CONTEXT)
            val all = tail + text
            val from = maxOf(0, tail.length + span.from - CONTEXT)
            val to = minOf(all.length, tail.length + span.to + CONTEXT)
            return all.substring(from, to) + '\u0000' + (span.from + tail.length - from) + '\u0000' + span.word.text
        }

        private companion object {
            // about two words each side
            const val CONTEXT = 4
        }
    }

    /** Into [into], the words of [homophones] [said] has where [text] has what voice heard. */
    fun putIn(homophones: VoiceHomophones, text: String, said: String, into: MutableSet<String>) {
        if (said == text) return
        for (span in homophones.candidates(text)) {
            val word = span.word.text
            if (said.regionMatches(span.from, word, 0, word.length) && !text.regionMatches(span.from, word, 0, word.length)) into += word
        }
    }

    // the first alone if each has
    private fun unheld(texts: List<String>, held: (String) -> Boolean) = texts.filterNot(held).ifEmpty { texts.take(1) }

    // VoiceBlocking.pick, [last] as there; only the splits it reads asked for
    private suspend fun firstUnblocked(nbest: List<String>, blocked: List<String>, language: Language, first: String = nbest[0], last: Boolean = false): String? {
        if (VoiceBlocking.suspects(nbest, blocked).isEmpty()) return first
        // below the first, short of the last search, one with a blocked word at all is not taken: its split unread
        val split = VoiceBlocking.suspects(if (last) nbest else nbest.take(1), blocked)
        val boundaries = if (split.isEmpty()) emptyMap() else language.boundaries(split)
        return VoiceBlocking.pick(nbest, blocked, boundaries, last)?.let { if (it == 0) first else nbest[it] }
    }
}
