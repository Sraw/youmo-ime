/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboardMode
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.ui.main.settings.SwipeFates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the settings page says of a way (SwipeFates) is what the keyboard draws of it: each key's ways'
 * fates of the page's scene against the keyboard's own scene, docked, one-handed, floating, split
 * landscape and the nine keys.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class SwipeFatesParityUatTest {
    @get:Rule
    val prefs = PrefsRule()

    private val slotsPref get() = AppPrefs.getInstance().internal.keySwipes

    private fun slots() = SwipeSlots.parse(slotsPref.getValue())

    private fun parity(nine: Boolean = false, landscape: Boolean = false) {
        if (landscape) RuntimeEnvironment.setQualifiers("w851dp-h393dp-land-440dpi")
        prefs.set(
            slotsPref,
            SwipeSlots.Default
                .with("full/j", false, SwipeSlot.Text("现在怎么样了"))
                .with("full/q", true, SwipeSlot.Command(KeyCommand.Paste))
                .with("fn/backspace", false, SwipeSlot.Text("删掉这一整句话")).serialize(),
        )
        KeyboardHarness(TestThemes.PixelLight, keyboards = if (nine) KeyboardHarness.NINE_KEYS else null).start().use { h ->
            val drawn = KeyboardHarness.boardCanvas(h.inputView)!!.scene!!.keys
                .filter { it.copy == 0 && it.shortcut != null }.associate { it.shortcut!! to it.fates }
            val page = SwipeFates.Page(h.service, listOf(KeyboardHarness.PINYIN), emptyMap())
            val seen = page.jobs(slots()).filter { it.landscape == landscape }.map(SwipeFates::made)
            assertEquals(1, seen.size)
            val said = seen.single().fates!!
            assertTrue("no way set drawn", drawn.values.any { it.isNotEmpty() })
            assertEquals(drawn, said)
        }
    }

    @Test
    fun docked() = parity()

    @Test
    fun oneHanded() {
        prefs.set(ThemeManager.prefs.oneHanded, OneHanded.Right)
        parity()
    }

    @Test
    fun floating() {
        prefs.set(ThemeManager.prefs.floatingKeyboard, true)
        parity()
    }

    @Test
    fun splitLandscape() {
        prefs.set(ThemeManager.prefs.splitKeyboard, SplitKeyboardMode.Always)
        parity(landscape = true)
    }

    @Test
    fun nineKeysBackspace() = parity(nine = true)
}
