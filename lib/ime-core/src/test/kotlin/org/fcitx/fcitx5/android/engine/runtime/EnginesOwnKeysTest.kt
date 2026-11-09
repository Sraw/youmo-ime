/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.input.scene.SceneData
import org.fcitx.fcitx5.android.input.scene.SceneGroups
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinyin on a layout's own letter groups through Engines (060-render-p3 T9b): the sample 17 keys told as
 * [EngineSettings.ownKeys], their codes and taps typed by pinyin's own-keys session, learned under their tag,
 * shown as letters; their codes with no keyboard told swallowed; the built-in keys' kinds as they were.
 */
class EnginesOwnKeysTest : EnginesFixture() {

    private val root = MemoryFiles()
    private val written = SceneGroups.written((LayoutReader.layout(SceneData.read("k17.xml")) as Parsed.Ok).value)
    private val k17 = NineKeyLetters.custom(written)

    private fun engines(ownKeys: List<String>? = written) = Engines(::load, root, warmUps = inPlace).also {
        it.settings = it.settings.copy(ownKeys = ownKeys)
        it.onEvent(PINYIN, EngineEvent.RESET, 0)
        it.context(PINYIN, "")
    }

    // the keyboard in use is process-wide (NineKeyLetters.inUse): none of this test's left for the next
    @After
    fun noneInUse() {
        NineKeyLetters.clearInUse()
    }

    private fun code(group: String, keys: NineKeyLetters = k17) = keys.codes[keys.tag().split(',').indexOf(group)]

    private fun Engines.typeCodes(codes: List<Int>) = codes.map { onEvent(PINYIN, EngineEvent.CHAR, it) }.last()

    private fun Engines.texts() = candidates(PINYIN, 0, 10).map { it.text }

    private fun Engines.afresh() {
        onEvent(PINYIN, EngineEvent.RESET, 0)
        context(PINYIN, "")
    }

    // ni hao: BN UI GH AS OP
    private val nihao = listOf("bn", "ui", "gh", "as", "op")

    @Test
    fun theOwnKeysCodesAndTapsReachTheirOwnSession() {
        assertEquals(InputMethodCatalog.PINYIN_KEYS_OWN, KeyboardPair.pinyinKeyed(code("bn").code))
        assertEquals(InputMethodCatalog.PINYIN_KEYS_OWN, KeyboardPair.pinyinKeyed(Keyboard.tapKey(code("pqrs"), 0.2f, 0.8f)))
        // the built-in keys' as they were
        assertEquals(InputMethodCatalog.PINYIN_KEYS, KeyboardPair.pinyinKeyed(NineKeyLetters.G14.key('n').code))
        assertEquals(InputMethodCatalog.PINYIN_KEYS, KeyboardPair.pinyinKeyed(Keyboard.tapKey(NineKeyLetters.G14.key('n'), 0.9f)))
        assertEquals(InputMethodCatalog.PINYIN_KEYS18, KeyboardPair.pinyinKeyed(Keyboard.pinyin18Key(NineKeyLetters.G18.key('n'))))
    }

    @Test
    fun theSeventeenKeysTypePinyinByCodeAndByTap() {
        val engines = engines()
        val coded = engines.typeCodes(nihao.map { code(it).code })
        assertTrue(coded.candidates.toString(), "你好" in coded.candidates.take(3))
        engines.afresh()
        // the taps leaning to the letters meant: n and i right, h right, a left, o left
        val tapped = engines.typeCodes(nihao.zip(listOf(0.9f, 0.9f, 0.9f, 0.1f, 0.1f)).map { (g, x) -> Keyboard.tapKey(code(g), x, 0.5f) })
        assertEquals("你好", tapped.candidates.first())
        // committed by a pick, back to nothing typed
        val picked = engines.onEvent(PINYIN, EngineEvent.PICK, 0)
        assertEquals("你好", picked.commit)
        engines.close()
    }

