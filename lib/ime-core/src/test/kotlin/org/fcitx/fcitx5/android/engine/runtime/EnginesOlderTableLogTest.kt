/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.host.VirtualHostClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The tables' logs of 0.4 (upgrade/v0.4.0-converted's wubi.user) read into the record once, at the
 * first open (F2 review): 0.3.0's pick vbvn 好女, which 0.4's table offered and its conversion made a
 * count of alone. Not read again, so what is forgotten or cleared after stays so; a backup made
 * after has it, and a 0.4 zip restored brings it from its log. On the disk: the fixture's tree is
 * copied as it is and the restore's stash is made by its paths there, as on the phone.
 *
 * contract: a 0.4.0 install's table log, which mutation cannot see.
 */
class EnginesOlderTableLogTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    private val clock = VirtualHostClock(epochMillis = 1_793_335_172_220L + 3_600_000L)
    private val errors = ArrayList<String>()

    private fun installed(): File = folder.newFolder("engine").also { File(FIXTURE, "engine").copyRecursively(it) }

    private fun open(dir: File) = Engines(
        ::load, JvmFiles(dir), onError = { errors += it.toString() }, warmUps = inPlace,
        clock = clock,
    ).also { it.settings = wubiShown }

    private fun Engines.wubi(keys: String): List<String> = type(WUBI, keys).candidates.also { onEvent(WUBI, EngineEvent.RESET, 0) }

    private fun tableWords(dir: File) = LearnLog.replay(File(dir, LearnLog.FILE).readBytes()).tableWords.keys.toList()

    @Test
    fun readOnceAndNotAgainAfterAForget() {
        val dir = installed()
        open(dir).use { assertEquals(listOf("好女"), it.wubi("vbvn")) }
        assertTrue(File(dir, Engines.TABLE_LOGS_READ).exists())
        val rows = tableWords(dir)
        assertTrue(Triple(WUBI, "vbvn", "好女") in rows)
        // a second open: nothing more
        open(dir).use { assertEquals(listOf("好女"), it.wubi("vbvn")) }
        assertEquals(rows, tableWords(dir))
        open(dir).use { engines ->
            val shown = engines.type(WUBI, "vbvn").candidates
            engines.onEvent(WUBI, EngineEvent.FORGET, shown.indexOf("好女"))
        }
        // the log still has it, read no more
        assertTrue(File(dir, "wubi.user").exists())
        open(dir).use { assertEquals(emptyList<String>(), it.wubi("vbvn")) }
        assertEquals(emptyList<String>(), errors)
    }

    // F2 review B4: the rows' write refused by the disk (EnginesRecordFaultTest's way), as the table loads for the read:
    // not marked, read again at the next open, and marked then
    @Test
    fun aReadWhoseWriteIsRefusedIsDoneAgainAtTheNextOpen() {
        val dir = installed()
        lateinit var engines: Engines
        var armed = true
        val refusing = { path: String ->
            if (armed && path == "${InputMethodCatalog.TABLE_DIR}/wbx.data") {
                armed = false
                checkNotNull(engines.learnLog().store).refuseNextWrite()
            }
            load(path)
        }
        // no warm-ups: the table loads first for the read, the record already open
        engines = Engines(refusing, JvmFiles(dir), onError = { errors += it.toString() }, warmUps = { }, clock = clock)
        engines.use { it.type(InputMethodCatalog.PINYIN, "ni") }
        assertFalse(armed)
        assertFalse(File(dir, Engines.TABLE_LOGS_READ).exists())
        assertTrue(errors.any { "refused" in it })
        assertFalse(Triple(WUBI, "vbvn", "好女") in tableWords(dir))
        open(dir).use { assertEquals(listOf("好女"), it.wubi("vbvn")) }
        assertTrue(File(dir, Engines.TABLE_LOGS_READ).exists())
        assertTrue(Triple(WUBI, "vbvn", "好女") in tableWords(dir))
    }

    @Test
    fun aClearAfterLeavesNone() {
        val dir = installed()
        open(dir).use { engines ->
            assertEquals(listOf("好女"), engines.wubi("vbvn"))
            assertTrue(engines.learning(null, LearningOp.ClearLearned(all = true)) is LearningResult.Cleared)
        }
        assertFalse(File(dir, "wubi.user").exists())
        open(dir).use { engines ->
            assertEquals(emptyList<String>(), engines.wubi("vbvn"))
            assertEquals(emptyList<String>(), engines.wubi("vbwq"))
        }
    }

    @Test
    fun aBackupMadeAfterHasThem() {
        val bytes = open(installed()).use { engines ->
            engines.wubi("vbvn")
            checkNotNull((engines.learning(null, BackupOp.Copy) as BackupResult).bytes)
        }
        open(folder.newFolder("fresh")).use { engines ->
            assertTrue((engines.learning(null, BackupOp.Restore(bytes)) as BackupResult).ok)
            assertEquals(listOf("好女"), engines.wubi("vbvn"))
            assertEquals(setOf("好你", "好妮"), engines.wubi("vbwq").toSet())
        }
    }

    // F2 review B1: a zip of the bare record (exported with no learning given) of a build that read the logs, after
    // 好女 was forgotten: the stash's logs, if the zip had them, are not read again, its mark beside them
    @Test
    fun aBareRecordOfABuildThatReadTheLogsIsNotReadAgain() {
        val dir = installed()
        open(dir).use { engines ->
            val shown = engines.type(WUBI, "vbvn").candidates
            engines.onEvent(WUBI, EngineEvent.FORGET, shown.indexOf("好女"))
        }
        val fresh = folder.newFolder("fresh")
        val stash = File(fresh, RestoreStash.DIR + "/1000").apply { mkdirs() }
        for (name in listOf(LearnLog.FILE, "wubi.user", Engines.TABLE_LOGS_READ)) File(dir, name).copyTo(File(stash, name))
        open(fresh).use { assertEquals(emptyList<String>(), it.wubi("vbvn")) }
        assertEquals(emptyList<String>(), errors)
    }

    // F2 review B3: a stash whose table does not load (none here for 仓颉) is kept aside, not merged without its phrases
    @Test
    fun aStashWhoseTableDoesNotLoadIsKeptAside() {
        val fresh = folder.newFolder("fresh")
        val stash = File(fresh, RestoreStash.DIR + "/1000").apply { mkdirs() }
        File(FIXTURE, "engine/${LearnLog.FILE}").copyTo(File(stash, LearnLog.FILE))
        File(FIXTURE, "engine/wubi.user").copyTo(File(stash, "cangjie.user"))
        open(fresh).use { assertEquals(emptyList<String>(), it.wubi("vbvn")) }
        assertTrue(errors.isNotEmpty())
        assertFalse(File(fresh, RestoreStash.DIR).exists())
        assertTrue(File(fresh, RestoreStash.UNREADABLE).exists())
    }

    // a 0.4 zip restored: its record and its table's log, left by the app in a stash
    @Test
    fun a04ZipRestoredBringsThemFromItsLog() {
        val fresh = folder.newFolder("fresh")
        val stash = File(fresh, RestoreStash.DIR + "/1000").apply { mkdirs() }
        for (name in listOf(LearnLog.FILE, "wubi.user")) File(FIXTURE, "engine/$name").copyTo(File(stash, name))
        open(fresh).use { engines ->
            assertEquals(listOf("好女"), engines.wubi("vbvn"))
            assertEquals(setOf("好你", "好妮"), engines.wubi("vbwq").toSet())
        }
        assertEquals(emptyList<String>(), errors)
    }

    // a log the disk does not read (here not readable): not taken as read, so read at the next open that can
    // (review F4890: a disk error was taken as read, the phrases lost). Skipped under root (a CI container),
    // which reads a file whatever its bits
    @Test
    fun aLogTheDiskDoesNotReadIsReadAtTheNextOpen() {
        val dir = installed()
        val log = File(dir, "wubi.user")
        assumeTrue(log.setReadable(false) && !log.canRead())
        open(dir).use { assertEquals(emptyList<String>(), it.wubi("vbvn")) }
        assertFalse(File(dir, Engines.TABLE_LOGS_READ).exists())
        assertTrue(errors.any { "older logs not read" in it })
        assertTrue(log.setReadable(true))
        open(dir).use { assertEquals(listOf("好女"), it.wubi("vbvn")) }
        assertTrue(File(dir, Engines.TABLE_LOGS_READ).exists())
    }

    private companion object {
        const val FIXTURE = "src/test/resources/upgrade/v0.4.0-converted"
    }
}
