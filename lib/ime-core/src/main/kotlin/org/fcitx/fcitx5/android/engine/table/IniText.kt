/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.unescapeValue

/**
 * fcitx's INI, the format of its profile and `.conf` files, as fcitx reads it: `[section]`
 * headers, a section named as written between the brackets, `key=value` lines, `#` starting a
 * comment, a value quoted and escaped where fcitx writes it so. Keys and values are read trimmed.
 */
internal object IniText {

    /**
     * [text]'s sections in order, each name with its lines as written, comments too, blank ones
     * left out: for a reader that writes them back. The lines before the first header come first,
     * under "".
     */
    fun sections(text: String): List<Pair<String, List<String>>> {
        val out = arrayListOf("" to ArrayList<String>())
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[") && trimmed.endsWith("]") -> out += trimmed.substring(1, trimmed.length - 1) to ArrayList<String>()
                trimmed.isNotEmpty() -> out.last().second += line
            }
        }
        return out
    }

    /** The key of [line], one of a section's: null for a comment or a line with none. */
    fun key(line: String): String? {
        val trimmed = line.trim()
        val eq = trimmed.indexOf('=')
        return if (eq > 0 && !trimmed.startsWith("#")) trimmed.substring(0, eq).trim() else null
    }

    /** The value of [line], one with a [key]: unquoted and unescaped. */
    fun value(line: String): String = unescapeValue(line.substringAfter('=').trim())
}
