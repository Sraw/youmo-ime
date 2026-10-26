/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The modules as the app plugs them into [HostCore] ([ChineseModules]): the status area per input
 * method and its event, tips in the app's words, the keyboard's quick input and Unicode buttons, a
 * temp mode's panel and taps through the core, the module files saved as their state changes, and
 * the events as the app's [FcitxEvent]s. Lines, at the tag `native-last`: `ae` is
 * `app/src/main/cpp/androidengine/androidengine.cpp`, `nl` `app/src/main/cpp/native-lib.cpp`, `af`
 * `app/src/main/cpp/androidfrontend/androidfrontend.cpp`.
 */
class ChineseModulesHostTest {

    private class Host : EngineHost {
        val events = mutableListOf<Int>()
        val settings = mutableListOf<String>()
        /** What a key composes; none, every key is passed on. */
        var preedit = ""
        override fun event(im: String, event: Int, arg: Int, learning: Boolean): Snapshot {
            events += event
            val composing = preedit.isNotEmpty() && event == EngineEvent.CHAR
            return Snapshot("", if (composing) preedit else "", emptyList(), 0, false, false, handled = composing, predicting = false)
        }

        override fun candidates(im: String, from: Int, count: Int) = emptyList<Choice>()
        override fun offers(im: String, index: Int) = emptySet<Offer>()
        override fun settings(flat: String) {
            settings += flat
        }
    }

    private class Dir(private val files: Map<String, String>) : PkgDataDir {
        override fun read(path: String) = files[path]?.toByteArray()
        override fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/") }
    }

    private val strings = object : HostStrings by HostStrings.ENGLISH {
        override fun tip(tip: Tip) = "tip:${tip.name}"
        override val quickPhrase = "快速输入: "
    }

    private val host = Host()
    private val clock = VirtualHostClock()
    private val persisted = mutableListOf<Persist>()
    private val modules = ChineseModules(
        ModuleInputs(
            PunctuationProfile.parse(". 。\n, ，\n"),
            PunctuationConfig(halfWidthAfterLatinOrNumber = false),
            quickPhraseTable = QuickPhraseTable.load(listOf(Dir(mapOf("data/QuickPhrase.mb" to "dz 北京市\ndzz 海淀区\n")))),
            charSelectData = { null },
        ),
        strings,
    )
    private val profile = Profile.load(null, Engines.LISTED.map { it.name }.toSet(), "Default")
    private val core = HostCore(host, clock, profile, strings = strings, firstRun = true, persist = { persisted += it }, modules = modules::build)
    private val log = mutableListOf<HostEvent>().also { l -> core.events.handlers += { l += it } }

    private fun open(caps: Long = Capability.CLIENT_UNFOCUS_COMMIT) {
        core.activate(1)
        core.setCapabilities(caps)
        core.focus(true)
        clock.advance(2 * HostCore.INFO_MILLIS)
    }

    @Test
    fun `a Chinese input method puts the three toggles up, English none, each told as a status area event (ae 497-510, af 376-379)`() {
        open()
        assertEquals(listOf("punctuation", "fullwidth", "chttrans").sorted(), core.statusArea().map { it.name }.sorted())
        val shown = log.filterIsInstance<HostEvent.StatusArea>().last()
        assertEquals(Engines.PINYIN, shown.im)
        assertEquals(3, shown.entries.size)
        core.toggle()
        assertEquals(ImGroup.KEYBOARD_US, core.currentInputMethod())
        assertTrue(core.statusArea().isEmpty())
        assertTrue(log.filterIsInstance<HostEvent.StatusArea>().last().entries.isEmpty())
    }

    @Test
    fun `a status area tap toggles, is saved at once and tells the area anew, no tip (ca chttrans cpp 198-213)`() {
        open()
        val trad = core.statusArea().first { it.name == "chttrans" }
        log.clear()
        core.activateAction(trad.id)
        assertEquals(setOf(Engines.PINYIN), modules.chttrans.enabledIm)
        assertEquals(Persist.ConfChanged(ModuleConfigs.CHTTRANS), persisted.last())
        assertEquals("fcitx-chttrans-active", core.statusArea().first { it.name == "chttrans" }.icon)
        assertEquals(1, log.count { it is HostEvent.StatusArea })
        assertTrue(log.none { it is HostEvent.Tip })
        assertTrue(modules.conf(ModuleConfigs.CHTTRANS, null)!!.contains("0=${Engines.PINYIN}"))
    }

