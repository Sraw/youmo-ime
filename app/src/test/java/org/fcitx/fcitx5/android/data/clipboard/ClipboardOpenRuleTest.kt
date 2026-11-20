/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteTableLockedException
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.Kept
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

// the open and clear rules, which need Android's exceptions and a cache dir but no app (unlike ClipboardManagerReopenTest)
@RunWith(RobolectricTestRunner::class)
class ClipboardOpenRuleTest {

    @Test
    fun anOpenThatSucceedsIsAllThatRuns() {
        listOf(false, true).forEach { assertEquals(listOf("open"), fallBack(databaseFile(), imported = it)) }
    }

    // files kept for the next start and this run's clips in memory, clearing keeps them too: never started over, nor after an import
    @Test
    fun whatMayPassByTheNextStartKeepsTheFiles() {
        val file = databaseFile()
        // a file there that cannot be opened now: EMFILE, a permission or SELinux denial, a journal on a full disk
        val mayPass = listOf(
            SQLiteFullException(), SQLiteDiskIOException(), SQLiteDatabaseLockedException(), SQLiteTableLockedException(),
            SQLiteOutOfMemoryException(), SQLiteAccessPermException(), SQLiteReadOnlyDatabaseException(),
            SQLiteCantOpenDatabaseException()
        )
        listOf(false, true).forEach { imported ->
            mayPass.forEach {
                assertEquals("$it, imported $imported", listOf("open", "in memory, may pass"), fallBack(file, imported, it))
            }
        }
    }

    // the user's own at a start, which a later build may open: never started over but for a directory, which holds no clips
    @Test
    fun aStartKeepsTheFilesWhateverFailsButADirectory() {
        val context = RuntimeEnvironment.getApplication()
        val file = databaseFile()
        val missingMigration = IllegalStateException("A migration from 4 to 5 was required but not found")
        // what will not pass: the file for clearing the clipboard to delete
        listOf(
            SQLiteDatabaseCorruptException(),
            missingMigration,
            IllegalStateException("Room cannot verify the data integrity"),
            SQLiteException("table clipboard has no column named sensitive"),
            RuntimeException("unforeseen")
        ).forEach { assertEquals(it.toString(), listOf("open", "in memory"), fallBack(file, imported = false, it)) }
        assertEquals(
            listOf("open", "in memory"),
            fallBack(File(context.cacheDir, "absent"), imported = false, SQLiteCantOpenDatabaseException())
        )
        val startedOver = listOf("open", "start over", "open")
        assertEquals(startedOver, fallBack(context.cacheDir, imported = false, missingMigration))
        val journalled = File(context.cacheDir, "journalled").apply { writeText("") }
        File(journalled.path + "-wal").mkdirs()
        assertEquals(startedOver, fallBack(journalled, imported = false, SQLiteFullException()))
    }

    // after an import the file is the backup's: what will not pass is started over
    @Test
    fun onlyWhatNeverOpensIsStartedOver() {
        val context = RuntimeEnvironment.getApplication()
        val file = databaseFile()
        val startedOver = listOf("open", "start over", "open")
        // corrupt, rejected by Room (identity hash, missing migration), or tables uncreatable
        listOf(
            SQLiteDatabaseCorruptException(),
            IllegalStateException("Room cannot verify the data integrity"),
            SQLiteException("table clipboard has no column named sensitive")
        ).forEach { assertEquals(it.toString(), startedOver, fallBack(file, imported = true, it)) }
        // nothing there, which the open could not create: nothing to keep
        assertEquals(startedOver, fallBack(File(context.cacheDir, "absent"), imported = true, SQLiteCantOpenDatabaseException()))
        // a directory, there or at a journal, whatever opening it throws
        assertEquals(startedOver, fallBack(context.cacheDir, imported = true, SQLiteDiskIOException()))
        val journalled = File(context.cacheDir, "journalled").apply { writeText("") }
        File(journalled.path + "-wal").mkdirs()
        assertEquals(startedOver, fallBack(journalled, imported = true, SQLiteFullException()))
    }

