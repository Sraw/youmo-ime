/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.scene.KeyNode
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
        val q = scene(SceneData.portrait.copy(texts = SceneData.portrait.texts.copy(alts = mapOf("full/q" to "1")))).speech("q")
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

    // the number pad's space had a name of its own (MiniSpaceKey): its board's, over its role's
    @Test
    fun aBoardsOwnNameOverTheRoles() {
        val space = scene().keys.first { it.id == "space" }
        assertEquals("button_mini_space", SceneA11y.of(space, "number", strings::get).resourceName)
        assertEquals("button_space", SceneA11y.of(space, "qwerty", strings::get).resourceName)
        assertEquals("button_space", SceneA11y.of(space, strings::get).resourceName)
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

    private fun board(name: String, env: Env): Scene {
        val layout = (org.fcitx.fcitx5.android.scene.layout.LayoutReader.layout(SceneData.read(name)) as Parsed.Ok).value
        return (SceneBuilder(SceneData.theme("material-light.xml"), env, SceneData.measurer).build(layout, emptyMap()) as Parsed.Ok).value
    }

    private val said = mapOf(
        "a11y_key_reading_on" to "注音，已开", "a11y_key_reading_off" to "注音，已关",
        "a11y_key_panel_lock_on" to "解锁", "a11y_key_panel_lock_off" to "锁定",
    )

    private fun Scene.said(id: String) = SceneA11y.of(keys.first { it.id == id }, said::get).description

    // handwriting's 注: its words as it is on (KeyState.On) or off
    @Test
    fun theReadingKeySaidOnOrOff() {
        assertEquals("注音，已关", board("handwriting.xml", SceneData.portrait).said("reading"))
        assertEquals("注音，已开", board("handwriting.xml", SceneData.portrait.copy(states = mapOf("reading" to setOf(KeyState.On)))).said("reading"))
    }

    // the symbol panel's lock: what a tap does, shut (KeyState.Locked) or open
    @Test
    fun theLockSaidAsWhatATapDoes() {
        assertEquals("锁定", board("symbols.xml", SceneData.portrait).said("lock"))
        assertEquals("解锁", board("symbols.xml", SceneData.portrait.copy(states = mapOf("lock" to setOf(KeyState.Locked)))).said("lock"))
    }

    // a toggle's words that are no string's name (a layout's own text) are said as they are, on or off
    @Test
    fun aTogglesWordsNotANameSaidAsTheyAre() {
        val s = board("handwriting.xml", SceneData.portrait.copy(states = mapOf("reading" to setOf(KeyState.On))))
        val k = s.keys.first { it.id == "reading" }
        val plain = KeyNode(
            k.id, k.copy, k.kind, k.role, k.shape, k.bounds, k.hit, k.anchor, k.up, k.states, k.faces, "注音", k.actions, k.label,
        )
        assertEquals("注音", SceneA11y.of(plain, said::get).description)
    }

    @Test
    fun aKeyNotUsableIsNotEnabled() {
        val s = scene(SceneData.portrait.copy(states = mapOf("space" to setOf(KeyState.Disabled))))
        assertFalse(s.speech("space").enabled)
        assertTrue(s.speech("q").enabled)
    }
}
