/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.dirsUnder
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.input.address.AddressBook
import org.fcitx.fcitx5.android.input.address.AddressLists
import org.fcitx.fcitx5.android.input.address.AddressStore
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
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

    private fun install(name: String) = dirsUnder(File(folder.root, name))

    private fun dictionaries(dirs: UserDataManager.Dirs) = File(dirs.external, "data/pinyin/dictionaries").apply { mkdirs() }

    private val dictWords = File("../uat/fixtures/dict-words.txt").readLines().joinToString("") { line ->
        val parts = line.split(" ")
        "${parts[0]} ${parts.drop(1).joinToString("'")} 0.0\n"
    }

    // 备份全部 as the page saves it, the metadata the app's: the package Robolectric runs as need not be the one the import checks for
    private fun backup(dirs: UserDataManager.Dirs, learning: ByteArray? = null, entries: (String) -> String? = { it }): ByteArray {
        val export = ByteArrayOutputStream().also { UserDataManager.export(it, 0L, dirs, learning).getOrThrow() }
        return ByteArrayOutputStream().also { bytes ->
            ZipInputStream(export.toByteArray().inputStream()).use { from ->
                ZipOutputStream(bytes).use { zip ->
                    generateSequence { from.nextEntry }.forEach { e ->
                        val content = if (e.name == "metadata.json") {
                            """{"packageName":"${BuildConfig.APPLICATION_ID}","versionCode":${BuildConfig.VERSION_CODE},"versionName":"1","exportTime":0}""".encodeToByteArray()
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

    private fun gunzip(bytes: ByteArray) = GZIPInputStream(bytes.inputStream()).readBytes()

    private fun names(zip: ByteArray) = ZipInputStream(zip.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }

    // the app started after the restore: the engine's start compiles what the backup brought
    private fun started(dir: File) {
        MergedDictionary.reset()
        MergedDictionary.refresh(dir)?.get(1, TimeUnit.MINUTES)
    }

    private fun typed(dir: File, text: String) = DictionaryFile.open(JvmFile(File(dir, MergedDictionary.FILE))).hasText(text)

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
        // vibration, and the microphone for voice input (asked for at its first use)
        assertEquals(listOf(Manifest.permission.VIBRATE, Manifest.permission.RECORD_AUDIO).sorted(), asked.sorted())
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
        )
        assertTrue("$asked", none.none { it in asked })
    }

    @Test
    @Uat("PR-39")
    fun noDocumentsProviderForTheFilesApp() {
        val pm = appContext.packageManager
        val providers = pm.getPackageInfo(appContext.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
        // the manifest is read (its providers there): none is a DocumentsProvider, none answers the files app
        assertTrue(providers.map { it.name }.toString(), providers.any { it.name == "androidx.startup.InitializationProvider" })
        // not initialized (a library's static set-up has no business running here); one not on the test's
        // classpath is a library's, none of which is a DocumentsProvider
        val loader = javaClass.classLoader
        fun documents(name: String) = try {
            DocumentsProvider::class.java.isAssignableFrom(Class.forName(name, false, loader))
        } catch (_: ClassNotFoundException) {
            false
        }
        assertTrue(providers.map { it.name }.toString(), providers.none { documents(it.name) })
        val roots = pm.queryIntentContentProviders(Intent(DocumentsContract.PROVIDER_INTERFACE), 0)
        assertTrue(roots.toString(), roots.none { it.providerInfo.packageName == appContext.packageName })
    }

    @Test
    @Uat("PR-40")
    fun aBackupBringsPinyinsAndShuangpinsKeyboards() {
        fun prefs(dirs: UserDataManager.Dirs, pinyin: String, shuangpin: String) = File(dirs.sharedPrefs.apply { mkdirs() }, "${appContext.packageName}_preferences.xml").apply {
            writeText(
                """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
                |<map>
                |    <string name="${AppPrefs.PINYIN_KEYBOARD}">$pinyin</string>
                |    <string name="${AppPrefs.SHUANGPIN_KEYBOARD}">$shuangpin</string>
                |</map>
                |""".trimMargin(),
            )
        }
        val old = install("old")
        prefs(old, PinyinKeyboard.Keys14.name, ShuangpinKeyboard.Keys18.name)
        val zip = backup(old)
        // both put back to the full keyboard since
        val phone = install("phone")
        val file = prefs(phone, PinyinKeyboard.Full.name, ShuangpinKeyboard.Full.name)
        UserDataManager.import(zip.inputStream(), phone).getOrThrow()
        // as the app restarted reads them: the keyboards each page shows and the field gets
        val read = Regex("""<string name="([a-z_]+)">([A-Za-z0-9]+)</string>""").findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(PinyinKeyboard.Keys14, PinyinKeyboard.valueOf(read.getValue(AppPrefs.PINYIN_KEYBOARD)))
        assertEquals(ShuangpinKeyboard.Keys18, ShuangpinKeyboard.valueOf(read.getValue(AppPrefs.SHUANGPIN_KEYBOARD)))
    }

    @Test
    @Uat("PR-10")
    fun aBackupRestoredReplacesSettingsPhrasesAndPunctuation() {
        val old = install("old")
        val config = File(old.config, "conf").apply { mkdirs() }
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
        File(phone.config, "conf").mkdirs()
        File(phone.config, "conf/androidengine.conf").writeText("PageSize=5\n")
        File(phone.external, "data/pinyin").mkdirs()
        File(phone.external, "data/pinyin/customphrase").writeText("yx,1=test@example.com\n")
        File(phone.external, "data/punctuation").mkdirs()
        File(phone.external, "data/punctuation/punc.mb.zh_CN").writeText(", 、\n")
        UserDataManager.import(zip.inputStream(), phone).getOrThrow()
        assertEquals("PageSize=7\n", File(phone.config, "conf/androidengine.conf").readText())
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

    @Test
    @Uat("PR-44")
    fun aBackupBringsTheAddressListsAndWhatTheyLearned() {
        val internal = AppPrefs.getInstance().internal
        val keyboard = AppPrefs.getInstance().keyboard
        val learned = mapOf(
            internal.addressCounts.key to "3\t@gmail.com\n1\t.org",
            internal.rememberedAddresses.key to "2\tzhang@gmail.com",
            keyboard.emailSuffixes.key to "work.cn\nqq.com",
            keyboard.urlSuffixes.key to ".io",
        )
        fun prefs(dirs: UserDataManager.Dirs, strings: Map<String, String>, remember: Boolean) = File(dirs.sharedPrefs.apply { mkdirs() }, "${appContext.packageName}_preferences.xml").apply {
            writeText(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
                    strings.entries.joinToString("") { "    <string name=\"${it.key}\">${it.value}</string>\n" } +
                    "    <boolean name=\"${keyboard.rememberAddresses.key}\" value=\"$remember\" />\n</map>\n",
            )
        }
        val old = install("old")
        prefs(old, learned, remember = false)
        val zip = backup(old)
        // a phone that learned nothing, the lists as they came
        val phone = install("phone")
        val file = prefs(phone, emptyMap(), remember = true)
        UserDataManager.import(zip.inputStream(), phone).getOrThrow()
        val text = file.readText()
        val read = strings(text)
        assertEquals(learned, read.filterKeys { it in learned })
        assertTrue(text, """<boolean name="${keyboard.rememberAddresses.key}" value="false" />""" in text)
        // as the app restarted reads them
        val book = AddressBook.parse(read.getValue(internal.addressCounts.key), read.getValue(internal.rememberedAddresses.key))
        assertEquals(3, book.count("@gmail.com"))
        assertEquals(listOf("zhang@gmail.com"), book.addresses.map { it.address })
        assertEquals(listOf("work.cn", "qq.com"), AddressLists.parse(read.getValue(keyboard.emailSuffixes.key), AddressLists::emailEntry))
    }

    // the address bar's book as the prefs hold it
    private fun book(counts: String, addresses: String) {
        AppPrefs.getInstance().internal.addressCounts.setValue(counts)
        AppPrefs.getInstance().internal.rememberedAddresses.setValue(addresses)
    }

    // the app's engines over [dir], the address book theirs to back up: no data needed for that
    private fun addressEngines(dir: File) = Engines({ throw FileNotFoundException(it) }, JvmFiles(dir), addresses = AddressStore::data, putAddresses = AddressStore::put)

    // a prefs file's strings, by name
    private fun strings(xml: String) =
        Regex("""<string name="([a-z_.]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    @Uat("PR-45")
    fun aLearningBackupBringsWhatTheAddressBarLearned() {
        try {
            book("3\t@gmail.com", "2\tzhang@gmail.com")
            // the learning's own backups, as the app's engines make them: no data needed for that
            addressEngines(folder.newFolder()).use { engines ->
                val copy = (engines.learning(null, BackupOp.Copy) as BackupResult).bytes!!
                // the phone since: another address, @gmail.com used once
                book("1\t@gmail.com\n1\t@qq.com", "1\tli@qq.com")
                assertTrue((engines.learning(null, BackupOp.Restore(copy)) as BackupResult).ok)
                // merged: the larger count, the phone's address first
                assertEquals(3, AddressStore.book().count("@gmail.com"))
                assertEquals(1, AddressStore.book().count("@qq.com"))
                assertEquals(listOf("li@qq.com", "zhang@gmail.com"), AddressStore.book().addresses.map { it.address })
                // 退回恢复前: as the phone had them
                assertTrue((engines.learning(null, BackupOp.Revert) as BackupResult).ok)
                assertEquals(listOf("li@qq.com"), AddressStore.book().addresses.map { it.address })
                assertEquals(1, AddressStore.book().count("@gmail.com"))
            }
        } finally {
            book("", "")
        }
    }

    @Test
    @Uat("PR-46")
    fun aSettingsBackupsAddressesMergeWithThePhones() {
        val internal = AppPrefs.getInstance().internal
        fun prefs(dirs: UserDataManager.Dirs, counts: String, addresses: String) = File(dirs.sharedPrefs.apply { mkdirs() }, "${appContext.packageName}_preferences.xml").apply {
            writeText(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
                    "    <string name=\"${internal.addressCounts.key}\">$counts</string>\n" +
                    "    <string name=\"${internal.rememberedAddresses.key}\">$addresses</string>\n" +
                    "    <boolean name=\"${AppPrefs.getInstance().keyboard.rememberAddresses.key}\" value=\"false\" />\n</map>\n",
            )
        }
        try {
            // the old phone's 备份全部: its book in its settings and in the learning it carries
            book("3\t@gmail.com", "2\tzhang@gmail.com")
            val learning = addressEngines(folder.newFolder()).use { engines ->
                (engines.learning(null, BackupOp.Copy) as BackupResult).bytes!!
            }
            val old = install("old")
            prefs(old, "3\t@gmail.com", "2\tzhang@gmail.com")
            val zip = backup(old, learning)
            // this phone's own book
            val phone = install("phone")
            val file = prefs(phone, "1\t@qq.com", "1\tli@qq.com")
            // as AppPrefs has it: the import reads the phone's book there, not from its file
            book("1\t@qq.com", "1\tli@qq.com")
            UserDataManager.import(zip.inputStream(), phone).getOrThrow()
            val text = file.readText()
            val read = strings(text)
            // the phone's book kept, the zip's settings else
            assertEquals("1\t@qq.com", read[internal.addressCounts.key])
            assertEquals("1\tli@qq.com", read[internal.rememberedAddresses.key])
            assertTrue(text, Regex("""<boolean name="${AppPrefs.getInstance().keyboard.rememberAddresses.key}" value="false" ?/>""") in text)
            // the app started again on them: the zip's learning merged in as the engines start
            book(read.getValue(internal.addressCounts.key), read.getValue(internal.rememberedAddresses.key))
            addressEngines(phone.engine).use { engines ->
                assertEquals(listOf("li@qq.com", "zhang@gmail.com"), AddressStore.book().addresses.map { it.address })
                assertEquals(3, AddressStore.book().count("@gmail.com"))
                assertEquals(1, AddressStore.book().count("@qq.com"))
                // 退回恢复前: the phone's book as it was
                assertTrue((engines.learning(null, BackupOp.Revert) as BackupResult).ok)
                assertEquals(listOf("li@qq.com"), AddressStore.book().addresses.map { it.address })
                assertEquals(0, AddressStore.book().count("@gmail.com"))
            }
        } finally {
            book("", "")
        }
    }

    // not a case: the zip's settings file is rewritten to put the phone's address book in it: what else it holds comes through as it was
    @Test
    fun aSettingsBackupsOtherSettingsComeThroughTheAddressBooksRewrite() {
        val internal = AppPrefs.getInstance().internal
        val odd = "\uD83D\uDE00\uD840\uDC00 <&> \"'"
        val learning = addressEngines(folder.newFolder()).use { engines ->
            (engines.learning(null, BackupOp.Copy) as BackupResult).bytes!!
        }
        val old = install("old")
        val escaped = odd.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        File(old.sharedPrefs.apply { mkdirs() }, "${appContext.packageName}_preferences.xml").writeText(
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
                "    <string name=\"${AppPrefs.POPUP_OVERRIDES}\">$escaped</string>\n</map>\n",
        )
        val phone = install("phone")
        UserDataManager.import(backup(old, learning).inputStream(), phone).getOrThrow()
        val read = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(phone.sharedPrefs, "${appContext.packageName}_preferences.xml")).documentElement
            .getElementsByTagName("string").let { n -> (0 until n.length).map(n::item) }
            .associate { (it as org.w3c.dom.Element).getAttribute("name") to it.textContent }
        assertEquals(odd, read[AppPrefs.POPUP_OVERRIDES])
    }

    // not a case: a settings file in the zip that will not parse does not fail the import; it lands as it was
    @Test
    fun anUnreadableSettingsFileStillImportsAsItWas() {
        val learning = addressEngines(folder.newFolder()).use { engines ->
            (engines.learning(null, BackupOp.Copy) as BackupResult).bytes!!
        }
        val old = install("old")
        val broken = "<?xml version='1.0' encoding='utf-8' ?>\n<map>\n    <string name=\"x\">cut off".toByteArray()
        File(old.sharedPrefs.apply { mkdirs() }, "${appContext.packageName}_preferences.xml").writeBytes(broken)
        val phone = install("phone")
        UserDataManager.import(backup(old, learning).inputStream(), phone).getOrThrow()
        assertArrayEquals(broken, File(phone.sharedPrefs, "${appContext.packageName}_preferences.xml").readBytes())
    }

    // not a case: what a 备份全部 zip from elsewhere may put in the prefs never fails the learning's backups
    @Test
    fun anOversizedAddressBookStillBacksUp() {
        val internal = AppPrefs.getInstance().internal
        try {
            val long = "汉".repeat(240)
            internal.addressCounts.setValue((1..5_000).joinToString("\n") { "$it\t@$long$it.com" } + "\n\u0000garbage\t\t")
            internal.rememberedAddresses.setValue((1..500).joinToString("\n") { "1\t$long$it@q.cn" } + "\nnot an address")
            addressEngines(folder.newFolder()).use { engines ->
                val copy = (engines.learning(null, BackupOp.Copy) as BackupResult).bytes!!
                internal.addressCounts.setValue("")
                internal.rememberedAddresses.setValue("")
                assertTrue((engines.learning(null, BackupOp.Restore(copy)) as BackupResult).ok)
                assertEquals(AddressBook.MAX_ADDRESSES, AddressStore.book().addresses.size)
                assertEquals(AddressBook.MAX_COUNTS, AddressStore.book().counts.size)
                assertEquals(5_000, AddressStore.book().count("@${long}5000.com"))
            }
        } finally {
            internal.addressCounts.setValue("")
            internal.rememberedAddresses.setValue("")
        }
    }
}
