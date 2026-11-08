/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.english.EnglishBlocks
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** English's words blocked from a long press: never offered, completed, corrected, predicted or glided to; unblocked in the settings. */
class EnginesEnglishBlockTest : EnginesFixture() {

    private fun engines(dir: UserFiles = MemoryFiles()) = Engines(::load, dir, warmUps = inPlace).also { it.context(ENGLISH, "", started = true) }

    private fun Engines.typed(keys: String): List<String> {
        onEvent(ENGLISH, EngineEvent.RESET, 0)
        context(ENGLISH, "", started = true)
        return type(ENGLISH, keys).candidates
    }

    private fun Engines.blocked() = userWords(UserWord.Kind.BLOCKED)

    private val the = UserWord("the", "", UserWord.Kind.BLOCKED)

    @Test
    fun aWordBlockedIsNotCompletedTillUnblockedInTheSettings() {
        val dir = MemoryFiles()
        engines(dir).use { e ->
            assertEquals(listOf("th", "the", "you"), e.typed("th"))
            // the letters typed are kept as typed: nothing to block
            assertFalse(Offer.BLOCK in e.offers(ENGLISH, 0))
            val menu = e.learning(ENGLISH, LearningOp.Menu(1, "the")) as LearningResult.Menu
            assertEquals(listOf(MenuItem.BLOCK), menu.items)
            val done = e.learning(ENGLISH, LearningOp.Block(1, "the")) as LearningResult.Done
            assertFalse("the" in e.candidates(ENGLISH, 0, 9).map { it.text })
            assertEquals(listOf(the), e.blocked())
            // taken back on the spot
            e.learning(ENGLISH, LearningOp.Undo(done.undo))
            assertEquals(emptyList<UserWord>(), e.blocked())
            assertTrue("the" in e.typed("th"))
            e.onEvent(ENGLISH, EngineEvent.BLOCK, 1)
            assertFalse("the" in e.typed("th"))
            // in any case: The at a sentence's start
            assertEquals(mapOf(UserWord.Kind.ADDED to 0, UserWord.Kind.LEARNED to 0, UserWord.Kind.BLOCKED to 1), e.userWordCounts())
        }
        // kept in the user directory, a file of its own; pinyin's list untouched
        assertTrue(dir.file(EnglishBlocks.FILE).exists())
        engines(dir).use { e ->
            assertFalse("the" in e.typed("th"))
            e.removeWord(the)
            assertEquals(emptyList<UserWord>(), e.blocked())
            assertTrue("the" in e.typed("th"))
        }
        engines(dir).use { e -> assertTrue("the" in e.typed("th")) }
    }

    @Test
    fun aWordBlockedIsNeverCorrectedTo() {
        engines().use { e ->
            assertEquals(listOf("the", "thw"), e.typed("thw"))
            e.onEvent(ENGLISH, EngineEvent.BLOCK, 0)
            assertEquals("thw ", e.typed("thw").let { e.onEvent(ENGLISH, EngineEvent.CHAR, ' '.code).commit })
            e.removeWord(the)
            assertEquals("the ", e.typed("thw").let { e.onEvent(ENGLISH, EngineEvent.CHAR, ' '.code).commit })
        }
    }

    private fun Engines.glided(keys: String): String {
        onEvent(ENGLISH, EngineEvent.RESET, 0)
        val path = Glide(keys.flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray(), GlideLayout.QWERTY)
        return onEvent(ENGLISH, EngineEvent.CHAR, Keyboard.GLIDE, payload = checkNotNull(glide(ENGLISH, path))).commit
    }

    @Test
    fun aWordBlockedIsNotGlided() {
        engines().use { e ->
            assertEquals("the ", e.glided("the"))
            e.typed("th")
            e.onEvent(ENGLISH, EngineEvent.BLOCK, 1)
            assertFalse(e.glided("the") == "the ")
            e.removeWord(the)
            assertEquals("the ", e.glided("the"))
        }
    }

    @Test
    fun anUnblockInTheSettingsIsUndone() {
        engines().use { e ->
            e.typed("th")
            e.onEvent(ENGLISH, EngineEvent.BLOCK, 1)
            val done = e.learning(null, LearningOp.RemoveWords(listOf(the))) as LearningResult.Done
            assertEquals(emptyList<UserWord>(), e.blocked())
            e.learning(null, LearningOp.Undo(done.undo))
            assertEquals(listOf(the), e.blocked())
        }
    }

    // review: a restore reverted gives back the phone's English blocks as they were
    @Test
    fun aRevertAfterARestoreKeepsThePhonesBlockedWords() {
        val bytes = engines().use { e ->
            checkNotNull(e.backup(BackupOp.Copy).bytes)
        }
        engines().use { e ->
            e.typed("th")
            e.onEvent(ENGLISH, EngineEvent.BLOCK, 1)
            e.backup(BackupOp.Restore(bytes))
            assertEquals(listOf(the), e.blocked())
            e.backup(BackupOp.Revert)
            assertEquals(listOf(the), e.blocked())
        }
    }

    // review H2: a zip restored while the engine was stopped (UserDataManager's stash): the phone's and the backup's both kept
    @Test
    fun aStashedBackupsBlockedWordsAreMergedWithThePhones() {
        val dir = MemoryFiles()
        engines(dir).use { e ->
            e.typed("th")
            e.onEvent(ENGLISH, EngineEvent.BLOCK, 1)
            assertEquals(listOf(the), e.blocked())
        }
        val stash = dir.dir(RestoreStash.DIR).dir("1000").apply { mkdirs() }
        EnglishBlocks(stash).block(listOf("hello"))
        engines(dir).use { e ->
            assertFalse(dir.dir(RestoreStash.DIR).exists())
            assertEquals(setOf("the", "hello"), e.blocked().map { it.text }.toSet())
            assertFalse("hello" in e.typed("hel"))
        }
    }

    private fun Engines.backup(op: BackupOp) = learning(null, op) as BackupResult

    // a backup takes English's blocked words with pinyin's (no pinyin to them), and gives them back
    @Test
    fun aBackupTakesTheBlockedWords() {
        val bytes = engines().use { e ->
            e.typed("th")
            e.onEvent(ENGLISH, EngineEvent.BLOCK, 1)
            checkNotNull(e.backup(BackupOp.Copy).bytes)
        }
        engines().use { e ->
            e.backup(BackupOp.Restore(bytes))
            assertEquals(listOf(the), e.blocked())
            assertFalse("the" in e.typed("th"))
        }
    }
}
