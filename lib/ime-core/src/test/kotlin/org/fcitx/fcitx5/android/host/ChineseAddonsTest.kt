/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Punctuation, fullwidth, chttrans and the status area on the [KeyPipeline], as fcitx's
 * chinese-addons do them. Lines: `ca/…` is upstream fcitx5-chinese-addons 0d3fd040 `modules/…`
 * (`lib/ime-core/tools/upstream.sh chinese-addons`), `ae/…` is `app/src/main/cpp/androidengine/…`
 * at the tag `native-last`.
 */
class ChineseAddonsTest {

    private val log = mutableListOf<String>()
    private val tips = mutableListOf<Tip>()
    private val saved = mutableListOf<Any>()

    private val output = object : HostOutput {
        override fun commit(ic: InputContext, text: String, cursor: Int) {
            log += "commit $text"
        }

        override fun handBack(ic: InputContext, key: Key, release: Boolean, timestamp: Int) {
            log += "back ${key.sym.toString(16)}"
        }

        override fun panel(ic: InputContext) {
            log += "panel"
        }
    }

    private val contexts = InputContexts(ImGroup.of("Default", listOf("pinyin", ImGroup.KEYBOARD_US)), { _, _, _, _ -> })
    private val ic = contexts.activate(1).also { contexts.focus(it, true) }
    private val languages = mapOf("pinyin" to "zh_CN", ImGroup.KEYBOARD_US to "en")

    private val statusArea = StatusArea { log += "status" }
    private val punctuation = Punctuation(
        PunctuationProfile.parse(TABLE),
        PunctuationConfig(halfWidthAfterLatinOrNumber = false),
        statusArea,
        tips::add,
        saved::add,
    )
    private lateinit var pipeline: KeyPipeline
    private val fullWidth = FullWidth(statusArea) { c, s -> pipeline.commit(c, s) }
    private var converter: ChineseConverter? = ChineseConverterTest.converter(
        phrases = mapOf("头发" to "頭髮"),
        characters = mapOf("发" to "發", "头" to "頭", "后" to "後", "汉" to "漢"),
        variants = mapOf("裡" to "裏"),
    )
    private val chttrans = Chttrans(
        emptySet(), statusArea, contexts::inputMethod, languages::get, { converter }, tips::add,
        { c -> pipeline.updatePanel(c) }, saved::add,
    )

    private val engine = object : KeyEngine {
        var takes: (KeyEvent) -> Boolean = { false }

        override fun keyEvent(ic: InputContext, e: KeyEvent) {
            if (takes(e)) e.filterAndAccept()
        }

        override fun filterKey(ic: InputContext, e: KeyEvent) {
            log += "filterKey"
        }
    }

    init {
        // the order fcitx registers them in (specs/host.md); the hotkeys after fullwidth's conversion
        pipeline = KeyPipeline(
            engine, output,
            postHooks = listOf(fullWidth.keyHook, punctuation.hotkey, chttrans.hotkey),
            resultListeners = listOf(punctuation.keyResult),
            forwardListeners = listOf(punctuation.forwardKey),
            commitFilters = listOf(punctuation.commitFilter, fullWidth.commitFilter, chttrans.commitFilter),
            outputFilters = listOf(chttrans.outputFilter),
            contextListeners = listOf(punctuation.contextListener),
        )
        chineseActivated()
    }

    /** What the host does as it activates a Chinese input method (`ae/androidengine.cpp:500-509`). */
    private fun chineseActivated() = statusArea.setInputMethodActions(ic, listOf("chttrans", "punctuation", "fullwidth"))

    private fun press(sym: Int, states: Int = 0) = pipeline.dispatch(ic, KeyEvent(Key(sym, states), release = false))
    private fun release(sym: Int, states: Int = 0) = pipeline.dispatch(ic, KeyEvent(Key(sym, states), release = true))
    private fun state() = punctuation.state(ic)

    // ---- punctuation

