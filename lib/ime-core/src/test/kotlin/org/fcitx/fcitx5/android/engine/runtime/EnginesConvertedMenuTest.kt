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
            .entry("国家", intArrayOf(Syllables.id("guo"), Syllables.id("jia")))
            .entry("过家", intArrayOf(Syllables.id("guo"), Syllables.id("jia")))
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
            assertEquals("國家", menu.text)
            val done = e.learning(PINYIN, LearningOp.Block(at, "國家"), traditional) as LearningResult.Done
            assertFalse("国家" in e.typed())
            assertEquals(listOf(UserWord("国家", "guo jia", UserWord.Kind.BLOCKED)), e.userWords())
            e.learning(PINYIN, LearningOp.Undo(done.undo))
            assertTrue("国家" in e.typed())
            // another candidate's text, converted or not, is not the one at the index
            assertEquals(LearningResult.Unavailable, e.learning(PINYIN, LearningOp.Block(at, "過家"), traditional))
            // without the host's conversion, a converted text names nothing
            assertEquals(LearningResult.Unavailable, e.learning(PINYIN, LearningOp.Menu(at, "國家")))
        }
    }
}
