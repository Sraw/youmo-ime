/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.res.Configuration
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A view made anew by a setting ends what was typed (A2): the engine's reset drops its context, and
 * the field stays, so no input restarts to tell it. Told after the end, the next word keeps its 前文
 * (ranking, 联想, learning); of the two ends dark mode brings, the last tell after the last end.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class RebuildContextTest {

    private val h = KeyboardHarness()

    @After
    fun tearDown() {
        AppPrefs.getInstance().keyboard.pinyinKeyboard.let { it.setValue(it.defaultValue) }
        h.close()
    }

    private fun written() {
        h.start()
        h.field.setText("今天")
        h.field.setSelection(2)
        h.idle()
        h.api.calls.clear()
    }

    private val calls get() = h.api.calls.toList()

    private fun lastEnd() = maxOf(calls.lastIndexOf("focusOutIn()"), calls.lastIndexOf("reset()"))

    private fun lastTold() = calls.indexOfLast { it.startsWith("engineContext(今天") }

    @Test
    fun aSettingsRebuildTellsTheContextAfterItEnds() {
        written()
        AppPrefs.getInstance().keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Keys14)
        h.idle()
        assertTrue("ended: $calls", lastEnd() >= 0)
        assertTrue("told after: $calls", lastTold() > lastEnd())
    }

    private fun night(on: Boolean) = Configuration(h.service.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (on) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    }

    // the theme's rebuild (the application's handler) and the service's own, in either order
    @Test
    fun darkModeLeavesTheContextToldWhicheverComesFirst() {
        written()
        try {
            for ((on, themeFirst) in listOf(true to true, false to false)) {
                h.api.calls.clear()
                val config = night(on)
                if (themeFirst) FcitxApplication.getInstance().onConfigurationChanged(config)
                h.service.onConfigurationChanged(config)
                if (!themeFirst) FcitxApplication.getInstance().onConfigurationChanged(config)
                h.idle()
                assertTrue("ended ($on, $themeFirst): $calls", lastEnd() >= 0)
                assertTrue("told after ($on, $themeFirst): $calls", lastTold() > lastEnd())
            }
        } finally {
            FcitxApplication.getInstance().onConfigurationChanged(night(false))
            h.idle()
        }
    }
}
