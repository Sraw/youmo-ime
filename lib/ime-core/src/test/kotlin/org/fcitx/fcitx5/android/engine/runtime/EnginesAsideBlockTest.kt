/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.fcitx.fcitx5.android.engine.phrase.EmojiData
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An emoji or a character by its parts among pinyin's candidates, blocked by its text in the reading it stands for. */
class EnginesAsideBlockTest : EnginesFixture() {

    init {
        emoji = EmojiData.format(emptyList(), mapOf("好" to listOf("👌", "👍"))).toByteArray()
        chaizi = Chaizi.format(mapOf("nvzi" to listOf("好", "妤"))).toByteArray()
    }

    private fun Engines.typed(keys: String): List<String> {
        onEvent(PINYIN, EngineEvent.RESET, 0)
        return type(PINYIN, keys).candidates
    }

    @Test
    fun anEmojiIsBlockedInItsWordsReadingTillUnblocked() {
        val dir = MemoryFiles()
        Engines(::load, dir, warmUps = inPlace).use { e ->
            val at = e.typed("hao").indexOf("👌")
            val menu = e.learning(PINYIN, LearningOp.Menu(at, "👌")) as LearningResult.Menu
            assertTrue(MenuItem.BLOCK in menu.items)
            val done = e.learning(PINYIN, LearningOp.Block(at, "👌")) as LearningResult.Done
            assertEquals(listOf("好", "👍"), e.candidates(PINYIN, 0, 9).map { it.text })
            assertEquals(listOf(UserWord("👌", "hao", UserWord.Kind.BLOCKED)), e.userWords())
            e.learning(PINYIN, LearningOp.Undo(done.undo))
            assertTrue("👌" in e.typed("hao"))
            e.onEvent(PINYIN, EngineEvent.BLOCK, e.typed("hao").indexOf("👌"))
            // 好 itself still offered
            assertEquals(listOf("好", "👍"), e.typed("hao"))
        }
        Engines(::load, dir, warmUps = inPlace).use { e ->
            assertFalse("👌" in e.typed("hao"))
            e.removeWord(UserWord("👌", "hao", UserWord.Kind.BLOCKED))
            assertTrue("👌" in e.typed("hao"))
        }
    }

    @Test
    fun aCharacterByItsPartsIsBlockedInItsReadingTillUnblocked() {
        val dir = MemoryFiles()
        Engines(::load, dir, warmUps = inPlace).use { e ->
            val at = e.typed("unvzi").indexOf("好")
            val menu = e.learning(PINYIN, LearningOp.Menu(at, "好")) as LearningResult.Menu
            assertTrue(menu.items.toString(), MenuItem.BLOCK in menu.items)
            e.learning(PINYIN, LearningOp.Block(at, "好"))
            assertFalse("好" in e.typed("unvzi"))
            assertEquals(listOf(UserWord("好", "hao", UserWord.Kind.BLOCKED)), e.userWords())
        }
        Engines(::load, dir, warmUps = inPlace).use { e ->
            assertFalse("好" in e.typed("unvzi"))
            e.removeWord(UserWord("好", "hao", UserWord.Kind.BLOCKED))
            assertTrue("好" in e.typed("unvzi"))
        }
    }
}
