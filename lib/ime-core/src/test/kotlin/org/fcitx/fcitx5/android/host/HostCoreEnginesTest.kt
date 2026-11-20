/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.EnginesFixture
import org.fcitx.fcitx5.android.engine.runtime.EnginesHost
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.runtime.type
import org.fcitx.fcitx5.android.engine.runtime.wubiShown
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.OfferedWord
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** On a real [Engines] (the fixture's tiny pinyin data), through [EnginesHost]. */
class HostCoreEnginesTest : EnginesFixture() {

    private val clock = VirtualHostClock(epochMillis = 1_800_000_000_000)

    private fun engines() = Engines(::load, null, warmUps = inPlace, clock = clock)

    private fun core(engines: Engines) =
        HostCore(EnginesHost(engines), clock, testProfile(), firstRun = true)

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

    // the app puts its notices away as text goes in: an offer, and a word remembered, come after the commit that made them
    @Test
    fun `a table's offer and the word remembered are told after the commit`() {
        val engines = Engines(::load, MemoryFiles(), warmUps = inPlace, clock = clock)
        engines.settings = wubiShown
        val core = core(engines)
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.setInputMethods(listOf(InputMethodCatalog.WUBI))
        core.activate(1)
        core.focus(true)
        engines.context(InputMethodCatalog.WUBI, "", started = true, said = true)
        "vaaa vbgf ".forEach { core.sendKey(Key(it.code), virtual = true) }
        val offered = log.indexOfLast { it is HostEvent.WordOffer && it.offer != null }
        assertTrue(offered > log.indexOfLast { it is HostEvent.Commit })
        val offer = (log[offered] as HostEvent.WordOffer).offer!!
        assertEquals(listOf(OfferedWord("一妤", "vavb")), offer.words)
        log.clear()
        core.sendKey(Key(UNICODE + Keyboard.REMEMBER, KeyStates.VIRTUAL), virtual = true, payload = Action.Remember(offer.id, 0, "一妤"))
        val commit = log.indexOfFirst { it is HostEvent.Commit && it.text == "一妤" }
        assertTrue(commit >= 0)
        assertEquals(listOf(HostEvent.WordRemembered(OfferedWord("一妤", "vavb"))), log.drop(commit).filterIsInstance<HostEvent.WordRemembered>())
        assertTrue(log.take(commit).none { it is HostEvent.WordRemembered })
        engines.close()
    }

    // leaving a field tells the withdrawal there, so the next field's first offer is told as new
    @Test
    fun `another field's offer is told afresh`() {
        val engines = Engines(::load, MemoryFiles(), warmUps = inPlace, clock = clock)
        engines.settings = wubiShown
        val core = core(engines)
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.setInputMethods(listOf(InputMethodCatalog.WUBI))
        core.activate(1)
        core.focus(true)
        engines.context(InputMethodCatalog.WUBI, "", started = true, said = true)
        "vaaa vbgf ".forEach { core.sendKey(Key(it.code), virtual = true) }
        assertTrue(log.any { it is HostEvent.WordOffer && it.offer != null })
        core.focus(false)
        assertEquals(HostEvent.WordOffer(null), log.last { it is HostEvent.WordOffer })
        log.clear()
        core.activate(2)
        core.focus(true)
        engines.context(InputMethodCatalog.WUBI, "", started = true, said = true)
        "vaaa vbgf ".forEach { core.sendKey(Key(it.code), virtual = true) }
        assertEquals(listOf(OfferedWord("一妤", "vavb")), log.filterIsInstance<HostEvent.WordOffer>().last().offer?.words)
        engines.close()
    }

    // 清除 while typing: what is typed goes, nothing committed; 恢复 types it again, the same keys
    @Test
    @Uat("KB-62")
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
        // pinyin holds a part chosen till the rest is: a pick sent again by index could choose another word, so dropped
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

    // the keys' codes go to a session per keyboard: the nine keys' typed again on the full keyboard would show nowhere
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

    // a digit picking a part while typing is never typed again: the list reordered since could give another word
    @Test
    fun `what is typed after a key picks a part of it is never typed again`() {
        val core = core(engines())
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.focus(true)
        fun candidates() = log.filterIsInstance<HostEvent.Candidates>().last().candidates.map { it.text }
        // nihao's third is 你, a part, held; Space takes the first, the whole sentence: no part to hold
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

    // a candidate's long-press action could land on another word if sent again: none typed again, nor what follows
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

    // the handwriting pad's 注: 候选词注音 turned on with something typed keeps it, the candidates read again
    @Test
    fun `turning the readings on keeps what is typed and annotates what is shown`() {
        val core = core(engines())
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.focus(true)
        core.configure(EngineOptions())
        "ni".forEach { core.sendKey(Key(it.code), virtual = true) }
        assertTrue(log.filterIsInstance<HostEvent.Candidates>().last().candidates.all { it.readings.isEmpty() })
        val before = log.size
        core.configure(EngineOptions().apply { load("CandidateReading=Pinyin\n") })
        assertEquals("ni", log.filterIsInstance<HostEvent.InputPanel>().last().preedit.toString())
        assertEquals(listOf("nǐ"), log.filterIsInstance<HostEvent.Candidates>().last().candidates.first().readings)
        assertTrue(log.drop(before).none { it is HostEvent.Commit })
        // the same setting again: nothing read again
        val after = log.size
        core.configure(EngineOptions().apply { load("CandidateReading=Pinyin\n") })
        assertEquals(after, log.size)
        // nothing typed: nothing to read again
        core.reset()
        val idle = log.size
        core.configure(EngineOptions())
        assertTrue(log.drop(idle).none { it is HostEvent.Candidates && it.candidates.isNotEmpty() })
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

    // the date phrases use the replay's clock (LearnClock.calendar), never the system's: 2026-10-17 09:05 UTC, a Saturday
    @Test
    fun `the date and time phrases are the virtual clock's`() {
        val at = VirtualHostClock(epochMillis = 1_792_227_900_000)
        val engines = Engines(::load, null, warmUps = inPlace, clock = at)
        val core = HostCore(EnginesHost(engines), at, testProfile(), firstRun = true)
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

    // the user's entry goes out as is, the built-in one converted; neither reaches the engine, so nothing learnt or predicted
    @Test
    @Uat("QU-23")
    fun `quick input's own entry goes out as it is under traditional, a built-in one converted, neither learnt nor predicted from`() {
        val engines = engines()
        val sent = mutableListOf<Int>()
        val real = EnginesHost(engines)
        val host = object : EngineHost by real {
            override fun event(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot? {
                sent += event
                return real.event(im, event, arg, learning, payload)
            }
        }
        val builtIn = QuickPhraseTable.load(
            listOf(
                object : PkgDataDir {
                    override fun read(path: String) = if (path == "data/quickphrase.d/t.mb") "tf 头发\n".toByteArray() else null
                    override fun list(dir: String) = listOf("t.mb")
                },
            ),
        )
        val book = QuickPhraseBook(builtIn, listOf(QuickPhraseEntry("tf", "头发")))
        val converter = ChineseConverterTest.converter(phrases = mapOf("头发" to "頭髮"), characters = mapOf("发" to "發", "头" to "頭"))
        val modules = ChineseModules(
            ModuleInputs(
                PunctuationProfile.parse(""),
                chttransEnabled = setOf(InputMethodCatalog.PINYIN),
                converter = { converter },
                quickPhrases = { book },
            ),
        )
        val profile = testProfile()
        val core = HostCore(host, clock, profile, firstRun = true, modules = modules::build)
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.focus(true)
        assertEquals(InputMethodCatalog.PINYIN, core.currentInputMethod())
        val learnt = engines.userWords()
        fun pick(index: Int): String {
            core.triggerQuickPhrase()
            "tf".forEach { core.sendKey(Key(it.code), virtual = true) }
            val shown = log.filterIsInstance<HostEvent.Candidates>().last().candidates.map { it.text }
            assertEquals(shown.toString(), 2, shown.size)
            assertEquals("the user's own shown as it goes out", "头发", shown[0])
            assertEquals("頭髮", shown[1])
            log.clear()
            sent.clear()
            assertTrue(core.select(index, shown[index]))
            return log.filterIsInstance<HostEvent.Commit>().single().text
        }
        assertEquals("头发", pick(0))
        assertTrue("the engine told nothing of it: $sent", sent.none { it == EngineEvent.PICK || it == EngineEvent.CHAR })
        assertTrue("nothing predicted after it", log.filterIsInstance<HostEvent.Candidates>().all { it.candidates.isEmpty() })
        assertEquals("頭髮", pick(1))
        assertEquals(learnt, engines.userWords())
        engines.close()
    }

    // a long press under 繁體: the menu named by the converted text answers with the engine's; acts name that
    @Test
    fun `a long press under traditional names the candidate as shown and acts on the engine's text`() {
        val engines = engines()
        val converter = ChineseConverterTest.converter(characters = mapOf("你" to "妳"))
        val modules = ChineseModules(
            ModuleInputs(PunctuationProfile.parse(""), chttransEnabled = setOf(InputMethodCatalog.PINYIN), converter = { converter }),
        )
        val profile = testProfile()
        val core = HostCore(EnginesHost(engines), clock, profile, firstRun = true, modules = modules::build)
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.focus(true)
        fun shown(): List<String> = log.filterIsInstance<HostEvent.Candidates>().last().candidates.map { it.text }
        "ni".forEach { core.sendKey(Key(it.code), virtual = true) }
        val at = shown().indexOf("妳")
        assertTrue("${shown()}", at >= 0)
        assertEquals("妳", core.shownText("你"))
        fun learning(op: LearningOp) = engines.learning(InputMethodCatalog.PINYIN, op, core::shownText)
        val menu = learning(LearningOp.Menu(at, "妳")) as LearningResult.Menu
        assertEquals("你", menu.text)
        assertTrue("${menu.items}", MenuItem.BLOCK in menu.items && MenuItem.PIN in menu.items)
        // the converted text is no act's: delete, pin and block all named by the menu's
        assertEquals(LearningResult.Unavailable, learning(LearningOp.Delete(at, "妳")))
        assertEquals(LearningResult.Unavailable, learning(LearningOp.Pin(at, "妳")))
        assertTrue(learning(LearningOp.Pin(at, menu.text)) is LearningResult.Done)
        assertTrue(learning(LearningOp.Block(shown().indexOf("妳"), menu.text)) is LearningResult.Done)
        assertEquals(listOf("你"), engines.userWords(UserWord.Kind.BLOCKED).map { it.text })
        engines.close()
    }

    // after a card's 删除: the book read again, the open list shown from it; the entry gone, what was typed kept
    @Test
    fun `an entry deleted while quick input is open leaves its list at the reload`() {
        val engines = engines()
        var built: HostModules? = null
        val modules = ChineseModules(ModuleInputs(PunctuationProfile.parse(""), quickPhrases = { QuickPhraseBook(QuickPhraseTable.EMPTY, listOf(QuickPhraseEntry("tf", "头发"), QuickPhraseEntry("tg", "头盖"))) }))
        val core = HostCore(EnginesHost(engines), clock, testProfile(), firstRun = true) {
            modules.build(it).also { m -> built = m }
        }
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.focus(true)
        core.triggerQuickPhrase()
        core.sendKey(Key('t'.code), virtual = true)
        fun shown() = log.filterIsInstance<HostEvent.Candidates>().last().candidates.map { it.text }
        assertEquals(listOf("头发", "头盖"), shown().take(2))
        built!!.quickPhrase!!.reload(QuickPhraseBook(QuickPhraseTable.EMPTY, listOf(QuickPhraseEntry("tg", "头盖"))))
        core.refreshQuickPhrase()
        assertEquals(listOf("头盖"), shown().filter { it.startsWith("头") })
        // what was typed kept: the next key narrows the new list
        core.sendKey(Key('g'.code), virtual = true)
        assertEquals(listOf("头盖"), shown().filter { it.startsWith("头") })
        engines.close()
    }

    // an Ink key quick input had holds its reading for the next; dropped, the stroke's ink stayed on the pad
    @Test
    fun `ink written under quick input is shown with the next stroke after it`() {
        val engines = engines()
        val modules = ChineseModules(ModuleInputs(PunctuationProfile.parse("")))
        val profile = testProfile()
        val core = HostCore(EnginesHost(engines), clock, profile, firstRun = true, modules = modules::build)
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.focus(true)
        core.setInputMethods(listOf(InputMethodCatalog.PINYIN, ImGroup.KEYBOARD_US))
        core.setCurrentInputMethod(InputMethodCatalog.PINYIN)
        // what KotlinHost does with what the pad read (EngineBridge.sendInk): the ink, its key, then inkSent
        fun write(piece: Int, vararg chars: String) {
            val ink = Action.Ink(piece, InkReading(chars.toList(), DoubleArray(chars.size)))
            engines.ink(ink, shown = true, im = InputMethodCatalog.PINYIN)
            core.sendKey(Key(UNICODE + Keyboard.INK, KeyStates.VIRTUAL), virtual = true, payload = ink)
            engines.inkSent(ink, im = InputMethodCatalog.PINYIN)
        }
        core.triggerQuickPhrase()
        write(0, "你")
        assertTrue("quick input had the key", log.filterIsInstance<HostEvent.InputPanel>().none { it.preedit.toString() == "你" })
        core.sendKey(Key(KeySyms.ESCAPE))
        write(1, "好")
        assertEquals("你好", log.filterIsInstance<HostEvent.InputPanel>().last().preedit.toString())
    }

    // 清除 and the backspace swipe drop what handwriting wrote; the input ended otherwise puts it in
    @Test
    fun `clearing handwriting commits nothing, the input ended commits it`() {
        val engines = engines()
        val core = core(engines)
        val log = mutableListOf<HostEvent>()
        core.events += HostEvents { log += it }
        core.activate(1)
        core.focus(true)
        core.setCurrentInputMethod(InputMethodCatalog.PINYIN)
        fun write(piece: Int, text: String) {
            val ink = Action.Ink(piece, InkReading(listOf(text), DoubleArray(1)))
            engines.ink(ink, shown = true, im = InputMethodCatalog.PINYIN)
            core.sendKey(Key(UNICODE + Keyboard.INK, KeyStates.VIRTUAL), virtual = true, payload = ink)
            engines.inkSent(ink, im = InputMethodCatalog.PINYIN)
        }
        fun commits() = log.filterIsInstance<HostEvent.Commit>().map { it.text }
        write(0, "你")
        assertEquals("你", log.filterIsInstance<HostEvent.InputPanel>().last().preedit.toString())
        // the session's code as typed code is: 编码行 输入框里 writes it into the field, underlined as composing text
        assertTrue(log.filterIsInstance<HostEvent.InputPanel>().last().code)
        assertNotNull(core.clearTyped())
        assertEquals(emptyList<String>(), commits())
        assertEquals("", log.filterIsInstance<HostEvent.InputPanel>().last().preedit.toString())
        write(1, "好")
        core.reset(drop = true)
        assertEquals(emptyList<String>(), commits())
        write(2, "天")
        core.reset()
        assertEquals(listOf("天"), commits())
    }

    // 编码行 输入框里: each keyboard's own codes (private use, past the BMP) are keys, never the code's text
    @Test
    fun `the code each keyboard types is text, no private-use character in it`() {
        val keyboards = listOf(
            "14 keys, tapped" to "ni".map { Keyboard.tapKey(NineKeyLetters.G14.key(it), 0.5f) },
            "14 keys, the code alone" to "ni".map { NineKeyLetters.G14.key(it).code },
            "18 keys" to "ni".map { Keyboard.pinyin18Key(NineKeyLetters.G18.key(it)) },
            "nine keys" to "64".map(Keyboard::pinyinNineKey),
            "注音九键" to listOf(Keyboard.zhuyinNineKey(6), Keyboard.zhuyinNineKey(5)),
        )
        // each row's keys sent inside its own check: one keyboard that throws is that row's failure
        val rows = keyboards.map { (name, keys) ->
            "$name: a code, its characters no private use's" to {
                val core = core(engines())
                val panels = mutableListOf<HostEvent.InputPanel>()
                core.events += HostEvents { if (it is HostEvent.InputPanel) panels += it }
                core.activate(1)
                core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
                core.focus(true)
                keys.forEach { core.sendKey(Key(KeySyms.fromUnicode(it)), virtual = true) }
                val codes = panels.filter { it.code }.map { it.preedit.toString() }
                assertTrue("$name ${panels.map { it.preedit }}", codes.isNotEmpty() && codes.last().isNotEmpty())
                codes.forEach { code -> assertTrue("$name '$code'", code.codePoints().noneMatch(KeySyms::isPrivateUse)) }
            }
        }
        assertRows(*rows.toTypedArray())
    }

    // a syllable taken off the nine keys' bar is a pick: never typed again from a list the session moved past
    @Test
    @Uat("KB-62")
    fun `what is typed after a syllable taken off the nine keys' bar is never typed again`() {
        val real = EnginesHost(engines())
        var last: Snapshot? = null
        val host = object : EngineHost by real {
            override fun event(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot? =
                real.event(im, event, arg, learning, payload).also { last = it }
        }
        val core = HostCore(host, clock, testProfile(), firstRun = true)
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.focus(true)
        "64".forEach { core.sendKey(Key(KeySyms.fromUnicode(Keyboard.pinyinNineKey(it))), virtual = true) }
        val typed = last!!
        assertTrue(typed.toString(), typed.syllables.isNotEmpty())
        core.sendKey(Key(KeySyms.fromUnicode(Keyboard.syllableKey(0, typed.syllablesId).code)), virtual = true)
        assertFalse("something still typed", core.isEmpty())
        assertNull("a syllable pick is not typed again", core.clearTyped()!!.keys)
    }
}
