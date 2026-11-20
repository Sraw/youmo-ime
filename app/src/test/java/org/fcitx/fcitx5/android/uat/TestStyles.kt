/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyleUnder

/** What is under is written first: the service and the input view are told of the style. */
object TestStyles {

    fun set(style: KeyboardStyle, landscape: Boolean? = null) {
        val under = if (style == KeyboardStyle.SPLIT) KeyboardStyleUnder.SPLIT else KeyboardStyleUnder.NORMAL
        for (l in shapes(landscape)) {
            under(l).setValue(under.name)
            style(l).setValue(style.name)
        }
    }

    fun clear(landscape: Boolean? = null) {
        for (l in shapes(landscape)) {
            under(l).setValue("")
            style(l).setValue("")
        }
    }

    fun stored(landscape: Boolean): KeyboardStyle? = KeyboardStyle.entries.firstOrNull { it.name == style(landscape).getValue() }

    private fun shapes(landscape: Boolean?) = landscape?.let(::listOf) ?: listOf(false, true)

    private fun style(landscape: Boolean) = ThemeManager.prefs.run { if (landscape) keyboardStyleLandscape else keyboardStylePortrait }

    private fun under(landscape: Boolean) = ThemeManager.prefs.run { if (landscape) keyboardStyleUnderLandscape else keyboardStyleUnderPortrait }
}
