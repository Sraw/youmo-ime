/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.simplifierOf
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserLog
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

class EnginesLearnLogTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    // impl/design-1a.md: the learning record beside the old logs, ranking nothing yet
    @Test
    fun aCommitIsRecordedInTheLearningLog() {
        val engines = Engines(::load, folder.root, simplifier = { simplifierOf('妳' to '你') })
        // the keyboard made by its first key, then told of the app's text
        repeat(2) {
            engines.context(Engines.PINYIN, "妳")
            engines.type(Engines.PINYIN, "ni")
            assertEquals("你", engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code).commit)
        }
        engines.close()
        assertTrue(File(folder.root, LearnLog.MARKER).exists())
        val state = LearnLog.replay(File(folder.root, LearnLog.FILE).readBytes())
        val key = state.stats.keys.single()
        assertEquals("qp:ni" to "你", key.code.toString() to key.text)
        // the app's 妳 read as 你: the word before
        assertEquals(1, state.pairs.keys.count { it.prev == "你" && it.next == "你" })
        // forgotten from the settings: there too
        val again = Engines(::load, folder.root)
        again.removeWords(listOf(Engines.UserWord("你", "ni", Engines.UserWord.Kind.LEARNED)))
        again.type(Engines.PINYIN, "ni")
        again.close()
        assertTrue(LearnLog.replay(File(folder.root, LearnLog.FILE).readBytes()).stats.isEmpty())
    }

    // review-1a-app N3: before the user unlocks (no user directory) it learns in memory, converting nothing
    @Test
    fun withNoUserDirectoryItLearnsInMemory() {
        val engines = Engines(::load, null)
        engines.type(Engines.PINYIN, "ni")
        assertEquals("你", engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code).commit)
        engines.close()
    }

    // uat-0.3.1: a heavy user's first open after the upgrade waited 19.5 s on converting the older logs
    @Test
    fun theOlderBuildsLogsAreConvertedOffTheFirstKeyAndMergedInAfter() {
        val dir = olderPinyin()
        val later = ArrayList<Runnable>()
        Engines(::load, dir, warmUps = held(later)).use { engines ->
            engines.type(Engines.PINYIN, "ni")
            // typed on before the conversion: owed, the mark beside the log says so
            assertTrue(File(dir, LearnLog.CONVERTING).exists())
            assertFalse(File(dir, LearnLog.MARKER).exists())
            assertEquals(1, later.size)
            later.single().run()
            // made, but put in place only where typing pauses (review-uat-fixes S7)
            engines.type(Engines.PINYIN, "h")
            assertFalse(File(dir, LearnLog.MARKER).exists())
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            engines.context(Engines.PINYIN, "", started = true)
            assertTrue(File(dir, LearnLog.MARKER).exists())
            assertFalse(File(dir, LearnLog.CONVERTING).exists())
            assertTrue("你好" in LearnLog.replay(File(dir, LearnLog.FILE).readBytes()).mine)
        }
    }

    private fun olderPinyin(): File = folder.newFolder().also { dir ->
        val niHao = UserModel.Entry("你好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
        File(dir, Engines.USER_PINYIN).writeBytes(UserLog.header() + UserLog.sentence(null, listOf(niHao)))
    }

    // the conversion held back, as on a phone it is still being made; the data's warm-ups run at once
    private fun held(later: MutableList<Runnable>) = Executor { r -> if (later.isEmpty() && loaded.contains(Engines.PINYIN_DATA)) later += r else r.run() }

    // review-uat-fixes S3: a copy saved (an export) while the conversion is still being made has
    // what the older build learned: made here first
    @Test
    fun aCopySavedWhileConvertingHasTheOlderBuildsLearning() {
        val dir = olderPinyin()
        Engines(::load, dir, warmUps = held(ArrayList())).use { engines ->
            engines.type(Engines.PINYIN, "ni")
            assertTrue(File(dir, LearnLog.CONVERTING).exists())
            val copy = checkNotNull((engines.learning(null, BackupOp.Copy) as BackupResult).bytes)
            assertTrue("你好" in LearnBackup.read(copy).state.mine)
            assertTrue(File(dir, LearnLog.MARKER).exists())
        }
    }

    // review-uat-fixes S6: the app asked what it gives (its dictionaries, libime's files) on the typing
    // thread, not on the conversion's
    @Test
    fun theConversionAsksTheAppNothingOffTheTypingThread() {
        val dir = olderPinyin()
        val later = ArrayList<Runnable>()
        var converting = false
        var asked = 0
        val additions = { asked += if (converting) 1 else 0; null }
        val legacy = { asked += if (converting) 1 else 0; null }
        Engines(::load, dir, legacy = legacy, additions = additions, warmUps = held(later)).use { engines ->
            engines.type(Engines.PINYIN, "ni")
            converting = true
            later.single().run()
            converting = false
            assertEquals(0, asked)
        }
    }

    // verify-uat-fixes B1: the backups' switch, told at each start as EngineBridge does, makes no
    // conversion on the typing thread; nor 清掉最近 10 分钟 (V2), which reaches the tail alone
    @Test
    fun neitherTheBackupsSwitchNorARecentClearConvertsOnTheTypingThread() {
        val dir = olderPinyin()
        val later = ArrayList<Runnable>()
        Engines(::load, dir, warmUps = { later += it }).use { engines ->
            engines.learning(null, BackupOp.SetAuto(true))
            engines.type(Engines.PINYIN, "ni")
            engines.learning(Engines.PINYIN, LearningOp.ClearRecent(LearningOp.Span.TEN_MINUTES))
            assertTrue(File(dir, LearnLog.CONVERTING).exists())
            assertFalse(File(dir, LearnLog.MARKER).exists())
            // today's reaches what the older build learned: made first
            engines.learning(Engines.PINYIN, LearningOp.ClearRecent(LearningOp.Span.TODAY))
            assertTrue(File(dir, LearnLog.MARKER).exists())
        }
    }

    // verify-uat-fixes V3: the user dictionary page's counts have the older build's words in them
    @Test
    fun theCountsShownWhileConvertingHaveTheOlderBuildsWords() {
        val dir = olderPinyin()
        Engines(::load, dir, warmUps = {}).use { engines ->
            engines.type(Engines.PINYIN, "ni")
            assertEquals(1, engines.userWordCounts()[Engines.UserWord.Kind.LEARNED])
        }
    }

    // uat-0.3.1 recheck: a field reopened for minutes never put it in place; now where typing pauses in
    // the field too, a second after the last key, never in the middle of a composition
    @Test
    fun theOlderBuildsLearningIsPutInPlaceWhereTypingPausesInTheField() {
        val dir = olderPinyin()
        val later = ArrayList<Runnable>()
        val notes = ArrayList<ConversionNote>()
        var now = 0L
        Engines(::load, dir, warmUps = held(later), onConversion = { notes += it }, uptimeMillis = { now }).use { engines ->
            engines.type(Engines.PINYIN, "ni")
            later.single().run()
            assertEquals(listOf(ConversionNote.Started(1), ConversionNote.Made), notes)
            now += Engines.IDLE_MILLIS
            // ni being composed
            assertTrue(engines.paused(idle = true))
            assertTrue(engines.paused())
            assertFalse(File(dir, LearnLog.MARKER).exists())
            engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code)
            now += Engines.IDLE_MILLIS / 2
            assertTrue(engines.paused(idle = true))
            assertFalse(File(dir, LearnLog.MARKER).exists())
            now += Engines.IDLE_MILLIS / 2
            assertFalse(engines.paused(idle = true))
            assertTrue(File(dir, LearnLog.MARKER).exists())
            assertTrue("你好" in LearnLog.replay(File(dir, LearnLog.FILE).readBytes()).mine)
            assertEquals(1, (notes.last() as ConversionNote.Landed).words)
        }
    }

    // the keyboard shown or hidden in the same field: put in place at once, the field not composing
    @Test
    fun theKeyboardShownAgainInTheFieldPutsItInPlace() {
        val dir = olderPinyin()
        val later = ArrayList<Runnable>()
        Engines(::load, dir, warmUps = held(later), uptimeMillis = { 0L }).use { engines ->
            engines.type(Engines.PINYIN, "ni")
            later.single().run()
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            assertFalse(engines.paused())
            assertTrue(File(dir, LearnLog.MARKER).exists())
        }
    }

    // review R11 F2665: libime's files that do not read leave the conversion owed, the record open and learning to disk
    @Test
    fun libimeFilesThatDoNotReadLeaveTheRecordOpenAndTheConversionOwed() {
        val dir = olderPinyin()
        Engines(::load, dir, legacy = { throw IOException("unreadable") }, warmUps = inPlace).use { engines ->
            engines.type(Engines.PINYIN, "ni")
            assertEquals("你", engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code).commit)
            assertTrue(File(dir, LearnLog.CONVERTING).exists())
        }
        assertTrue(File(dir, LearnLog.CONVERTING).exists())
        assertFalse(File(dir, LearnLog.MARKER).exists())
        assertTrue(LearnLog.replay(File(dir, LearnLog.FILE).readBytes()).stats.keys.any { it.text == "你" })
    }

    // review R11 F3100: a conversion that cannot land this run holds no automatic backup back
    @Test
    fun aConversionThatFailedThisRunLeavesTheAutomaticBackupDue() {
        val dir = olderPinyin()
        val install = File(folder.newFolder(), "learn.install")
        Engines(::load, dir, legacy = { throw IOException("unreadable") }, warmUps = inPlace, installFile = install).use { engines ->
            engines.type(Engines.PINYIN, "ni")
            assertEquals("你", engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code).commit)
            assertTrue(File(dir, LearnLog.CONVERTING).exists())
            // written in the background, the key not waiting on the disk
            val until = System.currentTimeMillis() + 5000
            while ((engines.learning(null, BackupOp.Kept) as BackupResult).backups.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10)
            assertEquals(1, (engines.learning(null, BackupOp.Kept) as BackupResult).backups.size)
        }
    }

    // review R11 F3212: the app's files that did not read once are read again before an export, made here
    @Test
    fun aConversionNotBegunIsMadeBeforeACopy() {
        val dir = olderPinyin()
        var reads = 0
        Engines(::load, dir, legacy = { if (reads++ == 0) throw IOException("unreadable") else null }, warmUps = inPlace).use { engines ->
            engines.type(Engines.PINYIN, "ni")
            assertTrue(File(dir, LearnLog.CONVERTING).exists())
            val copy = checkNotNull((engines.learning(null, BackupOp.Copy) as BackupResult).bytes)
            assertTrue("你好" in LearnBackup.read(copy).state.mine)
        }
    }

    // review R11 F2690: a record that did not open learns in memory: no pins-moved marker, which would
    // leave the phrases of the pins its next open converts
    @Test
    fun aRecordThatDidNotOpenWritesNoPinsMovedMarker() {
        val dir = folder.newFolder()
        // a directory where the log goes: the record cannot open
        File(dir, LearnLog.FILE).mkdirs()
        Engines(::load, dir, warmUps = inPlace).use { engines ->
            engines.type(Engines.PINYIN, "ni")
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            engines.context(Engines.PINYIN, "", started = true)
            engines.paused()
        }
        assertFalse(File(dir, Engines.PINS_MOVED).exists())
    }

    // review R11 F2695: a settings backup's stash merged while the conversion is owed: the copy for
    // 退回恢复前 has the older build's learning, so a revert keeps it
    @Test
    fun aRevertAfterASettingsRestoreKeepsTheOlderBuildsLearning() {
        val bytes = Engines(::load, folder.newFolder(), warmUps = inPlace).use { other ->
            other.type(Engines.PINYIN, "ni")
            other.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code)
            checkNotNull((other.learning(null, BackupOp.Copy) as BackupResult).bytes)
        }
        val dir = olderPinyin()
        File(dir, RestoreStash.DIR + "/1000").apply { mkdirs() }.resolve(RestoreStash.BACKUP).writeBytes(bytes)
        Engines(::load, dir, warmUps = held(ArrayList())).use { engines ->
            assertFalse(File(dir, RestoreStash.DIR).exists())
            assertTrue((engines.learning(null, BackupOp.Revert) as BackupResult).ok)
        }
        assertTrue("你好" in LearnLog.replay(File(dir, LearnLog.FILE).readBytes()).mine)
    }

    // review R11 F2748: an older build's pin of a word the user's own alone (妮 is no dictionary
    // character): its phrase taken out at the first key, the user's words in the model by then
    @Test
    fun aPinOfTheUsersOwnWordTakesItsPhraseOutAtTheFirstKey() {
        val dir = folder.newFolder()
        val niHao = UserModel.Entry("妮好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
        File(dir, Engines.USER_PINYIN).writeBytes(UserLog.header() + UserLog.sentence(null, listOf(niHao)))
        val saved = ArrayList<String>()
        val additions = { Engines.Additions("nihao,1=妮好\n", "", { saved += it.all.joinToString(" ") }) { emptySequence() } }
        Engines(::load, dir, additions = additions, warmUps = inPlace).use { engines ->
            engines.type(Engines.PINYIN, "ni")
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            engines.context(Engines.PINYIN, "", started = true)
            engines.paused()
        }
        assertEquals(listOf(""), saved)
        // as an install whose marker went before its phrases were looked at: the phrase there again
        assertTrue(File(dir, Engines.PINS_MOVED).delete())
        saved.clear()
        Engines(::load, dir, additions = additions, warmUps = inPlace).use { engines ->
            engines.type(Engines.PINYIN, "n")
            assertEquals(listOf(""), saved)
        }
        assertTrue(File(dir, Engines.PINS_MOVED).exists())
    }
}
