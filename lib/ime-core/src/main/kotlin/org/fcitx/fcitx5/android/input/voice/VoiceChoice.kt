/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What of a stretch the recognizer heard is typed (VoiceEngine.choose): the best of its
 * hypotheses by the pinyin engine's model too ([VoiceRerank]); of them, that first, the first with
 * none of the words the user blocked standing as a word in it, below it none with them at all
 * ([VoiceBlocking]); if each has one,
 * the stretch searched again with them blocked, and of that search's hypotheses the first with
 * none standing, below its first too, its best if each still has one. Before any of that, those
 * with a character 生僻字屏蔽 holds back are left out, unless each has one (the first then, design §5.3).
 */
object VoiceChoice {

    /** What the pinyin engine knows of Chinese, asked of it on its thread (VoiceEngine.language). */
    interface Language {
        /** where each of [texts] splits into words (Engines.wordBoundaries), none for some if it cannot say */
        suspend fun boundaries(texts: List<String>): Map<String, IntArray>

        /** how likely each of [texts] is (Engines.logProbs), null if it cannot say */
        suspend fun logProbs(texts: List<String>): FloatArray?

        /** [VoiceHomophones.allowed] of each of [spans] (Engines.voiceHomophonesAllowed), null if it cannot say */
        suspend fun homophonesAllowed(spans: List<VoiceHomophones.Span>): BooleanArray? = null
    }

    /**
     * What the recognizer heard of a stretch: its hypotheses, best first, with their [scores]
     * (none for some), and its best as it writes it, [text], the one hypothesis if it gives none.
     */
    class Heard(val nbest: List<String>, val scores: FloatArray, val text: String)

    /**
     * The text to type of [heard], [blocked] the words the user blocked as written
     * (VoiceHotwords.Words.blockedWords), [held] whether a text has a character 生僻字屏蔽 holds back
     * (VoiceHotwords.Words.held); [searchBlocked] hears the stretch again with them blocked in the search.
     */
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
        // asked before the words blocked, which may be none: held back whether or not the user blocked anything
        val texts = unheld(listOf(best.text) + hypotheses.filter { it !== best }.map { it.text }, held)
        if (blocked.isEmpty()) return texts[0]
        firstUnblocked(texts, blocked, language)?.let { return it }
        val again = searchBlocked()
        val nbest = unheld(again.nbest.ifEmpty { listOf(again.text) }, held)
        val first = again.text.takeUnless(held) ?: nbest[0]
        // the search blocks a word as the model spells it: written in other tokens it can still win;
        // its best, when typed, as it writes it ([Heard.text]), as with it in each
        return firstUnblocked(nbest, blocked, language, first = first, last = true) ?: first
    }

    // [texts] but those [held] says have a character held back; the first alone if each has
    private fun unheld(texts: List<String>, held: (String) -> Boolean) = texts.filterNot(held).ifEmpty { texts.take(1) }

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