    @Test
    fun whatDoesNotOpenEvenStartedOverGoesInMemory() {
        assertEquals(
            listOf("open", "start over", "open", "in memory, may pass"),
            fallBack(databaseFile(), imported = true, SQLiteDatabaseCorruptException(), SQLiteFullException())
        )
    }

    // what will not pass is for clearing to delete, whatever a start-over did
    @Test
    fun whatWillNotPassIsKeptUnopenable() {
        val flags = listOf(false, true)
        flags.forEach { startedOver ->
            flags.forEach { heldClips ->
                flags.forEach { leftClips ->
                    assertEquals(
                        "started over $startedOver, held $heldClips, left $leftClips",
                        Kept.UNOPENABLE,
                        keptInMemory(mayPass = false, startedOver, heldClips, leftClips)
                    )
                }
            }
        }
    }

    // beside a file holding clips with no start-over, clips come back once it opens and clearing waits; a start-over that
    // could not delete a clip-holding file keeps it unopenable for clearing to retry
    @Test
    fun whatMayPassKeepsOnlyAFileThatStillHoldsClips() {
        // started over, held clips before, left clips after: what is kept
        listOf(
            Triple(false, false, false) to Kept.NOTHING,
            Triple(false, true, false) to Kept.MAY_OPEN,
            // deleted, left one holding none, or none to delete
            Triple(true, false, false) to Kept.NOTHING,
            Triple(true, true, false) to Kept.NOTHING,
            // its directory not writable, say
            Triple(true, false, true) to Kept.UNOPENABLE,
            Triple(true, true, true) to Kept.UNOPENABLE
        ).forEach { (flags, kept) ->
            val (startedOver, heldClips, leftClips) = flags
            assertEquals(flags.toString(), kept, keptInMemory(mayPass = true, startedOver, heldClips, leftClips))
        }
    }

    // a clear failing after its DELETE (Room's VACUUM on a full disk): done if no row is left; not if rows remain or the count fails
    @Test
    fun aClearingThatFailsIsDoneOnlyIfNoRowIsLeft() = runTest {
        // the clear failing, the count (null: fails): whether cleared, the steps run
        listOf(
            (false to false) to (true to listOf("clear")),
            (true to true) to (true to listOf("clear", "is empty", "told")),
            (true to false) to (false to listOf("clear", "is empty")),
            (true to null) to (false to listOf("clear", "is empty"))
        ).forEach { (case, expected) ->
            assertEquals(case.toString(), expected, clearing(clearFails = case.first, empty = case.second))
        }
    }

    // clearedTables with a clear that throws if [clearFails], then a count finding [empty] (null: throws)
    private suspend fun clearing(clearFails: Boolean, empty: Boolean?): Pair<Boolean, List<String>> {
        val done = mutableListOf<String>()
        val cleared = clearedTables(
            clear = {
                done += "clear"
                if (clearFails) throw SQLiteFullException()
            },
            isEmpty = {
                done += "is empty"
                empty ?: throw SQLiteDiskIOException()
            },
            onClearedThoughFailed = { done += "told" }
        )
        return cleared to done
    }

    // openOrFallBack at [path] after a start or an import, with an open throwing [thrown] in turn, then opening
    private fun fallBack(path: File, imported: Boolean, vararg thrown: Exception): List<String> {
        val done = mutableListOf<String>()
        val failures = thrown.iterator()
        openOrFallBack(
            path,
            imported,
            open = {
                done += "open"
                if (failures.hasNext()) throw failures.next()
            },
            startOver = { done += "start over" },
            openInMemory = { done += if (it) "in memory, may pass" else "in memory" }
        )
        return done
    }

    // as a start finds it
    private fun databaseFile() = File(RuntimeEnvironment.getApplication().cacheDir, "clbdb").apply { writeText("") }
}
