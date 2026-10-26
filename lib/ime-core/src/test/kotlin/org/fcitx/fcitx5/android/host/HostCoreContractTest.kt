/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HostCore] and [KeyRouter] against a fake engine host. Lines: `ae` is
 * `app/src/main/cpp/androidengine/androidengine.cpp`, `af` `androidfrontend/androidfrontend.cpp`
 * (both at the tag `native-last`), `fcitx/…` upstream fcitx5 442edbc9 `src/lib/fcitx/…` (`lib/ime-core/tools/upstream.sh fcitx5`).
 */
class HostCoreContractTest {

    /** Records every call; answers with [answer]. */
    private inner class FakeHost : EngineHost {
        val calls = mutableListOf<String>()
        var answer: (im: String, event: Int, arg: Int) -> Snapshot? = { _, _, _ -> snap() }
        var throwOn: (Int) -> Boolean = { false }
        var throwable: () -> Throwable = { IllegalStateException("boom") }
        var all: List<Choice> = emptyList()
        var offered: Set<Offer> = emptySet()
        val fetches = mutableListOf<Pair<Int, Int>>()

        override fun event(im: String, event: Int, arg: Int, learning: Boolean): Snapshot? {
            calls += "$im $event $arg${if (learning) "" else " nolearn"}"
            if (throwOn(event)) throw throwable()
            return answer(im, event, arg)
        }

        override fun candidates(im: String, from: Int, count: Int): List<Choice> {
            fetches += from to count
            return all.drop(from).take(count)
        }

        override fun offers(im: String, index: Int) = offered
        override fun settings(flat: String) {
            calls += "settings"
        }
    }

    private fun snap(
        commit: String = "",
        preedit: String = "",
        candidates: List<String> = emptyList(),
        handled: Boolean = true,
        total: Int = candidates.size,
        first: Int = 0,
        refines: Boolean = false,
        actionable: Boolean = false,
        deleteBefore: Int = 0,
        cursor: Int = -1,
    ) = Snapshot(commit, preedit, candidates, 0, false, false, handled, false, total = total, first = first,
        refines = refines, actionable = actionable, deleteBefore = deleteBefore, cursor = cursor)

    private var fake = FakeHost()
    private var clock = VirtualHostClock()
    private val persisted = mutableListOf<Persist>()

    private fun core(
        profile: Profile = Profile.load(null, Engines.LISTED.map { it.name }.toSet(), "Default"),
        firstRun: Boolean = true,
        modules: (HostServices) -> HostModules = { HostModules() },
    ) = HostCore(fake, clock, profile, firstRun = firstRun, persist = { persisted += it }, modules = modules)

    private fun HostCore.log(): MutableList<HostEvent> = mutableListOf<HostEvent>().also { l -> events.handlers += { l += it } }

    private fun HostCore.open(uid: Int = 1, caps: Long = Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT or Capability.COMMIT_STRING_WITH_CURSOR) {
        activate(uid)
        setCapabilities(caps)
        focus(true)
        // the first run's new list shows the name 30 ms on (fcitx/instance.cpp:1213-1229): past it, and hidden
        clock.advance(2 * HostCore.INFO_MILLIS)
    }

    private fun HostCore.press(c: Char, virtual: Boolean = true) = sendKey(Key(c.code), virtual = virtual)

    // ---- first run and profile

    @Test
    fun `first run is the Default group, pinyin first, keyboard-us default, and saved`() {
        // fcitx/inputmethodmanager.cpp:147-162 + native-last:app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:437-447
        val listed = Engines.LISTED.map { it.name }.toSet()
        fun firstRun(available: Set<String>, expected: List<String>) {
            fake = FakeHost()
            clock = VirtualHostClock()
            persisted.clear()
            val core = HostCore(fake, clock, Profile.load(null, available, "Default"), available, firstRun = true, persist = { persisted += it })
            assertEquals(expected, core.contexts.group.inputMethods)
            assertEquals(ImGroup.KEYBOARD_US, core.contexts.group.defaultInputMethod)
            // saved when it changed the default profile (the keyboard alone is that profile)
            val saved = persisted.filterIsInstance<Persist.ProfileChanged>().lastOrNull()?.profile?.currentGroup()?.inputMethods
            assertEquals(expected.takeIf { Engines.PINYIN in it }, saved)
        }
        assertRows(
            "the engines list pinyin" to { firstRun(listed, listOf(Engines.PINYIN, ImGroup.KEYBOARD_US)) },
            // F4199: HostCore's init puts pinyin first only when the engines list it
            "no pinyin listed keeps the keyboard alone (F4199)" to { firstRun(listed - Engines.PINYIN, listOf(ImGroup.KEYBOARD_US)) },
        )
    }

