/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every keyboard's scene environment alike ([SceneEnvs]): the screen's and the settings', the keyboard's own given. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class SceneEnvsTest {

    private val view get() = View(RuntimeEnvironment.getApplication())

    @After
    fun floatingBack() = ThemeManager.prefs.floatingKeyboard.setValue(ThemeManager.prefs.floatingKeyboard.defaultValue)

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun theScreensAndTheKeyboardsOwn() {
        val blocks = TestThemes.PixelDark.look
        val env = SceneEnvs.of(view, blocks, dark = true, width = 2000, height = 600, input = Input(), split = true, shift = Shift.Lock)
        assertEquals(2000.0, env.width, 0.0)
        assertEquals(600.0, env.height, 0.0)
        assertTrue(env.landscape)
        assertTrue(env.split)
        assertTrue(env.dark)
        assertEquals(Shift.Lock, env.shift)
        assertEquals(!blocks.keys.blocks, env.flat)
        // one-handed and docked, InputView placed the keyboard already: its keys fill it
        assertEquals(SplitKeyboard.Dock.FULL, env.oneHanded)
        assertEquals(SplitKeyboard.Dock.FULL, env.dock)
    }

    // a settings page's preview is no floating keyboard: its text not scaled as one
    @Test
    fun floatingOnlyInTheInputMethod() {
        ThemeManager.prefs.floatingKeyboard.setValue(true)
        val env = SceneEnvs.of(view, TestThemes.PixelDark.look, dark = false, width = 1000, height = 700, input = Input())
        assertNull(env.floating)
        assertFalse(env.landscape)
    }
}