    @Test
    fun `quotes alternate per context and reset starts again (ca punctuation cpp 469-502, 287-302)`() {
        assertEquals("“" to "", punctuation.pushPunctuationV2(ic, '"'.code))
        assertEquals("”" to "", punctuation.pushPunctuationV2(ic, '"'.code))
        assertEquals("“" to "", punctuation.pushPunctuationV2(ic, '"'.code))
        val other = contexts.activate(2)
        assertEquals("another context has its own state", "“" to "", punctuation.pushPunctuationV2(other, '"'.code))
        pipeline.reset(ic)
        assertEquals("“" to "", punctuation.pushPunctuationV2(ic, '"'.code))
        assertEquals("each key its own turn", "‘", punctuation.pushPunctuation(ic, '\''.code))
        assertEquals("’", punctuation.pushPunctuation(ic, '\''.code))
    }

    @Test
    fun `paired together types both halves and keeps no state (ca punctuation cpp 486-488)`() {
        punctuation.setConfig(punctuation.config.copy(typePairedTogether = true))
        assertEquals("“" to "”", punctuation.pushPunctuationV2(ic, '"'.code))
        assertEquals("“" to "”", punctuation.pushPunctuationV2(ic, '"'.code))
        assertEquals("V1 still alternates", "“", punctuation.pushPunctuation(ic, '"'.code))
        assertTrue(state().lastPuncStack.isNotEmpty())
    }

    @Test
    fun `reset clears every part of the state (ca punctuation cpp 287-302)`() {
        punctuation.pushPunctuationV2(ic, '"'.code)
        state().lastIsEngOrDigit = 'a'.code
        state().notConverted = '.'.code
        pipeline.reset(ic)
        assertEquals(0, state().lastIsEngOrDigit)
        assertEquals(0, state().notConverted)
        assertTrue(state().lastPuncStack.isEmpty())
    }

    @Test
    fun `half width after a letter or digit, and cancelLast gives the mark (ca punctuation cpp 444-448, 504-516)`() {
        punctuation.setConfig(punctuation.config.copy(halfWidthAfterLatinOrNumber = true))
        pipeline.commit(ic, "abc")
        assertEquals('c'.code, state().lastIsEngOrDigit)
        assertEquals("" to "", punctuation.pushPunctuationV2(ic, '.'.code))
        assertEquals('.'.code, state().notConverted)
        assertEquals("only . and , stay", "？" to "", punctuation.pushPunctuationV2(ic, '?'.code))
        assertEquals("the push cleared it", "", punctuation.cancelLast(ic))
        punctuation.pushPunctuationV2(ic, ','.code)
        assertEquals("，", punctuation.cancelLast(ic))
        assertEquals("once", "", punctuation.cancelLast(ic))
        pipeline.commit(ic, "中")
        assertEquals("。" to "", punctuation.pushPunctuationV2(ic, '.'.code))
    }

    @Test
    fun `the option off converts after a letter too (ca punctuation cpp 445)`() {
        pipeline.commit(ic, "a")
        assertEquals("。" to "", punctuation.pushPunctuationV2(ic, '.'.code))
    }

    @Test
    fun `commit filter reads the last character as ASCII only (ca punctuation cpp 217-228)`() {
        pipeline.commit(ic, "x9")
        assertEquals('9'.code, state().lastIsEngOrDigit)
        pipeline.commit(ic, "Ａ")
        assertEquals(0, state().lastIsEngOrDigit)
        pipeline.commit(ic, "Z")
        pipeline.commit(ic, "")
        assertEquals(0, state().lastIsEngOrDigit)
    }

    @Test
    fun `an unaccepted letter, digit or keypad digit press is the last character (ca punctuation cpp 229-248)`() {
        press('q'.code)
        assertEquals('q'.code, state().lastIsEngOrDigit)
        press(KeySyms.KP_0 + 7)
        assertEquals('7'.code, state().lastIsEngOrDigit)
        release(KeySyms.SPACE)
        assertEquals("a release changes nothing", '7'.code, state().lastIsEngOrDigit)
        press(KeySyms.SPACE)
        assertEquals(0, state().lastIsEngOrDigit)
        press('Q'.code)
        engine.takes = { true }
        press('w'.code)
        assertEquals("an accepted key never reaches the result", 'Q'.code, state().lastIsEngOrDigit)
    }