    @Test
    fun `a later run keeps the profile as it is`() {
        val profile = Profile.load(Profile.default("Default").text(), setOf(ImGroup.KEYBOARD_US, Engines.PINYIN), "Default")
        val core = core(profile, firstRun = false)
        assertEquals(listOf(ImGroup.KEYBOARD_US), core.contexts.group.inputMethods)
        assertTrue(persisted.isEmpty())
    }

    // ---- activation, events order

    @Test
    fun `focus activates the input method, resetting the session, and tells the app`() {
        // fcitx/instance.cpp:1069-1091,2454-2494; ae:492-513; af:327-335
        val core = core()
        val log = core.log()
        core.open()
        assertEquals("engine-pinyin ${EngineEvent.RESET} 0", fake.calls.last())
        assertEquals(HostEvent.InputMethodChanged(Engines.PINYIN), log.first())
    }

    @Test
    fun `a commit goes out before the panel that clears its preedit, the panel once`() {
        // fcitx/inputcontext.cpp:433-466 (commit at once), ae:723-739 (show)
        val core = core()
        core.open()
        fake.answer = { _, e, _ -> if (e == EngineEvent.CHAR) snap(commit = "你", preedit = "", handled = true) else snap() }
        val log = core.log()
        assertTrue(core.press('a'))
        assertEquals(HostEvent.Commit("你", -1), log[0])
        assertEquals(1, log.count { it is HostEvent.InputPanel })
        assertTrue(log.indexOfFirst { it is HostEvent.InputPanel } > 0)
    }

    @Test
    fun `a pinyin code shows above the candidates, English's word in the app underlined`() {
        // ae:241-258 showPreedit, ae:375-383 composesText, inputcontext.cpp:69-75 masks Preedit
        val core = core()
        core.open()
        fake.answer = { _, e, _ -> if (e == EngineEvent.CHAR) snap(preedit = "ni", candidates = listOf("你")) else snap() }
        val log = core.log()
        core.press('n')
        val panel = log.filterIsInstance<HostEvent.InputPanel>().last()
        assertEquals("ni", panel.preedit.toString())
        assertTrue(log.none { it is HostEvent.ClientPreedit })
        core.setCurrentInputMethod(ImGroup.KEYBOARD_US)
        fake.answer = { _, e, _ -> if (e == EngineEvent.CHAR) snap(preedit = "he", candidates = listOf("hello")) else snap() }
        core.press('h')
        val client = log.filterIsInstance<HostEvent.ClientPreedit>().last()
        assertEquals("he", client.text.toString())
        assertEquals(1 shl 3, client.text.flags[0])
    }

    @Test
    fun `an untaken key is handed back, release too`() {
        // af:385-393
        val core = core()
        core.open()
        fake.answer = { _, _, _ -> snap(handled = false) }
        val log = core.log()
        assertFalse(core.sendKey(Key(KeySyms.RETURN), timestamp = 5))
        core.sendKey(Key(KeySyms.RETURN), release = true, timestamp = 6)
        assertEquals(listOf(HostEvent.Key(KeySyms.RETURN, 0, 0x0d, false, 5), HostEvent.Key(KeySyms.RETURN, 0, 0x0d, true, 6)), log.filterIsInstance<HostEvent.Key>())
    }

    @Test
    fun `an unfocused context hands every key back`() {
        // inputcontext.cpp:333-335 RETURN_IF_HAS_NO_FOCUS
        val core = core()
        core.activate(1)
        val log = core.log()
        assertFalse(core.press('a'))
        assertEquals(1, log.count { it is HostEvent.Key })
        assertTrue(fake.calls.isEmpty())
    }

    // ---- KeyRouter branches

    @Test
    fun `shortcuts are the app's`() {
        // ae:557-566; the mark on offer going in first: KeyRouterPunctuationTest
        val core = core()
        core.open()
        val n = fake.calls.size
        assertFalse(core.sendKey(Key('C'.code, KeyStates.CTRL)))
        assertEquals(n, fake.calls.size)
    }

