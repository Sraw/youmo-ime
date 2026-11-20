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

        /** Engines.voiceHomophonesAllowed; null if it cannot say. */
        suspend fun homophonesAllowed(spans: List<VoiceHomophones.Span>): BooleanArray? = null
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
