/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.dirsUnder
import org.fcitx.fcitx5.android.data.layout.CustomLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.layout.CustomLayoutFiles
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * The user's own layouts in a backup (061-custom-layout §4.5): `layouts/` with the record of the packs converted, and a
 * restore that lays them over the phone's as theme packs are; an older backup has none and leaves the phone's.
 * UserDataManagerTest is too large for more.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class BackupLayoutsTest {

    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun forget() = CustomLayouts.forgetAll()

    private fun install(name: String) = dirsUnder(File(folder.root, name))

    private fun metadata(versionCode: Long = BuildConfig.VERSION_CODE.toLong()) =
        """{"packageName":"${BuildConfig.APPLICATION_ID}","versionCode":$versionCode,"versionName":"1","exportTime":0}""".encodeToByteArray()

    // [dirs] exported, named as the app's (the package Robolectric runs as need not be the one the import checks for)
    private fun exported(dirs: UserDataManager.Dirs) = ByteArrayOutputStream().also { bytes ->
        val export = ByteArrayOutputStream().also { UserDataManager.export(it, 0L, dirs).getOrThrow() }
        ZipInputStream(export.toByteArray().inputStream()).use { from ->
            ZipOutputStream(bytes).use { zip ->
                generateSequence { from.nextEntry }.forEach {
                    zip.putNextEntry(ZipEntry(it.name))
                    if (it.name == "metadata.json") zip.write(metadata()) else from.copyTo(zip)
                }
            }
        }
    }.toByteArray()

    private fun entries(zip: InputStream) = ZipInputStream(zip).use { from ->
        generateSequence { from.nextEntry }.filterNot { it.isDirectory }.associate { it.name to from.readBytes().decodeToString() }
    }

    private fun own(id: String, name: String? = null): String {
        val t = BuiltInLayouts(appContext).text("qwerty")!!.replaceFirst("id=\"qwerty\"", "id=\"$id\" based-on=\"qwerty\"")
        return if (name == null) t else t.replaceFirst(Regex("name=\"[^\"]*\""), "name=\"$name\"")
    }

    @Test
    @Uat("PR-64")
    fun theLayoutsGoWithABackupAndAreLaidOverThePhones() {
        val old = install("old")
        CustomLayouts.save("mine", own("mine", "备份的"), old.layouts)
        File(old.layouts, CustomLayoutFiles.CONVERTED).writeText("p@1\n")
        // half written when the backup was made: not taken
        File(old.layouts, "x.saving").writeText("half")
        val zip = exported(old)
        val layouts = entries(zip.inputStream()).filterKeys { it.startsWith("layouts/") }
        assertEquals(setOf("layouts/mine.xml", "layouts/.converted"), layouts.keys)
        val phone = install("phone")
        CustomLayouts.save("mine", own("mine", "手机的"), phone.layouts)
        CustomLayouts.save("phones", own("phones"), phone.layouts)
        File(phone.layouts, CustomLayoutFiles.CONVERTED).writeText("q@2\n")
        UserDataManager.import(zip.inputStream(), phone).getOrThrow()
        val listed = CustomLayouts.list(phone.layouts)
        assertEquals(listOf("mine", "phones"), listed.map { it.id }.sorted())
        assertEquals("备份的", listed.single { it.id == "mine" }.name)
        assertEquals("q@2\np@1\n", File(phone.layouts, CustomLayoutFiles.CONVERTED).readText())
    }

    @Test
    @Uat("PR-64")
    fun aBackupWithoutLayoutsLeavesThePhonesAndOneForgedIsNotTakenAsItSays() {
        val phone = install("phone")
        CustomLayouts.save("phones", own("phones"), phone.layouts)
        // as 0.6.0 made it: no layouts/
        val older = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(metadata())
            }
        }.toByteArray()
        UserDataManager.import(older.inputStream(), phone).getOrThrow()
        assertEquals(listOf("phones"), CustomLayouts.list(phone.layouts).map { it.id })
        // forged: a file of another id (its name not its layout's), one that does not read; one that does is taken
        val forged = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(metadata())
                for ((name, text) in listOf("layouts/other.xml" to own("phones", "改名的"), "layouts/bad.xml" to "<keyboard", "layouts/ok.xml" to own("ok"))) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.encodeToByteArray())
                }
            }
        }.toByteArray()
        UserDataManager.import(forged.inputStream(), phone).getOrThrow()
        assertEquals(listOf("ok", "phones"), CustomLayouts.list(phone.layouts).map { it.id }.sorted())
        assertFalse(File(phone.layouts, "other.xml").exists())
        assertTrue(CustomLayouts.list(phone.layouts).all { it.layout != null })
    }

    // review MUST 1: the layouts' restore fails after the settings, databases and themes are the backup's; it is
    // logged, the engine's import after it still made, the restore not failed half done
    @Test
    @Uat("PR-64")
    fun aRestoreWhoseLayoutsFailIsNotFailedForThem() {
        val phone = install("phone")
        // no layouts' folder can be made: a file where it would be
        phone.layouts.parentFile!!.mkdirs()
        phone.layouts.writeText("in the way")
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(metadata())
                zip.putNextEntry(ZipEntry("layouts/.converted"))
                zip.write("p@1\n".encodeToByteArray())
                zip.putNextEntry(ZipEntry("layouts/ok.xml"))
                zip.write(own("ok").encodeToByteArray())
                zip.putNextEntry(ZipEntry("engine/x.txt"))
                zip.write("x".encodeToByteArray())
            }
        }.toByteArray()
        assertTrue(UserDataManager.import(zip.inputStream(), phone).isSuccess)
        assertTrue(File(phone.engine, "x.txt").isFile)
    }
}
