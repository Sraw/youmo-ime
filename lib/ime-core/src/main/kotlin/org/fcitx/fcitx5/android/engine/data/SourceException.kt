/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.io.BufferedReader

/** A problem in a text source, reported with where it is. */
class SourceException(val source: String, val line: Int, message: String, cause: Throwable? = null) :
    Exception("$source:$line: $message", cause)

/**
 * Spaces, tabs and a stray carriage return. Deliberately not [Char.isWhitespace]: that includes
 * the ideographic space U+3000, which code tables list as a character to type.
 */
fun Char.isSeparator() = this == ' ' || this == '\t' || this == '\r'

fun String.trimSeparators() = trim { it.isSeparator() }

/** [isSeparator]-separated fields; a run of separators counts as one. */
fun String.fields(): List<String> {
    val out = ArrayList<String>(4)
    var start = -1
    for (i in indices) {
        if (this[i].isSeparator()) {
            if (start >= 0) out += substring(start, i)
            start = -1
        } else if (start < 0) {
            start = i
        }
    }
    if (start >= 0) out += substring(start)
    return out
}

/**
 * [value] as fcitx writes one with spaces, quotes or line breaks in it: quoted, `\` escaping a
 * quote, a backslash or one of `n f r t v`, any other character escaped standing for itself.
 * Unchanged if it is not quoted, or a quote ends it early: then the quotes are its own.
 */
fun unescapeValue(value: String): String {
    if (value.length < 2 || value[0] != '"' || value[value.length - 1] != '"') return value
    val out = StringBuilder(value.length)
    var i = 1
    while (i < value.length - 1) {
        var c = value[i]
        if (c == '"') return value
        if (c == '\\') {
            i++
            c = value[i]
            c = ESCAPES[c] ?: c
            // the closing quote escaped: the value never closes
            if (i == value.length - 1) return value
        }
        out.append(c)
        i++
    }
    return out.toString()
}

private val ESCAPES = mapOf('n' to '\n', 'f' to '\u000C', 'r' to '\r', 't' to '\t', 'v' to '\u000B')

/** [value] as fcitx's `escapeForValue` writes it, which [unescapeValue] reads back. */
fun escapeValue(value: String): String {
    if (value.none { it in NEEDS_QUOTES }) return value
    val out = StringBuilder(value.length + 2).append('"')
    for (c in value) {
        val escape = ESCAPED[c]
        if (escape != null) out.append('\\').append(escape) else out.append(c)
    }
    return out.append('"').toString()
}

/**
 * [line] cut into values as fcitx's `consumeMaybeEscapedValue` cuts it at `FCITX_WHITESPACE`,
 * which libime's text dictionaries are read by: a value starting with a quote runs, unescaped, to
 * the quote closing it (what follows it starts the next value), and one whose quote never closes
 * is taken as it is, to the next whitespace, quote and all.
 */
fun splitValues(line: String): List<String> {
    val out = ArrayList<String>(4)
    var i = 0
    while (true) {
        while (i < line.length && line[i] in WHITESPACE) i++
        if (i == line.length) return out
        if (line[i] == '"') {
            val value = StringBuilder()
            var j = i + 1
            while (j < line.length && line[j] != '"') {
                if (line[j] == '\\' && j + 1 < line.length) {
                    j++
                    value.append(ESCAPES[line[j]] ?: line[j])
                } else {
                    value.append(line[j])
                }
                j++
            }
            if (j < line.length) {
                out += value.toString()
                i = j + 1
                continue
            }
        }
        val start = i
        i++
        while (i < line.length && line[i] !in WHITESPACE) i++
        out += line.substring(start, i)
    }
}

private const val WHITESPACE = "\u000C\n\r\t\u000B "

private const val NEEDS_QUOTES = "\u000C\r\t\u000B \"\\\n"
private val ESCAPED = ESCAPES.entries.associate { (k, v) -> v to k } + mapOf('"' to '"', '\\' to '\\')

/**
 * Calls [block] with each line and its 1-based number, turning a failure inside it into a
 * [SourceException] that points at the line. A leading byte-order mark is dropped.
 */
inline fun BufferedReader.forEachNumberedLine(source: String, block: (line: String, number: Int) -> Unit) {
    var number = 0
    while (true) {
        val raw = readLine() ?: break
        number++
        val line = if (number == 1) raw.removePrefix("\uFEFF") else raw
        try {
            block(line, number)
        } catch (e: SourceException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw SourceException(source, number, e.message ?: e.toString(), e)
        }
    }
}
