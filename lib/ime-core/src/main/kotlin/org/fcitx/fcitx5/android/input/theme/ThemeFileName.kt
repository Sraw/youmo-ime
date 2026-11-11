/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

/**
 * A theme file's name: `<name>.theme`, a zip inside, so it is not taken for any zip (the user,
 * 2026-11-04). Only that is imported: a `.zip` an earlier 0.5.1 or the page exported is renamed first.
 * Other input methods' skins are imported too, by theirs ([SKIN_EXTENSIONS]: Baidu's `.bds` and `.bdi`,
 * Sogou's `.ssf`), and converted (dev/design/060-skin-import.md).
 */
object ThemeFileName {
    const val EXTENSION = "theme"

    /** The extensions of the skins the import converts. */
    val SKIN_EXTENSIONS = setOf("bds", "bdi", "ssf")

    /** Whether [name] (a picked file's display name) is a theme file's, the extension's case aside. */
    fun isThemeFile(name: String?): Boolean {
        if (name == null) return false
        val dot = name.lastIndexOf('.')
        return dot > 0 && name.substring(dot + 1).equals(EXTENSION, ignoreCase = true)
    }

    /** Whether [name] is a skin's of another input method, converted as it is imported. */
    fun isSkinFile(name: String?): Boolean {
        val dot = name?.lastIndexOf('.') ?: return false
        return dot > 0 && name.substring(dot + 1).lowercase() in SKIN_EXTENSIONS
    }

    /** [name] without its extension: a skin's theme is named so where the skin names itself not. */
    fun base(name: String): String = name.substringAfterLast('/').let { n -> n.lastIndexOf('.').let { if (it > 0) n.substring(0, it) else n } }

    /** The name an exported theme [themeName] is offered under. */
    fun of(themeName: String) = "$themeName.$EXTENSION"
}
