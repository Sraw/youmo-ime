/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What of a stretch the recognizer heard is typed (VoiceEngine.recognize): the best of its
 * hypotheses by the pinyin engine's model too ([VoiceRerank]); of them, that first, the first with
 * none of the words the user blocked standing as a word in it, below it none with them at all
 * ([VoiceBlocking]); if each has one,
 * the stretch searched again with them blocked, and of that search's hypotheses the first with
 * none standing, below its first too, its best if each still has one.
 */
object VoiceChoice {

    /** What the pinyin engine knows of Chinese, asked of it on its thread (VoiceEngine.language). */
    interface Language {
        /** where each of [texts] splits into words (Engines.wordBoundaries), none for some if it cannot say */
        suspend fun boundaries(texts: List<String>): Map<String, IntArray>

        /** how likely each of [texts] is (Engines.logProbs), null if it cannot say */
        suspend fun logProbs(texts: List<String>): FloatArray?
    }

    /**
     * What the recognizer heard of a stretch: its hypotheses, best first, with their [scores]
     * (none for some), and its best as it writes it, [text], the one hypothesis if it gives none.
     */
    class Heard(val nbest: List<String>, val scores: FloatArray, val text: String)

    /**
     * The text to type of [heard], [blocked] the words the user blocked as written
     * (VoiceHotwords.Words.blockedWords); [searchBlocked] hears the stretch again with them
     * blocked in the search.
     */
    suspend fun choose(heard: Heard, blocked: List<String>, language: Language, searchBlocked: suspend () -> Heard): String {
        val hypotheses = VoiceRerank.distinct(
            heard.nbest.indices.map { VoiceRerank.Hypothesis(heard.nbest[it], heard.scores.getOrElse(it) { 0f }) }
                .ifEmpty { listOf(VoiceRerank.Hypothesis(heard.text, 0f)) },
        )
        // the best first, the rest as the recognizer has them
        val candidates = VoiceRerank.candidates(hypotheses)
        val best = if (candidates.size < 2) hypotheses[0] else {
            VoiceRerank.best(candidates, language.logProbs(candidates.map { VoiceRerank.bare(it.text) }))
        }
        val texts = listOf(best.text) + hypotheses.filter { it !== best }.map { it.text }
        if (blocked.isEmpty()) return texts[0]
        firstUnblocked(texts, blocked, language)?.let { return it }
        val again = searchBlocked()
        // the search blocks a word as the model spells it: written in other tokens it can still win;
        // its best, when typed, as it writes it ([Heard.text]), as with it in each
        return firstUnblocked(again.nbest.ifEmpty { listOf(again.text) }, blocked, language, first = again.text, last = true) ?: again.text
    }

    // the first of nbest with no blocked word standing in it (VoiceBlocking.pick, [last] as there),
    // only the splits it reads asked for; the first of all as [first], null if none is
    private suspend fun firstUnblocked(nbest: List<String>, blocked: List<String>, language: Language, first: String = nbest[0], last: Boolean = false): String? {
        if (VoiceBlocking.suspects(nbest, blocked).isEmpty()) return first
        // below the first, short of the last search, one with a blocked word at all is not taken: its split unread
        val split = VoiceBlocking.suspects(if (last) nbest else nbest.take(1), blocked)
        val boundaries = if (split.isEmpty()) emptyMap() else language.boundaries(split)
        return VoiceBlocking.pick(nbest, blocked, boundaries, last)?.let { if (it == 0) first else nbest[it] }
    }
}
