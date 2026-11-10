/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The text editing panel with the editor's selection report lagging behind its selection (uat/05 TB-30). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class EditingLagUatTest {

    private val h = KeyboardHarness()

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        h.close()
    }

    private fun s(id: Int) = h.service.getString(id)

    private fun button(text: String): View {
        val t = KeyboardHarness.all(h.inputView).firstOrNull { h.shown(it) && it is TextView && it.text.toString() == text }
            ?: error("no $text in ${h.texts(h.inputView)}")
        return generateSequence(t) { it.parent as? View }.first { it.hasOnClickListeners() }
    }

    // the tracked selection collapsed, the editor's text selected: 复制 goes by what the editor says is selected
    // and leaves its selection as it is (剪切 is not shown then) (R20 F6626)
    @Test
    @Uat("TB-30")
    fun copyWithTheSelectionReportLagging() {
        h.start()
        h.field.setText("今天天气很好")
        h.field.setSelection(6)
        h.idle()
        if (KeyboardHarness.all(h.inputView).none { h.shown(it) && it.contentDescription == s(R.string.hide_toolbar) }) {
            h.tap(h.described(s(R.string.expand_toolbar)))
            h.after(500)
            h.relayout()
        }
        h.tap(h.described(s(R.string.text_editing)))
        h.selectionLags = true
        h.field.selectAll()
        h.tap(button(h.service.getString(android.R.string.copy)))
        assertTrue(h.noticeTexts().toString(), s(R.string.text_copied) in h.noticeTexts())
        assertEquals(0 to 6, h.field.selectionStart to h.field.selectionEnd)
    }
}
