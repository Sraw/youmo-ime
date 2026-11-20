/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.broadcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.plusAssign

class PunctuationComponentTest {

    private class Recorder : UniqueComponent<Recorder>(), InputBroadcastReceiver {
        val updates = mutableListOf<Map<String, String>>()
        override fun onPunctuationUpdate(mapping: Map<String, String>) {
            updates += mapping
        }
    }

    private class Fixture {
        val recorder = Recorder()
        val punctuation = PunctuationComponent()

        init {
            val scope = DynamicScope()
            scope += InputBroadcaster()
            scope += recorder
            scope += punctuation
        }
    }

    @Test
    fun theHostsMarksAreShownAndBroadcast() {
        val f = Fixture()
        f.punctuation.update(mapOf("," to "，", "." to "。"))
        assertTrue(f.punctuation.enabled)
        assertEquals("，", f.punctuation.transform(","))
        assertEquals("。", f.punctuation.transform("."))
        assertEquals("unmapped keys pass through", "!", f.punctuation.transform("!"))
        assertEquals(listOf(mapOf("," to "，", "." to "。")), f.recorder.updates)
    }

    @Test
    fun noMarksMapNothingAndTellNoChange() {
        val f = Fixture()
        f.punctuation.update(emptyMap())
        assertFalse(f.punctuation.enabled)
        assertEquals(",", f.punctuation.transform(","))
        assertEquals(emptyList<Map<String, String>>(), f.recorder.updates)
    }

    // off and on switch in the status's own call; a status not told (null, replayed before the host said) keeps them
    @Test
    fun offAndOnSwitchInTheCallItselfAndNotToldKeeps() {
        val f = Fixture()
        f.punctuation.update(mapOf("," to "，"))
        assertEquals("，", f.punctuation.transform(","))
        f.punctuation.update(null)
        assertEquals("，", f.punctuation.transform(","))
        f.punctuation.update(emptyMap())
        assertEquals(",", f.punctuation.transform(","))
        f.punctuation.update(emptyMap())
        f.punctuation.update(mapOf("," to "，"))
        assertEquals("，", f.punctuation.transform(","))
        assertEquals(listOf(mapOf("," to "，"), emptyMap(), mapOf("," to "，")), f.recorder.updates)
    }

    @Test
    fun theKeysTypingThemselvesClearedShowTheTableAgain() {
        val f = Fixture()
        f.punctuation.update(mapOf("," to "，"))
        f.punctuation.updateAsTyped(setOf(","))
        assertEquals(",", f.punctuation.transform(","))
        f.punctuation.updateAsTyped(emptySet())
        assertEquals("，", f.punctuation.transform(","))
    }

    // each key shown as typed: the engine's commit filter turns marks and own characters alike (, shown, ， typed)
    @Test
    fun fullWidthTurnsWhatEachKeyTypes() {
        val f = Fixture()
        val marks = mapOf("," to "，", "." to "。")
        f.punctuation.update(emptyMap(), fullWidth = true)
        assertEquals("\uFF0C", f.punctuation.transform(","))
        assertEquals("\uFF0E", f.punctuation.transform("."))
        assertTrue(f.punctuation.enabled)
        f.punctuation.update(marks, fullWidth = true)
        assertEquals("，", f.punctuation.transform(","))
        assertEquals("。", f.punctuation.transform("."))
        f.punctuation.updateAsTyped(setOf("."))
        assertEquals("\uFF0E", f.punctuation.transform("."))
        assertEquals("\uFF0E", f.punctuation.mapping["."])
        f.punctuation.update(marks, fullWidth = false)
        assertEquals(".", f.punctuation.transform("."))
        assertEquals("，", f.punctuation.transform(","))
        f.punctuation.update(emptyMap(), fullWidth = false)
        assertEquals(",", f.punctuation.transform(","))
        assertFalse(f.punctuation.enabled)
    }

    @Test
    fun knownOnceTold() {
        val f = Fixture()
        assertFalse(f.punctuation.known)
        f.punctuation.update(null)
        assertFalse(f.punctuation.known)
        f.punctuation.update(emptyMap())
        assertTrue(f.punctuation.known)
    }
}
