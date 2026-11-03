/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.EnginesFixture
import org.fcitx.fcitx5.android.engine.runtime.EnginesHost
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.runtime.type
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HostCore] on a real [Engines] (the fixture's tiny pinyin data), through [EnginesHost]. */
class HostCoreEnginesTest : EnginesFixture() {

    private val clock = VirtualHostClock(epochMillis = 1_800_000_000_000)

    private fun engines() = Engines(::load, null, warmUps = inPlace, clock = clock)

    private fun core(engines: Engines) =
        HostCore(EnginesHost(engines), clock, Profile.load(null, InputMethodCatalog.LISTED.map { it.name }.toSet(), "Default"), firstRun = true)

    @Test
    fun `typing ni and picking commits 你 on pinyin`() {
        val core = core(engines())
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
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

    // 清除 while typing: what is typed goes, nothing committed; 恢复 types it again, the same keys
    @Test
    fun `what is typed is cleared without a commit and typed again`() {
        val core = core(engines())
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.focus(true)
        assertNull("nothing typed", core.clearTyped())
        "nihao".forEach { core.sendKey(Key(it.code), virtual = true) }
        val typed = log.filterIsInstance<HostEvent.InputPanel>().last().preedit.toString()
        val typing = core.clearTyped()!!
        assertTrue(core.isEmpty())
        assertTrue(log.none { it is HostEvent.Commit })
        assertTrue(core.retype(typing))
        assertEquals(typed, log.filterIsInstance<HostEvent.InputPanel>().last().preedit.toString())
        assertTrue(log.none { it is HostEvent.Commit })
        core.setCurrentInputMethod(InputMethodCatalog.ENGLISH)
        assertFalse("another input method", core.retype(typing))
        core.setCurrentInputMethod(InputMethodCatalog.PINYIN)
        core.reset()
        "nihao".forEach { core.sendKey(Key(it.code), virtual = true) }
        val shown = log.filterIsInstance<HostEvent.Candidates>().last().candidates.map { it.text }
        assertTrue(core.select(shown.indexOf("你"), "你"))
        // pinyin holds a part chosen till the rest is: nothing committed; a pick sent again by its
        // index could choose another word, so what is typed then is dropped for good (review M2)
        val commits = log.filterIsInstance<HostEvent.Commit>()
        val picked = core.clearTyped()!!
        assertNull("a pick is not typed again", picked.keys)
        assertFalse(core.retype(picked))
        assertTrue(core.isEmpty())
        assertEquals(commits, log.filterIsInstance<HostEvent.Commit>())
        // typed again after: a fresh log, the keys kept again
        "ni".forEach { core.sendKey(Key(it.code), virtual = true) }
        assertEquals(2, core.clearTyped()!!.keys!!.size)
    }

    // review2: the keyboard is the app's to tell, the keys' codes each going to a session of their own
    // (KeyboardPair): the nine keys' typed again on the full keyboard would type a session shown on none
    @Test
    fun `what is typed is typed again on the keyboard it was typed with alone`() {
        val core = core(engines())
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.focus(true)
        fun preedit() = log.filterIsInstance<HostEvent.InputPanel>().last().preedit.toString()
        "64".forEach { core.sendKey(Key(KeySyms.fromUnicode(Keyboard.pinyinNineKey(it))), virtual = true) }
        assertEquals("ni", preedit())
        val typing = core.clearTyped { "nine keys of $it" }!!
        assertEquals("nine keys of ${InputMethodCatalog.PINYIN}", typing.keyboard)
        assertFalse("another keyboard", core.retype(typing, "full keys"))
        assertTrue(core.isEmpty())
        assertTrue(core.retype(typing, "nine keys of ${InputMethodCatalog.PINYIN}"))
        assertEquals("ni", preedit())
        assertTrue(log.none { it is HostEvent.Commit })
    }

    // review2 M2: a digit or the keypad's digit picks while something is typed, a part of it
    // chosen as a pick's is: never typed again, the list reordered since could give another word
    @Test
    fun `what is typed after a key picks a part of it is never typed again`() {
        val core = core(engines())
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.focus(true)
        fun candidates() = log.filterIsInstance<HostEvent.Candidates>().last().candidates.map { it.text }
        // nihao's third is 你, a part, held till the rest is chosen. Space picks the same way (Keyboard's
        // select) but takes the first, the whole sentence here: no part of it to hold
        val picks = listOf(
            Triple("3", "nihao", Key('3'.code)),
            Triple("the keypad's 3", "nihao", Key(KeySyms.KP_0 + 3)),
        )
        for ((name, typed, key) in picks) {
            core.reset()
            typed.forEach { core.sendKey(Key(it.code), virtual = true) }
            val shown = candidates()
            assertTrue(name, core.sendKey(key, virtual = true))
            assertTrue("$name: a part of $shown taken, the rest typed", !core.isEmpty())
            assertTrue("$name: nothing committed: the pick alone tells", log.none { it is HostEvent.Commit })
            val typing = core.clearTyped()!!
            assertNull("$name: ${typing.keys}", typing.keys)
            assertFalse(core.retype(typing))
        }
    }

    // review M2: a candidate's long-press action (forget, pin, block) sent again by its index could
    // land on another word, the list reordered by it: none is typed again, nor what follows it
    @Test
    fun `what is typed after a candidate's action is never typed again`() {
        val core = core(engines())
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.focus(true)
        "ni".forEach { core.sendKey(Key(it.code), virtual = true) }
        val shown = log.filterIsInstance<HostEvent.Candidates>().last().candidates.map { it.text }
        val i = shown.indices.first { core.candidateActions(it).isNotEmpty() }
        val action = core.candidateActions(i).first()
        core.triggerCandidateAction(i, action.id, shown[i])
        assertTrue("still typing", !core.isEmpty())
        core.sendKey(Key('h'.code), virtual = true)
        val typing = core.clearTyped()!!
        assertNull(typing.keys)
        assertFalse(core.retype(typing))
    }

    @Test
    fun `every listener hears every event, in the order they are added`() {
        val core = core(engines())
        val heard = mutableListOf<Pair<Int, HostEvent>>()
        core.events += HostEvents { heard += 1 to it }
        core.events += HostEvents { heard += 2 to it }
        core.activate(1)
        core.focus(true)
        assertEquals(HostEvent.InputMethodChanged(InputMethodCatalog.PINYIN), heard.first().second)
        assertEquals(heard.size / 2, heard.count { it.first == 1 })
        assertEquals(heard.filter { it.first == 1 }.map { it.second }, heard.filter { it.first == 2 }.map { it.second })
        heard.chunked(2).forEach { (a, b) -> assertEquals(listOf(1, 2), listOf(a.first, b.first)) }
    }

    // review B 3.9: the date, time and weekday phrases are the replay's clock's (LearnClock.calendar),
    // the same on every run, never the system's: 2026-10-17 09:05 UTC, a Saturday
    @Test
    fun `the date and time phrases are the virtual clock's`() {
        val at = VirtualHostClock(epochMillis = 1_792_227_900_000)
        val engines = Engines(::load, null, warmUps = inPlace, clock = at)
        val core = HostCore(EnginesHost(engines), at, Profile.load(null, InputMethodCatalog.LISTED.map { it.name }.toSet(), "Default"), firstRun = true)
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.focus(true)
        fun shown(keys: String): List<String> {
            core.reset()
            keys.forEach { core.sendKey(Key(it.code), virtual = true) }
            return log.filterIsInstance<HostEvent.Candidates>().last().candidates.map { it.text }
        }
        assertTrue(shown("rq").containsAll(listOf("2026年10月17日", "2026-10-17", "二〇二六年十月十七日")))
        assertTrue(shown("sj").containsAll(listOf("09:05", "09时05分")))
        assertTrue(shown("xq").containsAll(listOf("星期六", "周六")))
        // and those a table's word for them brings (日期's: PhraseBook.WORD_KEYS)
        engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.RESET, 0)
        engines.settings = wubiShown
        assertTrue("2026年10月17日" in engines.type(InputMethodCatalog.WUBI, "jjad").candidates)
        engines.close()
    }

    // review LOW (S13): an Ink key quick input had, never reaching handwriting, holds its reading
    // for the next, as before the key carried it; dropped, the stroke's ink stayed on the pad
    @Test
    fun `ink written under quick input is shown with the next stroke after it`() {
        val engines = engines()
        val modules = ChineseModules(ModuleInputs(PunctuationProfile.parse("")))
        val profile = Profile.load(null, InputMethodCatalog.LISTED.map { it.name }.toSet(), "Default")
        val core = HostCore(EnginesHost(engines), clock, profile, firstRun = true, modules = modules::build)
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.focus(true)
        core.setInputMethods(listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.HANDWRITING, ImGroup.KEYBOARD_US))
        core.setCurrentInputMethod(InputMethodCatalog.HANDWRITING)
        // what KotlinHost does with what the pad read (EngineBridge.sendInk): the ink, its key, then inkSent
        fun write(piece: Int, vararg chars: String) {
            val ink = Action.Ink(piece, InkReading(chars.toList(), DoubleArray(chars.size)))
            engines.ink(ink, shown = true)
            core.sendKey(Key(KeySyms.UNICODE_OFFSET + Keyboard.INK, KeyStates.VIRTUAL), virtual = true, payload = ink)
            engines.inkSent(ink)
        }
        core.triggerQuickPhrase()
        write(0, "你")
        assertTrue("quick input had the key", log.filterIsInstance<HostEvent.InputPanel>().none { it.preedit.toString() == "你" })
        core.sendKey(Key(KeySyms.ESCAPE))
        write(1, "好")
        assertEquals("你好", log.filterIsInstance<HostEvent.InputPanel>().last().preedit.toString())
    }
}