    @Test
    fun `keys map to their events, keypad apart, controls as Other`() {
        // ae:650-673
        val core = core()
        core.open()
        fake.answer = { _, _, _ -> snap(handled = false) }
        core.sendKey(Key(KeySyms.BACKSPACE))
        core.sendKey(Key(KeySyms.KP_0 + 2))
        core.sendKey(Key(KeySyms.TAB))
        core.sendKey(Key(KeySyms.PAGE_DOWN))
        assertEquals(
            listOf("${EngineEvent.BACKSPACE} 0", "${EngineEvent.KEYPAD} ${'2'.code}", "${EngineEvent.OTHER} ${KeySyms.TAB}", "${EngineEvent.PAGE_DOWN} 0"),
            fake.calls.takeLast(4).map { it.substringAfter(' ') },
        )
    }

    @Test
    fun `hardware minus and equal turn pages only while typing`() {
        // ae:639-649
        val core = core()
        core.open()
        fake.answer = { _, e, _ -> if (e == EngineEvent.CHAR) snap(preedit = "ni", candidates = listOf("你")) else snap() }
        core.sendKey(Key(KeySyms.MINUS), virtual = false)
        assertEquals("${EngineEvent.CHAR} ${'-'.code}", fake.calls.last().substringAfter(' '))
        core.sendKey(Key(KeySyms.EQUAL), virtual = false)
        assertEquals("${EngineEvent.PAGE_DOWN} 0", fake.calls.last().substringAfter(' '))
        core.sendKey(Key(KeySyms.EQUAL), virtual = true)
        assertEquals("${EngineEvent.CHAR} ${'='.code}", fake.calls.last().substringAfter(' '))
    }

    @Test
    fun `English that suggests nothing tells the session what the screen key typed, none in a password`() {
        // ae:583-638 asIs, ae:681-687 suggestsEnglish
        val core = core()
        core.open(caps = Capability.PREEDIT or Capability.NO_SPELL_CHECK)
        core.setCurrentInputMethod(ImGroup.KEYBOARD_US)
        assertFalse(core.press('a'))
        assertEquals("keyboard-us ${EngineEvent.TYPED} ${'a'.code}", fake.calls.last())
        core.press('a', virtual = false)
        assertEquals("keyboard-us ${EngineEvent.ENTER} 0", fake.calls.last())
        // a glide is swallowed, and the key after it is the session's
        assertTrue(core.sendKey(Key(KeySyms.UNICODE_OFFSET or KeyRouter.GLIDE_CHAR)))
        core.press('.')
        assertEquals("keyboard-us ${EngineEvent.CHAR} ${'.'.code}", fake.calls.last())
    }

    @Test
    fun `a nine-keys letter commits itself in its case`() {
        // ae:596-606
        val core = core()
        core.open(caps = Capability.NO_SPELL_CHECK)
        core.setCurrentInputMethod(ImGroup.KEYBOARD_US)
        val log = core.log()
        assertTrue(core.sendKey(Key(KeySyms.UNICODE_OFFSET or (0xF0060 + 26 + 1))))
        assertEquals(HostEvent.Commit("B", -1), log.filterIsInstance<HostEvent.Commit>().single())
    }

    @Test
    fun `learning is off in a sensitive field`() {
        // ae:696
        val core = core()
        core.open(caps = Capability.SENSITIVE)
        assertTrue(fake.calls.last().endsWith("nolearn"))
    }

    // ---- failure fallback

    @Test
    fun `a failing session clears the panel, is reset, and one failing reset is not reset again`() {
        // native-last:app/src/main/cpp/native-lib.cpp:722-745
        fun fails(throwable: () -> Throwable) {
            fake = FakeHost()
            clock = VirtualHostClock()
            val core = core()
            core.open()
            fake.answer = { _, _, _ -> snap(preedit = "ni", candidates = listOf("你")) }
            core.press('n')
            fake.throwable = throwable
            fake.throwOn = { true }
            assertFalse(core.press('i'))
            assertTrue(core.isEmpty())
            assertEquals("engine-pinyin ${EngineEvent.RESET} 0", fake.calls.last())
            val n = fake.calls.size
            core.press('i')
            // the reset failed too: this time only the key
            assertEquals(n + 1, fake.calls.size)
        }
        assertRows(
            "an exception" to { fails { IllegalStateException("boom") } },
            // F4195: native's engineFailed took any pending throwable (native-last:app/src/main/cpp/native-lib.cpp:510-528)
            "an engine out of memory (F4195)" to { fails { OutOfMemoryError() } },
        )
    }

