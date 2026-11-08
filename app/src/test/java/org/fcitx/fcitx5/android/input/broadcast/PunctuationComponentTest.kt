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

/** An input-layer component built the way InputView builds it: a [DynamicScope], no Android. */
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

    // the keys shown as the engine types in the status's own call, before any key after it: off and on switch
    // there and then; a status not told (null, replayed before the host said) keeps them (060-render-p2 S7a)
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

    // a new host's keys typing themselves are told anew: what an earlier one told goes (060-render-p2 S7a)
    @Test
    fun theKeysTypingThemselvesClearedShowTheTableAgain() {
        val f = Fixture()
        f.punctuation.update(mapOf("," to "，"))
        f.punctuation.updateAsTyped(setOf(","))
        assertEquals(",", f.punctuation.transform(","))
        f.punctuation.updateAsTyped(emptySet())
        assertEquals("，", f.punctuation.transform(","))
    }
}
