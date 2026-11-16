/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.dirsUnder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 0.6.1 has no 中文标点 page (DECISIONS FB9): a punctuation table a backup brings, this app's or upstream Fcitx5's,
 * is not used, and the phone's goes too; anything else in its directory stays, as the other followed files'
 * dot-entries do. (UserDataManagerTest has the rest of a restore.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PunctuationRestoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun zip(packageName: String, vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("metadata.json"))
            val metadata = """{"packageName":"$packageName","versionCode":${BuildConfig.VERSION_CODE},"versionName":"1","exportTime":0}"""
            zip.write(metadata.encodeToByteArray())
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
            }
        }
    }.toByteArray().inputStream()

    @Test
    @Uat("PR-62")
    fun aRestoredPunctuationTableIsDropped() {
        // this build type's id before the renaming: upstream's backups
        val legacy = LEGACY_APPLICATION_ID_ROOT + BuildConfig.APPLICATION_ID.removePrefix(BuildConfig.APPLICATION_ID_ROOT)
        for (from in listOf(BuildConfig.APPLICATION_ID, legacy)) {
            val phone = dirsUnder(File(folder.root, "phone-$from"))
            File(phone.data, "punctuation").mkdirs()
            File(phone.data, "punctuation/punc.mb.zh_CN").writeText(", 、\n")
            File(phone.data, "punctuation/.libime").writeText("phone's")
            val backup = zip(from, "external/data/punctuation/punc.mb.zh_CN" to ", ；\n", "external/data/pinyin/customphrase" to "dz,1=北京\n")
            UserDataManager.import(backup, phone).getOrThrow()
            assertEquals(from, "dz,1=北京\n", File(phone.data, "pinyin/customphrase").readText())
            assertEquals(from, listOf(".libime"), File(phone.data, "punctuation").list()!!.toList())
        }
    }
}
