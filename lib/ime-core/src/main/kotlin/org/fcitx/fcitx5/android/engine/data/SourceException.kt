/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.io.BufferedReader

class SourceException(val source: String, val line: Int, message: String, cause: Throwable? = null) :
    Exception("$source:$line: $message", cause)

/** Calls [block] per line with its 1-based number, wrapping failures as [SourceException]. Drops a leading BOM. */
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
