/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.res.Configuration
import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UAT cases of the keyboard across fields and system changes (uat/18). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class LifecycleUatTest {

    private val h = KeyboardHarness()

    @After
    fun close() = h.close()

    private fun letters() = KeyboardHarness.all(h.inputView).any { h.shown(it) && it is KeyView && (it as? TextKeyView)?.mainText?.text?.toString() == "q" }

    @Test
    @Uat("LC-05")
    fun aNumberFieldThenTextIsLettersAgain() {
        h.start(InputType.TYPE_CLASS_NUMBER)
        assertTrue("numbers in a number field", !letters())
        h.start(InputType.TYPE_CLASS_TEXT)
        assertTrue(letters())
    }

    @Test
    @Uat("LC-06")
    fun anotherFieldFromTheSymbolsIsLetters() {
        h.start()
        h.tap(h.key("符"))
        assertTrue("the symbols shown", !letters())
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertTrue(letters())
    }

    private fun system(night: Boolean) {
        val config = Configuration(h.service.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        // what the application is told as the system's mode flips: its own handler, the glue this checks
        FcitxApplication.getInstance().onConfigurationChanged(config)
        h.idle()
        h.relayout()
    }

    @Test
    @Uat("LC-09")
    fun systemDarkModeFlippedWhileShown() {
        h.start()
        try {
            for (night in listOf(true, false)) {
                system(night)
                val want = (if (night) ThemeManager.prefs.darkModeTheme else ThemeManager.prefs.lightModeTheme).getValue()
                assertEquals(want.name, ThemeManager.activeTheme.name)
                val n = h.key("n")
                assertEquals("the keys in the theme", want.name, n.theme.name)
                h.api.calls.clear()
                "nihao".forEach { h.tap(h.key(it.toString())) }
                assertEquals(h.api.calls.toString(), 5, h.api.calls.count { it.startsWith("sendKey") && "up=false" in it })
            }
        } finally {
            system(false)
        }
    }
}
