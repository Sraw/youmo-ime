/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
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
}
