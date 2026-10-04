/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

/**
 * A few sentences each of [words] uses, for whoever decides whether it is a word
 * (dev/TRAINING-PLAN.md 12.5): a candidate alone says little of what it is, three sentences most
 * of it. Pages go in through [page]; [sentences] has what was found.
 *
 * A sentence is what lies between two ends ([ENDS]), [MIN_SENTENCE] to [MAX_SENTENCE] characters,
 * and is taken for a word only once, the first [perWord] different ones in the pages' order.
 * Words of up to four characters are found by a key of their chars, as [NewWords] counts them;
 * longer ones by the key of their first four, then compared whole.
 */
class Examples(words: Collection<String>, private val perWord: Int = PER_WORD) {

    private val short = HashMap<Long, String>()
    private val long = HashMap<Long, MutableList<String>>()
    private val found = HashMap<String, MutableList<String>>()

    init {
        require(perWord >= 1) { "per word $perWord" }
        for (word in words) {
            if (word.length < 2) continue
            if (word.length <= KEY_CHARS) short[key(word, 0, word.length)] = word
            else long.getOrPut(key(word, 0, KEY_CHARS)) { ArrayList() } += word
        }
    }

    fun page(text: String) {
        var start = 0
        for (i in 0..text.length) {
            if (i < text.length && text[i] !in ENDS) continue
            // the end goes with the sentence: 了吗？ reads better than 了吗
            val end = if (i < text.length) i + 1 else i
            val sentence = text.substring(start, end).trim()
            start = end
            if (sentence.length in MIN_SENTENCE..MAX_SENTENCE) sentence(sentence)
        }
    }

    private fun sentence(s: String) {
        for (i in 0 until s.length - 1) {
            var k = 0L
            for (n in 1..minOf(KEY_CHARS, s.length - i)) {
                k = (k shl Char.SIZE_BITS) or s[i + n - 1].code.toLong()
                if (n >= 2) short[k]?.let { take(it, s) }
            }
            if (i + KEY_CHARS <= s.length) {
                long[k]?.forEach { word -> if (s.startsWith(word, i)) take(word, s) }
            }
        }
    }

    private fun take(word: String, sentence: String) {
        val list = found.getOrPut(word) { ArrayList(perWord) }
        if (list.size < perWord && sentence !in list) list += sentence
    }

    /** Each word found with its sentences, in no order; a word never found is not there. */
    fun sentences(): Map<String, List<String>> = found

    companion object {
        const val PER_WORD = 3
        const val MIN_SENTENCE = 6
        const val MAX_SENTENCE = 80
        const val ENDS = "。！？!?；;…\n"
        private const val KEY_CHARS = 4

        private fun key(s: String, from: Int, length: Int): Long {
            var k = 0L
            for (i in from until from + length) k = (k shl Char.SIZE_BITS) or s[i].code.toLong()
            return k
        }
    }
}
