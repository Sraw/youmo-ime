/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/**
 * The spec's 前文 (PLAN §2.1 `p()`, 「光标前最近的那个词」): the last word of a text, the same one
 * whether the text was just committed here or read back from the app, so 汪小明 typed and 汪小明 in
 * the field are one context. Of a run of Han characters, the last word of the reading [split]
 * gives it (the model's likeliest words, as 联想 reads the text before: 他点了他 ends with 他, not 了他;
 * uat-triage-pinyin T9-54), or one of the user's ([isMine]) that ends it and starts where a word of
 * that reading does (汪｜小明 read, 汪小明 theirs); without [split], the longest [isWord] knows,
 * else the last character. A trailing run of letters as typed (an English word, code: U-52). None ("") after punctuation, a line break, an emoji, digits, a URL or
 * an e-mail address: what follows those starts afresh. The text is [simplify]d first: the app's
 * may be traditional (chttrans, or the app's own), what is learned never is.
 */
class LastWord(
    private val isWord: (String) -> Boolean,
    private val simplify: (String) -> String = { it },
    /** Where a run of Han characters splits into words (`TextWords.boundaries`): 0, each end, its length. */
    private val split: ((String) -> IntArray)? = null,
    /** A word of the user's own the model has not (added by hand, formed): one word wherever the split puts its pieces. */
    private val isMine: (String) -> Boolean = isWord,
) {

    fun of(text: String): String {
        val t = simplify(text).trimEnd(' ')
        if (t.isEmpty()) return ""
        val last = t.codePointBefore(t.length)
        return when {
            Han.isHan(last) -> han(t)
            last < ASCII && Character.isLetterOrDigit(last) -> letters(t)
            else -> ""
        }
    }

    private fun han(t: String): String {
        var start = t.length
        var n = 0
        val longest = if (split == null) MAX_WORD else WINDOW
        while (start > 0 && n < longest) {
            val c = t.codePointBefore(start)
            if (!Han.isHan(c)) break
            start -= Character.charCount(c)
            n++
        }
        split?.let { return read(t.substring(start), it) }
        // the longest first: 汪小明 before 小明 before 明
        var from = start
        while (from < t.length) {
            val word = t.substring(from)
            if (isWord(word)) return word
            from += Character.charCount(t.codePointAt(from))
        }
        return t.substring(t.offsetByCodePoints(t.length, -1))
    }

    // [run]'s last word as [split] reads it, or the longest of the user's ending it at one of its starts
    private fun read(run: String, split: (String) -> IntArray): String {
        val ends = split(run)
        for (k in 0 until ends.size - 1) {
            val word = run.substring(ends[k])
            if (word.codePointCount(0, word.length) <= MAX_WORD && isMine(word)) return word
        }
        return run.substring(ends[ends.size - 2])
    }

    private fun letters(t: String): String {
        var start = t.length
        while (start > 0 && !t[start - 1].isWhitespace()) start--
        val token = t.substring(start)
        if (address(token)) return ""
        var run = token.length
        while (run > 0 && token[run - 1].code < ASCII && token[run - 1].isLetterOrDigit()) run--
        val word = token.substring(run)
        return if (word.any { it.isLetter() }) word else ""
    }

    // a URL or an e-mail address, or a domain: no word of the user's to go on from
    private fun address(token: String) =
        '@' in token || "://" in token || token.startsWith("www.", ignoreCase = true) || DOMAIN.matches(token)

    companion object {
        /** Words longer than this are not looked for. */
        const val MAX_WORD = 8

        // the run read with [split]: enough for the last words and what decides where they begin, as TextWords.lastTwo reads
        private const val WINDOW = 3 * MAX_WORD
        private const val ASCII = 0x80
        private val DOMAIN = Regex("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+/?\\S*")
    }
}

/** Han characters, by code point: not Character.UnicodeScript, which API 23 lacks. */
internal object Han {
    // CJK unified ideographs, extension A, the supplementary planes' and the compatibility ones
    fun isHan(c: Int) = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x323AF || c in 0xF900..0xFAFF

    fun all(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (!isHan(c)) return false
            i += Character.charCount(c)
        }
        return text.isNotEmpty()
    }

    /** Whether [text] has a Han character, one of the supplementary planes' (two UTF-16 units) too. */
    fun any(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (isHan(c)) return true
            i += Character.charCount(c)
        }
        return false
    }
}