    @Test
    fun `toggling traditional sends the composing text again (ca chttrans cpp 214-215)`() {
        // handwriting's composing text is the app's (client preedit), which a toggle must re-send
        host.preedit = "n"
        open(Capability.CLIENT_UNFOCUS_COMMIT or Capability.PREEDIT)
        core.setInputMethods(listOf(Engines.PINYIN, Engines.HANDWRITING, ImGroup.KEYBOARD_US))
        core.setCurrentInputMethod(Engines.HANDWRITING)
        core.sendKey(Key('n'.code), virtual = true)
        assertTrue(log.toString(), log.any { it is HostEvent.ClientPreedit && it.text.toString() == "n" })
        log.clear()
        core.activateAction(core.statusArea().first { it.name == "chttrans" }.id)
        assertTrue(log.toString(), log.any { it is HostEvent.ClientPreedit && it.text.toString() == "n" })
    }

    @Test
    fun `chttrans read again takes the file's input methods and saves nothing`() {
        open()
        persisted.clear()
        modules.chttrans.reload(setOf(Engines.PINYIN))
        assertEquals("fcitx-chttrans-active", core.statusArea().first { it.name == "chttrans" }.icon)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `Ctrl+period tips in the app's words and saves punctuation (ca punctuation cpp 255-271)`() {
        open()
        core.sendKey(Key('.'.code, KeyStates.CTRL))
        assertEquals(HostEvent.Tip("tip:PunctuationDisabled"), log.filterIsInstance<HostEvent.Tip>().single())
        assertEquals(Persist.ConfChanged(ModuleConfigs.PUNCTUATION), persisted.last())
        assertTrue(modules.conf(ModuleConfigs.PUNCTUATION, null)!!.lines().contains("Enabled=False"))
    }

    @Test
    fun `a Chinese key the session passes on types the module's mark (ae 515-548)`() {
        open()
        log.clear()
        assertTrue(core.sendKey(Key(','.code), virtual = true))
        assertEquals(HostEvent.Commit("，", -1), log.filterIsInstance<HostEvent.Commit>().single())
    }

    @Test
    fun `the quick input button shows its code above the candidates, typing filters, a tap commits (nl 286-296)`() {
        open(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.triggerQuickPhrase()
        val ic = core.contexts.active!!
        assertFalse("the code above the candidates, not in the app", ic.has(Capability.PREEDIT))
        assertEquals("快速输入: ", log.filterIsInstance<HostEvent.InputPanel>().last().auxUp.toString())
        host.events.clear()
        "dz".forEach { core.sendKey(Key(it.code), virtual = true) }
        assertTrue("the engine sees none of the temp mode's keys", host.events.isEmpty())
        val list = log.filterIsInstance<HostEvent.Candidates>().last()
        assertEquals(listOf("北京市", "海淀区"), list.candidates.map { it.text })
        assertEquals("dz", log.filterIsInstance<HostEvent.InputPanel>().last().preedit.toString())
        log.clear()
        assertTrue(core.select(1, "海淀区"))
        assertEquals(HostEvent.Commit("海淀区", -1), log.filterIsInstance<HostEvent.Commit>().single())
        assertTrue("the panel cleared as the mode leaves", core.isEmpty())
    }

    @Test
    fun `a temp mode's panel goes out once per key, at the event's end`() {
        open()
        core.triggerQuickPhrase()
        log.clear()
        core.sendKey(Key('d'.code), virtual = true)
        assertEquals(1, log.count { it is HostEvent.InputPanel })
        assertEquals(1, log.count { it is HostEvent.Candidates })
    }

    @Test
    fun `Unicode with no data does not start, the key stays the app's`() {
        open()
        core.triggerUnicode()
        assertTrue(core.isEmpty())
    }

    @Test
    fun `the Unicode button with the data searches by name and a tap commits (nl 298-303)`() {
        val withData = ChineseModules(
            ModuleInputs(
                PunctuationProfile.parse(""),
                charSelectData = { javaClass.getResourceAsStream("/pkgdata/unicode/charselectdata")!!.readBytes() },
            ),
            strings,
        )
        val c = HostCore(host, clock, profile, strings = strings, firstRun = true, modules = withData::build)
        val seen = mutableListOf<HostEvent>().also { l -> c.events.handlers += { l += it } }
        c.activate(1)
        c.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        c.focus(true)
        clock.advance(2 * HostCore.INFO_MILLIS)
        c.triggerUnicode()
        assertTrue(seen.filterIsInstance<HostEvent.InputPanel>().last().auxUp.toString().startsWith("Unicode"))
        "degree".forEach { c.sendKey(Key(it.code), virtual = true) }
        val list = seen.filterIsInstance<HostEvent.Candidates>().last().candidates
        val degree = list.indexOfFirst { it.text == "°" }
        assertTrue("° among ${list.take(5)}", degree >= 0)
        seen.clear()
        assertTrue(c.select(degree, "°"))
        assertEquals("°", seen.filterIsInstance<HostEvent.Commit>().single().text)
    }

    @Test
    fun `the paged window is vertical for Unicode (CandidateList layoutHint)`() {
        val withData = ChineseModules(
            ModuleInputs(
                PunctuationProfile.parse(""),
                charSelectData = { javaClass.getResourceAsStream("/pkgdata/unicode/charselectdata")!!.readBytes() },
            ),
            strings,
        )
        val c = HostCore(host, clock, profile, strings = strings, firstRun = true, modules = withData::build)
        val seen = mutableListOf<HostEvent>().also { l -> c.events.handlers += { l += it } }
        c.activate(1)
        c.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        c.focus(true)
        clock.advance(2 * HostCore.INFO_MILLIS)
        c.setCandidatePagingMode(1)
        c.triggerUnicode()
        "degree".forEach { c.sendKey(Key(it.code), virtual = true) }
        val paged = seen.filterIsInstance<HostEvent.Paged>().last()
        assertEquals(FcitxEvent.PagedCandidateEvent.LayoutHint.Vertical, paged.layoutHint)
        val event = FcitxEvents.of(paged) as FcitxEvent.PagedCandidateEvent
        assertEquals(FcitxEvent.PagedCandidateEvent.LayoutHint.Vertical, event.data.layoutHint)
    }

    @Test
    fun `the globe key enumerates the group round, English then back to pinyin (SET-08)`() {
        open()
        assertEquals(listOf(Engines.PINYIN, ImGroup.KEYBOARD_US), core.contexts.group.inputMethods)
        core.enumerate(true)
        assertEquals(ImGroup.KEYBOARD_US, core.currentInputMethod())
        core.enumerate(true)
        assertEquals(Engines.PINYIN, core.currentInputMethod())
    }

    @Test
    fun `engine options reach the router and the engines (ae 405-490)`() {
        val options = EngineOptions().apply { load("PageSize=9\n") }
        core.configure(options)
        assertEquals(9, core.router.options.pageSize)
        assertTrue(core.router.options.englishSuggest)
        assertTrue(host.settings.last().contains("PageSize=9"))
    }

    @Test
    fun `the events map one for one onto the app's, a tip onto none`() {
        open()
        val im = FcitxEvents.of(HostEvent.InputMethodChanged(Engines.PINYIN)) as FcitxEvent.IMChangeEvent
        assertEquals("拼音", im.data.name)
        assertEquals("拼", im.data.label)
        assertEquals("zh_CN", im.data.languageCode)
        assertTrue(im.data.isConfigurable)
        val status = FcitxEvents.of(log.filterIsInstance<HostEvent.StatusArea>().last()) as FcitxEvent.StatusAreaEvent
        assertEquals(core.statusArea().map { it.id }, status.data.actions.map { it.id })
        val sw = FcitxEvents.of(HostEvent.SwitchInputMethod(SwitchReason.Enumerate, "x")) as FcitxEvent.SwitchInputMethodEvent
        assertEquals(FcitxEvent.SwitchInputMethodEvent.Reason.Enumerate, sw.data.reason)
        val key = FcitxEvents.of(HostEvent.Key(0x61, 4, 0x61, true, 7)) as FcitxEvent.KeyEvent
        assertEquals(0x61, key.data.sym.sym)
        assertEquals(4, key.data.states.toInt())
        assertNull(FcitxEvents.of(HostEvent.Tip("x")))
        assertEquals(EngineEvent.RESET, host.events.last())
    }
}
