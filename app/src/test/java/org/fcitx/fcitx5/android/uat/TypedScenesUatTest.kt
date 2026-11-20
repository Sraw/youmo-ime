/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.scene.engine.Input
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What is typed reaches a theme as its scenes: the input method's kind, a table's name, the field; a settings page's keys none. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class TypedScenesUatTest {

    private var h: KeyboardHarness? = null

    @After
    fun close() {
        h?.close()
        // a process's: the next test's keyboard tells its own
        SceneEnvs.typing = null
    }

    private fun entry(im: String, name: String) = InputMethodEntry(im, name, "", name.take(1), "zh_CN")

    private fun typed(view: View) =
        SceneEnvs.of(view, TestThemes.PixelLight.look, false, 1000, 700, Input(ime = "pinyin")).typed

    @Test
    fun theInputMethodAndTheFieldAreTheThemesScenes() {
        val h = KeyboardHarness(im = entry(InputMethodCatalog.WUBI, "Wubi")).also { h = it }
            .start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(setOf("im=table", "im=wubi", "field=password"), SceneEnvs.typing)
        assertEquals(setOf("im=table", "im=wubi", "field=password"), typed(h.inputView))
        h.restart(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        assertEquals(setOf("im=table", "im=wubi", "field=url"), SceneEnvs.typing)
        h.restart(InputType.TYPE_CLASS_TEXT)
        assertEquals(setOf("im=table", "im=wubi"), SceneEnvs.typing)
        assertNull(typed(View(h.service.applicationContext)))
    }
}