    @Test
    fun `a forwarded key updates it too (ca punctuation cpp 249-254)`() {
        pipeline.commit(ic, "a")
        pipeline.forwardKey(ic, Key(0xff51), release = false)
        assertEquals(0, state().lastIsEngOrDigit)
        pipeline.forwardKey(ic, Key('5'.code), release = false)
        assertEquals('5'.code, state().lastIsEngOrDigit)
        pipeline.forwardKey(ic, Key(0xff51), release = true)
        assertEquals('5'.code, state().lastIsEngOrDigit)
    }

    @Test
    fun `off gives nothing (ca punctuation cpp 438-441, 518-543)`() {
        punctuation.setEnabled(false, ic)
        assertEquals("" to "", punctuation.pushPunctuationV2(ic, '.'.code))
        assertEquals("" to "", punctuation.getPunctuation('.'.code))
        assertEquals(emptyList<String>(), punctuation.getPunctuationCandidates('['.code))
        assertEquals("", punctuation.cancelLast(ic))
        assertEquals(listOf(PunctuationConfig(enabled = false, halfWidthAfterLatinOrNumber = false)), saved)
    }

    @Test
    fun `Ctrl+period toggles on a Chinese input method only, with a tip (ca punctuation cpp 255-276)`() {
        val e = press('.'.code, KeyStates.CTRL)
        assertTrue(e.filtered && e.accepted)
        assertFalse(punctuation.enabled)
        assertEquals(listOf(Tip.PunctuationDisabled), tips)
        assertTrue("the status area shown anew", "status" in log)
        press('.'.code, KeyStates.CTRL)
        assertEquals(listOf(Tip.PunctuationDisabled, Tip.PunctuationEnabled), tips)
        // English: its status area is empty (ae/androidengine.cpp:500), the key goes back
        statusArea.setInputMethodActions(ic, emptyList())
        log.clear()
        val en = press('.'.code, KeyStates.CTRL)
        assertFalse(en.accepted)
        assertTrue(punctuation.enabled)
        assertEquals("back 2e", log.last())
        assertFalse("a release never toggles", release('.'.code, KeyStates.CTRL).accepted)
    }

    @Test
    fun `a user table replaces the built-in one wholesale (ca punctuation cpp 101-104, 410-430)`() {
        val user = PunctuationProfile.load(TABLE, ", ，\n")
        punctuation.setProfile(user)
        assertEquals("" to "", punctuation.pushPunctuationV2(ic, '.'.code))
        assertEquals("，" to "", punctuation.pushPunctuationV2(ic, ','.code))
        assertEquals("an empty user file is an empty table", 0, PunctuationProfile.load(TABLE, "").entries.size)
        assertEquals(PunctuationProfile.parse(TABLE).entries, PunctuationProfile.load(TABLE, null).entries)
    }

    @Test
    fun `table lines are 2 or 3 words, a key one character, hash a key (ca punctuation cpp 107-128)`() {
        val p = PunctuationProfile.parse(" # ＃ \n. 。 。 。\nab x\n\n\t[ 【\t\n' ‘ ’\r\nonly\n😀 笑\n")
        assertEquals(
            listOf(PunctuationEntry("#", "＃"), PunctuationEntry("[", "【"), PunctuationEntry("'", "‘", "’"), PunctuationEntry("😀", "笑")),
            p.entries,
        )
        assertEquals("笑" to "", p.punctuation("😀".codePointAt(0)))
    }

