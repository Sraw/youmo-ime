/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.HANDWRITING
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Under 繁體 a block takes every candidate shown alike (国 with 國), each at its index of all, the
 * engine's text with it: past the first page too, where an act takes the list back to its first
 * page and another character would be at the page's index (review R3).
 */
class EnginesAlikeBlockTest : EnginesFixture() {

    // ten before 国 and 國, a page and more
    private val fillers = "甲乙丙丁戊己庚辛壬癸".map { it.toString() }

    init {
        pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .apply { (fillers + "国" + "國").forEachIndexed { i, c -> unigram(c, -2f - i * 0.1f, 0f).entry(c, intArrayOf(Syllables.id("guo"))) } }
            .build().toByteArray()
        strokes = Strokes.read(("...\n" + (fillers + "国" + "國").joinToString("") { "$it\tzpsss\n" }).reader().buffered(), "stroke").build().toByteArray()
    }

    private val traditional = { text: String -> text.replace('国', '國') }

    private fun Engines.underCode() =
        (learning(null, LearningOp.ListItems(LearningOp.Listed.TABLE_BLOCKS)) as LearningResult.Listed).items.map { it.text }.toSet()

    private fun Engines.blocked() = userWords(UserWord.Kind.BLOCKED).map { it.text }.toSet()

    @Test
    fun aStrokeLookupBlockPastThePageTakesTheAlikeAndNoOther() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            e.onEvent(PINYIN, EngineEvent.RESET, 0)
            val list = e.type(PINYIN, "uzps").let { e.candidates(PINYIN, 0, 30).map { it.text } }
            val at = list.indexOf("国")
            assertTrue("$list", at >= 7 && "國" in list)
            val done = e.learning(PINYIN, LearningOp.Block(at, "国"), traditional)
            assertTrue("$done", done is LearningResult.Done)
            // under the strokes typed, as the lookup blocks
            assertEquals(setOf("国", "國"), e.underCode())
            e.learning(PINYIN, LearningOp.Undo((done as LearningResult.Done).undo))
            assertEquals(emptySet<String>(), e.underCode())
        }
    }

    @Test
    fun aHandwritingBlockPastThePageTakesTheAlikeAndNoOther() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            val texts = fillers + "国" + "國"
            e.onEvent(HANDWRITING, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(0, InkReading(texts, DoubleArray(texts.size) { -it.toDouble() })))
            val list = e.candidates(HANDWRITING, 0, 30).map { it.text }
            val at = list.indexOf("国")
            assertTrue("$list", at >= 7 && "國" in list)
            val done = e.learning(HANDWRITING, LearningOp.Block(at, "国"), traditional)
            assertTrue("$done", done is LearningResult.Done)
            assertEquals(setOf("国", "國"), e.blocked())
        }
    }

    // review R4 M1: the pressed one refused by the host (its list changed), the one shown alike
    // acted on before it is taken back: Unavailable, and nothing blocked
    @Test
    fun aBlockWhoseOwnActIsRefusedLeavesNothingBlocked() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            e.onEvent(PINYIN, EngineEvent.RESET, 0)
            e.type(PINYIN, "uzps")
            val before = e.candidates(PINYIN, 0, 30).map { it.text }
            val at = before.indexOf("国")
            val acts = ArrayList<String>()
            val done = e.learning(PINYIN, LearningOp.Block(at, "国"), traditional) { ev, i, t ->
                acts += t
                i != at && e.pressShown(PINYIN, ev, i)
            }
            assertEquals(LearningResult.Unavailable, done)
            assertTrue("$acts", "國" in acts)
            assertEquals(emptySet<String>(), e.underCode())
        }
    }

    // the same of a delete: the words added by hand, deleted with it, are back
    @Test
    fun aDeleteWhoseOwnActIsRefusedLeavesTheWords() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            assertTrue(e.addWord("国甲", "guo jia"))
            assertTrue(e.addWord("國甲", "guo jia"))
            e.onEvent(PINYIN, EngineEvent.RESET, 0)
            val list = e.type(PINYIN, "guojia").candidates
            val at = list.indexOf("国甲")
            assertTrue("$list", at >= 0 && "國甲" in list)
            val acts = ArrayList<String>()
            val done = e.learning(PINYIN, LearningOp.Delete(at, "国甲"), traditional) { ev, i, t ->
                acts += t
                i != at && e.pressShown(PINYIN, ev, i)
            }
            assertEquals(LearningResult.Unavailable, done)
            assertTrue("$acts", "國甲" in acts)
            assertEquals(setOf("国甲", "國甲"), e.userWords().map { it.text }.toSet())
        }
    }
}
