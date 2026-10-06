/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Blocked words in what was heard, as typing blocks them: a word, not a run of characters. The
 * recognizer writes characters and knows no words, so 开饭 blocked in its search would take 开饭店
 * and 打开饭盒 with it. Instead its hypotheses (best first) are split into words as the pinyin
 * engine's model finds likeliest (TextWords.boundaries), and the first in which no blocked word
 * stands as words of its own is written: starting and ending where words do. If each has one, the
 * user said it, and the search is run again with the words blocked in it (VoiceListener).
 */
object VoiceBlocking {

    /** Of [nbest], those with a blocked word in them at all: the only ones to split. */
    fun suspects(nbest: List<String>, blocked: List<String>): List<String> =
        nbest.filter { text -> blocked.any { it in text } }.distinct()

    /**
     * The first of [nbest] in which none of [blocked] stands as words, given [boundaries] of the
     * [suspects] (offsets between words, as TextWords.boundaries); null if none is. A suspect
     * with no boundaries (the engine could not split it) is taken to have one.
     */
    fun pick(nbest: List<String>, blocked: List<String>, boundaries: Map<String, IntArray>): Int? =
        nbest.indices.firstOrNull { i ->
            val text = nbest[i]
            blocked.none { word -> word in text && stands(text, word, boundaries[text]) }
        }

    // some occurrence of word starts and ends between words
    private fun stands(text: String, word: String, boundaries: IntArray?): Boolean {
        if (boundaries == null) return true
        var at = text.indexOf(word)
        while (at >= 0) {
            if (boundaries.binarySearch(at) >= 0 && boundaries.binarySearch(at + word.length) >= 0) return true
            at = text.indexOf(word, at + 1)
        }
        return false
    }
}
