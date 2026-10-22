/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.backup.Backups
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.user.UserLog
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** U-100, U-106 as the engine does them: a backup made, restored on another phone, taken back. */
class EnginesBackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    // twenty codes of four characters each, the first the likeliest
    private val syllables = listOf("ba", "bo", "ca", "ce", "da", "de", "fa", "fo", "ga", "ge", "ha", "he", "ka", "ke", "la", "le", "ma", "me", "na", "ne")

    private fun char(i: Int, j: Int) = String(Character.toChars(0x4E00 + i * 4 + j))

    private val pinyin = PinyinDataBuilder().unigram("<unk>", -7f, 0f).apply {
        for ((i, s) in syllables.withIndex()) {
            for (j in 0..3) {
                unigram(char(i, j), -2f - j, 0f)
                entry(char(i, j), syl(s))
            }
        }
    }.build().toByteArray()

    private fun load(path: String): ByteBuffer = if (path == Engines.PINYIN_DATA) ByteBuffer.wrap(pinyin) else throw FileNotFoundException(path)

    private fun engines(dir: File, install: File? = null) = Engines(::load, dir, installFile = install)

    private fun Engines.top3(keys: String): List<String> {
        context(Engines.PINYIN, "", cleared = true)
        type(Engines.PINYIN, keys)
        return candidates(Engines.PINYIN, 0, 3).map { it.text }.also { onEvent(Engines.PINYIN, EngineEvent.RESET, 0) }
    }

    private fun Engines.pick(keys: String, text: String) {
        context(Engines.PINYIN, "", cleared = true)
        type(Engines.PINYIN, keys)
        onEvent(Engines.PINYIN, EngineEvent.PICK, candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == text })
    }

    // the twenty, each corrected: the third picked twice (first now), the fourth once (second now)
    private fun Engines.correctAll() {
        for ((i, s) in syllables.withIndex()) {
            if (i % 2 == 0) repeat(2) { pick(s, char(i, 2)) } else pick(s, char(i, 3))
        }
    }

    private fun Engines.allTop3() = syllables.map { top3(it) }

    private fun Engines.backup(op: BackupOp) = learning(null, op) as BackupResult

    // a settings backup's stash, as the app leaves it (UserDataManager): a directory of its own, named by the time
    private fun stash(dir: File, time: Long = 1000L) = File(dir, RestoreStash.DIR + "/" + time).apply { mkdirs() }

    // U-100: exported, a new install, imported: the first three of each corrected input as they were
    @Test
    fun aBackupKeepsTheFirstThreeOfTwentyCorrectedInputsOnANewPhone() {
        val (before, bytes) = engines(folder.newFolder()).use { phone ->
            val fresh = phone.allTop3()
            phone.correctAll()
            val learned = phone.allTop3()
            assertNotEquals(fresh, learned)
            assertEquals(char(0, 2), learned[0][0])
            learned to checkNotNull(phone.backup(BackupOp.Copy).bytes)
        }
        engines(folder.newFolder()).use { newPhone ->
            assertTrue(newPhone.backup(BackupOp.Restore(bytes)).ok)
            assertEquals(before, newPhone.allTop3())
            // twice is once
            newPhone.backup(BackupOp.Restore(bytes))
            assertEquals(before, newPhone.allTop3())
        }
    }

    // PLAN §2.9: the words added and blocked go with it, as the lists are no part of the record
    @Test
    fun theWordsAddedAndBlockedComeBack() {
        val bytes = engines(folder.newFolder()).use { phone ->
            phone.addWord("巴波", "ba bo")
            phone.blockWord(char(0, 1) + char(1, 1), "ba bo")
            checkNotNull(phone.backup(BackupOp.Copy).bytes)
        }
        engines(folder.newFolder()).use { newPhone ->
            newPhone.backup(BackupOp.Restore(bytes))
            assertEquals(listOf("巴波"), newPhone.userWords(Engines.UserWord.Kind.ADDED).map { it.text })
            assertEquals(listOf(char(0, 1) + char(1, 1)), newPhone.userWords(Engines.UserWord.Kind.BLOCKED).map { it.text })
        }
    }

    // C38: 退回恢复前 puts back the learning and the lists as they were, whole
    @Test
    fun aRestoreIsTakenBackWhole() {
        val bytes = engines(folder.newFolder()).use { other ->
            other.correctAll()
            other.addWord("巴波", "ba bo")
            checkNotNull(other.backup(BackupOp.Copy).bytes)
        }
        engines(folder.newFolder()).use { phone ->
            phone.pick("ba", char(0, 3))
            val before = phone.allTop3()
            phone.backup(BackupOp.Restore(bytes))
            assertNotEquals(before, phone.allTop3())
            assertTrue(phone.backup(BackupOp.Kept).canRevert)
            assertTrue(phone.backup(BackupOp.Revert).ok)
            assertEquals(before, phone.allTop3())
            assertEquals(emptyList<String>(), phone.userWords(Engines.UserWord.Kind.ADDED).map { it.text })
            assertFalse(phone.backup(BackupOp.Kept).canRevert)
            // learning goes on after it, on the record rewritten
            phone.pick("bo", char(1, 3))
            phone.pick("bo", char(1, 3))
            assertEquals(char(1, 3), phone.top3("bo")[0])
        }
    }

    // U-106: on the first typing day one is made, in the engine's own directory, of this install
    @Test
    fun aBackupIsMadeOnTheFirstTypingDay() {
        val dir = folder.newFolder()
        val install = File(folder.newFolder(), "learn.install")
        engines(dir, install).use { phone ->
            assertEquals(emptyList<Any>(), phone.backup(BackupOp.Kept).backups)
            phone.pick("ba", char(0, 2))
            // written in the background, the key not waiting on the disk
            val until = System.currentTimeMillis() + 5000
            while (phone.backup(BackupOp.Kept).backups.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10)
            val kept = phone.backup(BackupOp.Kept).backups.single()
            assertTrue(kept.own)
            assertTrue(File(File(dir, Backups.DIR), kept.name).isFile)
        }
        // another install (the id gone with the app) sees it as another's
        engines(dir, File(folder.newFolder(), "learn.install")).use { assertFalse(it.backup(BackupOp.Kept).backups.single().own) }
    }

    // a settings backup restored while the engine was stopped: merged as it starts, then gone
    @Test
    fun aSettingsBackupsLearningIsMergedAtTheStart() {
        val bytes = engines(folder.newFolder()).use { other ->
            other.correctAll()
            checkNotNull(other.backup(BackupOp.Copy).bytes)
        }
        val learned = engines(folder.newFolder()).use { it.apply { correctAll() }.allTop3() }
        val dir = folder.newFolder()
        val before = engines(dir).use { it.pick("ne", char(19, 1)); it.allTop3() }
        stash(dir).resolve(RestoreStash.BACKUP).writeBytes(bytes)
        val merged = engines(dir).use { phone ->
            assertFalse(File(dir, RestoreStash.DIR).exists())
            assertEquals(learned.dropLast(1), phone.allTop3().dropLast(1))
            // what the phone learned of ne kept beside the backup's
            assertTrue(phone.top3("ne").containsAll(listOf(char(19, 1), char(19, 3))))
            assertTrue(phone.backup(BackupOp.Kept).canRevert)
            phone.allTop3()
        }
        // D-M2: killed before the stash was gone: merged again at the next start, which changes nothing, the copy before the first kept
        stash(dir).resolve(RestoreStash.BACKUP).writeBytes(bytes)
        engines(dir).use { phone ->
            assertEquals(merged, phone.allTop3())
            assertTrue(phone.backup(BackupOp.Revert).ok)
            assertEquals(before, phone.allTop3())
        }
    }

    // a record that does not open learns in memory: a restore there would not last, so none is made and the stash waits
    @Test
    fun noRestoreOnARecordThatDidNotOpen() {
        val bytes = engines(folder.newFolder()).use { checkNotNull(it.apply { correctAll() }.backup(BackupOp.Copy).bytes) }
        val dir = folder.newFolder()
        File(dir, LearnLog.FILE).mkdirs()
        stash(dir).resolve(RestoreStash.BACKUP).writeBytes(bytes)
        engines(dir).use { phone ->
            assertTrue(File(dir, RestoreStash.DIR).isDirectory)
            assertFalse(phone.backup(BackupOp.Restore(bytes)).ok)
            assertFalse(phone.backup(BackupOp.Kept).canRevert)
        }
    }

    // a stash that does not read is set aside, told once, not tried at every start; one after it still merged
    @Test
    fun aStashThatDoesNotReadIsSetAside() {
        val bytes = engines(folder.newFolder()).use { other ->
            other.correctAll()
            checkNotNull(other.backup(BackupOp.Copy).bytes)
        }
        val dir = folder.newFolder()
        stash(dir, 1000L).resolve(RestoreStash.BACKUP).writeBytes(byteArrayOf(1, 2, 3))
        stash(dir, 2000L).resolve(RestoreStash.BACKUP).writeBytes(bytes)
        val errors = ArrayList<Exception>()
        Engines(::load, dir, onError = { errors += it }).use { phone ->
            assertEquals(char(0, 2), phone.top3("ba")[0])
        }
        assertEquals(1, errors.count { "set aside" in it.message.orEmpty() })
        assertTrue(File(dir, RestoreStash.UNREADABLE).isDirectory)
        assertFalse(File(dir, RestoreStash.DIR).exists())
    }

    // a zip of an earlier build: its learning record, read as another install's
    @Test
    fun anEarlierBuildsLogIsMergedAtTheStart() {
        val other = folder.newFolder()
        val learned = engines(other).use { it.apply { correctAll() }.allTop3() }
        val dir = folder.newFolder()
        stash(dir).resolve(LearnLog.FILE).writeBytes(File(other, LearnLog.FILE).readBytes())
        engines(dir).use { assertEquals(learned, it.allTop3()) }
    }

    // a 0.3.0 zip: its pinyin.user converted, its words made the user's, and its lists merged
    @Test
    fun anOldBuildsFilesAreConvertedAndMerged() {
        val dir = folder.newFolder()
        val stash = stash(dir)
        val made = UserModel.Entry(char(0, 3) + char(1, 3), syl("ba", "bo"))
        stash.resolve(Engines.USER_PINYIN).writeBytes(UserLog.header() + UserLog.sentence(null, listOf(made)) + UserLog.sentence(null, listOf(made)))
        stash.resolve("words.added").writeText("巴波 ba'bo\n")
        engines(dir).use { phone ->
            assertEquals(setOf(made.text), phone.userWords(Engines.UserWord.Kind.LEARNED).map { it.text }.toSet())
            assertEquals(listOf("巴波"), phone.userWords(Engines.UserWord.Kind.ADDED).map { it.text })
        }
        assertFalse(File(dir, RestoreStash.DIR).exists())
    }

    @Test
    fun aFileThatIsNoBackupIsSaidSoAndChangesNothing() {
        engines(folder.newFolder()).use { phone ->
            phone.correctAll()
            val before = phone.allTop3()
            val result = phone.backup(BackupOp.Restore("no backup".toByteArray()))
            assertFalse(result.ok)
            assertEquals(before, phone.allTop3())
            assertFalse(phone.backup(BackupOp.Kept).canRevert)
        }
    }

    // D-S6: the user dictionary's export restored is a restore: imported, and taken back by 退回恢复前
    @Test
    fun anExportRestoredIsTakenBack() {
        engines(folder.newFolder()).use { phone ->
            assertTrue(phone.backup(BackupOp.Restore("# youmo-words 1\n巴波 ba'bo\n".toByteArray())).ok)
            assertEquals(listOf("巴波"), phone.userWords(Engines.UserWord.Kind.ADDED).map { it.text })
            assertTrue(phone.backup(BackupOp.Revert).ok)
            assertEquals(emptyList<String>(), phone.userWords(Engines.UserWord.Kind.ADDED).map { it.text })
        }
    }

    // no user directory (direct boot, the eval tools): nothing to keep a copy in, so no restore
    @Test
    fun noRestoreWithNoUserDirectory() {
        val bytes = engines(folder.newFolder()).use { checkNotNull(it.apply { correctAll() }.backup(BackupOp.Copy).bytes) }
        Engines(::load, null).use { phone ->
            val before = phone.allTop3()
            assertFalse(phone.backup(BackupOp.Restore(bytes)).ok)
            assertEquals(before, phone.allTop3())
        }
    }
}