    @Test
    fun `deactivating an unknown uid leaves the focused context as it is (androidfrontend 485-490)`() {
        val core = core()
        // no CLIENT_UNFOCUS_COMMIT: a focus out would commit the client preedit
        core.open(caps = Capability.PREEDIT)
        fake.answer = { _, _, _ -> snap(preedit = "ni", candidates = listOf("你")) }
        core.press('n')
        val log = core.log()
        val calls = fake.calls.size
        core.deactivate(99)
        assertTrue(log.filterIsInstance<HostEvent.Commit>().isEmpty())
        assertEquals(calls, fake.calls.size)
        assertTrue(core.contexts.focused?.hasFocus == true)
        assertTrue(core.contexts.active != null)
    }

    @Test
    fun `an engine that cannot load hands the key back`() {
        // EngineHost.event null
        val core = core()
        core.open()
        fake.answer = { _, _, _ -> null }
        assertFalse(core.press('a'))
    }

    // ---- candidates

    private fun HostCore.typed(all: List<String>, shown: Int = 5, total: Int = all.size, first: Int = 0) {
        fake.all = all.map { Choice(it) }
        fake.answer = { _, e, _ -> if (e == EngineEvent.CHAR || e == EngineEvent.PAGE_DOWN) snap(preedit = "a", candidates = all.drop(first).take(shown), total = total, first = first, actionable = true) else snap() }
        press('a')
    }

    @Test
    fun `the bulk list sends the first 16, fetched a chunk at a time`() {
        // af:94-123, ae:125-152 (Chunk 32)
        val core = core()
        core.open()
        val log = core.log()
        core.typed((1..40).map { "c$it" })
        val sent = log.filterIsInstance<HostEvent.Candidates>().last()
        assertEquals(40, sent.total)
        assertEquals(16, sent.candidates.size)
        assertEquals(listOf(5 to KeyRouter.CHUNK), fake.fetches)
        assertEquals((20..27).map { "c$it" }, core.candidates(19, 8).map { it.text })
    }

    @Test
    fun `an uncounted list asks till a chunk runs short`() {
        // ae:126-150, af:103-113
        val core = core()
        core.open()
        val log = core.log()
        core.typed((1..10).map { "c$it" }, total = -1)
        assertEquals(10, log.filterIsInstance<HostEvent.Candidates>().last().total)
    }

    @Test
    fun `those before the page are fetched once from the first`() {
        // ae:128-139
        val core = core()
        core.open()
        core.typed((1..20).map { "c$it" }, first = 5)
        assertEquals("c1", core.candidates(0, 1).single().text)
        core.candidates(0, 3)
        assertEquals(1, fake.fetches.count { it.first == 0 })
    }

    @Test
    fun `select checks the text after the output filters`() {
        // af:150-168,302-307
        val core = core(modules = { HostModules(outputFilters = listOf(OutputFilter { _, t -> t.copy(strings = t.strings.map { it.replace("门", "門") }.toTypedArray()) })) })
        core.open()
        core.typed(listOf("门", "们"))
        assertEquals("門", core.candidates(0, 1).single().text)
        assertFalse(core.select(0, "门"))
        assertTrue(core.select(0, "門"))
        assertEquals("engine-pinyin ${EngineEvent.PICK} 0", fake.calls.last())
        // no text, a key's pick: not checked
        core.typed(listOf("门"))
        assertTrue(core.select(0))
    }

    @Test
    fun `the paged window shows the page, labels by number, and turns while composing`() {
        // af:125-148,270-289; ae:94-121,156-172
        val core = core()
        core.open()
        core.setCandidatePagingMode(1)
        val log = core.log()
        core.typed((1..10).map { "c$it" })
        val paged = log.filterIsInstance<HostEvent.Paged>().last()
        assertEquals(FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet, paged.layoutHint)
        assertEquals(listOf("1", "2", "3", "4", "5"), paged.candidates.map { it.label })
        assertEquals(0, paged.cursorIndex)
        assertTrue(paged.hasNext)
        assertFalse(paged.hasPrev)
        core.offsetCandidatePage(1)
        assertEquals("engine-pinyin ${EngineEvent.PAGE_DOWN} 0", fake.calls.last())
    }

