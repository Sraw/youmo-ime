/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

/**
 * English text as the English model reads it: runs of words. A run starts a sentence after a full
 * stop, a question or exclamation mark, a line break or the start of the text; it starts no
 * sentence after what is no word the keyboard types as one (a number, a symbol, a word with a
 * letter past ASCII), which breaks the run. Commas, quotes, brackets and dashes keep it going.
 *
 * The data is counted as this splits the pages (lib/ime-dict-tool), and the engine takes its
 * context from the text before the cursor as this splits it: the two must agree. A change here
 * reaches the shipped model only at the next `english-data` release; until then the engine reads its
 * context by the new split from a model counted by the old, so a change must do no harm with either.
 */
object EnglishText {

    /** The word before a sentence's first: the model's `<s>`. */
    const val START = "<s>"

    /** The longest word: anything longer is no word (a hash, a run of letters typed for fun). */
    const val MAX_WORD = 24

    // a full stop after these ends no sentence: Mr. Smith, Dr. Who, vs. them. Not no or etc, which end
    // one more often than not (I said no.), and No. 5 breaks the run at its number all the same
    private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "prof", "inc", "ltd", "co", "vol", "fig")

    private const val ENDS = "!?…\n\r\u000B\u000C\u2028\u2029\u0085"
    private const val SEPARATORS = " \t,;:\"“”()[]-–— /*"

    /**
     * Calls [run] for each run of words of [text], in order: its words (a list it may keep) and
     * whether it starts a sentence.
     */
    fun runs(text: CharSequence, run: (words: List<String>, sentence: Boolean) -> Unit) {
        Splitter(sentence = true, run).apply {
            for (i in text.indices) char(text, i)
            breakRun(sentence = false)
        }
    }

    /**
     * The words the next word typed after [before] follows, the nearest last, at most two: [START]
     * first at a sentence's start, none after what breaks a run (or where [before] ends inside a
     * word, which the next key goes on with). Only the last [CONTEXT_CHARS] are looked at; [before]
     * starts a sentence only if [start], the text's own start (a field's), not a piece of it.
     */
    fun context(before: CharSequence, start: Boolean = true): List<String> {
        val from = maxOf(0, before.length - CONTEXT_CHARS)
        // inside a word: what follows is the word's, not a word after it
        if (before.length > from && before.last().let { it.isAsciiLetter() || it == '\'' }) return emptyList()
        // cut short, the text's first run starts no sentence that is known
        val splitter = Splitter(sentence = start && from == 0) { _, _ -> }
        for (i in from until before.length) splitter.char(before, i)
        splitter.endWord()
        val words = if (splitter.sentence) listOf(START) + splitter.current else splitter.current
        return words.takeLast(2)
    }

    /**
     * Whether [text] ends a sentence as [context] reads it: a full stop that ends one (no initial's
     * or abbreviation's), a question or exclamation mark, an ellipsis or a line break last, spaces
     * after it or not (any Unicode space, U+00A0 too, as [context] separates words by); what
     * starts [text] starts no sentence of its own.
     */
    fun endsSentence(text: CharSequence): Boolean {
        val splitter = Splitter(sentence = false) { _, _ -> }
        for (i in text.indices) splitter.char(text, i)
        splitter.endWord()
        return splitter.sentence && splitter.current.isEmpty()
    }

    /** Whether [text] is a word as English types it: letters, an apostrophe or a hyphen between them, at most [MAX_WORD]. */
    fun isWord(text: String): Boolean =
        text.length in 1..MAX_WORD && text.first().isAsciiLetter() && text.all { it.isAsciiLetter() || it == '\'' || it == '-' }

    /** How much of the text before the cursor [context] looks at: enough for two words and what is before them. */
    const val CONTEXT_CHARS = 160

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private class Splitter(
        /** Whether the run being read starts a sentence. */
        var sentence: Boolean,
        private val run: (List<String>, Boolean) -> Unit,
    ) {
        val current = ArrayList<String>()
        private val word = StringBuilder()

        // the word has something in it that makes it none: the run breaks where it ends
        private var spoiled = false

        fun char(text: CharSequence, i: Int) {
            val c = text[i]
            when {
                c.isAsciiLetter() -> word.append(c)
                (c == '\'' || c == '\u2019') && word.isNotEmpty() && text.getOrNull(i + 1)?.isAsciiLetter() == true -> word.append('\'')
                c == '.' -> fullStop(text, i)
                c in ENDS -> breakRun(sentence = true)
                c in SEPARATORS || c == '\'' || c == '\u2019' || c == '\u2018' -> endWord()
                // any other Unicode space separates too (U+00A0 after a full stop), the line breaks being ENDS
                c.isWhitespace() -> endWord()
                else -> spoiled = true
            }
        }

        private fun fullStop(text: CharSequence, i: Int) {
            val next = text.getOrNull(i + 1)
            when {
                // e.g, example.com, U.S: no word the keyboard types whole
                next != null && (next.isAsciiLetter() || next.isDigit()) -> spoiled = true
                // an initial (J. Smith; not I, as in So do I.) or an abbreviation: the sentence goes on
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

        /** Ends the run: the next starts a sentence or not as the last break before it says. */
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
