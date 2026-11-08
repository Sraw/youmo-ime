/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.phrase.EmojiData
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An emoji of more code points than its word has syllables (👍🏻 hao), blocked: kept so, listed, undone, backed up (review H1). */
class EnginesEmojiBlockTest : EnginesFixture() {
    init {
        emoji = EmojiData.format(emptyList(), mapOf("好" to listOf("👍🏻", "👌"), "你好" to listOf("😃"))).toByteArray()
    }

    private fun Engines.typed(keys: String): List<String> {
        onEvent(PINYIN, EngineEvent.RESET, 0)
        return type(PINYIN, keys).candidates
    }

    @Test
    fun twoCodePointEmojiStaysBlockedAfterRestart() {
        val dir = MemoryFiles()
        Engines(::load, dir, warmUps = inPlace).use { e ->
            val list = e.typed("hao")
            val at = list.indexOf("👍🏻")
            assertTrue(list.toString(), at >= 0)
            e.learning(PINYIN, LearningOp.Block(at, "👍🏻")) as LearningResult.Done
            assertFalse("👍🏻" in e.typed("hao"))
        }
        Engines(::load, dir, warmUps = inPlace).use { e ->
            assertFalse("👍🏻" in e.typed("hao"))
        }
    }

    // fewer code points than syllables: 😃 read ni.hao
    @Test
    fun anEmojiOfTwoSyllablesStaysBlockedAfterRestart() {
        val dir = MemoryFiles()
        Engines(::load, dir, warmUps = inPlace).use { e ->
            val list = e.typed("nihao")
            assertTrue(list.toString(), "😃" in list)
            e.learning(PINYIN, LearningOp.Block(list.indexOf("😃"), "😃")) as LearningResult.Done
        }
        Engines(::load, dir, warmUps = inPlace).use { e ->
            assertFalse("😃" in e.typed("nihao"))
            e.removeWord(e.userWords().single())
            assertTrue("😃" in e.typed("nihao"))
        }
    }

    @Test
    fun twoCodePointEmojiUnblocksFromSettings() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            val at = e.typed("hao").indexOf("👍🏻")
            e.learning(PINYIN, LearningOp.Block(at, "👍🏻")) as LearningResult.Done
            val listed = e.userWords().single()
            val done = e.learning(null, LearningOp.RemoveWords(listOf(listed))) as LearningResult.Done
            assertTrue("👍🏻" in e.typed("hao"))
            // review M1: the unblock taken back blocks it again
            e.learning(null, LearningOp.Undo(done.undo))
            assertFalse("👍🏻" in e.typed("hao"))
            assertEquals(listOf(listed), e.userWords())
        }
    }

    @Test
    fun twoCodePointEmojiBackupRoundTrip() {
        val bytes = Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            val at = e.typed("hao").indexOf("👍🏻")
            e.learning(PINYIN, LearningOp.Block(at, "👍🏻")) as LearningResult.Done
            checkNotNull((e.learning(null, BackupOp.Copy) as BackupResult).bytes)
        }
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            e.learning(null, BackupOp.Restore(bytes))
            assertFalse("👍🏻" in e.typed("hao"))
        }
    }

    @Test
    fun twoCodePointEmojiBlockIsUndone() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            val at = e.typed("hao").indexOf("👍🏻")
            val done = e.learning(PINYIN, LearningOp.Block(at, "👍🏻")) as LearningResult.Done
            assertFalse("👍🏻" in e.typed("hao"))
            e.learning(PINYIN, LearningOp.Undo(done.undo))
            assertTrue("👍🏻" in e.typed("hao"))
            assertEquals(emptyList<Any>(), e.userWords())
        }
    }

    // review R2: the user's text export, imported again: the reading as blocked, not split among the code points
    @Test
    fun twoCodePointEmojiTextExportImportRoundTrip() {
        val lines = Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            val at = e.typed("hao").indexOf("👍🏻")
            e.learning(PINYIN, LearningOp.Block(at, "👍🏻")) as LearningResult.Done
            e.exportWords()
        }
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { e ->
            assertEquals(1, e.importWords(lines.asSequence()).blocked)
            assertFalse("👍🏻" in e.typed("hao"))
            assertEquals(listOf(UserWord("👍🏻", "hao", UserWord.Kind.BLOCKED)), e.userWords())
        }
    }
}