    @Test
    fun `the long-press menu asks the session, and acts only on the candidate it was asked for`() {
        // ae:176-211, af:233-256
        val core = core()
        core.open()
        core.typed(listOf("你", "拟"))
        fake.offered = setOf(Offer.FORGET, Offer.DELETE_PHRASE)
        val actions = core.candidateActions(0)
        assertEquals(listOf(CandidateAction(EngineEvent.FORGET, "Forget word"), CandidateAction(EngineEvent.UNPIN, "Delete from custom phrase")), actions)
        // asked of 0, triggered on 1: dropped
        core.triggerCandidateAction(1, EngineEvent.FORGET, "拟")
        assertFalse(fake.calls.last().contains(" ${EngineEvent.FORGET} "))
        // the session no longer offers it: dropped
        fake.offered = emptySet()
        core.triggerCandidateAction(0, EngineEvent.FORGET, "你")
        assertFalse(fake.calls.last().contains(" ${EngineEvent.FORGET} "))
        fake.offered = setOf(Offer.FORGET)
        core.candidateActions(0)
        core.triggerCandidateAction(0, EngineEvent.FORGET, "你")
        assertEquals("engine-pinyin ${EngineEvent.FORGET} 0", fake.calls.last())
    }

    // ---- refine

    @Test
    fun `a snapshot that refines is refined after a 30 ms pause, any key first cancels it`() {
        // ae:26-28,689-721
        val core = core()
        core.open()
        fake.answer = { _, e, _ -> if (e == EngineEvent.CHAR) snap(preedit = "n", candidates = listOf("你"), refines = true) else snap(handled = false) }
        core.press('n')
        clock.advance(29)
        core.press('i')
        clock.advance(29)
        assertFalse(fake.calls.any { it.contains(" ${EngineEvent.REFINE} ") })
        clock.advance(1)
        assertEquals("engine-pinyin ${EngineEvent.REFINE} 0", fake.calls.last())
    }

    @Test
    fun `a refining slice that changes nothing leaves the panel, the next slice at once`() {
        // ae:699-702
        val core = core()
        core.open()
        var slices = 0
        fake.answer = { _, e, _ ->
            when (e) {
                EngineEvent.CHAR -> snap(preedit = "n", candidates = listOf("你"), refines = true)
                EngineEvent.REFINE -> snap(handled = false, refines = ++slices < 3)
                else -> snap()
            }
        }
        core.press('n')
        val log = core.log()
        clock.advance(30)
        assertEquals(3, slices)
        assertTrue(log.none { it is HostEvent.InputPanel })
    }

    @Test
    fun `a menu shown stops refining`() {
        // ae:194-195
        val core = core()
        core.open()
        fake.answer = { _, e, _ -> if (e == EngineEvent.CHAR) snap(preedit = "n", candidates = listOf("你"), refines = true, actionable = true) else snap() }
        core.press('n')
        fake.offered = setOf(Offer.FORGET)
        core.candidateActions(0)
        clock.advance(100)
        assertFalse(fake.calls.any { it.contains(" ${EngineEvent.REFINE} ") })
    }

    // ---- switching and the overlay

    @Test
    fun `a switch commits what was typed, activates the new one, tells the app, shows its name a second`() {
        // fcitx/instance.cpp:1178-1212,414-452,521-531; ae:764-772; af:349-359
        val core = core()
        core.open()
        val log = core.log()
        core.toggle()
        val calls = fake.calls.takeLast(3)
        assertEquals(listOf("engine-pinyin ${EngineEvent.ENTER} 0", "engine-pinyin ${EngineEvent.RESET} 0", "keyboard-us ${EngineEvent.RESET} 0"), calls)
        assertTrue(HostEvent.SwitchInputMethod(SwitchReason.Trigger, Engines.PINYIN) in log)
        assertEquals("En", log.filterIsInstance<HostEvent.InputPanel>().last().auxUp.toString())
        clock.advance(HostCore.INFO_MILLIS)
        assertEquals("", log.filterIsInstance<HostEvent.InputPanel>().last().auxUp.toString())
    }

