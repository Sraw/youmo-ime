/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.uat.TestStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 竖屏键盘样式 and 横屏键盘样式 as the keyboard and the settings page read and write them ([KeyboardStyles]). */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KeyboardStylesTest {

    @get:Rule
    val prefs = PrefsRule()

    private val ctx get() = RuntimeEnvironment.getApplication()

    private val store get() = ThemeManager.prefs

    @Test
    @Config(qualifiers = "w393dp-h851dp-port")
    fun uprightReadsThePortraitOne() {
        TestStyles.set(KeyboardStyle.FLOATING, landscape = false)
        TestStyles.set(KeyboardStyle.SPLIT, landscape = true)
        assertTrue(KeyboardStyles.of(ctx).floating)
        assertFalse(KeyboardArea.splits(KeyboardArea.screen(ctx)))
    }

    @Test
    @Config(qualifiers = "w841dp-h393dp-land")
    fun turnedReadsTheLandscapeOne() {
        TestStyles.set(KeyboardStyle.FLOATING, landscape = false)
        TestStyles.set(KeyboardStyle.ONE_HANDED, landscape = true)
        assertEquals(KeyboardStyle.ONE_HANDED, KeyboardStyles.of(ctx).style)
    }

    // a foldable unfolded and a tablet are 横屏 held either way: the landscape one
    @Test
    @Config(qualifiers = "w673dp-h841dp-port")
    fun aWideScreenUprightReadsTheLandscapeOne() {
        TestStyles.set(KeyboardStyle.NORMAL, landscape = false)
        TestStyles.set(KeyboardStyle.FLOATING, landscape = true)
        assertTrue(KeyboardStyles.floating(ctx))
    }

    @Test
    @Uat("LS-46")
    @Config(qualifiers = "w592dp-h360dp-land")
    fun notSetTurnedUnder600IsNormal() {
        TestStyles.clear()
        assertEquals(KeyboardStyle.NORMAL, KeyboardStyles.of(ctx).style)
    }

    @Test
    @Uat("LS-46")
    @Config(qualifiers = "w640dp-h360dp-land")
    fun notSetTurnedFrom600Splits() {
        TestStyles.clear()
        assertEquals(KeyboardStyle.SPLIT, KeyboardStyles.of(ctx).style)
        // the settings page shows it as it is, nothing stored
        assertEquals(KeyboardStyle.SPLIT, KeyboardStyles.shown(ctx, landscape = true))
        assertEquals(KeyboardStyle.NORMAL, KeyboardStyles.shown(ctx, landscape = false))
        assertEquals("", store.keyboardStyleLandscape.getValue())
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun theKeyboardsToggleWritesItsShapeOnlyBothKeys() {
        TestStyles.clear()
        val s = KeyboardArea.screen(ctx)
        KeyboardStyles.toggleFloating(s)
        assertEquals("FLOATING" to "SPLIT", store.keyboardStyleLandscape.getValue() to store.keyboardStyleUnderLandscape.getValue())
        assertEquals("" to "", store.keyboardStylePortrait.getValue() to store.keyboardStyleUnderPortrait.getValue())
        // docked: what was under it, split
        KeyboardStyles.toggleFloating(s)
        assertEquals(KeyboardStyle.SPLIT, KeyboardStyles.of(s).style)
        // one-handed over split, and both hands again split
        KeyboardStyles.toggleOneHanded(s)
        assertEquals("ONE_HANDED" to "SPLIT", store.keyboardStyleLandscape.getValue() to store.keyboardStyleUnderLandscape.getValue())
        KeyboardStyles.oneHandedOff(s)
        assertEquals(KeyboardStyle.SPLIT, KeyboardStyles.of(s).style)
    }

    // the view taken out after a turn docks the shape it was made on, not the one there is now
    @Test
    @Config(qualifiers = "w393dp-h851dp-port")
    fun aViewMadeOnTheOtherShapeWritesThatOne() {
        TestStyles.set(KeyboardStyle.FLOATING, landscape = true)
        TestStyles.set(KeyboardStyle.FLOATING, landscape = false)
        val turned = KeyboardArea.screens(ctx).second
        KeyboardStyles.floatingOff(turned)
        assertEquals(KeyboardStyle.NORMAL, TestStyles.stored(landscape = true))
        assertEquals(KeyboardStyle.FLOATING, TestStyles.stored(landscape = false))
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-port")
    fun theSettingsPageWritesBothKeys() {
        TestStyles.clear()
        KeyboardStyles.set(ctx, landscape = true, KeyboardStyle.ONE_HANDED)
        // over the default turned here (851 dp wide, split)
        assertEquals("ONE_HANDED" to "SPLIT", store.keyboardStyleLandscape.getValue() to store.keyboardStyleUnderLandscape.getValue())
        KeyboardStyles.set(ctx, landscape = false, KeyboardStyle.SPLIT)
        assertEquals("SPLIT" to "SPLIT", store.keyboardStylePortrait.getValue() to store.keyboardStyleUnderPortrait.getValue())
        // the default picked is stored as it is
        TestStyles.clear()
        KeyboardStyles.set(ctx, landscape = true, KeyboardStyles.shown(ctx, landscape = true))
        assertEquals("SPLIT", store.keyboardStyleLandscape.getValue())
    }

    // 分体时其他面板 (§2.3): of use where a shape splits, or goes back to split
    @Test
    @Config(qualifiers = "w393dp-h851dp-port")
    fun theOtherPanelsWhereSplitIsOrIsUnder() {
        TestStyles.set(KeyboardStyle.NORMAL)
        assertFalse(KeyboardStyles.splitDockOffered(ctx))
        KeyboardStyles.set(ctx, landscape = true, KeyboardStyle.SPLIT)
        KeyboardStyles.set(ctx, landscape = true, KeyboardStyle.FLOATING)
        assertTrue(KeyboardStyles.splitDockOffered(ctx))
        TestStyles.clear()
        // not set: turned splits here
        assertTrue(KeyboardStyles.splitDockOffered(ctx))
    }
}