    @Test
    fun whatIsPickedOnThemIsLearnedUnderTheirTagAndReportedAsLetters() {
        val engines = engines()
        val ni = listOf("bn", "ui").map { code(it).code }
        repeat(2) {
            engines.afresh()
            engines.typeCodes(ni)
            engines.onEvent(PINYIN, EngineEvent.PICK, engines.texts().indexOf("拟"))
        }
        engines.afresh()
        engines.typeCodes(ni)
        val at = engines.texts().indexOf("拟")
        assertTrue(engines.texts().toString(), at in 0..1)
        val menu = engines.learning(PINYIN, LearningOp.Menu(at, "拟")) as LearningResult.Menu
        assertEquals(Kinds.keys(k17.tag()), menu.why!!.code!!.kind)
        assertEquals(k17, Kinds.keysOf(menu.why!!.code!!.kind))
        val report = (engines.learning(PINYIN, LearningOp.Report(at, "拟")) as LearningResult.Text).text
        assertTrue(report, " bn ui\n" in report)
        assertTrue(report, report.none { it.code in NineKeyLetters.CUSTOM_BLOCK })
        // the built-in 14 keys learned nothing of it: their kind apart
        engines.afresh()
        val on14 = engines.typeCodes(listOf(NineKeyLetters.G14.key('n').code, NineKeyLetters.G14.key('i').code))
        assertTrue(on14.candidates.toString(), on14.candidates.indexOf("拟") != 0)
        engines.close()
    }

    @Test
    fun theirCodesWithNoKeyboardToldTypeNothingAndLeaveWhatIsShown() {
        val engines = engines(ownKeys = null)
        // nothing typed: nothing, never the code as a character
        val alone = engines.onEvent(PINYIN, EngineEvent.CHAR, code("bn").code)
        assertTrue(alone.handled)
        assertEquals("", alone.commit)
        assertEquals("", alone.preedit)
        // typed on the full keyboard: left as it was
        val ni = engines.type(PINYIN, "ni")
        for (c in listOf(code("bn").code, Keyboard.tapKey(code("pqrs"), 0.5f, 0.5f))) {
            val out = engines.onEvent(PINYIN, EngineEvent.CHAR, c)
            assertTrue(out.handled)
            assertEquals("", out.commit)
            assertEquals(ni.preedit, out.preedit)
            assertEquals(ni.candidates, out.candidates)
        }
        // review L7: a pick (space) or a refine the snapshot before had is not the swallowed key's
        val picked = engines.onEvent(PINYIN, EngineEvent.CHAR, ' '.code)
        assertTrue(picked.toString(), picked.picked)
        val after = engines.onEvent(PINYIN, EngineEvent.CHAR, code("bn").code)
        assertTrue(after.handled)
        assertEquals("", after.commit)
        assertFalse(after.picked)
        assertFalse(after.refines)
        // groups that make no keyboard are none told
        val none = engines(ownKeys = listOf("ab", "cd"))
        assertEquals("", none.onEvent(PINYIN, EngineEvent.CHAR, code("bn").code).commit)
        none.close()
        engines.close()
    }

    // review M1: other groups told at the field's first key, after its context: the new session on them knows the
    // text before the cursor (好 puts 拟 first, as EnginesKeys14Test), and the others keep what they typed
    @Test
    fun otherGroupsToldKeepTheTextBeforeTheCursorAndTheOtherSessions() {
        val engines = engines()
        // the session on the groups made, then a field whose text before the cursor is 好
        engines.typeCodes(listOf(code("bn").code))
        engines.onEvent(PINYIN, EngineEvent.RESET, 0)
        engines.context(PINYIN, "好")
        val wubi = engines.type(InputMethodCatalog.WUBI, "vb")
        val reversed = written.reversed()
        val other = NineKeyLetters.custom(reversed)
        engines.settings = engines.settings.copy(ownKeys = reversed)
        val ni = engines.typeCodes(listOf("bn", "ui").map { code(it, other).code })
        assertEquals(listOf("拟", "你"), ni.candidates.take(2))
        // 五笔's input kept: its session not made anew
        assertEquals(wubi.preedit + "g", engines.type(InputMethodCatalog.WUBI, "g").preedit.replace(" ", ""))
        engines.close()
    }

    @Test
    fun otherGroupsToldMakeTheSessionAnew() {
        val engines = engines()
        engines.typeCodes(listOf(code("bn").code))
        // the same groups, but in another order: each code another group's
        val reversed = written.reversed()
        val other = NineKeyLetters.custom(reversed)
        engines.settings = engines.settings.copy(ownKeys = reversed)
        engines.afresh()
        val shown = engines.typeCodes(nihao.map { code(it, other).code })
        assertTrue(shown.candidates.toString(), "你好" in shown.candidates.take(3))
        assertEquals(other, NineKeyLetters.ofCode(other.codes[0].code))
        engines.close()
    }
}
