/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.EnginesFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HostCore] on a real [Engines] (the fixture's tiny pinyin data), through [EnginesHost]. */
class HostCoreEnginesTest : EnginesFixture() {

    private val clock = VirtualHostClock(epochMillis = 1_800_000_000_000)

    private fun engines() = Engines(::load, null, warmUps = inPlace, learnClock = clock, uptimeMillis = clock::uptimeMillis)

    private fun core(engines: Engines) =
        HostCore(EnginesHost(engines), clock, Profile.load(null, Engines.LISTED.map { it.name }.toSet(), "Default"), firstRun = true)

    @Test
    fun `typing ni and picking commits 你 on pinyin`() {
        val core = core(engines())
        val log = mutableListOf<HostEvent>()
        core.events.handlers += { log += it }
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.focus(true)
        assertTrue(core.sendKey(Key('n'.code), virtual = true))
        assertTrue(core.sendKey(Key('i'.code), virtual = true))
        assertEquals("ni", log.filterIsInstance<HostEvent.InputPanel>().last().preedit.toString())
        val shown = log.filterIsInstance<HostEvent.Candidates>().last().candidates.map { it.text }
        assertTrue("你" in shown)
        assertTrue(core.select(shown.indexOf("你"), "你"))
        assertEquals("你", log.filterIsInstance<HostEvent.Commit>().first().text)
    }

    @Test
    fun `the events flow keeps every event for a collector that starts before them`() = runTest {
        val core = core(engines())
        val got = mutableListOf<HostEvent>()
        val job = launch { core.events.events.take(3).toList(got) }
        testScheduler.runCurrent()
        core.activate(1)
        core.focus(true)
        repeat(3) { core.sendKey(Key('n'.code), virtual = true) }
        job.join()
        assertEquals(3, got.size)
        assertEquals(HostEvent.InputMethodChanged(Engines.PINYIN), got.first())
    }
}
