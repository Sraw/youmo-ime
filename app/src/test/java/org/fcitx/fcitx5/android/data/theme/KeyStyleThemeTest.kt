/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.KeyBorderChoice
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.KeyRadiusChoice
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.KeyHSpacingChoice
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.KeyVSpacingChoice
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The key style settings (大小与样式) over the theme in use, as the keyboard reads it, and the theme left as it is. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KeyStyleThemeTest {

    private val prefs = ThemeManager.prefs

    @After
    fun followTheTheme() {
        prefs.keyBorderChoice.setValue(KeyBorderChoice.FollowTheme)
        prefs.keyHSpacingChoice.setValue(KeyHSpacingChoice.FollowTheme)
        prefs.keyVSpacingChoice.setValue(KeyVSpacingChoice.FollowTheme)
        prefs.keyRadiusChoice.setValue(KeyRadiusChoice.FollowTheme)
    }

    @Uat("ST-58")
    @Test
    fun aChoiceIsOverTheThemeAndFollowingIsTheThemesOwn() {
        val theme = ThemeManager.activeTheme
        assertSame(theme.look, ThemeManager.shownLook(theme))

        prefs.keyRadiusChoice.setValue(KeyRadiusChoice.Square)
        assertEquals(0, ThemeManager.shownLook(theme).keys.radiusDp)
        // asked again, the same object: what is decoded of a look is kept by it
        assertSame(ThemeManager.shownLook(theme), ThemeManager.shownLook(theme))
        // up and down alone: across the theme's
        prefs.keyVSpacingChoice.setValue(KeyVSpacingChoice.Loose)
        ThemeManager.shownLook(theme).keys.gap.let {
            assertEquals(KeyStyleChoice.V_LOOSE, KeyStyleChoice.Spacing(it.v, it.vLand))
            assertEquals(theme.look.keys.gap.h to theme.look.keys.gap.hLand, it.h to it.hLand)
        }
        prefs.keyHSpacingChoice.setValue(KeyHSpacingChoice.Compact)
        prefs.keyBorderChoice.setValue(KeyBorderChoice.Off)
        ThemeManager.shownLook(theme).keys.let {
            assertEquals(Gap(2, 9, 2, 6), it.gap)
            assertEquals(false, it.blocks)
        }
        // the theme's own (exported, saved) is not changed: a built-in one's are the defaults
        ThemeManager.activeTheme.look.keys.let {
            assertEquals(LookDefaults.KEYS.radiusDp, it.radiusDp)
            assertEquals(LookDefaults.KEYS.gap, it.gap)
            assertTrue(it.blocks)
        }
        // another theme is its own look under the settings, not the one cached
        val other = ThemeManager.BuiltinThemes.first { it.name != theme.name }
        assertNotSame(ThemeManager.shownLook(theme), ThemeManager.shownLook(other))
        assertEquals(other.look.palette, ThemeManager.shownLook(other).palette)

        followTheTheme()
        assertSame(theme.look, ThemeManager.shownLook(theme))
    }

    @Uat("ST-58")
    @Test
    fun changingOneTellsTheKeyboardOnce() {
        var told = 0
        // held here: the listeners are weak
        val listener = ThemeManager.OnThemeChangeListener { told++ }
        ThemeManager.addOnChangedListener(listener)
        try {
            prefs.keyRadiusChoice.setValue(KeyRadiusChoice.Round)
            assertEquals(1, told)
            assertEquals(KeyStyleChoice.RADIUS_ROUND, ThemeManager.shownLook(ThemeManager.activeTheme).keys.radiusDp)
        } finally {
            ThemeManager.removeOnChangedListener(listener)
        }
    }

    // what the keyboard draws follows the choice: Square, every key's corners square on the canvas, Round
    // rounded (a canvas reading a look that ignores the choice, cached or the theme's own, draws them as before)
    @Uat("ST-58")
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theKeyboardDrawnIsSquareWhenChosen() {
        fun radii(choice: KeyRadiusChoice): List<Double> {
            prefs.keyRadiusChoice.setValue(choice)
            return KeyboardHarness().start().use { h ->
                h.relayout()
                val keys = KeyboardHarness.boardCanvas(h.keyboard)!!.scene!!.keys.map { it.shape }.filterIsInstance<Shape.Rect>()
                assertTrue(keys.size >= 26)
                keys.flatMap { it.radii }
            }
        }
        assertTrue("square", radii(KeyRadiusChoice.Square).all { it == 0.0 })
        assertTrue("round", radii(KeyRadiusChoice.Round).any { it > 0.0 })
    }

    // a reader of the theme's own look in the keyboard would draw over what is chosen: none there (specs/app.md)
    // only `.look` as written is caught: `with(theme) { look }` or `Theme::look` would pass it (none of them now)
    @Test
    fun theKeyboardReadsNoThemesOwnLook() {
        val root = File("src/main/java/org/fcitx/fcitx5/android")
        val files = File(root, "input").walk().filter { it.extension == "kt" } +
            File(root, "ui/main/settings/theme/KeyboardPreviewUi.kt")
        val readers = files.flatMap { f ->
            // an import is no reader: keyboard-scene's package `scene.look` (060-render-p3 T2) has the word
            f.readLines().withIndex().filter { !it.value.startsWith("import ") && Regex("""\.look\b""").containsMatchIn(it.value) }
                .map { "${f.name}:${it.index + 1}" }
        }.toList()
        assertTrue(files.count() > 20)
        assertEquals(emptyList<String>(), readers)
    }
}
