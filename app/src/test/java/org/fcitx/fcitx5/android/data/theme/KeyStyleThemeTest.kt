/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import androidx.core.view.allViews
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.KeyBorderChoice
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.KeyRadiusChoice
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.KeySpacingChoice
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.input.theme.LookDefaults
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The key style settings (大小与样式) over the theme in use, as the keyboard reads it, and the theme left as it is. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KeyStyleThemeTest {

    private val prefs = ThemeManager.prefs

    @After
    fun followTheTheme() {
        prefs.keyBorderChoice.setValue(KeyBorderChoice.FollowTheme)
        prefs.keySpacingChoice.setValue(KeySpacingChoice.FollowTheme)
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
        prefs.keySpacingChoice.setValue(KeySpacingChoice.Loose)
        prefs.keyBorderChoice.setValue(KeyBorderChoice.Off)
        ThemeManager.shownLook(theme).keys.let {
            assertEquals(KeyStyleChoice.GAP_LOOSE, it.gap)
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

    // the keys drawn are the settings', not the theme's
    @Test
    @Uat("ST-58")
    fun aKeyboardMadeIsSquareAndFlatWhenChosen() {
        prefs.keyRadiusChoice.setValue(KeyRadiusChoice.Square)
        prefs.keyBorderChoice.setValue(KeyBorderChoice.Off)
        val keyboard = TextKeyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemeManager.activeTheme)
        val keys = keyboard.allViews.filterIsInstance<KeyView>().toList()
        assertTrue(keys.isNotEmpty())
        keys.forEach {
            assertEquals(0f, it.radius)
            assertFalse(it.bordered)
        }
    }

    // a reader of the theme's own look in the keyboard would draw over what is chosen: none there (specs/app.md)
    // only `.look` as written is caught: `with(theme) { look }` or `Theme::look` would pass it (none of them now)
    @Test
    fun theKeyboardReadsNoThemesOwnLook() {
        val root = File("src/main/java/org/fcitx/fcitx5/android")
        val files = File(root, "input").walk().filter { it.extension == "kt" } +
            File(root, "ui/main/settings/theme/KeyboardPreviewUi.kt")
        val readers = files.flatMap { f ->
            f.readLines().withIndex().filter { Regex("""\.look\b""").containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" }
        }.toList()
        assertTrue(files.count() > 20)
        assertEquals(emptyList<String>(), readers)
    }
}
