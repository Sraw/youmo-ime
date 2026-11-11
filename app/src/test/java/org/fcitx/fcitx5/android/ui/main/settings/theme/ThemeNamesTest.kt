/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.text.TextUtils
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.deriveCustomNoBackground
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The name under each card of the theme list (the owner, 0.6.0-beta2): what it says and how it is laid. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN")
class ThemeNamesTest {

    private fun shown(theme: Theme) = ThemeNames.shown(appContext, theme)

    @Test
    fun eachKindOfThemeIsNamedAsTheUserKnowsIt() {
        val builtin = ThemeManager.BuiltinThemes.associate { it.file to shown(it) }
        assertEquals(mapOf("monet" to "动态取色", "material" to "Material", "pixel" to "Pixel", "amoled-black" to "AMOLED 黑"), builtin)
        // a pack by its file, both versions of 简蓝 one name
        assertEquals(setOf("简蓝"), ThemeManager.BuiltinPackThemes.filter { it.file == "简蓝" }.map(::shown).toSet())
        assertEquals("国风·天青", shown(ThemeManager.BuiltinPackThemes.single { it.file == "国风·天青" }))
        assertEquals("我的蓝", shown(TestThemes.MaterialLight.deriveCustomNoBackground("我的蓝")))
        assertEquals("我的主题", shown(TestThemes.MaterialLight.deriveCustomNoBackground("1b4e28ba-2fa1-11d2-883f-0016d3cca427")))
    }

    @Test
    fun theCardShowsItsNameOnOneLineUnderIt() {
        val ui = ThemeThumbnailUi(appContext)
        val long = "一个名字很长很长很长很长很长很长很长很长的主题"
        ui.setTheme(TestThemes.MaterialLight.deriveCustomNoBackground(long))
        assertEquals(long, ui.name.text.toString())
        assertEquals(1, ui.name.maxLines)
        assertEquals(TextUtils.TruncateAt.END, ui.name.ellipsize)
        assertSame(ui.root, ui.name.parent)
        assertSame(ui.root, ui.card.parent)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, ui.name.importantForAccessibility)
        // the card above the name
        assertEquals(listOf(ui.card, ui.name), (0 until ui.root.childCount).map { ui.root.getChildAt(it) })
    }

    // several themes named by UUIDs: 我的主题, 我的主题 2, 3 by their UUIDs' order, whatever the list's
    @Test
    fun severalUnnamedThemesAreNumberedTheSameWayInAnyOrder() {
        val ids = listOf("c0000000-0000-0000-0000-000000000000", "a0000000-0000-0000-0000-000000000000", "b0000000-0000-0000-0000-000000000000")
        val themes = ids.map { TestThemes.MaterialLight.deriveCustomNoBackground(it) }
        val list = themes + TestThemes.MaterialLight.deriveCustomNoBackground("我的蓝")
        val names = { among: List<Theme> -> themes.associate { it.name to ThemeNames.shown(appContext, it, among) } }
        val expected = mapOf(ids[1] to "我的主题", ids[2] to "我的主题 2", ids[0] to "我的主题 3")
        assertEquals(expected, names(list))
        assertEquals(expected, names(list.reversed()))
        // the card reads (and TalkBack says) the same
        val ui = ThemeThumbnailUi(appContext)
        ui.setTheme(themes[0], list)
        assertEquals("我的主题 3", ui.name.text.toString())
    }

    // the name row grows with the font size set (at 2.0 its 12 sp line does not fit 24 dp)
    @Test
    fun theNameRowGrowsWithTheFontScale() {
        val normal = ThemeNames.rowHeight(appContext)
        val normalItem = ResponsiveThemeListView(appContext).itemHeight
        RuntimeEnvironment.setFontScale(2f)
        try {
            val large = ThemeNames.rowHeight(appContext)
            assertTrue("$normal < $large", normal < large)
            // its line (24 sp at 2.0) and room
            assertTrue(themeNameView(appContext).textSize * 1.4f <= large)
            assertEquals(normalItem - normal + large, ResponsiveThemeListView(appContext).itemHeight)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}
