/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeThumbnailUi.State
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The marks on the theme list after a theme is deleted or edited: ThemeManager reports no change
 * for either unless it was the active theme, so a mark left on the wrong row stays there.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeListAdapterTest {

    private fun theme(name: String) = ThemePreset.MaterialLight.deriveCustomNoBackground(name)

    private val a = theme("a")
    private val x = theme("x")
    private val y = theme("y")
    private val b = theme("b")

    private fun adapter(vararg themes: Theme) = object : ThemeListAdapter() {
        override fun onAddNewTheme() = Unit
        override fun onSelectTheme(theme: Theme) = Unit
        override fun onEditTheme(theme: Theme.Custom) = Unit
        override fun onExportTheme(theme: Theme.Custom) = Unit
    }.apply { setThemes(themes.toList()) }

    /** Each theme's name and mark, in the list's order. */
    private fun ThemeListAdapter.marks() =
        entries.mapIndexed { i, theme -> theme.name to stateAt(i + ThemeListAdapter.OFFSET) }

    @Test
    fun deletingAThemeAfterTheActiveOneLeavesTheMarkWhereItWas() {
        val list = adapter(a, x, y, b).apply { setSelectedThemes(a) }
        list.removeTheme("b")
        assertEquals(listOf("a" to State.Selected, "x" to State.Normal, "y" to State.Normal), list.marks())
        list.removeTheme("x")
        assertEquals(listOf("a" to State.Selected, "y" to State.Normal), list.marks())
    }

    @Test
    fun deletingAThemeBeforeTheMarkedOnesMovesTheirMarksUpWithThem() {
        val list = adapter(a, x, y, b).apply { setSelectedThemes(b, light = x, dark = y) }
        list.removeTheme("a")
        assertEquals(listOf("x" to State.LightMode, "y" to State.DarkMode, "b" to State.Selected), list.marks())
    }

    @Test
    fun deletingTheActiveThemeLeavesNoRowMarked() {
        val list = adapter(a, x, y, b).apply { setSelectedThemes(x) }
        list.removeTheme("x")
        assertEquals(listOf("a" to State.Normal, "y" to State.Normal, "b" to State.Normal), list.marks())
    }

    @Test
    fun editingAThemeMovesItFirstAndTheMarksOfThoseItPassedWithThem() {
        val list = adapter(a, x, y, b).apply { setSelectedThemes(a, light = x, dark = y) }
        list.replaceTheme(theme("b"))
        assertEquals(
            listOf("b" to State.Normal, "a" to State.Selected, "x" to State.LightMode, "y" to State.DarkMode),
            list.marks()
        )
        list.replaceTheme(theme("x"))
        assertEquals(
            listOf("x" to State.LightMode, "b" to State.Normal, "a" to State.Selected, "y" to State.DarkMode),
            list.marks()
        )
    }
}
