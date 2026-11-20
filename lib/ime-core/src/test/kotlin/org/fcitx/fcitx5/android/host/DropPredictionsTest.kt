/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 联想's ×: [HostCore.dropPredictions] puts away only candidates marked [HostEvent.Candidates.predicting]; what is typed stays. */
class DropPredictionsTest {

    // the session's answer to any key [next]; the ×'s and a reset's, nothing shown
    private class Host : EngineHost {
        var next = NOTHING
        val kinds = mutableListOf<Int>()
        override fun event(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot {
            kinds += event
            return if (event == EngineEvent.DROP_PREDICTIONS || event == EngineEvent.RESET) NOTHING else next
        }
        override fun candidates(im: String, from: Int, count: Int) = emptyList<Choice>()
        override fun offers(im: String, index: Int) = emptySet<Offer>()
        override fun settings(settings: EngineSettings) = Unit
    }

    // a bar of its own for each row: what one row showed is never the next row's
    private class Bar {
        val host = Host()
        val core = HostCore(host, VirtualHostClock(), testProfile("D"), firstRun = true).also {
            it.activate(1)
            it.setCapabilities(Capability.PREEDIT or Capability.COMMIT_STRING_WITH_CURSOR)
            it.focus(true)
        }
        val log = mutableListOf<HostEvent>().also { l -> core.events += HostEvents { l += it } }

        fun shown(s: Snapshot) {
            host.next = s
            core.sendKey(Key(KeySyms.fromUnicode('a'.code)))
        }

        fun lastCandidates() = log.filterIsInstance<HostEvent.Candidates>().last()
    }

    // the × sends no Escape and resets nothing, the bug DropPredictionsEnginesTest pins
    @Test
    fun theCrossDropsThePredictionsAlone() {
        assertRows(
            "联想 shown: the bar told so; the × puts them away (no Escape, no reset), the list empty" to {
                with(Bar()) {
                    shown(PREDICTING)
                    assertTrue(lastCandidates().predicting)
                    host.kinds.clear()
                    assertTrue(core.dropPredictions())
                    assertEquals(listOf(EngineEvent.DROP_PREDICTIONS), host.kinds)
                    assertEquals(HostEvent.Candidates.EMPTY, lastCandidates())
                }
            },
            "something typed: not 联想's, the × not there; a tap that came late drops nothing" to {
                with(Bar()) {
                    shown(COMPOSING)
                    assertFalse(lastCandidates().predicting)
                    host.kinds.clear()
                    assertFalse(core.dropPredictions())
                    assertEquals(emptyList<Int>(), host.kinds)
                    assertEquals(listOf("你", "尼"), lastCandidates().candidates.map { it.text })
                }
            },
            "nothing shown: nothing to drop" to {
                with(Bar()) {
                    shown(NOTHING.copy(handled = true))
                    host.kinds.clear()
                    assertFalse(core.dropPredictions())
                    assertEquals(emptyList<Int>(), host.kinds)
                }
            },
        )
    }

    // 空格上屏联想词: the bar draws the prediction space takes as the first, none when space is a space
    @Test
    @Uat("PY-160")
    fun theBarLeadsWithWhatSpaceTakes() {
        fun row(why: String, shown: Snapshot, lead: Int) = why to {
            with(Bar()) {
                shown(shown)
                assertEquals(lead, lastCandidates().lead)
            }
        }
        assertRows(
            row("off: the first, as ever", PREDICTING, lead = 0),
            row("on: past an emoji, the one space takes", PREDICTING.copy(spacePicks = 1, spaceMarks = true), lead = 1),
            row("on, space a space: none", PREDICTING.copy(spaceMarks = true), lead = -1),
            row("typing: the first", COMPOSING, lead = 0),
        )
    }

    private companion object {
        val NOTHING = Snapshot("", "", emptyList(), 0, false, false, handled = false, predicting = false)
        val PREDICTING = Snapshot("", "", listOf("们", "好"), 0, false, false, handled = true, predicting = true)
        val COMPOSING = Snapshot("", "ni", listOf("你", "尼"), 0, false, false, handled = true, predicting = false)
    }
}
