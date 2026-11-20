/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.core.FcitxUtils

/** Space, tab, stray CR. Not [Char.isWhitespace]: that includes U+3000, which code tables list as a character. */
fun Char.isSeparator() = this == ' ' || this == '\t' || this == '\r'

fun String.trimSeparators() = trim { it.isSeparator() }

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

/** Unquotes [value] as fcitx does; unchanged if unquoted or a quote ends it early (where [FcitxUtils.unescapeForValue] gives null). */
fun unescapeValue(value: String): String = FcitxUtils.unescapeForValue(value) ?: value

fun escapeValue(value: String): String = FcitxUtils.escapeForValue(value)

/** Splits [line] as fcitx's `consumeMaybeEscapedValue`: a quoted value runs unescaped to its close; one never closed is taken to the next whitespace. */
fun splitValues(line: String): List<String> {
    val out = ArrayList<String>(4)
    val value = StringBuilder()
    var i = 0
    while (true) {
        i = FcitxUtils.consumeMaybeEscapedValue(line, i, WHITESPACE, value)
        if (i < 0) return out
        out += value.toString()
    }
}

private const val WHITESPACE = "\u000C\n\r\t\u000B "
