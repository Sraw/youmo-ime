/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.deriveCustomNoBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A slot's dialog (060-render-p3 T3 review): the theme picked is a theme, so the 浅/深 of its card shows
 * the other version unmarked and picks nothing; it once picked the version shown, unseen.
 */
@RunWith(RobolectricTestRunner::class)
class SimpleThemeListAdapterTest {

    private val mine = TestThemes.MaterialLight.deriveCustomNoBackground("mine")
    private val light = TestThemes.PixelLight
    private val dark = TestThemes.PixelDark

    private fun adapter() = SimpleThemeListAdapter(listOf(mine, light, dark, TestThemes.DeepBlue))

    @Test
    fun theOtherVersionOfTheCardPickedIsShownUnmarkedAndNotPicked() {
        val list = adapter().apply { select(light) }
        assertEquals(light, list.selectedTheme)
        assertTrue(list.checkedAt(1))
        list.switchVersion(1)
        assertEquals(dark.name, list.shownAt(1).name)
        assertFalse(list.checkedAt(1))
        assertEquals(light, list.selectedTheme)
        // back: marked again
        list.switchVersion(1)
        assertTrue(list.checkedAt(1))
        // the card tapped while it shows the dark one picks that
        list.switchVersion(1)
        list.pick(1)
        assertEquals(dark, list.selectedTheme)
        assertTrue(list.checkedAt(1))
        assertFalse(list.checkedAt(0))
    }

    @Test
    fun theSlotsVersionIsShownMarkedAndAnotherCardTappedPicksIt() {
        val list = adapter().apply { select(dark) }
        assertEquals(dark.name, list.shownAt(1).name)
        assertTrue(list.checkedAt(1))
        list.pick(0)
        assertEquals(mine, list.selectedTheme)
        assertTrue(list.checkedAt(0))
        assertFalse(list.checkedAt(1))
        // a card of one version has nothing to switch
        list.switchVersion(2)
        assertEquals("deep-blue", list.shownAt(2).name)
    }

    @Test
    fun aThemeNoCardHasIsNoPick() {
        val list = adapter().apply { select(TestThemes.Monokai) }
        assertNull(list.selectedTheme)
        assertFalse((0 until list.itemCount).any(list::checkedAt))
    }
}
