/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.backup.Backups
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.platform.write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer

/** U-100, U-106 as the engine does them: a backup made, restored on another phone, taken back. */
class EnginesBackupTest {
    // a directory where a file should be: on the disk too
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

    private fun load(path: String): ByteBuffer = if (path == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(pinyin) else throw FileNotFoundException(path)

    private fun engines(dir: UserFiles, install: LogFile? = null) = Engines(::load, dir, installFile = install)

    private fun Engines.top3(keys: String): List<String> {
        context(InputMethodCatalog.PINYIN, "", cleared = true)
        type(InputMethodCatalog.PINYIN, keys)
        return candidates(InputMethodCatalog.PINYIN, 0, 3).map { it.text }.also { onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0) }
    }

    private fun Engines.pick(keys: String, text: String) {
        context(InputMethodCatalog.PINYIN, "", cleared = true)
        type(InputMethodCatalog.PINYIN, keys)
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == text })
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
    private fun stash(dir: UserFiles, time: Long = 1000L) = dir.dir(RestoreStash.DIR).dir("$time").apply { mkdirs() }

    // U-100: exported, a new install, imported: the first three of each corrected input as they were
    @Test
    fun aBackupKeepsTheFirstThreeOfTwentyCorrectedInputsOnANewPhone() {
        val (before, bytes) = engines(MemoryFiles()).use { phone ->
            val fresh = phone.allTop3()
            phone.correctAll()
            val learned = phone.allTop3()
            assertNotEquals(fresh, learned)
            assertEquals(char(0, 2), learned[0][0])
            learned to checkNotNull(phone.backup(BackupOp.Copy).bytes)
        }
        engines(MemoryFiles()).use { newPhone ->
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
        val bytes = engines(MemoryFiles()).use { phone ->
            phone.addWord("巴波", "ba bo")
            phone.blockWord(char(0, 1) + char(1, 1), "ba bo")
            checkNotNull(phone.backup(BackupOp.Copy).bytes)
        }
        engines(MemoryFiles()).use { newPhone ->
            newPhone.backup(BackupOp.Restore(bytes))
            assertEquals(listOf("巴波"), newPhone.userWords(UserWord.Kind.ADDED).map { it.text })
            assertEquals(listOf(char(0, 1) + char(1, 1)), newPhone.userWords(UserWord.Kind.BLOCKED).map { it.text })
        }
    }

    // C38: 退回恢复前 puts back the learning and the lists as they were, whole
    @Test
    fun aRestoreIsTakenBackWhole() {
        val bytes = engines(MemoryFiles()).use { other ->
            other.correctAll()
            other.addWord("巴波", "ba bo")
            checkNotNull(other.backup(BackupOp.Copy).bytes)
        }
        engines(MemoryFiles()).use { phone ->
            phone.pick("ba", char(0, 3))
            val before = phone.allTop3()
            phone.backup(BackupOp.Restore(bytes))
            assertNotEquals(before, phone.allTop3())
            assertTrue(phone.backup(BackupOp.Kept).canRevert)
            assertTrue(phone.backup(BackupOp.Revert).ok)
            assertEquals(before, phone.allTop3())
            assertEquals(emptyList<String>(), phone.userWords(UserWord.Kind.ADDED).map { it.text })
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
        val dir = MemoryFiles()
        val install = MemoryFiles().file("learn.install")
        val background = ArrayList<Runnable>()
        Engines(::load, dir, installFile = install, background = { background += it }).use { phone ->
            assertEquals(emptyList<Any>(), phone.backup(BackupOp.Kept).backups)
            phone.pick("ba", char(0, 2))
            // written in the background, the key not waiting on the disk: none till that runs
            assertEquals(emptyList<Any>(), phone.backup(BackupOp.Kept).backups)
            assertEquals(1, background.size)
            background.removeAt(0).run()
            val kept = phone.backup(BackupOp.Kept).backups.single()
            assertTrue(kept.own)
            assertTrue(dir.dir(Backups.DIR).file(kept.name).exists())
        }
        // another install (the id gone with the app) sees it as another's
        engines(dir, MemoryFiles().file("learn.install")).use { assertFalse(it.backup(BackupOp.Kept).backups.single().own) }
    }

    // a settings backup restored while the engine was stopped: merged as it starts, then gone
    @Test
    fun aSettingsBackupsLearningIsMergedAtTheStart() {
        val bytes = engines(MemoryFiles()).use { other ->
            other.correctAll()
            checkNotNull(other.backup(BackupOp.Copy).bytes)
        }
        val learned = engines(MemoryFiles()).use { it.apply { correctAll() }.allTop3() }
        val dir = MemoryFiles()
        val before = engines(dir).use { it.pick("ne", char(19, 1)); it.allTop3() }
        stash(dir).file(RestoreStash.BACKUP).write(bytes)
        val merged = engines(dir).use { phone ->
            assertFalse(dir.dir(RestoreStash.DIR).exists())
            assertEquals(learned.dropLast(1), phone.allTop3().dropLast(1))
            // what the phone learned of ne kept beside the backup's
            assertTrue(phone.top3("ne").containsAll(listOf(char(19, 1), char(19, 3))))
            assertTrue(phone.backup(BackupOp.Kept).canRevert)
            phone.allTop3()
        }
        // D-M2: killed before the stash was gone: merged again at the next start, which changes nothing, the copy before the first kept
        stash(dir).file(RestoreStash.BACKUP).write(bytes)
        engines(dir).use { phone ->
            assertEquals(merged, phone.allTop3())
            assertTrue(phone.backup(BackupOp.Revert).ok)
            assertEquals(before, phone.allTop3())
        }
    }

    // a record that does not open learns in memory: a restore there would not last, so none is made and the stash waits
    @Test
    fun noRestoreOnARecordThatDidNotOpen() = assertRows(
        listOf<Pair<String, () -> UserFiles>>("in memory" to { MemoryFiles() }, "on disk" to { JvmFiles(folder.newFolder()) }),
        { it.first },
    ) { noRestoreOnARecordNotOpen(it.second()) }

    private fun noRestoreOnARecordNotOpen(dir: UserFiles) {
        val bytes = engines(MemoryFiles()).use { checkNotNull(it.apply { correctAll() }.backup(BackupOp.Copy).bytes) }
        dir.dir(LearnLog.FILE).mkdirs()
        stash(dir).file(RestoreStash.BACKUP).write(bytes)
        engines(dir).use { phone ->
            assertTrue(dir.dir(RestoreStash.DIR).exists())
            assertFalse(phone.backup(BackupOp.Restore(bytes)).ok)
            assertFalse(phone.backup(BackupOp.Kept).canRevert)
        }
    }

    // a stash that does not read is set aside, told once, not tried at every start; one after it still merged
    @Test
    fun aStashThatDoesNotReadIsSetAside() {
        val bytes = engines(MemoryFiles()).use { other ->
            other.correctAll()
            checkNotNull(other.backup(BackupOp.Copy).bytes)
        }
        val dir = MemoryFiles()
        stash(dir, 1000L).file(RestoreStash.BACKUP).write(byteArrayOf(1, 2, 3))
        stash(dir, 2000L).file(RestoreStash.BACKUP).write(bytes)
        val errors = ArrayList<Exception>()
        Engines(::load, dir, onError = { errors += it }).use { phone ->
            assertEquals(char(0, 2), phone.top3("ba")[0])
        }
        assertEquals(1, errors.count { "set aside" in it.message.orEmpty() })
        assertTrue(dir.dir(RestoreStash.UNREADABLE).exists())
        assertFalse(dir.dir(RestoreStash.DIR).exists())
    }

    // a zip of an earlier build: its learning record, read as another install's
    @Test
    fun anEarlierBuildsLogIsMergedAtTheStart() {
        val other = MemoryFiles()
        val learned = engines(other).use { it.apply { correctAll() }.allTop3() }
        val dir = MemoryFiles()
        stash(dir).file(LearnLog.FILE).write(other.file(LearnLog.FILE).read())
        engines(dir).use { assertEquals(learned, it.allTop3()) }
    }

    // a 0.3.0 zip, its learning in the old logs alone: no longer converted, set aside unread, the lists left too
    @Test
    fun anOldBuildsFilesAreSetAsideUnread() {
        val dir = MemoryFiles()
        val stash = stash(dir)
        stash.file("pinyin.user").write(byteArrayOf(0x46, 0x58, 0x55, 0x4C))
        stash.file("words.added").write("巴波 ba'bo\n".toByteArray())
        val errors = ArrayList<IOException>()
        Engines(::load, dir, onError = { errors += it }).use { phone ->
            assertEquals(emptyList<UserWord>(), phone.userWords())
        }
        assertEquals(1, errors.size)
        assertFalse(dir.dir(RestoreStash.DIR).exists())
        assertTrue(dir.dir(RestoreStash.UNREADABLE).exists())
    }

    @Test
    fun aFileThatIsNoBackupIsSaidSoAndChangesNothing() {
        engines(MemoryFiles()).use { phone ->
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
        engines(MemoryFiles()).use { phone ->
            assertTrue(phone.backup(BackupOp.Restore("# youmo-words 1\n巴波 ba'bo\n".toByteArray())).ok)
            assertEquals(listOf("巴波"), phone.userWords(UserWord.Kind.ADDED).map { it.text })
            assertTrue(phone.backup(BackupOp.Revert).ok)
            assertEquals(emptyList<String>(), phone.userWords(UserWord.Kind.ADDED).map { it.text })
        }
    }

    // no user directory (direct boot, the eval tools): nothing to keep a copy in, so no restore
    @Test
    fun noRestoreWithNoUserDirectory() {
        val bytes = engines(MemoryFiles()).use { checkNotNull(it.apply { correctAll() }.backup(BackupOp.Copy).bytes) }
        Engines(::load, null).use { phone ->
            val before = phone.allTop3()
            assertFalse(phone.backup(BackupOp.Restore(bytes)).ok)
            assertEquals(before, phone.allTop3())
        }
    }
}
