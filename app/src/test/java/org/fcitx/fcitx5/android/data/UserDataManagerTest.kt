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
import org.fcitx.fcitx5.android.core.backupZip
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

/** Whole or not at all, without clips an app marked sensitive; UI-less settings reset at the start after an import. */
@RunWith(RobolectricTestRunner::class)
// the real application: the manager's directories come from appContext
@Config(application = FcitxApplication::class)
class UserDataManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    // not the app's own, which it writes as the test runs
    private fun install(name: String) = dirsUnder(File(folder.root, name))

    // as this build would write it, or as release [versionCode] did
    private fun metadata(packageName: String, versionCode: Long = BuildConfig.VERSION_CODE.toLong()) =
        """{"packageName":"$packageName","versionCode":$versionCode,"versionName":"1","exportTime":0}""".encodeToByteArray()

    private fun zip(packageName: String, vararg entries: Pair<String, String>, versionCode: Long = BuildConfig.VERSION_CODE.toLong()) =
        backupZip(*entries, versionCode = versionCode, packageName = packageName)

    // the package Robolectric runs as need not be the one the import checks for
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

    // rebuilt from the texts, never carried
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
        // the backup brings none
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
        // a copy torn by the clipboard writing: SQLite cannot clean it
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
        val backup = zip(BuildConfig.APPLICATION_ID, "databases/clbdb" to "db", "databases/clbdb-wal" to "its log")
        UserDataManager.import(backup, phone).getOrThrow()
        assertEquals("its log", File(phone.dataBases, "clbdb-wal").readText())
        assertFalse(File(phone.dataBases, "clbdb-shm").exists())
    }

    @Test
    fun anImportThatFailsCopyingInTheDatabasesLeavesTheirJournals() {
        val phone = install("phone").apply { dataBases.mkdirs() }
        File(phone.dataBases, "clbdb-wal").writeText("open")
        // a directory the copy cannot delete where the backup's clbdb goes
        val stuck = File(phone.dataBases, "clbdb/stuck").apply { mkdirs() }
        File(stuck, "x").writeText("")
        stuck.setWritable(false)
        try {
            // as root, nothing fails
            assumeFalse(File(stuck, "x").delete())
            assertTrue(UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "databases/clbdb" to "db"), phone).isFailure)
            assertEquals("open", File(phone.dataBases, "clbdb-wal").readText())
            // what is at clbdb may still be the user's: not marked as the backup's
            assertFalse(importedClipboardMarker(phone.dataBases).exists())
        } finally {
            // else the folder cannot be cleaned up
            stuck.setWritable(true)
        }
    }

    // a later open may start over what Room rejects of the backup's; the user's own is kept
    @Test
    fun anImportTellsTheClipboardOnlyOfADatabaseItPutIn() {
        val phone = install("phone")
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "databases/clbdb" to "db"), phone).getOrThrow()
        assertTrue(importedClipboardMarker(phone.dataBases).isFile)
        val other = install("other")
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "databases/other" to "db"), other).getOrThrow()
        assertFalse(importedClipboardMarker(other.dataBases).exists())
    }

    // only an import's own copy marks the file the backup's, not a marker the backup brings
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

    // a wrong zip: nothing copied, the user's file kept for the reopen as a start does
    @Test
    fun anImportThatFailsBeforeTheCopyTellsTheClipboardNothing() {
        val phone = install("phone")
        val backup = zip("another.app", "databases/clbdb" to "db")
        assertTrue(UserDataManager.import(backup, phone).isFailure)
        assertFalse(importedClipboardMarker(phone.dataBases).exists())
    }

    // before 0.3.1 (upstream's code): not refused, having none of the engine's learning
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
        assertEquals("a file", File(phone.engine, "lists").readText())
        assertEquals(listOf("engine"), File(folder.root, "phone").list()!!.filter { it.startsWith("engine") })
    }

    // the phone's learning is merged with the backup's, not replaced
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
        // the record's copies kept aside, by any number, are the phone's as the record is
        File(phone.engine, "${LearnLog.FILE}.unreadable.1").writeText("the phone's")
        val backup = zip(
            BuildConfig.APPLICATION_ID,
            RestoreStash.BACKUP to "the backup's learning",
            "engine/${LearnLog.FILE}.unreadable.1" to "the backup's",
            "engine/${OLD_MARKER}" to "the backup's",
            "engine/${WordLists.ADDED}" to "the backup's",
            "engine/${EnglishBlocks.FILE}" to "the backup's English",
            "engine/wubi.user" to "the backup's table",
        )
        UserDataManager.import(backup, phone).getOrThrow()
        // every old log the phone's: a changed one would read as words removed
        for (name in listOf(LearnLog.FILE, OLD_MARKER, WordLists.ADDED, EnglishBlocks.FILE, "${Backups.DIR}/auto-1.fxlb", "wubi.user", "${LearnLog.FILE}.unreadable.1")) {
            assertEquals(name, "the phone's", File(phone.engine, name).readText())
        }
        val stash = stashes(phone).single()
        assertEquals("the backup's learning", File(stash, RestoreStash.BACKUP).readText())
        assertEquals("the backup's", File(stash, WordLists.ADDED).readText())
        // English's blocked words merged as pinyin's
        assertEquals("the backup's English", File(stash, EnglishBlocks.FILE).readText())
        assertEquals("the backup's table", File(stash, "wubi.user").readText())
        // a second import before the engine merged the first: both left, in order
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, RestoreStash.BACKUP to "the second's"), phone).getOrThrow()
        val both = stashes(phone)
        assertEquals(2, both.size)
        assertEquals(stash.name, both[0].name)
        assertEquals("the second's", File(both[1], RestoreStash.BACKUP).readText())
    }

    // a setting changed after the backup goes back with it, though its default is not in the backup
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
        assertEquals("the phone's dictionary", File(phone.external, "data/pinyin/dictionaries/mine.txt").readText())
    }

    private fun entries(zip: InputStream) = ZipInputStream(zip).use { from ->
        generateSequence { from.nextEntry }.filterNot { it.isDirectory }.associate { it.name to from.readBytes().decodeToString() }
    }

    private fun moved(name: String) = File(folder.root, name).let {
        dirsUnder(it, data = File(it, "files/host-data"), theme = File(it, "files/host-theme"))
    }

    private fun File.put(path: String, text: String) = File(this, path).apply { parentFile!!.mkdirs() }.writeText(text)

    // entries, their order of use and the disabled built-in tables come back as the backup has them
    @Test
    @Uat("QU-16")
    fun quickInputsEntriesOrderAndSwitchesRoundTripThroughABackup() {
        val phone = moved("phone")
        val mine = "gh https://github.com\naddr \"北京\\n3 号楼\"\n"
        // a backup without settings keeps all of the phone's
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

    // a backup from before notes and folders restores files as they are, read converted; a current one keeps its folders
    @Test
    @Uat("QU-27")
    fun quickInputOfABackupOfBeforeIsReadConvertedAndOneOfNowKeepsItsFolders() {
        val phone = moved("phone")
        val old = "#wz百度 www.baidu.com\n地址 北京\n"
        phone.config.put("conf/androidengine.conf", "PageSize=9\n")
        phone.data.put("data/quickphrase.d/mine.mb", old)
        phone.data.put("data/quickphrase.d/mine.used", "5\t地址\t北京\n")
        val fresh = moved("fresh")
        UserDataManager.import(exported(phone), fresh).getOrThrow()
        val dir = File(fresh.data, "data/quickphrase.d")
        assertEquals(listOf(QuickPhraseEntry("", "北京", "地址"), QuickPhraseEntry("#wz", "www.baidu.com", "百度")), QuickPhraseManager.load(dir))
        QuickPhraseManager.addFolder("网址", dir = dir)
        QuickPhraseManager.put(QuickPhraseEntry("#wz", "www.baidu.com", "百度"), QuickPhraseEntry("#wz", "www.baidu.com", "百度", "网址"), now = 1, dir = dir)
        val again = moved("again")
        UserDataManager.import(exported(fresh), again).getOrThrow()
        assertEquals(listOf("网址"), QuickPhraseManager.loadAll(File(again.data, "data/quickphrase.d")).folders)
    }

    // stored where every backup has had them so older builds interoperate, restored into their own directories
    @Test
    @Uat("WD-55")
    fun theConfigDataAndThemesAreBackedUpWhereBackupsHaveThemAndRestoredIntoTheirOwnDirectories() {
        val phone = moved("phone")
        phone.config.put("conf/androidengine.conf", "PageSize=9\n")
        phone.data.put("pinyin/customphrase", "dz,1=北京\n")
        phone.data.put("pinyin/commonwords.txt", "米字旁[mi'zi'pang]\t米\n")
        phone.data.put("pinyin/rarechars.txt", "# youmo-rare-chars 1\non\t1\n")
        phone.data.put("pinyin/dictionaries/mine.txt.gz", "gz")
        phone.data.put("pinyin/dictionaries/merged.ymd", "compiled")
        phone.data.put("punctuation/punc.mb.zh_CN", ", 、\n")
        phone.theme.put("mine.json", "{}")
        // restored, the move's marker would have the next start delete an old copy it never moved
        phone.data.put(ConfigHome.MOVED, "moved")
        for (old in listOf("config/profile", "data/pinyin/customphrase", "theme/old.json")) phone.external.put(old, "moved from")
        val zip = exported(phone).readBytes()
        assertEquals(
            mapOf(
                "external/config/conf/androidengine.conf" to "PageSize=9\n",
                "external/data/pinyin/customphrase" to "dz,1=北京\n",
                "external/data/pinyin/commonwords.txt" to "米字旁[mi'zi'pang]\t米\n",
                "external/data/pinyin/rarechars.txt" to "# youmo-rare-chars 1\non\t1\n",
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
        assertEquals("米字旁[mi'zi'pang]\t米\n", File(fresh.data, "pinyin/commonwords.txt").readText())
        assertEquals("# youmo-rare-chars 1\non\t1\n", File(fresh.data, "pinyin/rarechars.txt").readText())
        // backed up but not restored: the built-in table is everyone's since 0.6.1
        assertFalse(File(fresh.data, "punctuation/punc.mb.zh_CN").exists())
        assertEquals("phone's", File(fresh.data, "pinyin/dictionaries/theirs.txt.gz").readText())
        assertEquals("gz", File(fresh.data, "pinyin/dictionaries/mine.txt.gz").readText())
        assertEquals(setOf("mine.json", "theirs.json"), fresh.theme.list()!!.toSet())
        assertFalse(File(fresh.data, "data/quickphrase.d/gone.mb").exists())
        assertFalse(File(fresh.external, "config").exists())
        assertFalse(File(fresh.external, "data").exists())
        assertFalse(File(fresh.external, "theme").exists())
    }

    // comes back whole, the phone's own packs left be
    @Test
    fun aThemePacksFolderRoundTripsThroughABackup() {
        val phone = moved("phone")
        phone.theme.put("mine.json", "{}")
        phone.theme.put("v4/p/theme.xml", "<theme/>")
        phone.theme.put("v4/p/k.png", "png")
        phone.theme.put("v4/p/layouts/qwerty.xml", "<layout/>")
        // a stamp in the backup is not taken for the app's check
        phone.theme.put("v4/p/.checked", "stamp")
        val zip = exported(phone).readBytes()
        assertEquals(
            mapOf(
                "external/theme/mine.json" to "{}",
                "external/theme/v4/p/theme.xml" to "<theme/>",
                "external/theme/v4/p/k.png" to "png",
                "external/theme/v4/p/layouts/qwerty.xml" to "<layout/>",
                "external/theme/v4/p/.checked" to "stamp",
            ),
            entries(zip.inputStream()).filterKeys { it.startsWith("external/theme/") },
        )
        val fresh = moved("fresh")
        fresh.theme.put("v4/q/theme.xml", "phone's")
        fresh.theme.put("v4/q/.checked", "phone's stamp")
        // replaced whole, not merged
        fresh.theme.put("v4/p/theme.xml", "phone's p")
        fresh.theme.put("v4/p/old.png", "old")
        UserDataManager.import(zip.inputStream(), fresh).getOrThrow()
        assertEquals("<theme/>", File(fresh.theme, "v4/p/theme.xml").readText())
        assertEquals("png", File(fresh.theme, "v4/p/k.png").readText())
        assertEquals("<layout/>", File(fresh.theme, "v4/p/layouts/qwerty.xml").readText())
        assertFalse(File(fresh.theme, "v4/p/old.png").exists())
        assertEquals("phone's", File(fresh.theme, "v4/q/theme.xml").readText())
        assertFalse(File(fresh.theme, "v4/p/.checked").exists())
        assertFalse(File(fresh.theme, "v4/q/.checked").exists())
    }

    // an export would take a stand-in and a restore write where the next start does not read: both refuse
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

    private fun UserDataManager.Dirs.at(path: String) = when {
        path.startsWith("config/") -> File(config, path.removePrefix("config/"))
        path.startsWith("data/") -> File(data, path.removePrefix("data/"))
        else -> File(external, path)
    }

    // the user-edited text files follow the backup; the collections, dot-entries and, without external/config, all are kept
    @Test
    @Uat("WD-55")
    fun theUsersTextFilesFollowTheBackupItsCollectionsAndDotEntriesStay() {
        val phoneOwn = listOf(
            "data/pinyin/customphrase", "data/pinyin/commonwords.txt", "data/pinyin/rarechars.txt", "data/data/quickphrase.d/mine.mb",
            "data/punctuation/punc.mb.zh_CN", "config/conf/pinyin.conf",
        )
        fun phone(name: String) = install(name).also { dirs ->
            (phoneOwn + listOf("config/.libime/conf/pinyin.conf", "data/punctuation/.libime/x", "data/pinyin/dictionaries/mine.txt"))
                .forEach { dirs.at(it).apply { parentFile!!.mkdirs() }.writeText("phone's") }
        }
        // each row its own phone and import
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
            "a zip without external/config keeps them all but the punctuation table (no restore keeps one since 0.6.1)" to {
                val dirs = bare()
                val table = "data/punctuation/punc.mb.zh_CN"
                (phoneOwn - table).forEach { assertTrue(it, dirs.at(it).exists()) }
                assertFalse(dirs.at(table).exists())
            },
        )
    }

    private fun stashes(phone: UserDataManager.Dirs) =
        File(phone.engine, RestoreStash.DIR).listFiles().orEmpty().sortedBy { it.name.toLong() }

    // an earlier build's zip: its record and an old pinyin log beside it are left as the phone's
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
        File(phone.engine, RestoreStash.DIR).deleteRecursively()
        UserDataManager.import(zip(BuildConfig.APPLICATION_ID, "engine/notes" to "notes"), phone).getOrThrow()
        assertFalse(File(phone.engine, RestoreStash.DIR).exists())
    }

    // learning in old logs alone (0.3.0 or before): refused before anything is copied
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
        val v031 = zip(BuildConfig.APPLICATION_ID, RestoreStash.BACKUP to "a backup", "engine/$OLD_PINYIN" to "a log", versionCode = V031)
        assertTrue(UserDataManager.import(v031, phone).isSuccess)
    }

    // this app's by version, a pre-rename one by its files
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

    @Test
    fun anExportCarriesTheLearningAsABackup() {
        val phone = install("phone")
        File(phone.engine, Backups.DIR).mkdirs()
        File(phone.engine, LearnLog.FILE).writeText("record")
        File(phone.engine, OLD_PINYIN).writeText("old log")
        // a table's 0.4 log and its mark are learning too
        File(phone.engine, OLD_TABLE).writeText("old log")
        File(phone.engine, "$OLD_TABLE.told").writeText("")
        File(phone.engine, Engines.TABLE_LOGS_READ).writeText("")
        File(phone.engine, WordLists.ADDED).writeText("words")
        File(phone.engine, "${Backups.DIR}/auto-1.fxlb").writeText("backup")
        File(phone.engine, "${LearnLog.FILE}.unreadable").writeText("kept aside")
        File(phone.engine, "${LearnLog.FILE}.unreadable.2").writeText("kept aside")
        val names = ByteArrayOutputStream().also { UserDataManager.export(it, 0L, phone, "learning".toByteArray()).getOrThrow() }.let { bytes ->
            ZipInputStream(bytes.toByteArray().inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }
        }
        assertTrue(RestoreStash.BACKUP in names)
        assertTrue("engine/${WordLists.ADDED}" in names)
        val gone = listOf(
            LearnLog.FILE, OLD_PINYIN, OLD_TABLE, "$OLD_TABLE.told", Engines.TABLE_LOGS_READ, "${Backups.DIR}/auto-1.fxlb",
            // every copy kept aside, not only the first
            "${LearnLog.FILE}.unreadable", "${LearnLog.FILE}.unreadable.2",
        ).map { "engine/$it" }
        for (name in gone) assertFalse(name, name in names)
    }

    // an export without learning leaves the read logs out too, and their mark goes into the stash
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
        assertFalse(File(fresh.engine, Engines.TABLE_LOGS_READ).exists())
    }

    private fun ByteArrayOutputStream.names() =
        ZipInputStream(toByteArray().inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }

    private companion object {
        const val OLD_MARKER = "learn.converted"
        const val OLD_PINYIN = "pinyin.user"
        const val OLD_TABLE = "wubi.user"

        // arm64-v8a's codes
        const val V030 = 2122L
        const val V031 = 2132L
        const val FIRST_WITH_THE_RECORD = 2130L
        const val UPSTREAM = 114L
    }

    // a zip bomb or nearly full phone: stopped before the disk fills; unpacked to a temp folder first, so nothing changes
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
        val roomy = folder.newFolder("roomy")
        assertEquals(listOf("big"), UserDataManager.extractWithinRoom(zip.inputStream(), roomy, room = 2L shl 20).map { it.name })
    }

    // empty directories fill no disk: past the entry cap it stops; exactly the cap is unpacked
    @Test
    @Uat("PR-52")
    fun aBackupOfTooManyEntriesStopsAsItIsUnpacked() {
        fun dirs(n: Int) = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> repeat(n) { z.putNextEntry(ZipEntry("d$it/")); z.closeEntry() } }
        }.toByteArray()
        val e = runCatching { UserDataManager.extractWithinRoom(dirs(11).inputStream(), folder.newFolder("many"), maxEntries = 10) }.exceptionOrNull()
        assertEquals(appContext.getString(R.string.exception_user_data_too_many_entries), e?.message)
        UserDataManager.extractWithinRoom(dirs(10).inputStream(), folder.newFolder("enough"), maxEntries = 10)
    }

    // the picker offers every file, so this content check keeps non-backups off the phone; the picker side is checked by hand
    @Test
    @Uat("PR-52")
    fun aFileWithoutTheMetadataIsRefusedAndNothingChanges() {
        val otherZip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("external/data/pinyin/customphrase"))
                zip.write("dh,1=电话".encodeToByteArray())
                zip.putNextEntry(ZipEntry("engine/pinyin.log"))
                zip.write("another's".encodeToByteArray())
            }
        }.toByteArray()
        // each row its own phone: a file wrongly put in by one is not found by the next
        fun refused(phoneNamed: String, file: ByteArray, why: Int = R.string.exception_user_data_metadata) {
            val phone = install(phoneNamed)
            File(phone.sharedPrefs.apply { mkdirs() }, prefs).writeText("<map />")
            File(phone.engine.apply { mkdirs() }, "pinyin.log").writeText("learned")
            val e = UserDataManager.import(file.inputStream(), phone).exceptionOrNull()
            assertEquals(appContext.getString(why), e?.message)
            assertEquals("<map />", File(phone.sharedPrefs, prefs).readText())
            assertEquals("learned", File(phone.engine, "pinyin.log").readText())
            assertEquals(listOf("pinyin.log"), phone.engine.list()!!.toList())
        }
        assertRows(
            "a zip that is no backup (no metadata.json), with files where a backup has them: none of them put in" to { refused("phone-given-another-zip", otherZip) },
            "a file that is no zip at all (a photo picked by mistake): refused on its first bytes as no backup (review 061-small S2)" to {
                refused("phone-given-no-zip", "not a zip, nor a backup".encodeToByteArray(), R.string.exception_user_data_not_a_backup)
            },
        )
    }
}
