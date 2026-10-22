/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.EngineMigration
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.importedClipboardMarker
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.TableSettings
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.backup.Backups
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A backup takes the user's data to another install, or from the app before the renaming, whole
 * or not at all, and without what an app marked sensitive on the clipboard (or without the
 * clipboard's history, when its copy cannot be cleaned). Its settings that have no UI are put
 * back to their defaults at the start after its import.
 */
@RunWith(RobolectricTestRunner::class)
// the real application: the manager's directories come from appContext
@Config(application = FcitxApplication::class)
class UserDataManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    // an install's directories, made as they are needed: not the app's, which it writes as the test runs
    private fun install(name: String) = File(folder.root, name).let {
        UserDataManager.Dirs(
            File(it, "shared_prefs"), File(it, "databases"), File(it, "external"), File(it, "recently_used"), File(it, "engine")
        )
    }

    private fun metadata(packageName: String) =
        """{"packageName":"$packageName","versionCode":1,"versionName":"1","exportTime":0}""".encodeToByteArray()

    private fun zip(packageName: String, vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write(metadata(packageName))
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
            }
        }
    }.toByteArray().inputStream()

    // named as the app's: the package Robolectric runs it as need not be the one the import checks for
    private fun exported(dirs: UserDataManager.Dirs) = ByteArrayOutputStream().also { bytes ->
        val export = ByteArrayOutputStream().also { UserDataManager.export(it, 0L, dirs).getOrThrow() }
        ZipInputStream(export.toByteArray().inputStream()).use { from ->
            ZipOutputStream(bytes).use { zip ->
                generateSequence { from.nextEntry }.forEach {
                    zip.putNextEntry(ZipEntry(it.name))
                    if (it.name == "metadata.json") zip.write(metadata(BuildConfig.APPLICATION_ID)) else from.copyTo(zip)
                }
            }
        }
    }.toByteArray().inputStream()

    private val prefs = UserDataOrigin.preferencesFile(BuildConfig.APPLICATION_ID)

    // this build type's id before the renaming
    private val legacy = LEGACY_APPLICATION_ID_ROOT + BuildConfig.APPLICATION_ID.removePrefix(BuildConfig.APPLICATION_ID_ROOT)

    @Test
    fun anImportLeavesTheNextStartToResetTheSettingsWithoutAUi() {
        val marker = importedUserDataMarker(appContext)
        marker.delete()
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID)).getOrThrow()
        assertTrue(marker.exists())
    }

    @Test
    fun whatIsExportedIsImportedIntoAFreshInstallAsItWas() {
        val old = install("old")
        File(old.sharedPrefs.apply { mkdirs() }, prefs).writeText("<map />")
        File(old.engine, "tables").mkdirs()
        File(old.engine, "pinyin.log").writeText("learned")
        File(old.engine, "tables/mine.txt").writeText("table")
        // kept aside by its store, which could not read it: the only copy of what it held
        File(old.engine, "pinyin.log.unreadable").writeText("damaged")
        // half written by a compaction, which renames it over the log once done
        File(old.engine, "pinyin.log.compacting").writeText("half")
        val fresh = install("fresh")
        UserDataManager.import(exported(old), fresh).getOrThrow()
        assertEquals("<map />", File(fresh.sharedPrefs, prefs).readText())
        assertEquals("learned", File(fresh.engine, "pinyin.log").readText())
        assertEquals("table", File(fresh.engine, "tables/mine.txt").readText())
        assertEquals("damaged", File(fresh.engine, "pinyin.log.unreadable").readText())
        assertFalse(File(fresh.engine, "pinyin.log.compacting").exists())
        assertEquals(listOf("engine"), File(folder.root, "fresh").list()!!.filter { it.startsWith("engine") })
    }

    @Test
    fun whatAnAppMarkedSensitiveOnTheClipboardIsLeftOutOfTheBackup() = runTest {
        val old = install("old").apply { dataBases.mkdirs() }
        Room.databaseBuilder(appContext, ClipboardDatabase::class.java, File(old.dataBases, "clbdb").path).build().run {
            clipboardDao().insert(ClipboardEntry(text = "kept"))
            clipboardDao().insert(ClipboardEntry(text = "hunter2", sensitive = true))
            close()
        }
        val fresh = install("fresh")
        // of the database the import replaces: the backup brings none
        File(fresh.dataBases.apply { mkdirs() }, "clbdb-wal").writeText("stale")
        UserDataManager.import(exported(old), fresh).getOrThrow()
        assertFalse(File(fresh.dataBases, "clbdb-wal").exists())
        // out of the file, not only out of the table
        assertTrue(fresh.dataBases.walkTopDown().filter { it.isFile }.none { "hunter2" in it.readText(Charsets.ISO_8859_1) })
        val db = Room.databaseBuilder(appContext, ClipboardDatabase::class.java, File(fresh.dataBases, "clbdb").path).build()
        val texts = mutableListOf<String>()
        db.openHelper.readableDatabase.query("SELECT text FROM ${ClipboardEntry.TABLE_NAME}").use {
            while (it.moveToNext()) texts += it.getString(0)
        }
        db.close()
        assertEquals(listOf("kept"), texts)
    }

    @Test
    fun aClipboardCopyThatCannotBeCleanedIsLeftOutOfTheBackupAlone() {
        val old = install("old").apply { dataBases.mkdirs() }
        File(old.sharedPrefs.apply { mkdirs() }, prefs).writeText("<map />")
        // as a copy torn by the clipboard writing to it reads: SQLite cannot clean it
        File(old.dataBases, "clbdb").writeText("not a database")
        val backup = ByteArrayOutputStream().also { UserDataManager.export(it, 0L, old).getOrThrow() }
        val names = ZipInputStream(backup.toByteArray().inputStream()).use { from -> generateSequence { from.nextEntry }.map { it.name }.toList() }
        assertTrue(names.toString(), "shared_prefs/$prefs" in names)
        assertTrue(names.toString(), names.none { it.startsWith("databases/clbdb") })
    }

    @Test
    fun anImportDropsTheJournalsOfWhatItReplacesButKeepsTheOnesTheBackupBrings() {
        val phone = install("phone").apply { dataBases.mkdirs() }
        File(phone.dataBases, "clbdb-wal").writeText("stale")
        File(phone.dataBases, "clbdb-shm").writeText("stale")
        // as a backup from before the clipboard's database was cleaned has it: the files as they were
        val backup = zip(BuildConfig.APPLICATION_ID, "databases/clbdb" to "db", "databases/clbdb-wal" to "its log")
        UserDataManager.import(backup, phone).getOrThrow()
        assertEquals("its log", File(phone.dataBases, "clbdb-wal").readText())
        assertFalse(File(phone.dataBases, "clbdb-shm").exists())
    }

    @Test
    fun anImportThatFailsCopyingInTheDatabasesLeavesTheirJournals() {
        val phone = install("phone").apply { dataBases.mkdirs() }
        File(phone.dataBases, "clbdb-wal").writeText("open")
        // where the backup's clbdb goes, a directory the copy cannot delete to put it there
        val stuck = File(phone.dataBases, "clbdb/stuck").apply { mkdirs() }
        File(stuck, "x").writeText("")
        stuck.setWritable(false)
        try {
            // as root, or where a directory's mode does not keep what is in it, nothing fails
            assumeFalse(File(stuck, "x").delete())
            assertTrue(UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "databases/clbdb" to "db"), phone).isFailure)
            assertEquals("open", File(phone.dataBases, "clbdb-wal").readText())
            // what is at clbdb may be the user's still: the clipboard is not told it is the backup's
            assertFalse(importedClipboardMarker(phone.dataBases).exists())
        } finally {
            // else the folder cannot be cleaned up
            stuck.setWritable(true)
        }
    }

    // the clipboard's file the backup's: what Room rejects of it the next start, or the reopen after a failure from
    // here, may start over, where the user's own is kept
    @Test
    fun anImportTellsTheClipboardOnlyOfADatabaseItPutIn() {
        val phone = install("phone")
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "databases/clbdb" to "db"), phone).getOrThrow()
        assertTrue(importedClipboardMarker(phone.dataBases).isFile)
        // none in the backup: the file the user's still
        val other = install("other")
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "databases/other" to "db"), other).getOrThrow()
        assertFalse(importedClipboardMarker(other.dataBases).exists())
    }

    // a marker a backup brings, hand-built say: only an import's own copy marks the file the backup's, where the next
    // open would else start over a user's file that a start had kept. One an earlier import left marks that backup's
    // file, which a start kept for what may pass: an import that brings no clipboard database leaves both
    @Test
    fun onlyAnImportsOwnCopyMarksTheClipboardsFile() {
        val phone = install("phone")
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "databases/clbdb.imported" to ""), phone).getOrThrow()
        assertFalse(importedClipboardMarker(phone.dataBases).exists())
        val earlier = install("earlier")
        val clbdb = File(earlier.dataBases.apply { mkdirs() }, "clbdb").apply { writeText("the earlier backup's") }
        val kept = importedClipboardMarker(earlier.dataBases).apply { createNewFile() }
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "databases/other" to "db"), earlier).getOrThrow()
        assertEquals("the earlier backup's", clbdb.readText())
        assertTrue(kept.isFile)
    }

    // a wrong zip, say: nothing copied, the user's own file left theirs, for the reopen to keep as a start does
    @Test
    fun anImportThatFailsBeforeTheCopyTellsTheClipboardNothing() {
        val phone = install("phone")
        val backup = zip("another.app", "databases/clbdb" to "db")
        assertTrue(UserDataManager.import(backup, phone).isFailure)
        assertFalse(importedClipboardMarker(phone.dataBases).exists())
    }

    @Test
    fun aBackupFromBeforeTheRenamingBringsItsSettingsUnderTheNewName() {
        val fresh = install("fresh")
        UserDataManager.import(zip(legacy, "shared_prefs/${UserDataOrigin.preferencesFile(legacy)}" to "<map />"), fresh)
            .getOrThrow()
        assertEquals(listOf(prefs), fresh.sharedPrefs.list()!!.toList())
        assertEquals("<map />", File(fresh.sharedPrefs, prefs).readText())
    }

    @Test
    fun aBackupWhoseSettingsCannotTakeTheNewNameIsNotImported() {
        val marker = importedUserDataMarker(appContext).apply { delete() }
        val backup = zip(
            legacy,
            "shared_prefs/${UserDataOrigin.preferencesFile(legacy)}" to "<map />",
            // a directory where they would go: the rename fails
            "shared_prefs/$prefs/x" to "",
            "engine/pinyin.log" to "learned",
        )
        assertTrue(UserDataManager.import(backup, install("fresh")).isFailure)
        assertFalse(marker.exists())
        assertFalse(File(folder.root, "fresh").exists())
    }

    @Test
    fun anImportKeepsTheEngineFilesTheBackupHasNot() {
        val phone = install("phone")
        File(phone.engine, "cache").mkdirs()
        File(phone.engine, "pinyin.log").writeText("old")
        File(phone.engine, "cache/table.bin").writeText("built")
        File(phone.engine, "lists").mkdir()
        File(phone.engine, "lists/a").writeText("a list")
        val backup = zip(BuildConfig.APPLICATION_ID, "engine/pinyin.log" to "learned", "engine/lists" to "a file")
        UserDataManager.import(backup, phone).getOrThrow()
        assertEquals("learned", File(phone.engine, "pinyin.log").readText())
        assertEquals("built", File(phone.engine, "cache/table.bin").readText())
        // where the backup has a file, its file
        assertEquals("a file", File(phone.engine, "lists").readText())
        assertEquals(listOf("engine"), File(folder.root, "phone").list()!!.filter { it.startsWith("engine") })
    }

    // U-100: the phone's learning is merged with the backup's, not replaced: the backup's left for the engine's next start
    @Test
    fun aBackupsLearningIsLeftToMergeThePhonesKept() {
        val phone = install("phone")
        File(phone.engine, Backups.DIR).mkdirs()
        File(phone.engine, LearnLog.FILE).writeText("the phone's")
        File(phone.engine, LearnLog.MARKER).writeText("the phone's")
        File(phone.engine, WordLists.ADDED).writeText("the phone's")
        File(phone.engine, "${Backups.DIR}/auto-1.fxlb").writeText("the phone's")
        File(phone.engine, "wubi.user").writeText("the phone's")
        val backup = zip(
            BuildConfig.APPLICATION_ID,
            RestoreStash.BACKUP to "the backup's learning",
            "engine/${LearnLog.MARKER}" to "the backup's",
            "engine/${WordLists.ADDED}" to "the backup's",
            "engine/wubi.user" to "the backup's table",
        )
        UserDataManager.import(backup, phone).getOrThrow()
        // review-DE-design D-M3: every old log the phone's, a changed one reading to the record as words removed
        for (name in listOf(LearnLog.FILE, LearnLog.MARKER, WordLists.ADDED, "${Backups.DIR}/auto-1.fxlb", "wubi.user")) {
            assertEquals(name, "the phone's", File(phone.engine, name).readText())
        }
        val stash = stashes(phone).single()
        assertEquals("the backup's learning", File(stash, RestoreStash.BACKUP).readText())
        assertEquals("the backup's", File(stash, WordLists.ADDED).readText())
        assertEquals("the backup's table", File(stash, "wubi.user").readText())
        // D-S5: a second import before the engine merged the first: both left, in the order made
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, RestoreStash.BACKUP to "the second's"), phone).getOrThrow()
        val both = stashes(phone)
        assertEquals(2, both.size)
        assertEquals(stash.name, both[0].name)
        assertEquals("the second's", File(both[1], RestoreStash.BACKUP).readText())
    }

    // R11 F2745: a zip from before tables.order-kept keeps 调频 on for its tables typed with, as
    // such a build's first start would; the phone's marker would otherwise skip it
    @Test
    fun aBackupFromBeforeTheOrderMarkerKeepsItsTypedTablesOrderingByUse() {
        val im = Engines.TABLES.entries.first { it.value.options.orderByUse }.key
        val phone = install("phone")
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "engine/${Engines.userTable(im)}" to "a log"), phone).getOrThrow()
        val conf = File(phone.external, "config/conf/androidengine.conf").readText()
        assertTrue(conf, "${TableSettings.ORDER_POLICY}=${TableSettings.ORDER_BY_USE}" in conf)
        // a newer backup, with the marker: its settings as they are
        val other = install("other")
        val newer = zip(BuildConfig.APPLICATION_ID, "engine/${Engines.userTable(im)}" to "a log", "engine/${EngineMigration.ORDER_KEPT}" to "")
        UserDataManager.import(newer, other).getOrThrow()
        assertFalse(File(other.external, "config/conf/androidengine.conf").exists())
    }

    // R11 F3123: a zip without the addon's settings over a phone whose fcitx wrote them in full,
    // 调频 off as its default: the backup's typed table orders by use again, one it did not type stays off
    @Test
    fun aBackupWithoutTheAddonsSettingsTurnsItsTypedTablesOrderingOnOverThePhones() {
        val ordered = Engines.TABLES.entries.filter { it.value.options.orderByUse }
        val (im, typed) = ordered[0]
        val untyped = ordered.first { it.value.group != typed.group }.value
        val phone = install("phone")
        val settings = File(phone.external, "config/conf/androidengine.conf").apply { parentFile!!.mkdirs() }
        settings.writeText("[${typed.group}]\n${TableSettings.ORDER_POLICY}=No\n\n[${untyped.group}]\n${TableSettings.ORDER_POLICY}=No\n")
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "engine/${Engines.userTable(im)}" to "a log"), phone).getOrThrow()
        val conf = settings.readText()
        assertTrue(conf, "[${typed.group}]\n${TableSettings.ORDER_POLICY}=${TableSettings.ORDER_BY_USE}" in conf)
        assertTrue(conf, "[${untyped.group}]\n${TableSettings.ORDER_POLICY}=No" in conf)
    }

    private fun stashes(phone: UserDataManager.Dirs) =
        File(phone.engine, RestoreStash.DIR).listFiles().orEmpty().sortedBy { it.name.toLong() }

    // a zip of an earlier build: its learning record; older still, its pinyin log, to convert
    @Test
    fun anOlderBackupsLearningFilesAreLeftToMerge() {
        val phone = install("phone")
        phone.engine.mkdirs()
        File(phone.engine, Engines.USER_PINYIN).writeText("the phone's")
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "engine/${LearnLog.FILE}" to "a record", "engine/${Engines.USER_PINYIN}" to "a log"), phone).getOrThrow()
        val stash = stashes(phone).single()
        assertEquals("a record", File(stash, LearnLog.FILE).readText())
        assertEquals("a log", File(stash, Engines.USER_PINYIN).readText())
        assertEquals("the phone's", File(phone.engine, Engines.USER_PINYIN).readText())
        assertFalse(File(phone.engine, LearnLog.FILE).exists())
        // a backup of no learning leaves nothing to merge
        File(phone.engine, RestoreStash.DIR).deleteRecursively()
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "engine/notes" to "notes"), phone).getOrThrow()
        assertFalse(File(phone.engine, RestoreStash.DIR).exists())
    }

    // the learning goes as a backup of it, not as the files it is made of, nor the phone's own backups
    @Test
    fun anExportCarriesTheLearningAsABackup() {
        val phone = install("phone")
        File(phone.engine, Backups.DIR).mkdirs()
        File(phone.engine, LearnLog.FILE).writeText("record")
        File(phone.engine, Engines.USER_PINYIN).writeText("old log")
        File(phone.engine, WordLists.ADDED).writeText("words")
        File(phone.engine, "${Backups.DIR}/auto-1.fxlb").writeText("backup")
        val names = ByteArrayOutputStream().also { UserDataManager.export(it, 0L, phone, "learning".toByteArray()).getOrThrow() }.let { bytes ->
            ZipInputStream(bytes.toByteArray().inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }
        }
        assertTrue(RestoreStash.BACKUP in names)
        assertTrue("engine/${WordLists.ADDED}" in names)
        for (gone in listOf("engine/${LearnLog.FILE}", "engine/${Engines.USER_PINYIN}", "engine/${Backups.DIR}/auto-1.fxlb")) assertFalse(gone, gone in names)
    }
}
