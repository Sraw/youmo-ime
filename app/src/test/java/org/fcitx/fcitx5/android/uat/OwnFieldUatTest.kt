/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.picker.PickerGridView
import org.fcitx.fcitx5.android.input.picker.PickerSideUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * uat/15-privacy-backup.md, this app's own fields (its settings' search): the real service's field
 * policy tells the engine to learn nothing there and reads none of the field's text, so nothing
 * typed there is learned or kept as the text before (docs/keyboard.md「本应用的输入框」). Another
 * app's field, alike, is told both: what the assertions would catch. What was deleted (PR-16) is
 * the engine's: OwnFieldEngineUatTest, as no context is told after a predicted Backspace here (F4277).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class OwnFieldUatTest {

    private val h = KeyboardHarness()

    @After
    fun close() = h.close()

    // 我 put in the field and the cursor reported after it, as the editor reports it; the engine's contexts since
    private fun contextsAfterTyping(packageName: String): List<String> {
        h.start(packageName = packageName)
        h.api.calls.clear()
        h.service.currentInputConnection.commitText("我", 1)
        h.service.onUpdateSelection(0, 0, 1, 1, -1, -1)
        h.after(500)
        h.idle()
        return h.api.calls.filter { it.startsWith("engineContext") }
    }

    @Test
    fun anotherAppsFieldIsReadAndLearnedIn() {
        val told = contextsAfterTyping("org.example.app")
        assertTrue(told.toString(), told.any { it.startsWith("engineContext(我, true") })
    }

    @Test
    @Uat("PR-15")
    fun thisAppsOwnFieldLearnsNothing() {
        val told = contextsAfterTyping(h.service.packageName)
        assertTrue(told.toString(), told.isNotEmpty() && told.all { it.startsWith("engineContext(, false") })
    }

    private fun shown(text: String) = KeyboardHarness.all(h.inputView).filterIsInstance<TextView>().firstOrNull { h.shown(it) && it.text.toString() == text }

    private fun categories() = KeyboardHarness.all(KeyboardHarness.find<PickerSideUi>(h.inputView)!!)
        .filterIsInstance<TextView>().filter { h.shown(it) }.map { it.text.toString() }.toList()

    // the grid binds only the rows on screen: scrolled down a row at a time till the key is among them
    private fun scrolledTo(label: String): View {
        val grid = KeyboardHarness.find<PickerGridView>(h.inputView)!!
        repeat(30) {
            KeyboardHarness.cells(grid).firstOrNull { KeyboardHarness.cellText(it) == label }?.let { return it }
            grid.scrollBy(0, grid.height / 3)
            h.relayout()
        }
        error("no $label in the grid")
    }

    // a sum and its = in the field, as the engine commits the = typed
    private fun sum() {
        h.field.setText("1+1")
        h.field.setSelection(3)
        h.idle()
        h.emit(HostEvent.Commit("=", -1))
        h.idle()
    }

    @Test
    @Uat("PR-15")
    fun thisAppsOwnFieldKeepsNoRecentSymbolAndReadsNoSum() {
        try {
            h.start(packageName = h.service.packageName)
            sum()
            assertEquals(null, shown("= 2"))
            h.start(packageName = h.service.packageName)
            h.tap(h.key("符"))
            h.tap(shown("Shapes")!!)
            h.tap(scrolledTo("★"))
            h.tap(h.key("符"))
            assertTrue(categories().toString(), "Recent" !in categories())
            // another app's field, alike: its sum read
            h.start()
            sum()
            assertTrue(h.texts(h.inputView).toString(), shown("= 2") != null)
        } finally {
            FcitxApplication.getInstance().directBootAwareContext
                .getSharedPreferences(RecentlyUsed.PREFERENCE_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
