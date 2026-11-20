/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Blocked words as typing blocks them: a word, not a run of characters (开饭 blocked in the recognizer's search took
 * 开饭店 and 打开饭盒). Hypotheses are split by the pinyin model's likeliest words; the first with no blocked word
 * standing as words is written. If each has one, the search runs again with them blocked (VoiceChoice).
 */
object VoiceBlocking {

    /** Typed without the recognizer's spaces; those with a blocked word in them at all, the only ones to split. */
    fun suspects(nbest: List<String>, blocked: List<String>): List<String> =
        nbest.map { typed(it) }.filter { text -> blocked.any { it in text } }.distinct()

    /**
     * The first in which none of [blocked] stands as words; null if none. A suspect with no boundaries counts as having
     * one. Below the first, only one without them at all (今天气很好 for 今天天气很好 with 天气 is a worse hearing);
     * the [last] search takes one standing-free below its first.
     */
    fun pick(nbest: List<String>, blocked: List<String>, boundaries: Map<String, IntArray>, last: Boolean = false): Int? =
        nbest.indices.firstOrNull { i ->
            val text = typed(nbest[i])
            blocked.none { word -> word in text && ((i > 0 && !last) || stands(text, word, boundaries[text])) }
        }

    // VoiceText takes out the spaces X-ASR puts by Chinese, and a blocked word is all Chinese
    private fun typed(text: String) = text.filterNot { it.isWhitespace() }

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
