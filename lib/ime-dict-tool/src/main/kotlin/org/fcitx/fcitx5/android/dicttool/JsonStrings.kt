/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

/**
 * The strings of a JSON array of strings, as LCCC has a conversation a line
 * (`["你 好", "好 久 不见"]`); anything else in the line is an error.
 */
object JsonStrings {

    /** [strings] as such an array, what [parse] reads back. */
    fun format(strings: List<String>): String = strings.joinToString(",", "[", "]") { s ->
        buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }

    fun parse(line: String): List<String> {
        val out = ArrayList<String>()
        var i = skipSpace(line, 0)
        require(i < line.length && line[i] == '[') { "not a JSON array: $line" }
        i = skipSpace(line, i + 1)
        if (i < line.length && line[i] == ']') return out.also { requireEnd(line, i + 1) }
        while (true) {
            require(i < line.length && line[i] == '"') { "expected a string at $i: $line" }
            val text = StringBuilder()
            i = string(line, i + 1, text)
            out += text.toString()
            i = skipSpace(line, i)
            require(i < line.length) { "unterminated array: $line" }
            when (line[i]) {
                ',' -> i = skipSpace(line, i + 1)
                ']' -> return out.also { requireEnd(line, i + 1) }
                else -> throw IllegalArgumentException("expected , or ] at $i: $line")
            }
        }
    }

    /** Appends to [text] the string that starts at [from], just past its quote; @return where it ends, past its quote */
    private fun string(line: String, from: Int, text: StringBuilder): Int {
        var i = from
        while (true) {
            require(i < line.length) { "unterminated string: $line" }
            when (val c = line[i++]) {
                '"' -> return i
                '\\' -> {
                    require(i < line.length) { "unterminated escape: $line" }
                    val e = line[i++]
                    if (e == 'u') {
                        require(i + HEX <= line.length) { "short \\u escape: $line" }
                        text.append(line.substring(i, i + HEX).toInt(HEX_RADIX).toChar())
                        i += HEX
                    } else {
                        text.append(ESCAPES[e] ?: throw IllegalArgumentException("bad escape \\$e: $line"))
                    }
                }
                else -> text.append(c)
            }
        }
    }

    private fun skipSpace(line: String, from: Int): Int {
        var i = from
        while (i < line.length && line[i].isWhitespace()) i++
        return i
    }

    private fun requireEnd(line: String, from: Int) = require(skipSpace(line, from) == line.length) { "text after the array: $line" }

    private val ESCAPES = mapOf(
        '"' to '"', '\\' to '\\', '/' to '/', 'b' to '\b', 'f' to '\u000c', 'n' to '\n', 'r' to '\r', 't' to '\t',
    )
    private const val HEX = 4
    private const val HEX_RADIX = 16
}
