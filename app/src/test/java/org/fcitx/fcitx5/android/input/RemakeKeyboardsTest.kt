/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 youmo-ime Contributors
 */
package org.fcitx.fcitx5.android.input

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A keyboard setting makes the keyboards anew in the input view there is (060-render-perf-2 #1):
 * what shows is what a view made with the setting shows, keys, code row and bar alike.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class RemakeKeyboardsTest {

    @After
    fun close() = KeyboardHarness.setKeyboards(ImKeyboards.Chosen())

    // what the user sees: each key's label where it is, the code row's box, the bar's buttons shown
    private fun KeyboardHarness.seen(): List<Any> {
        relayout()
        val bar = KeyboardHarness.all(inputView).filter { shown(it) && it.contentDescription != null }.map { it.contentDescription.toString() to box(it) }.toList()
        return listOf(keys().map { it.label to it.bounds }, box(inputView.findViewById<View>(R.id.code_row_text)), bar)
    }

    private fun fresh(chosen: ImKeyboards.Chosen): List<Any> =
        KeyboardHarness(keyboards = chosen).use { it.start(); it.seen() }

    @Test
    fun eachKeyboardPickedShowsAsAViewMadeWithIt() {
        val picks = listOf(ImKeyboard.KEYS14, ImKeyboard.NINE_KEYS, ImKeyboard.KEYS18, ImKeyboard.FULL)
        val expected = picks.associateWith { fresh(ImKeyboards.Chosen(pinyin = it)) }
        KeyboardHarness(keyboards = ImKeyboards.Chosen()).use { h ->
            h.start()
            val view = h.inputView
            for (pick in picks) {
                KeyboardHarness.setKeyboards(ImKeyboards.Chosen(pinyin = pick))
                h.idle()
                assertSame("the view kept", view, h.inputView)
                assertEquals(pick.name, expected.getValue(pick), h.seen())
            }
        }
    }

    /** The toolbar opened before the pick folds as a new view's would. */
    @Test
    fun theToolbarFoldsAsANewViewsWould() {
        KeyboardHarness(keyboards = ImKeyboards.Chosen()).use { h ->
            h.start()
            val expand = h.service.getString(R.string.expand_toolbar)
            h.tap(h.described(expand))
            h.after(500)
            KeyboardHarness.setKeyboards(ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
            h.after(500)
            h.relayout()
            assertTrue(KeyboardHarness.all(h.inputView).any { h.shown(it) && it.contentDescription == expand })
        }
    }
}
