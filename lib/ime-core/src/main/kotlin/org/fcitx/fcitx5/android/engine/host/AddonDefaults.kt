/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.table.IniText

/**
 * What the app wants of fcitx's own addons where their defaults differ and the app shows no page
 * for them: written into their configs before fcitx reads them, every start.
 */
object AddonDefaults {

    /**
     * punctuation.conf as [text] with Chinese punctuation after a letter or digit too, null where
     * it is so already: the addon's default turned `,` after `a` or `1` into English, and the
     * user had to delete it and type it again. The one option covers `.` as well, so the letter
     * keyboard's `3.14` reads `3。14`; the number keyboard's `.`, a keypad key, is never converted.
     */
    fun punctuation(text: String): String? = withKey(text, "HalfWidthPuncAfterLetterOrNumber", "False")

    // [key]=[value] among the lines before the first section, the others as they were
    private fun withKey(text: String, key: String, value: String): String? {
        val lines = text.lines().toMutableList()
        val top = lines.indexOfFirst { it.trim().startsWith("[") }.let { if (it < 0) lines.size else it }
        val at = (0 until top).firstOrNull { IniText.key(lines[it]) == key }
        if (at != null && IniText.value(lines[at]) == value) return null
        if (at != null) lines[at] = "$key=$value" else lines.add(0, "$key=$value")
        return lines.joinToString("\n").let { if (it.endsWith("\n")) it else "$it\n" }
    }
}
