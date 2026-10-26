/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.Manifest
import android.content.pm.PackageManager
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.input.voice.VoiceFeature
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * uat/15-privacy-backup.md and 13-words.md, the files' half: what 备份全部 puts in the zip and what
 * 从备份恢复 does with it, into an install's directories made in a temporary folder (as
 * UserDataManagerTest does), then the engine's start compiling the dictionaries restored (the
 * merged file what typing reads); the permissions the app asks for, from its merged manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class BackupUatTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun install(name: String) = File(folder.root, name).let {
        UserDataManager.Dirs(File(it, "shared_prefs"), File(it, "databases"), File(it, "external"), File(it, "recently_used"), File(it, "engine"))
    }

    private fun dictionaries(dirs: UserDataManager.Dirs) = File(dirs.external, "data/pinyin/dictionaries").apply { mkdirs() }

    private val dictWords = File("../uat/fixtures/dict-words.txt").readLines().joinToString("") { line ->
        val parts = line.split(" ")
        "${parts[0]} ${parts.drop(1).joinToString("'")} 0.0\n"
    }

    // 备份全部 as the page saves it, the metadata the app's: the package Robolectric runs as need not be the one the import checks for
    private fun backup(dirs: UserDataManager.Dirs, entries: (String) -> String? = { it }): ByteArray {
        val export = ByteArrayOutputStream().also { UserDataManager.export(it, 0L, dirs).getOrThrow() }
        return ByteArrayOutputStream().also { bytes ->
            ZipInputStream(export.toByteArray().inputStream()).use { from ->
                ZipOutputStream(bytes).use { zip ->
                    generateSequence { from.nextEntry }.forEach { e ->
                        val content = if (e.name == "metadata.json") {
                            """{"packageName":"${BuildConfig.APPLICATION_ID}","versionCode":1,"versionName":"1","exportTime":0}""".encodeToByteArray()
                        } else {
                            from.readBytes()
                        }
                        val name = entries(e.name) ?: return@forEach
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(if (name != e.name && e.name.endsWith(SourceNames.GZ)) gunzip(content) else content)
                    }
                }
            }
        }.toByteArray()
    }

    private fun gunzip(bytes: ByteArray) = java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes()

    private fun names(zip: ByteArray) = ZipInputStream(zip.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }

    // the app started after the restore: the engine's start compiles what the backup brought
    private fun started(dir: File) {
        MergedDictionary.reset()
        MergedDictionary.refresh(dir)?.get(1, TimeUnit.MINUTES)
    }

    private fun typed(dir: File, text: String) = DictionaryFile.open(File(dir, MergedDictionary.FILE)).hasText(text)

    private fun files(dir: File) = dir.list()!!.filter { !it.startsWith(".") && !MergedDictionary.isCompiled(File(dir, it)) }.sorted()

    @Before
    fun fresh() {
        MergedDictionary.reset()
        MergedDictionary.made = {}
        MergedDictionary.base = { null }
    }

    @After
    fun done() = MergedDictionary.reset()

    @Test
    @Uat("PR-01")
    fun noNetwork() {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_PERMISSIONS)
        val asked = info.requestedPermissions.orEmpty().toList()
        assertFalse("$asked", Manifest.permission.INTERNET in asked)
        // vibration only; the voice build adds RECORD_AUDIO from its flavor's manifest
        val voice = if (VoiceFeature.AVAILABLE) listOf(Manifest.permission.RECORD_AUDIO) else emptyList()
        assertEquals((listOf(Manifest.permission.VIBRATE) + voice).sorted(), asked.sorted())
    }

    @Test
    @Uat("PR-02")
    fun noStorageContactsOrOtherPermissions() {
        val asked = appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()
        val none = listOf(
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA,
        ) + if (VoiceFeature.AVAILABLE) emptyList() else listOf(Manifest.permission.RECORD_AUDIO)
        assertTrue("$asked", none.none { it in asked })
    }

    @Test
    @Uat("PR-10")
    fun aBackupRestoredReplacesSettingsPhrasesAndPunctuation() {
        val old = install("old")
        val config = File(old.external, "config/conf").apply { mkdirs() }
        File(config, "androidengine.conf").writeText("PageSize=7\n")
        val data = File(old.external, "data/pinyin").apply { mkdirs() }
        File(data, "customphrase").writeText("dz,1=北京市海淀区\n")
        File(old.external, "data/punctuation").mkdirs()
        File(old.external, "data/punctuation/punc.mb.zh_CN").writeText(", ，\n")
        val dir = dictionaries(old)
        SourceNames.writeGzip(File(dir, "dict-words.txt.gz")) { it.write(dictWords.toByteArray()) }
        MergedDictionary.refreshNow(dir)
        File(dir, "dict-words.txt.gz.tmp").writeText("half")
        val zip = backup(old)
        // the dictionary as it is kept, never the file made of it nor a half-written one
        val entries = names(zip)
        assertTrue("$entries", "external/data/pinyin/dictionaries/dict-words.txt.gz" in entries)
        assertTrue("$entries", entries.none { it.endsWith(MergedDictionary.FILE) || it.endsWith(".tmp") })
        // a phone with a phrase, a page size and a punctuation of its own since
        val phone = install("phone")
        File(phone.external, "config/conf").mkdirs()
        File(phone.external, "config/conf/androidengine.conf").writeText("PageSize=5\n")
        File(phone.external, "data/pinyin").mkdirs()
        File(phone.external, "data/pinyin/customphrase").writeText("yx,1=test@example.com\n")
        File(phone.external, "data/punctuation").mkdirs()
        File(phone.external, "data/punctuation/punc.mb.zh_CN").writeText(", 、\n")
        UserDataManager.import(zip.inputStream(), phone).getOrThrow()
        assertEquals("PageSize=7\n", File(phone.external, "config/conf/androidengine.conf").readText())
        assertEquals("dz,1=北京市海淀区\n", File(phone.external, "data/pinyin/customphrase").readText())
        assertEquals(", ，\n", File(phone.external, "data/punctuation/punc.mb.zh_CN").readText())
        val restored = dictionaries(phone)
        started(restored)
        assertEquals(listOf("dict-words.txt.gz"), files(restored))
        assertTrue(typed(restored, "氪喵糖铺"))
    }

    @Test
    @Uat("PR-12")
    fun aSensitiveClipboardEntryIsNotBackedUp() = runTest {
        val old = install("old").apply { dataBases.mkdirs() }
        Room.databaseBuilder(appContext, ClipboardDatabase::class.java, File(old.dataBases, "clbdb").path).build().run {
            clipboardDao().insert(ClipboardEntry(text = "mima1234", sensitive = true))
            clipboardDao().insert(ClipboardEntry(text = "公园"))
            close()
        }
        val fresh = install("fresh")
        UserDataManager.import(backup(old).inputStream(), fresh).getOrThrow()
        val db = Room.databaseBuilder(appContext, ClipboardDatabase::class.java, File(fresh.dataBases, "clbdb").path).build()
        val texts = mutableListOf<String>()
        db.openHelper.readableDatabase.query("SELECT text FROM ${ClipboardEntry.TABLE_NAME}").use {
            while (it.moveToNext()) texts += it.getString(0)
        }
        db.close()
        assertEquals(listOf("公园"), texts)
    }

    // this build's backup with its dictionary as 0.3.2 kept it, a text: what WD-14u makes of it
    private fun backup032(): ByteArray {
        val old = install("old")
        val dir = dictionaries(old)
        SourceNames.writeGzip(File(dir, "dict-words.txt.gz")) { it.write(dictWords.toByteArray()) }
        return backup(old) { name -> if (name.endsWith("dict-words.txt.gz")) name.removeSuffix(".gz") else name }
            .also { assertTrue("${names(it)}", "external/data/pinyin/dictionaries/dict-words.txt" in names(it)) }
    }

    @Test
    @Uat("WD-14u")
    fun a032BackupOnAFreshInstallBringsItsDictionaryWithoutThePage() {
        val zip = backup032()
        val phone = install("phone")
        UserDataManager.import(zip.inputStream(), phone).getOrThrow()
        val dir = dictionaries(phone)
        // the keyboard shown, the settings' page never opened
        started(dir)
        assertEquals(listOf("dict-words.txt.gz"), files(dir))
        assertTrue(typed(dir, "氪喵糖铺"))
        assertEquals(listOf("dict-words.txt.gz"), ImportedDictionaries.list(dir).map { it.name })
    }

    @Test
    @Uat("PR-10a")
    fun a032BackupOverAnImportOfTheNameOff() {
        val zip = backup032()
        val phone = install("phone")
        val dir = dictionaries(phone)
        SourceNames.writeGzip(File(dir, "dict-words.txt.gz.disable")) { it.write(dictWords.toByteArray()) }
        File(dir, "dict-words.txt.gz.disable").setLastModified(System.currentTimeMillis() - 3_600_000)
        UserDataManager.import(zip.inputStream(), phone).getOrThrow()
        started(dir)
        // as the backup has it: on, one row
        assertEquals(listOf("dict-words.txt.gz"), files(dir))
        assertTrue(typed(dir, "氪喵糖铺"))
    }

    @Test
    @Uat("PR-10b")
    fun thisBuildsBackupOverItsDictionaryTurnedOff() {
        val old = install("old")
        val kept = dictionaries(old)
        SourceNames.writeGzip(File(kept, "dict-words.txt.gz")) { it.write(dictWords.toByteArray()) }
        val zip = backup(old)
        val phone = install("phone")
        val dir = dictionaries(phone)
        // turned off on the phone after the backup, the backup's file the newer as the zip writes it now
        SourceNames.writeGzip(File(dir, "dict-words.txt.gz.disable")) { it.write(dictWords.toByteArray()) }
        File(dir, "dict-words.txt.gz.disable").setLastModified(System.currentTimeMillis() - 3_600_000)
        UserDataManager.import(zip.inputStream(), phone).getOrThrow()
        started(dir)
        assertEquals(listOf("dict-words.txt.gz"), files(dir))
        assertTrue(typed(dir, "氪喵糖铺"))
    }
}
