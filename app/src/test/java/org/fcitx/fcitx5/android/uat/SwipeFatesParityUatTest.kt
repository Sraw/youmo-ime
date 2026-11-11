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
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboardMode
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.MarkFate
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.ui.main.settings.SwipeFates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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

    // low keys, the down way at the bottom: j's long text shortened or a sign, not as set
    private fun setWays() {
        val k = AppPrefs.getInstance().keyboard
        prefs.set(k.swipeDownPosition, KeyMarkPosition.Bottom)
        prefs.set(k.keyboardHeightPercent, 20)
        prefs.set(k.keyboardHeightPercentLandscape, 34)
        setSlots()
    }

    private fun setSlots() = prefs.set(
            slotsPref,
            SwipeSlots.Default
                .with("full/j", false, SwipeSlot.Text("现在怎么样了"))
                .with("full/q", true, SwipeSlot.Command(KeyCommand.Paste))
                .with("fn/backspace", false, SwipeSlot.Text("删掉这一整句话")).serialize(),
    )

    private fun page(h: KeyboardHarness) = SwipeFates.Page(h.service, listOf(KeyboardHarness.PINYIN), emptyMap())

    // both sides measure a character alike (Robolectric measures no text: every way would fit, as set)
    @Before
    fun fixedWidths() {
        SceneBoard.measurerFor = { FixedMeasurer }
    }

    @After
    fun fontsWidths() {
        SceneBoard.measurerFor = null
    }

    private fun parity(nine: Boolean = false, landscape: Boolean = false) {
        if (landscape) RuntimeEnvironment.setQualifiers("w851dp-h393dp-land-440dpi")
        setWays()
        KeyboardHarness(TestThemes.PixelLight, keyboards = if (nine) KeyboardHarness.NINE_KEYS else null).start().use { h ->
            val drawn = KeyboardHarness.boardCanvas(h.inputView)!!.scene!!.keys
                .filter { it.copy == 0 && it.shortcut != null }.associate { it.shortcut!! to it.fates }
            val seen = page(h).jobs(slots()).filter { it.landscape == landscape }.map(SwipeFates::made)
            assertEquals(1, seen.size)
            val said = seen.single().fates!!
            assertTrue("no way set drawn", drawn.values.any { it.isNotEmpty() })
            // not all as set: the comparison tells a way drawn otherwise (j's long text, upright or turned; the
            // nine keys' backspace is wide enough for its text's first two, so there both sides' as set is the check)
            if (!nine) assertTrue("every way drawn as set: $drawn", drawn.values.flatMap { it.values }.any { it != MarkFate.AsSet })
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

    // a way switched off or placed 无 is drawn nowhere as the user chose: the page says nothing of it
    private fun noNoteOfDown() {
        setSlots()
        KeyboardHarness(TestThemes.PixelLight).start().use { h ->
            val seen = page(h).jobs(slots()).map(SwipeFates::made)
            val keys = seen.flatMap { it.fates.orEmpty().keys }.toSet()
            assertTrue(keys.isNotEmpty())
            keys.forEach { assertNull(it, SwipeFates.note(h.service, seen, it, up = false)) }
        }
    }

    @Test
    fun noNoteOfAWayPlacedNowhere() {
        prefs.set(AppPrefs.getInstance().keyboard.swipeDownPosition, KeyMarkPosition.None)
        noNoteOfDown()
    }

    @Test
    fun noNoteOfAWaySwitchedOff() {
        prefs.set(AppPrefs.getInstance().keyboard.swipeDown, false)
        noNoteOfDown()
    }

    // the page's scenes made on its thread work out the theme's looks in cascades of its own: none of SceneThemes'
    // kept ones, which the input view and the main thread work out at the same time
    @Test
    fun thePagesThreadWorksOutLooksOfItsOwn() {
        setWays()
        KeyboardHarness(TestThemes.PixelLight).start().use { h ->
            val jobs = page(h).jobs(slots())
            val theme = ThemeManager.activeTheme
            val kept = SceneThemes.cascades(SceneThemes.of(theme, ThemeManager.shownLook(theme))!!)
            val keptBefore = kept.size
            jobs.forEach { assertNotSame(kept, it.cascades) }
            jobs.forEach { SwipeFates.made(it) }
            jobs.forEach { assertTrue(it.cascades.size > 0) }
            // a side check only: the kept ones may be worked out by others, but not by the page here
            assertEquals(keptBefore, kept.size)
        }
    }
}