    @Test
    fun `a key of one entry offers its first mark, of several each pair apart (ca punctuation cpp 184-203)`() {
        val p = PunctuationProfile.parse(TABLE)
        assertEquals(listOf("“"), p.punctuations('"'.code))
        assertEquals(listOf("【", "「", "『", "［"), p.punctuations('['.code))
        assertEquals(listOf("“", "”", "＂"), PunctuationProfile.parse("\" “ ”\n\" ＂\n").punctuations('"'.code))
        assertEquals(emptyList<String>(), p.punctuations('z'.code))
    }

    @Test
    fun `the edit page's table drops empty and long keys (ca punctuation cpp 131-152)`() {
        val p = PunctuationProfile.fromEntries(
            listOf(PunctuationEntry("", "x"), PunctuationEntry("a", ""), PunctuationEntry("ab", "x"), PunctuationEntry("/", "、")),
        )
        assertEquals(listOf(PunctuationEntry("/", "、")), p.entries)
    }

    // ---- fullwidth

    @Test
    fun `an untaken space commits U+3000, accepted not filtered, ReservedLast still runs (ca fullwidth cpp 37-53)`() {
        fullWidth.setEnabled(true, ic)
        log.clear()
        val e = press(KeySyms.SPACE)
        assertTrue(e.accepted)
        assertFalse(e.filtered)
        assertEquals(listOf("commit 　", "filterKey"), log)
        assertEquals("the space is not a letter: the last character is cleared", 0, state().lastIsEngOrDigit)
    }

    @Test
    fun `a letter's full width goes through the commit filters, a modifier or release is left alone`() {
        fullWidth.setEnabled(true, ic)
        log.clear()
        press('a'.code)
        assertEquals("commit ａ", log.first())
        assertEquals("the commit was already full width: no ASCII letter last", 0, state().lastIsEngOrDigit)
        log.clear()
        assertFalse(press('a'.code, KeyStates.CTRL).accepted)
        assertFalse(release('a'.code).accepted)
        assertFalse(press(KeySyms.RETURN).accepted)
        assertTrue(log.none { it.startsWith("commit") })
    }

    @Test
    fun `the commit filter converts above space only, while on and in the white list (ca fullwidth cpp 84-103)`() {
        pipeline.commit(ic, "a 1")
        fullWidth.setEnabled(true, ic)
        pipeline.commit(ic, "a 1!~中😀")
        statusArea.setInputMethodActions(ic, emptyList())
        pipeline.commit(ic, "a 1")
        assertEquals(listOf("commit a 1", "commit ａ １！～中😀", "commit a 1"), log.filter { it.startsWith("commit") })
        assertEquals(95, FullWidth.TABLE.length)
    }

    @Test
    fun `commit order is punctuation then fullwidth then chttrans (specs host md)`() {
        fullWidth.setEnabled(true, ic)
        press('F'.code, KeyStates.CTRL or KeyStates.SHIFT)
        log.clear()
        pipeline.commit(ic, "头发z")
        assertEquals("commit 頭髮ｚ", log.single { it.startsWith("commit") })
        assertEquals("punctuation saw the ASCII z", 'z'.code, state().lastIsEngOrDigit)
    }

    // ---- chttrans

    @Test
    fun `Ctrl+Shift+F toggles the current input method, saved, with a tip, the panel sent (ca chttrans cpp 97-131, 198-213)`() {
        log.clear()
        val e = press('f'.code, KeyStates.CTRL or KeyStates.SHIFT)
        assertTrue(e.filtered && e.accepted)
        assertEquals(setOf("pinyin"), chttrans.enabledIm)
        assertEquals(listOf<Any>(setOf("pinyin")), saved)
        assertEquals(listOf(Tip.TraditionalEnabled), tips)
        assertEquals(listOf("status", "panel"), log)
        assertTrue(chttrans.isTraditional(ic))
        assertEquals(
            StatusEntry(chttrans.action.id, "chttrans", "fcitx-chttrans-active", "Traditional Chinese"),
            statusArea.entries(ic).first(),
        )
        press('F'.code, KeyStates.CTRL or KeyStates.SHIFT or KeyStates.CAPS_LOCK)
        assertEquals(emptySet<String>(), chttrans.enabledIm)
        assertEquals(listOf(Tip.TraditionalEnabled, Tip.SimplifiedEnabled), tips)
    }

