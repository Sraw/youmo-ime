/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

/**
 * English text as the model reads it: runs of words, a sentence starting after . ? ! a line break or the start;
 * a number, symbol or non-ASCII letter breaks the run. The data is counted with this same split
 * (lib/ime-dict-tool), and a change reaches the model only at the next `english-data` release.
 */
object EnglishText {

    /** The model's `<s>`: before a sentence's first word. */
    const val START = "<s>"

    /** Anything longer is no word (a hash, letters typed for fun). */
    const val MAX_WORD = 24

    // a full stop after these ends no sentence (Mr. Smith); not no or etc, which end one more often than not
    private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "prof", "inc", "ltd", "co", "vol", "fig")

    private const val ENDS = "!?…\n\r\u000B\u000C\u2028\u2029\u0085"
    private const val SEPARATORS = " \t,;:\"“”()[]-–— /*"

    /** Calls [run] per run of words in order: its words (a list it may keep) and whether it starts a sentence. */
    fun runs(text: CharSequence, run: (words: List<String>, sentence: Boolean) -> Unit) {
        Splitter(sentence = true, run).apply {
            for (i in text.indices) char(text, i)
            breakRun(sentence = false)
        }
    }

    /** The words the next word follows, nearest last, at most two: [START] at a sentence start, none after a run break or inside a word. */
    fun context(before: CharSequence, start: Boolean = true): List<String> {
        val from = maxOf(0, before.length - CONTEXT_CHARS)
        if (before.length > from && before.last().let { it.isAsciiLetter() || it == '\'' }) return emptyList()
        val splitter = Splitter(sentence = start && from == 0) { _, _ -> }
        for (i in from until before.length) splitter.char(before, i)
        splitter.endWord()
        val words = if (splitter.sentence) listOf(START) + splitter.current else splitter.current
        return words.takeLast(2)
    }

    /** Letters, with an apostrophe or hyphen between them, at most [MAX_WORD]. */
    fun isWord(text: String): Boolean =
        text.length in 1..MAX_WORD && text.first().isAsciiLetter() && text.all { it.isAsciiLetter() || it == '\'' || it == '-' }

    const val CONTEXT_CHARS = 160

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private class Splitter(
        var sentence: Boolean,
        private val run: (List<String>, Boolean) -> Unit,
    ) {
        val current = ArrayList<String>()
        private val word = StringBuilder()

        private var spoiled = false

        fun char(text: CharSequence, i: Int) {
            val c = text[i]
            when {
                c.isAsciiLetter() -> word.append(c)
                (c == '\'' || c == '\u2019') && word.isNotEmpty() && text.getOrNull(i + 1)?.isAsciiLetter() == true -> word.append('\'')
                c == '.' -> fullStop(text, i)
                c in ENDS -> breakRun(sentence = true)
                c in SEPARATORS || c == '\'' || c == '\u2019' || c == '\u2018' -> endWord()
                // any other Unicode space separates too (U+00A0 after a full stop); line breaks are ENDS
                c.isWhitespace() -> endWord()
                else -> spoiled = true
            }
        }

        private fun fullStop(text: CharSequence, i: Int) {
            val next = text.getOrNull(i + 1)
            when {
                next != null && (next.isAsciiLetter() || next.isDigit()) -> spoiled = true
                // an initial (J. Smith; not I) or an abbreviation: the sentence goes on
                !spoiled && abbreviated() -> endWord()
                else -> breakRun(sentence = true)
            }
        }

        private fun abbreviated() = word.length == 1 && word[0] != 'I' || word.toString().lowercase() in ABBREVIATIONS

        fun endWord() {
            if (spoiled || word.length > MAX_WORD) {
                word.setLength(0)
                spoiled = false
                breakRun(sentence = false)
                return
            }
            if (word.isEmpty()) return
            current += word.toString()
            word.setLength(0)
        }

        fun breakRun(sentence: Boolean) {
            endWord()
            if (current.isNotEmpty()) {
                run(ArrayList(current), this.sentence)
                current.clear()
            }
            this.sentence = sentence
        }
    }
}
