/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
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

/**
 * 联想's × (0.6.1): the bar's candidates say whether they are 联想's ([HostEvent.Candidates.predicting]), and
 * [HostCore.dropPredictions] puts those alone away; what is typed it leaves.
 */
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

    private val host = Host()
    private val core = HostCore(host, VirtualHostClock(), Profile.load(null, InputMethodCatalog.LISTED.map { it.name }.toSet(), "D"), firstRun = true).also {
        it.activate(1)
        it.setCapabilities(Capability.PREEDIT or Capability.COMMIT_STRING_WITH_CURSOR)
        it.focus(true)
    }
    private val log = mutableListOf<HostEvent>().also { l -> core.events += HostEvents { l += it } }

    private fun shown(s: Snapshot) {
        host.next = s
        core.sendKey(Key(KeySyms.fromUnicode('a'.code)))
    }

    private fun lastCandidates() = log.filterIsInstance<HostEvent.Candidates>().last()

    @Test
    fun theCrossDropsThePredictionsAloneAsEscapeDoes() {
        assertRows(
            "联想 shown: the bar told so; the × puts them away (no Escape, no reset), the list empty" to {
                shown(PREDICTING)
                assertTrue(lastCandidates().predicting)
                host.kinds.clear()
                assertTrue(core.dropPredictions())
                assertEquals(listOf(EngineEvent.DROP_PREDICTIONS), host.kinds)
                assertEquals(HostEvent.Candidates.EMPTY, lastCandidates())
            },
            "something typed: not 联想's, the × not there; a tap that came late drops nothing" to {
                shown(COMPOSING)
                assertFalse(lastCandidates().predicting)
                host.kinds.clear()
                assertFalse(core.dropPredictions())
                assertEquals(emptyList<Int>(), host.kinds)
                assertEquals(listOf("你", "尼"), lastCandidates().candidates.map { it.text })
            },
            "nothing shown: nothing to drop" to {
                shown(NOTHING.copy(handled = true))
                host.kinds.clear()
                assertFalse(core.dropPredictions())
                assertEquals(emptyList<Int>(), host.kinds)
            },
        )
    }

    private companion object {
        val NOTHING = Snapshot("", "", emptyList(), 0, false, false, handled = false, predicting = false)
        val PREDICTING = Snapshot("", "", listOf("们", "好"), 0, false, false, handled = true, predicting = true)
        val COMPOSING = Snapshot("", "ni", listOf("你", "尼"), 0, false, false, handled = true, predicting = false)
    }
}