    @Test
    fun `not on an input method that is not zh_CN (ca chttrans cpp 108-111)`() {
        contexts.toggle(ic)
        assertEquals(ImGroup.KEYBOARD_US, contexts.inputMethod(ic))
        val e = press('F'.code, KeyStates.CTRL or KeyStates.SHIFT)
        assertFalse(e.accepted)
        chttrans.toggle(ic)
        assertEquals(emptySet<String>(), chttrans.enabledIm)
        assertTrue(tips.isEmpty())
    }

    @Test
    fun `enabled per input method - commit and output converted, others left (ca chttrans cpp 133-194, 323-337)`() {
        val other = Chttrans(
            setOf("pinyin"), StatusArea(), contexts::inputMethod, languages::get, { converter }, {}, {},
        )
        assertTrue("listed, but its toggle is not in that status area", other.commitFilter.filter(ic, "头发") == "头发")
        chttrans.toggle(ic)
        pipeline.commit(ic, "头发")
        assertEquals("commit 頭髮", log.last { it.startsWith("commit") })
        val text = FormattedText(arrayOf("头", "发后"), intArrayOf(1, 2), 3)
        val shown = pipeline.filterOutput(ic, text)
        assertEquals(listOf("頭", "髮後"), shown.strings.toList())
        assertEquals(listOf(1, 2), shown.flags.toList())
        assertEquals(3, shown.cursor)
        // a candidate is checked against the text as shown (native-last:app/src/main/cpp/androidfrontend/androidfrontend.cpp:302-305)
        assertEquals("漢", pipeline.filterOutput(ic, FormattedText(arrayOf("汉"), intArrayOf(0), -1)).toString())
    }

    @Test
    fun `data that does not load leaves text as it is (ca chttrans cpp 302-305)`() {
        converter = null
        chttrans.toggle(ic)
        pipeline.commit(ic, "头发")
        assertEquals("commit 头发", log.last { it.startsWith("commit") })
    }

    // ---- status area

    @Test
    fun `status area lists the three in androidengine's order and a tap toggles (ae androidengine cpp 500-509, native-lib cpp 344-350)`() {
        assertEquals(listOf("chttrans", "punctuation", "fullwidth"), statusArea.entries(ic).map { it.name })
        assertEquals(
            listOf("fcitx-chttrans-inactive", "fcitx-punc-active", "fcitx-fullwidth-inactive"),
            statusArea.entries(ic).map { it.icon },
        )
        assertEquals(listOf(1, 2, 3).toSet(), listOf(chttrans.action.id, punctuation.action.id, fullWidth.action.id).toSet())
        log.clear()
        statusArea.activate(ic, fullWidth.action.id)
        statusArea.activate(ic, punctuation.action.id)
        statusArea.activate(ic, chttrans.action.id)
        assertTrue(fullWidth.enabled)
        assertFalse(punctuation.enabled)
        assertTrue(chttrans.isTraditional(ic))
        assertEquals(
            listOf("Traditional Chinese", "Half width punctuation", "Full width Character"),
            statusArea.entries(ic).map { it.shortText },
        )
        assertTrue("a tap never tips", tips.isEmpty())
        statusArea.activate(ic, 99)
        // a switch clears the list (fcitx/instance.cpp:2491): the modules leave that context alone
        statusArea.setInputMethodActions(ic, emptyList())
        assertFalse(fullWidth.inWhiteList(ic) || punctuation.inWhiteList(ic))
        assertEquals(emptyList<StatusEntry>(), statusArea.entries(ic))
    }

    companion object {
        // ca/punctuation/punc.mb.zh_CN, the lines these tests read
        const val TABLE = ". 。\n, ，\n? ？\n\" “ ”\n' ‘ ’\n[ 【\n[ 「\n[ 『\n[ ［\n"
    }
}
