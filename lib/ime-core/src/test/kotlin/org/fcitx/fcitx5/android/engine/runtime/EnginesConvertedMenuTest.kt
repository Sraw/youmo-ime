/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A long press on a candidate the host shows converted (繁體, chttrans): the menu and its acts work on the engine's text. */
class EnginesConvertedMenuTest : EnginesFixture() {

    init {
        pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("国家", -2f, 0f)
            .unigram("过家", -4f, 0f)
            .unigram("國家", -5f, 0f)
            .entry("国家", intArrayOf(Syllables.id("guo"), Syllables.id("jia")))
            .entry("过家", intArrayOf(Syllables.id("guo"), Syllables.id("jia")))
            .entry("國家", intArrayOf(Syllables.id("guo"), Syllables.id("jia")))
            .build().toByteArray()
    }

    // 国 as 國, as chttrans would show it
    private val traditional = { text: String -> text.replace('国', '國').replace('过', '過') }

    private fun Engines.typed(): List<String> {
        onEvent(PINYIN, EngineEvent.RESET, 0)
        return type(PINYIN, "guojia").candidates
    }

    @Test
    fun aCandidateShownConvertedIsBlockedAsTheEngineHasIt() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            val at = e.typed().indexOf("国家")
            val menu = e.learning(PINYIN, LearningOp.Menu(at, "國家"), traditional) as LearningResult.Menu
            assertTrue(MenuItem.BLOCK in menu.items)
            // the engine's: what the acts name
            assertEquals("国家", menu.text)
            // a text as shown is no act's: the menu's is
            assertEquals(LearningResult.Unavailable, e.learning(PINYIN, LearningOp.Block(at, "國家"), traditional))
            val done = e.learning(PINYIN, LearningOp.Block(at, menu.text), traditional) as LearningResult.Done
            // the dictionary's own 國家 too: nothing left shown 國家 (review R2)
            assertFalse("国家" in e.typed())
            assertFalse("國家" in e.typed())
            assertEquals(setOf("国家", "國家"), e.userWords().filter { it.kind == UserWord.Kind.BLOCKED }.map { it.text }.toSet())
            e.learning(PINYIN, LearningOp.Undo(done.undo))
            assertTrue("国家" in e.typed())
            assertTrue("國家" in e.typed())
            assertEquals(emptyList<UserWord>(), e.userWords())
            // another candidate's text, converted or not, is not the one at the index
            assertEquals(LearningResult.Unavailable, e.learning(PINYIN, LearningOp.Block(at, "過家"), traditional))
            // without the host's conversion, a converted text names nothing
            assertEquals(LearningResult.Unavailable, e.learning(PINYIN, LearningOp.Menu(at, "國家")))
        }
    }

    // the dictionary's own 國家 pressed: 国家, converted to it, blocked with it (UAT PY-116)
    @Test
    fun blockingTheDictionarysOwnTakesTheOneConvertedToIt() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            val at = e.typed().indexOf("國家")
            val menu = e.learning(PINYIN, LearningOp.Menu(at, "國家"), traditional) as LearningResult.Menu
            e.learning(PINYIN, LearningOp.Block(at, menu.text), traditional) as LearningResult.Done
            assertFalse("国家" in e.typed())
            assertFalse("國家" in e.typed())
        }
    }

    // the menu was for the dictionary's own 國家; the list changed and the index names 国家, shown
    // alike: the act's text is the engine's, and not the one there now (review R2)
    @Test
    fun anActOnACandidateShownAlikeButAnotherIsNotDone() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            val at = e.typed().indexOf("国家")
            assertEquals(LearningResult.Unavailable, e.learning(PINYIN, LearningOp.Block(at, "國家"), traditional))
            assertEquals(LearningResult.Unavailable, e.learning(PINYIN, LearningOp.Delete(at, "國家"), traditional))
            assertEquals(LearningResult.Unavailable, e.learning(PINYIN, LearningOp.Pin(at, "國家"), traditional))
            assertEquals(emptyList<UserWord>(), e.userWords())
        }
    }

    // a pin is the pressed one's alone; a delete, like a block, every one shown alike, undone together
    @Test
    fun aPinIsThePressedOnesADeleteEveryOneShownAlike() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            // the dictionary's own 國家 pinned: 国家, shown alike, stays where it was, after it
            val native = e.typed().indexOf("國家")
            val menu = e.learning(PINYIN, LearningOp.Menu(native, "國家"), traditional) as LearningResult.Menu
            e.learning(PINYIN, LearningOp.Pin(native, menu.text), traditional) as LearningResult.Done
            val list = e.typed()
            assertEquals("國家", list.first())
            assertTrue("$list", "国家" in list && "过家" in list)
            assertTrue("$list", list.indexOf("国家") < list.indexOf("过家"))
        }
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            assertTrue(e.addWord("国度", "guo du"))
            assertTrue(e.addWord("國度", "guo du"))
            onEventReset(e)
            val list = e.type(PINYIN, "guodu").candidates
            val at = list.indexOf("国度")
            val menu = e.learning(PINYIN, LearningOp.Menu(at, "國度"), traditional) as LearningResult.Menu
            assertTrue("${menu.items}", MenuItem.DELETE in menu.items)
            val done = e.learning(PINYIN, LearningOp.Delete(at, menu.text), traditional) as LearningResult.Done
            assertEquals(emptyList<UserWord>(), e.userWords())
            e.learning(PINYIN, LearningOp.Undo(done.undo))
            assertEquals(setOf("国度", "國度"), e.userWords().map { it.text }.toSet())
        }
    }

    private fun onEventReset(e: Engines) = e.onEvent(PINYIN, EngineEvent.RESET, 0)
}
