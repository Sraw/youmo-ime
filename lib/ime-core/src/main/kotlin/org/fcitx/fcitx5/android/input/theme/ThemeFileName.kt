/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

/**
 * A theme file's name: `<name>.theme`, a zip inside, so it is not taken for any zip (the user,
 * 2026-11-04). Only that is imported: a `.zip` an earlier 0.5.1 or the page exported is renamed first.
 */
object ThemeFileName {
    const val EXTENSION = "theme"

    /** Whether [name] (a picked file's display name) is a theme file's, the extension's case aside. */
    fun isThemeFile(name: String?): Boolean {
        if (name == null) return false
        val dot = name.lastIndexOf('.')
        return dot > 0 && name.substring(dot + 1).equals(EXTENSION, ignoreCase = true)
    }

    /** The name an exported theme [themeName] is offered under. */
    fun of(themeName: String) = "$themeName.$EXTENSION"
}
