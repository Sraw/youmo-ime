/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.dirsUnder
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.importedClipboardMarker
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.engine.english.EnglishBlocks
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.backup.Backups
import org.fcitx.fcitx5.android.engine.learn.backup.RestoreStash
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
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
import java.io.InputStream
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
    private fun install(name: String) = dirsUnder(File(folder.root, name))

    // as this build would write it, or as the release [versionCode] did (Versions.calculateVersionCode)
    private fun metadata(packageName: String, versionCode: Long = BuildConfig.VERSION_CODE.toLong()) =
        """{"packageName":"$packageName","versionCode":$versionCode,"versionName":"1","exportTime":0}""".encodeToByteArray()

    private fun zip(packageName: String, vararg entries: Pair<String, String>, versionCode: Long = BuildConfig.VERSION_CODE.toLong()) =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(metadata(packageName, versionCode))
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
        phoneStorage()
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

    // the merged dictionaries' compiled file is made again from their texts: as large as they are, never carried
    @Test
    fun theDictionariesGoWithoutWhatIsCompiledFromThem() {
        val old = install("old")
        val dictionaries = File(old.external, "data/pinyin/dictionaries").apply { mkdirs() }
        File(dictionaries, "frost.txt").writeText("你好 ni'hao 0.0\n")
        File(dictionaries, "merged.ymd").writeText("compiled")
        File(dictionaries, "merged.wubi86.ymc").writeText("indexed")
        File(dictionaries, ".merged.ymd.build/run0").apply { parentFile!!.mkdirs() }.writeText("half")
        val fresh = install("fresh")
        UserDataManager.import(exported(old), fresh).getOrThrow()
        val restored = File(fresh.external, "data/pinyin/dictionaries")
        assertEquals(listOf("frost.txt"), restored.walkTopDown().filter { it.isFile }.map { it.name }.toList())
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

    // of a version before 0.3.1 (upstream's code): not refused for it, having none of the engine's learning (F2 review 8)
    @Test
    fun aBackupFromBeforeTheRenamingBringsItsSettingsUnderTheNewName() {
        val fresh = install("fresh")
        val backup = zip(legacy, "shared_prefs/${UserDataOrigin.preferencesFile(legacy)}" to "<map />", versionCode = UPSTREAM)
        UserDataManager.import(backup, fresh)
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
        File(phone.engine, OLD_MARKER).writeText("the phone's")
        File(phone.engine, WordLists.ADDED).writeText("the phone's")
        File(phone.engine, EnglishBlocks.FILE).writeText("the phone's")
        File(phone.engine, "${Backups.DIR}/auto-1.fxlb").writeText("the phone's")
        File(phone.engine, "wubi.user").writeText("the phone's")
        val backup = zip(
            BuildConfig.APPLICATION_ID,
            RestoreStash.BACKUP to "the backup's learning",
            "engine/${OLD_MARKER}" to "the backup's",
            "engine/${WordLists.ADDED}" to "the backup's",
            "engine/${EnglishBlocks.FILE}" to "the backup's English",
            "engine/wubi.user" to "the backup's table",
        )
        UserDataManager.import(backup, phone).getOrThrow()
        // review-DE-design D-M3: every old log the phone's, a changed one reading to the record as words removed
        for (name in listOf(LearnLog.FILE, OLD_MARKER, WordLists.ADDED, EnglishBlocks.FILE, "${Backups.DIR}/auto-1.fxlb", "wubi.user")) {
            assertEquals(name, "the phone's", File(phone.engine, name).readText())
        }
        val stash = stashes(phone).single()
        assertEquals("the backup's learning", File(stash, RestoreStash.BACKUP).readText())
        assertEquals("the backup's", File(stash, WordLists.ADDED).readText())
        // review H2: English's blocked words merged as pinyin's, not laid over the phone's
        assertEquals("the backup's English", File(stash, EnglishBlocks.FILE).readText())
        assertEquals("the backup's table", File(stash, "wubi.user").readText())
        // D-S5: a second import before the engine merged the first: both left, in the order made
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, RestoreStash.BACKUP to "the second's"), phone).getOrThrow()
        val both = stashes(phone)
        assertEquals(2, both.size)
        assertEquals(stash.name, both[0].name)
        assertEquals("the second's", File(both[1], RestoreStash.BACKUP).readText())
    }

    // PR-10: a setting changed after the backup was made goes back with it, though its default is not in the backup
    @Test
    fun aBackupsSettingsReplaceThePhonesThoseAtTheirDefaultsIncluded() {
        val phone = install("phone")
        val config = phone.config.apply { File(this, "conf").mkdirs() }
        File(config, "conf/androidengine.conf").writeText("PageSize=5\n")
        File(config, "conf/punctuation.conf").writeText("phone's")
        File(phone.external, "data/pinyin/dictionaries").mkdirs()
        File(phone.external, "data/pinyin/dictionaries/mine.txt").writeText("the phone's dictionary")
        val backup = zip(
            BuildConfig.APPLICATION_ID,
            "external/config/config" to "backup's",
            "external/config/conf/punctuation.conf" to "backup's",
        )
        UserDataManager.import(backup, phone).getOrThrow()
        assertFalse(File(config, "conf/androidengine.conf").exists())
        assertEquals("backup's", File(config, "conf/punctuation.conf").readText())
        assertEquals("backup's", File(config, "config").readText())
        // what is not settings is merged as before
        assertEquals("the phone's dictionary", File(phone.external, "data/pinyin/dictionaries/mine.txt").readText())
    }

    private fun entries(zip: InputStream) = ZipInputStream(zip).use { from ->
        generateSequence { from.nextEntry }.filterNot { it.isDirectory }.associate { it.name to from.readBytes().decodeToString() }
    }

    // an install whose config, data and themes are in the app's own files, as on a phone once moved in (A4)
    private fun moved(name: String) = File(folder.root, name).let {
        dirsUnder(it, data = File(it, "files/host-data"), theme = File(it, "files/host-theme"))
    }

    private fun File.put(path: String, text: String) = File(this, path).apply { parentFile!!.mkdirs() }.writeText(text)

    // 051 quick input: the user's entries, their order of use and the built-in tables turned off go
    // with a backup and come back as it has them, what the phone has and the backup not deleted
    @Test
    @Uat("QU-16")
    fun quickInputsEntriesOrderAndSwitchesRoundTripThroughABackup() {
        val phone = moved("phone")
        val mine = "gh https://github.com\naddr \"北京\\n3 号楼\"\n"
        // a backup with settings: one without keeps all of the phone's
        phone.config.put("conf/androidengine.conf", "PageSize=9\n")
        phone.data.put("data/quickphrase.d/mine.mb", mine)
        phone.data.put("data/quickphrase.d/mine.used", "5\tgh\t\"https://github.com\"\n")
        phone.data.put("data/quickphrase.d/latex.mb.disable", "")
        val zip = exported(phone).readBytes()
        assertEquals(mine, entries(zip.inputStream())["external/data/data/quickphrase.d/mine.mb"])
        val fresh = moved("fresh")
        fresh.data.put("data/quickphrase.d/emoji.mb.disable", "the phone's, not the backup's")
        UserDataManager.import(zip.inputStream(), fresh).getOrThrow()
        val dir = File(fresh.data, "data/quickphrase.d")
        assertEquals(mine, File(dir, "mine.mb").readText())
        assertEquals(listOf(QuickPhraseEntry("gh", "https://github.com"), QuickPhraseEntry("addr", "北京\n3 号楼")), QuickPhraseManager.load(dir))
        assertTrue(File(dir, "latex.mb.disable").exists())
        assertFalse(File(dir, "emoji.mb.disable").exists())
    }

    // A4: the config, data and themes in the app's own files go where every backup has had them
    // (external/config, external/data, external/theme: so older builds restore them and this one theirs), and
    // come back into their own directories; the older build's copies left on the external storage are not
    // backed up, nor what is compiled of the dictionaries
    @Test
    fun theConfigDataAndThemesAreBackedUpWhereBackupsHaveThemAndRestoredIntoTheirOwnDirectories() {
        val phone = moved("phone")
        phone.config.put("conf/androidengine.conf", "PageSize=9\n")
        phone.data.put("pinyin/customphrase", "dz,1=北京\n")
        phone.data.put("pinyin/dictionaries/mine.txt.gz", "gz")
        phone.data.put("pinyin/dictionaries/merged.ymd", "compiled")
        phone.data.put("punctuation/punc.mb.zh_CN", ", 、\n")
        phone.theme.put("mine.json", "{}")
        // the move's marker is not backed up: restored, it would have the next start delete an old copy it never moved
        phone.data.put(ConfigHome.MOVED, "moved")
        for (old in listOf("config/profile", "data/pinyin/customphrase", "theme/old.json")) phone.external.put(old, "moved from")
        val zip = exported(phone).readBytes()
        assertEquals(
            mapOf(
                "external/config/conf/androidengine.conf" to "PageSize=9\n",
                "external/data/pinyin/customphrase" to "dz,1=北京\n",
                "external/data/pinyin/dictionaries/mine.txt.gz" to "gz",
                "external/data/punctuation/punc.mb.zh_CN" to ", 、\n",
                "external/theme/mine.json" to "{}",
            ),
            entries(zip.inputStream()).filterKeys { it.startsWith("external/") },
        )
        val fresh = moved("fresh")
        fresh.data.put("pinyin/dictionaries/theirs.txt.gz", "phone's")
        fresh.theme.put("theirs.json", "phone's")
        fresh.data.put("data/quickphrase.d/gone.mb", "not in the backup")
        UserDataManager.import(zip.inputStream(), fresh).getOrThrow()
        assertEquals("PageSize=9\n", File(fresh.config, "conf/androidengine.conf").readText())
        assertEquals("dz,1=北京\n", File(fresh.data, "pinyin/customphrase").readText())
        assertEquals(", 、\n", File(fresh.data, "punctuation/punc.mb.zh_CN").readText())
        // dictionaries and themes merged with the phone's; the text files the user edits follow the backup
        assertEquals("phone's", File(fresh.data, "pinyin/dictionaries/theirs.txt.gz").readText())
        assertEquals("gz", File(fresh.data, "pinyin/dictionaries/mine.txt.gz").readText())
        assertEquals(setOf("mine.json", "theirs.json"), fresh.theme.list()!!.toSet())
        assertFalse(File(fresh.data, "data/quickphrase.d/gone.mb").exists())
        assertFalse(File(fresh.external, "config").exists())
        assertFalse(File(fresh.external, "data").exists())
        assertFalse(File(fresh.external, "theme").exists())
    }

    // 060-render-p3 T8a: a theme 4.0 pack's folder (its theme.xml, its files, its layouts/) goes with the themes and
    // comes back whole, the phone's own packs left be (an older build restoring it lists json files only)
    @Test
    fun aThemePacksFolderRoundTripsThroughABackup() {
        val phone = moved("phone")
        phone.theme.put("mine.json", "{}")
        phone.theme.put("v4/p/theme.xml", "<theme/>")
        phone.theme.put("v4/p/k.png", "png")
        phone.theme.put("v4/p/layouts/qwerty.xml", "<layout/>")
        val zip = exported(phone).readBytes()
        assertEquals(
            mapOf(
                "external/theme/mine.json" to "{}",
                "external/theme/v4/p/theme.xml" to "<theme/>",
                "external/theme/v4/p/k.png" to "png",
                "external/theme/v4/p/layouts/qwerty.xml" to "<layout/>",
            ),
            entries(zip.inputStream()).filterKeys { it.startsWith("external/theme/") },
        )
        val fresh = moved("fresh")
        fresh.theme.put("v4/q/theme.xml", "phone's")
        // the phone's pack of a name the backup has: replaced whole, not merged (a file of it left over)
        fresh.theme.put("v4/p/theme.xml", "phone's p")
        fresh.theme.put("v4/p/old.png", "old")
        UserDataManager.import(zip.inputStream(), fresh).getOrThrow()
        assertEquals("<theme/>", File(fresh.theme, "v4/p/theme.xml").readText())
        assertEquals("png", File(fresh.theme, "v4/p/k.png").readText())
        assertEquals("<layout/>", File(fresh.theme, "v4/p/layouts/qwerty.xml").readText())
        assertFalse(File(fresh.theme, "v4/p/old.png").exists())
        assertEquals("phone's", File(fresh.theme, "v4/q/theme.xml").readText())
    }

    // A4: with the config, data or themes not settled, an export would take a stand-in and a restore write
    // where the next start does not read: both refuse, the user told why, nothing written
    @Test
    @Uat("PR-42")
    fun aBackupAndARestoreAreRefusedWhileNotSettled() {
        val phone = moved("phone")
        phone.data.put("pinyin/customphrase", "dz,1=北京\n")
        val zip = exported(phone).readBytes()
        val away = File(folder.root, "away").let {
            dirsUnder(it, data = File(it, "cache/host-data.unsettled"), theme = File(it, "files/host-theme"), settled = false)
        }
        val notSettled = appContext.getString(R.string.user_data_not_settled)
        assertEquals(notSettled, UserDataManager.export(ByteArrayOutputStream(), 0L, away).exceptionOrNull()?.message)
        assertEquals(notSettled, UserDataManager.import(zip.inputStream(), away).exceptionOrNull()?.message)
        assertFalse(File(folder.root, "away").exists())
    }

    // where an install keeps [path] as a backup's external/ names it: the config in its own directory
    private fun UserDataManager.Dirs.at(path: String) = when {
        path.startsWith("config/") -> File(config, path.removePrefix("config/"))
        path.startsWith("data/") -> File(data, path.removePrefix("data/"))
        else -> File(external, path)
    }

    // F3717/F3719: the text files the user edits follow the backup as the settings do; the collections,
    // the dot-entries (the .libime copies an older build kept) and, from a zip without external/config, all are kept
    @Test
    fun theUsersTextFilesFollowTheBackupItsCollectionsAndDotEntriesStay() {
        val phoneOwn = listOf(
            "data/pinyin/customphrase", "data/data/quickphrase.d/mine.mb", "data/punctuation/punc.mb.zh_CN",
            "config/conf/pinyin.conf",
        )
        fun phone(name: String) = install(name).also { dirs ->
            (phoneOwn + listOf("config/.libime/conf/pinyin.conf", "data/punctuation/.libime/x", "data/pinyin/dictionaries/mine.txt"))
                .forEach { dirs.at(it).apply { parentFile!!.mkdirs() }.writeText("phone's") }
        }
        // each row its own phone and import: a failing import shows in its row, not in every row that reads it
        fun full(name: String) = phone(name).also {
            UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "external/config/config" to "backup's"), it).getOrThrow()
        }
        fun own() = phone("own").also {
            UserDataManager.import(
                zip(BuildConfig.APPLICATION_ID, "external/config/config" to "backup's", "external/data/pinyin/customphrase" to "backup's"),
                it,
            ).getOrThrow()
        }
        fun bare() = phone("bare").also {
            UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "engine/x" to "x"), it).getOrThrow()
        }
        assertRows(
            "a backup without them leaves no phone-added phrases, quick phrases, punctuation or settings" to {
                val dirs = full("full")
                phoneOwn.forEach { assertFalse(it, dirs.at(it).exists()) }
            },
            "dot-entries are left alone" to {
                val dirs = full("dots")
                assertTrue(dirs.at("config/.libime/conf/pinyin.conf").exists())
                assertTrue(dirs.at("data/punctuation/.libime/x").exists())
            },
            "the backup's own replaces the phone's" to {
                assertEquals("backup's", File(own().external, "data/pinyin/customphrase").readText())
            },
            "dictionaries are merged" to { assertTrue(File(full("dicts").external, "data/pinyin/dictionaries/mine.txt").exists()) },
            "a zip without external/config keeps them all" to {
                val dirs = bare()
                phoneOwn.forEach { assertTrue(it, dirs.at(it).exists()) }
            },
        )
    }

    private fun stashes(phone: UserDataManager.Dirs) =
        File(phone.engine, RestoreStash.DIR).listFiles().orEmpty().sortedBy { it.name.toLong() }

    // a zip of an earlier build: its learning record, and an old pinyin log beside it, left as the phone's
    @Test
    fun anOlderBackupsLearningFilesAreLeftToMerge() {
        val phone = install("phone")
        phone.engine.mkdirs()
        File(phone.engine, OLD_PINYIN).writeText("the phone's")
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "engine/${LearnLog.FILE}" to "a record", "engine/${OLD_PINYIN}" to "a log"), phone).getOrThrow()
        val stash = stashes(phone).single()
        assertEquals("a record", File(stash, LearnLog.FILE).readText())
        assertEquals("a log", File(stash, OLD_PINYIN).readText())
        assertEquals("the phone's", File(phone.engine, OLD_PINYIN).readText())
        assertFalse(File(phone.engine, LearnLog.FILE).exists())
        // a backup of no learning leaves nothing to merge
        File(phone.engine, RestoreStash.DIR).deleteRecursively()
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "engine/notes" to "notes"), phone).getOrThrow()
        assertFalse(File(phone.engine, RestoreStash.DIR).exists())
    }

    // a zip of 0.3.0 or before, its learning in the old logs alone: refused before anything is copied, by the
    // version that made it, with old logs or none (F2 review 8)
    @Test
    fun aBackupOf030OrBeforeIsRefusedLeavingThePhoneAsItWas() {
        val phone = install("phone")
        phone.config.mkdirs()
        File(phone.config, "config").writeText("the phone's")
        val logs = zip(BuildConfig.APPLICATION_ID, "external/config/config" to "the backup's", "engine/$OLD_PINYIN" to "a log", "engine/wubi.user" to "a log", versionCode = V030)
        val none = zip(BuildConfig.APPLICATION_ID, "external/config/config" to "the backup's", versionCode = V030)
        for (old in listOf(logs, none)) assertTrue(UserDataManager.import(old, phone).isFailure)
        assertEquals("the phone's", File(phone.config, "config").readText())
        assertFalse(File(phone.engine, RestoreStash.DIR).exists())
        // 0.3.1 on: a backup of the learning beside them, which is read
        val v031 = zip(BuildConfig.APPLICATION_ID, RestoreStash.BACKUP to "a backup", "engine/$OLD_PINYIN" to "a log", versionCode = V031)
        assertTrue(UserDataManager.import(v031, phone).isSuccess)
    }

    // which zips are too old: this app's by the version that made it (0.3.1's lowest code the first
    // taken), one from before the renaming by its files (old logs with neither a backup nor a record)
    @Test
    fun aBackupIsTooOldByItsVersionOrBeforeTheRenamingByItsFiles() {
        val prefs = "shared_prefs/${UserDataOrigin.preferencesFile(legacy)}" to "<map />"
        assertRows(
            "this app's of the code before 0.3.1's lowest is refused" to {
                assertTrue(UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "engine/$OLD_PINYIN" to "a log", versionCode = FIRST_WITH_THE_RECORD - 1), install("2129")).isFailure)
            },
            "this app's of 0.3.1's lowest code is taken" to {
                assertTrue(UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "engine/$OLD_PINYIN" to "a log", versionCode = FIRST_WITH_THE_RECORD), install("2130")).isSuccess)
            },
            "this app's of now with old logs alone is taken (its learning not copied), not judged by its files" to {
                assertTrue(UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "engine/$OLD_PINYIN" to "a log"), install("now")).isSuccess)
            },
            "one from before the renaming, old logs alone, is refused" to {
                assertTrue(UserDataManager.import(zip(legacy, prefs, "engine/$OLD_PINYIN" to "a log", versionCode = UPSTREAM), install("legacy-logs")).isFailure)
            },
            "one from before the renaming with a backup of the learning beside its old logs is taken" to {
                val zip = zip(legacy, prefs, RestoreStash.BACKUP to "a backup", "engine/$OLD_PINYIN" to "a log", versionCode = UPSTREAM)
                assertTrue(UserDataManager.import(zip, install("legacy-backup")).isSuccess)
            },
            "one from before the renaming with a learning record beside its old logs is taken" to {
                val zip = zip(legacy, prefs, "engine/${LearnLog.FILE}" to "record", "engine/$OLD_PINYIN" to "a log", versionCode = UPSTREAM)
                assertTrue(UserDataManager.import(zip, install("legacy-record")).isSuccess)
            },
        )
    }

    // the learning goes as a backup of it, not as the files it is made of, nor the phone's own backups
    @Test
    fun anExportCarriesTheLearningAsABackup() {
        val phone = install("phone")
        File(phone.engine, Backups.DIR).mkdirs()
        File(phone.engine, LearnLog.FILE).writeText("record")
        File(phone.engine, OLD_PINYIN).writeText("old log")
        // a table's 0.4 log and its mark: the learning too (F2 review 1)
        File(phone.engine, OLD_TABLE).writeText("old log")
        File(phone.engine, "$OLD_TABLE.told").writeText("")
        File(phone.engine, Engines.TABLE_LOGS_READ).writeText("")
        File(phone.engine, WordLists.ADDED).writeText("words")
        File(phone.engine, "${Backups.DIR}/auto-1.fxlb").writeText("backup")
        val names = ByteArrayOutputStream().also { UserDataManager.export(it, 0L, phone, "learning".toByteArray()).getOrThrow() }.let { bytes ->
            ZipInputStream(bytes.toByteArray().inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }
        }
        assertTrue(RestoreStash.BACKUP in names)
        assertTrue("engine/${WordLists.ADDED}" in names)
        val gone = listOf(LearnLog.FILE, OLD_PINYIN, OLD_TABLE, "$OLD_TABLE.told", Engines.TABLE_LOGS_READ, "${Backups.DIR}/auto-1.fxlb").map { "engine/$it" }
        for (name in gone) assertFalse(name, name in names)
    }

    // F2 review B1: the tables' old logs read into the record, an export with no learning given (the record bare) leaves
    // them out too, and its mark goes with the record into the stash: a restore reads none of them again
    @Test
    fun theOldLogsReadAreLeftOutOfABareExportAndItsMarkGoesToTheStash() {
        val phone = install("phone")
        phone.engine.mkdirs()
        File(phone.engine, LearnLog.FILE).writeText("record")
        File(phone.engine, OLD_TABLE).writeText("old log")
        File(phone.engine, "$OLD_TABLE.told").writeText("")
        val unread = ByteArrayOutputStream().also { UserDataManager.export(it, 0L, phone).getOrThrow() }.names()
        assertTrue("engine/$OLD_TABLE" in unread)
        File(phone.engine, Engines.TABLE_LOGS_READ).writeText("")
        val bytes = exported(phone)
        val read = ByteArrayOutputStream().also { UserDataManager.export(it, 0L, phone).getOrThrow() }.names()
        for (name in listOf(OLD_TABLE, "$OLD_TABLE.told")) assertFalse(name, "engine/$name" in read)
        assertTrue("engine/${LearnLog.FILE}" in read && "engine/${Engines.TABLE_LOGS_READ}" in read)
        val fresh = install("fresh")
        UserDataManager.import(bytes, fresh).getOrThrow()
        val stash = stashes(fresh).single()
        assertEquals("record", File(stash, LearnLog.FILE).readText())
        assertTrue(File(stash, Engines.TABLE_LOGS_READ).exists())
        // the phone's own mark: none, as this phone has read no log
        assertFalse(File(fresh.engine, Engines.TABLE_LOGS_READ).exists())
    }

    private fun ByteArrayOutputStream.names() =
        ZipInputStream(toByteArray().inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }

    private companion object {
        // 0.4's names of files beside the record: still a phone's
        const val OLD_MARKER = "learn.converted"
        const val OLD_PINYIN = "pinyin.user"
        const val OLD_TABLE = "wubi.user"

        // 0.3.0's and 0.3.1's version codes, of arm64-v8a (Versions.calculateVersionCode)
        const val V030 = 2122L
        const val V031 = 2132L
        // UserDataManager's FIRST_WITH_THE_RECORD: 0.3.1's lowest ABI's code
        const val FIRST_WITH_THE_RECORD = 2130L
        // upstream's, which the builds before the renaming carried
        const val UPSTREAM = 114L
    }

    // a backup restored unpacked till the disk was full (a zip bomb, or a phone nearly full): stopped
    // once what is unpacked would pass the room there, told so, before the disk fills
    // (import unpacks into a temporary folder before it copies anything in, so a restore stopped here changes nothing)
    @Test
    @Uat("PR-50")
    fun aBackupBiggerThanTheRoomLeftStopsAsItIsUnpacked() {
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("big"))
                z.write(ByteArray(1 shl 20))
                z.closeEntry()
            }
        }.toByteArray()
        val dir = folder.newFolder("unpacked")
        val e = runCatching { UserDataManager.extractWithinRoom(zip.inputStream(), dir, room = 4096) }.exceptionOrNull()
        assertEquals(appContext.getString(R.string.exception_user_data_no_room), e?.message)
        assertTrue("stopped near the room, not unpacked whole", dir.walk().filter { it.isFile }.sumOf { it.length() } <= 4096)
        // the same with room enough: unpacked
        val roomy = folder.newFolder("roomy")
        assertEquals(listOf("big"), UserDataManager.extractWithinRoom(zip.inputStream(), roomy, room = 2L shl 20).map { it.name })
    }
}
