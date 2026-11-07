/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneA11yTest {

    private val strings = mapOf(
        "a11y_key_shift" to "Shift",
        "a11y_key_backspace" to "Backspace",
        SceneA11y.SHIFT_ONCE to "On for one letter",
        SceneA11y.CAPS_LOCK to "Caps lock",
    )

    private fun scene(env: Env = SceneData.portrait): Scene =
        (SceneBuilder(SceneData.theme("material-light.xml"), env, SceneData.measurer).build(SceneData.qwerty, emptyMap()) as Parsed.Ok).value

    private fun Scene.speech(id: String) = SceneA11y.of(keys.first { it.id == id }, strings::get)

    @Test
    fun spokenWordsTheAppsStringsWhereNamed() {
        assertEquals("Shift", SceneA11y.spoken("a11y_key_shift", null, strings::get))
        assertEquals("⇧", SceneA11y.spoken("a11y_key_unknown", "⇧", strings::get))
        assertEquals("a11y_key_unknown", SceneA11y.spoken("a11y_key_unknown", null, strings::get))
        assertEquals("q，1", SceneA11y.spoken("q，1", "q", strings::get))
        assertEquals("q", SceneA11y.spoken("", "q", strings::get))
        assertEquals("", SceneA11y.spoken("", null, strings::get))
    }

    @Test
    fun aLetterItsLabelItsCornerApartNoOldName() {
        val q = scene(SceneData.portrait.copy(texts = SceneData.portrait.texts.copy(alts = mapOf("q" to "1")))).speech("q")
        assertEquals("q", q.text)
        // read with its corner, as the views' label and corner were read one after the other (the engine's a11y)
        assertEquals("q, 1", q.description)
        assertEquals("1", q.alt)
        assertEquals("q", q.id)
        assertNull("a letter's view had no name of the layout's", q.resourceName)
        assertNull(q.state)
        assertTrue(q.enabled)
        // no corner shown: none told
        assertNull(scene().speech("q").alt)
    }

    @Test
    fun theOldViewsNamesByRole() {
        val s = scene()
        assertEquals("button_caps", s.speech("shift").resourceName)
        assertEquals("button_return", s.speech("enter").resourceName)
        assertEquals("button_space", s.speech("space").resourceName)
        assertEquals("button_backspace", s.speech("backspace").resourceName)
        assertEquals("Backspace", s.speech("backspace").description)
        assertNull("符 had none", s.speech("symbols").resourceName)
    }

    @Test
    fun shiftsStateAsTheViewsSaidIt() {
        assertNull(scene().speech("shift").state)
        assertEquals("On for one letter", scene(SceneData.portrait.copy(shift = Shift.Once)).speech("shift").state)
        assertEquals("Caps lock", scene(SceneData.portrait.copy(shift = Shift.Lock)).speech("shift").state)
        // a string the app has not: its name, not nothing
        assertEquals(SceneA11y.CAPS_LOCK, SceneA11y.of(scene(SceneData.portrait.copy(shift = Shift.Lock)).keys.first { it.id == "shift" }) { null }.state)
        // only Shift's: the letters drawn shifted tell nothing
        assertNull(scene(SceneData.portrait.copy(shift = Shift.Once)).speech("q").state)
        assertNull(SceneA11y.state("reading", setOf(KeyState.On)))
    }

    @Test
    fun aKeyNotUsableIsNotEnabled() {
        val s = scene(SceneData.portrait.copy(states = mapOf("space" to setOf(KeyState.Disabled))))
        assertFalse(s.speech("space").enabled)
        assertTrue(s.speech("q").enabled)
    }
}