    @Test
    fun `a key press hides the name, a release does not`() {
        // fcitx/instance.cpp:970-976
        val core = core()
        core.open()
        fake.answer = { _, _, _ -> snap(handled = false) }
        core.toggle()
        val log = core.log()
        core.sendKey(Key(KeySyms.RETURN), release = true)
        assertTrue(log.none { it is HostEvent.InputPanel })
        core.sendKey(Key(KeySyms.RETURN))
        assertEquals("", log.filterIsInstance<HostEvent.InputPanel>().last().auxUp.toString())
    }

    @Test
    fun `a tap no one takes in handwriting's composing text commits it, then resets`() {
        // fcitx/inputmethodengine.cpp:46-54 (defaultInvokeActionBehavior), F3788
        val core = core()
        core.open()
        core.setInputMethods(listOf(Engines.PINYIN, Engines.HANDWRITING, ImGroup.KEYBOARD_US))
        core.setCurrentInputMethod(Engines.HANDWRITING)
        fake.answer = { _, e, _ -> if (e == EngineEvent.CHAR) snap(preedit = "你") else snap(handled = false) }
        core.press('a')
        val log = core.log()
        core.moveCursor(0)
        assertTrue("$log ${fake.calls}", HostEvent.Commit("你", -1) in log)
        assertTrue(fake.calls.toString(), fake.calls.last().startsWith("${Engines.HANDWRITING} ${EngineEvent.RESET} 0"))
    }

    @Test
    fun `a password field's switch ends the old input method under the old flags, starts the new under the new`() {
        // fcitx's CapabilityAboutToChange then CapabilityChanged (F3855): the old engine's ENTER learns
        // as its field did, the new engine's activation as its own field does (a throwing switch: InputContextsTest)
        fun of(calls: List<String>, im: String) = calls.filter { it.startsWith("$im ") }
        // each row its own core: the way out starts from a password field set up here, not by the row before
        fun calls(from: Long, to: Long): List<String> {
            fake = FakeHost()
            clock = VirtualHostClock()
            val core = core()
            core.open()
            if (from != 0L) core.setCapabilities(from)
            fake.calls.clear()
            core.setCapabilities(to)
            return fake.calls.toList()
        }
        assertRows(
            // the plain field's text learns, not "nolearn" under the password's flags
            "into a password field" to {
                val c = calls(0, Capability.PASSWORD)
                assertEquals(c.toString(), listOf("${Engines.PINYIN} ${EngineEvent.ENTER} 0"), c.filter { it.split(' ')[1] == "${EngineEvent.ENTER}" })
                assertTrue(c.toString(), of(c, Engines.PINYIN).none { it.endsWith(" nolearn") })
            },
            // the privacy half: what was typed in the password field is never learnt; pinyin comes
            // back learning, its activation not under the password's flags
            "out of a password field" to {
                val c = calls(Capability.PASSWORD, 0)
                assertTrue(c.toString(), of(c, ImGroup.KEYBOARD_US).all { it.endsWith(" nolearn") })
                val pinyin = of(c, Engines.PINYIN)
                assertTrue(c.toString(), "${Engines.PINYIN} ${EngineEvent.RESET} 0" in pinyin && pinyin.none { it.endsWith(" nolearn") })
            },
        )
    }

    @Test
    fun `a password field switches without the name or a switch event`() {
        // fcitx/instance.cpp:725-764,1203-1211 (CapabilityChanged not listed); S1 deviation 4
        val core = core()
        core.open()
        val log = core.log()
        core.setCapabilities(Capability.PASSWORD)
        assertEquals("keyboard-us", core.currentInputMethod())
        assertTrue(log.none { it is HostEvent.SwitchInputMethod })
        assertTrue(log.filterIsInstance<HostEvent.InputPanel>().all { it.auxUp.isEmpty() })
        assertEquals(0, clock.pending)
    }

