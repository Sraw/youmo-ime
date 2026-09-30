/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import java.io.BufferedReader

/** A problem in a text source, reported with where it is. */
class SourceException(val source: String, val line: Int, message: String, cause: Throwable? = null) :
    Exception("$source:$line: $message", cause)

/**
 * Spaces, tabs and a stray carriage return. Deliberately not [Char.isWhitespace]: that includes
 * the ideographic space U+3000, which code tables list as a character to type.
 */
internal fun Char.isSeparator() = this == ' ' || this == '\t' || this == '\r'

internal fun String.trimSeparators() = trim { it.isSeparator() }

/** [isSeparator]-separated fields; a run of separators counts as one. */
internal fun String.fields(): List<String> {
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
 * Calls [block] with each line and its 1-based number, turning a failure inside it into a
 * [SourceException] that points at the line. A leading byte-order mark is dropped.
 */
internal inline fun BufferedReader.forEachNumberedLine(source: String, block: (line: String, number: Int) -> Unit) {
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
