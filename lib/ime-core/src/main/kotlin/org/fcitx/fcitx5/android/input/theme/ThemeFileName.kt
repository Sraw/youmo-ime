/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

/** `<name>.theme`, a zip inside, so it is not taken for any zip; an old `.zip` export is renamed first. Other IMEs' skins ([SKIN_EXTENSIONS]) are converted. */
object ThemeFileName {
    const val EXTENSION = "theme"

    val SKIN_EXTENSIONS = setOf("bds", "bdi", "ssf", "flex", "yaml", "yml", "zip")

    /** A picked file's display name; the extension's case aside. */
    fun isThemeFile(name: String?): Boolean {
        if (name == null) return false
        val dot = name.lastIndexOf('.')
        return dot > 0 && name.substring(dot + 1).equals(EXTENSION, ignoreCase = true)
    }

    /** Converted as it is imported. */
    fun isSkinFile(name: String?): Boolean {
        val dot = name?.lastIndexOf('.') ?: return false
        return dot > 0 && name.substring(dot + 1).lowercase() in SKIN_EXTENSIONS
    }

    /** A Trime theme's: read as text, not unpacked. */
    fun isYaml(name: String?): Boolean = name != null && extension(name).let { it == "yaml" || it == "yml" }

    /** A skin's download, or a theme exported under the old name. */
    fun isZip(name: String?): Boolean = name != null && extension(name) == "zip"

    private fun extension(name: String) = name.lastIndexOf('.').let { if (it > 0) name.substring(it + 1).lowercase() else "" }

    /** A skin's theme is named so where the skin names itself not. */
    fun base(name: String): String = name.substringAfterLast('/').let { n -> n.lastIndexOf('.').let { if (it > 0) n.substring(0, it) else n } }

    fun of(themeName: String) = "$themeName.$EXTENSION"
}