    @Test
    fun `a new list switches the focused context without a switch event, its name after 30 ms, saved`() {
        // fcitx/instance.cpp:687-711,1213-1229; native-last:app/src/main/cpp/native-lib.cpp:196-206
        val core = core()
        core.open()
        val log = core.log()
        persisted.clear()
        core.setInputMethods(listOf(Engines.WUBI, Engines.PINYIN, ImGroup.KEYBOARD_US))
        assertTrue(log.none { it is HostEvent.SwitchInputMethod })
        assertEquals("engine-wubi ${EngineEvent.RESET} 0", fake.calls.last())
        clock.advance(HostCore.GROUP_INFO_DELAY)
        assertEquals("五", log.filterIsInstance<HostEvent.InputPanel>().last().auxUp.toString())
        assertEquals(listOf(Engines.WUBI, Engines.PINYIN, ImGroup.KEYBOARD_US), (persisted.single() as Persist.ProfileChanged).profile.currentGroup().inputMethods)
    }

    @Test
    fun `the name overlay can be turned off`() {
        // TestFcitx.kt:120's ShowInputMethodInformation, now a constructor switch
        val core = HostCore(fake, clock, Profile.load(null, Engines.LISTED.map { it.name }.toSet(), "Default"), firstRun = true, showInputMethodInformation = false)
        core.open()
        core.toggle()
        assertTrue(core.isEmpty())
        assertEquals(0, clock.pending)
    }

    @Test
    fun `focus out resets the engine without committing, temp modes leave`() {
        // fcitx/instance.cpp:1125-1153, ae:764-772, tempmodemanager.cpp:44-46
        var reset = 0
        val mode = object : TempMode {
            override fun isActive(ic: InputContext) = true
            override fun trigger(ic: InputContext, e: KeyEvent) = false
            override fun keyEvent(ic: InputContext, e: KeyEvent) = true
            override fun invokeAction(ic: InputContext, e: InvokeActionEvent) = true
            override fun reset(ic: InputContext) {
                reset++
            }
        }
        val core = core(modules = { HostModules(tempModes = listOf(mode)) })
        core.open()
        core.focus(false)
        assertEquals("engine-pinyin ${EngineEvent.RESET} 0", fake.calls.last())
        assertFalse(fake.calls.any { it.contains(" ${EngineEvent.ENTER} ") })
        assertEquals(1, reset)
    }

    @Test
    fun `without ClientUnfocusCommit the client preedit is committed on focus out`() {
        // fcitx/instance.cpp:1131-1139
        val core = core()
        core.open(caps = Capability.PREEDIT)
        core.setCurrentInputMethod(ImGroup.KEYBOARD_US)
        fake.answer = { _, e, _ -> if (e == EngineEvent.CHAR) snap(preedit = "he", candidates = listOf("hello")) else snap() }
        core.press('h')
        val log = core.log()
        core.focus(false)
        assertEquals(HostEvent.Commit("he", -1), log.filterIsInstance<HostEvent.Commit>().single())
    }

    @Test
    fun `a tap in the code row moves the session's cursor, one it does not take leaves the panel as it is`() {
        // ae:741-756, af:454-458
        val core = core()
        core.open()
        fake.answer = { _, e, _ -> snap(handled = e == EngineEvent.CURSOR) }
        core.moveCursor(1)
        assertEquals("engine-pinyin ${EngineEvent.CURSOR} 1", fake.calls.last())
        // refused, its empty snapshot is not shown: the code typed stays on the panel (F3815)
        fake.answer = { _, e, _ -> if (e == EngineEvent.CURSOR) snap(handled = false) else snap(preedit = "ab") }
        core.press('a')
        val log = core.log()
        core.moveCursor(1)
        assertEquals("engine-pinyin ${EngineEvent.CURSOR} 1", fake.calls.last())
        assertTrue(log.none { it is HostEvent.InputPanel || it is HostEvent.Candidates })
    }

    @Test
    fun `deleteBefore deletes ahead of the commit`() {
        // ae:730-732, af:51-60
        val core = core()
        core.open()
        fake.answer = { _, e, _ -> if (e == EngineEvent.CHAR) snap(commit = ". ", deleteBefore = 1) else snap() }
        val log = core.log()
        core.press('.')
        assertEquals(listOf(HostEvent.DeleteSurrounding(1, 0), HostEvent.Commit(". ", -1)), log.filter { it is HostEvent.DeleteSurrounding || it is HostEvent.Commit })
    }

    @Test
    fun `a module's state change reaches the writer`() {
        var services: HostServices? = null
        core(modules = { services = it; HostModules() })
        services!!.persistConf("conf/chttrans.conf")
        assertEquals(Persist.ConfChanged("conf/chttrans.conf"), persisted.last())
    }
}
