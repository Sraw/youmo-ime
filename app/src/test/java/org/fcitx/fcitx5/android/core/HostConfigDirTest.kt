/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.os.Environment
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMaps
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.io.IOException

// A4: fcitx's data settled once a process, for the engine and the editors alike
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class HostConfigDirTest {

    private val external get() = appContext.getExternalFilesDir(null)!!

    // an upgraded install (its engine's directory there) started without the external storage: nothing
    // moved, nothing written, and the storage back later in the process changes nothing till the next
    @Test
    fun theExternalStorageAwayFixesThePlaceForTheProcessAndAnEditorsSaveIsRefused() {
        File(appContext.filesDir, "engine").mkdirs()
        File(external, "data/pinyin").mkdirs()
        File(external, "data/pinyin/customphrase").writeText("dz,1=北京\n")
        ShadowEnvironment.setExternalStorageState(external, Environment.MEDIA_REMOVED)
        val first = HostConfigDir.settle(ConfigHome.DATA)
        assertEquals(HostConfig(null, writable = false, ConfigHome.Problem.EXTERNAL_AWAY), first)
        ShadowEnvironment.setExternalStorageState(external, Environment.MEDIA_MOUNTED)
        assertEquals(first, HostConfigDir.settle(ConfigHome.DATA))
        assertFalse(HostConfigDir.inUse(ConfigHome.DATA).writable)
        val refused = assertThrows(IOException::class.java) {
            CustomPhraseManager.save(listOf(PinyinCustomPhrase("yx", 1, "信箱")))
        }
        assertEquals(appContext.getString(R.string.user_data_not_settled), refused.message)
        assertFalse(File(appContext.filesDir, "host-data").exists())
        assertFalse(File(appContext.filesDir, "data").exists())
        assertEquals("dz,1=北京\n", File(external, "data/pinyin/customphrase").readText())
    }

    // the move made, the engine and the editor read and write the one directory
    @Test
    fun movedTheEditorWritesWhereTheEngineReads() {
        File(appContext.filesDir, "engine").mkdirs()
        File(external, "data/pinyin").mkdirs()
        File(external, "data/pinyin/customphrase").writeText("dz,1=北京\n")
        ShadowEnvironment.setExternalStorageState(external, Environment.MEDIA_MOUNTED)
        val data = HostConfigDir.settle(ConfigHome.DATA)
        assertEquals(File(appContext.filesDir, "host-data"), data.dir)
        assertTrue(data.writable)
        assertEquals(listOf(PinyinCustomPhrase("dz", 1, "北京")), CustomPhraseManager.load())
        CustomPhraseManager.save(listOf(PinyinCustomPhrase("yx", 1, "信箱")))
        assertEquals("yx,1=信箱\n", File(data.dir, "pinyin/customphrase").readText())
        // the old copy deleted once moved in: nothing left there to be edited by mistake
        assertFalse(File(external, "data").exists())
    }

    // the move failed (used where it is, on the external storage) and that storage gone since: nowhere
    // to write, not a write that silently goes nowhere
    @Test
    fun theExternalHomeRemovedAfterItWasSettledIsNotWritable() {
        File(appContext.filesDir, "engine").mkdirs()
        File(external, "data/pinyin").mkdirs()
        File(external, "data/pinyin/customphrase").writeText("dz,1=北京\n")
        val externalDir = external
        ShadowEnvironment.setExternalStorageState(externalDir, Environment.MEDIA_MOUNTED)
        // the copy cannot be staged: NOT_MOVED, used on the external storage
        try {
            // root writes anyway: a skip there (F4987), not a failure
            assumeTrue("a directory not writable (not as root)", appContext.filesDir.setWritable(false) && !appContext.filesDir.canWrite())
            val first = HostConfigDir.settle(ConfigHome.DATA)
            assertEquals(HostConfig(File(externalDir, "data"), writable = true, ConfigHome.Problem.NOT_MOVED), first)
            ShadowEnvironment.setExternalStorageState(externalDir, Environment.MEDIA_REMOVED)
            assertEquals(HostConfig(null, writable = false, ConfigHome.Problem.NOT_MOVED), HostConfigDir.settle(ConfigHome.DATA))
        } finally {
            appContext.filesDir.setWritable(true)
        }
    }

    // 0.6.1 has no 中文标点 page (DECISIONS FB9): the table edited before is deleted at the first start, the built-in one
    // used; once, a table put in after it (a test's) staying, and nothing else of the data touched
    @Test
    @Uat("ST-145")
    fun anEditedPunctuationTableIsDeletedOnceOnTheUpgrade() {
        val done = AppPrefs.getInstance().internal.punctuationReset061
        done.setValue(false)
        val home = File(appContext.filesDir, "host-data")
        val table = File(home, "punctuation/punc.mb.zh_CN").apply { parentFile!!.mkdirs() }
        table.writeText(", 、\n")
        File(home, "pinyin").mkdirs()
        File(home, "pinyin/customphrase").writeText("dz,1=北京\n")
        phoneStorage()
        val data = HostConfigDir.settle(ConfigHome.DATA)
        assertEquals(home, data.dir)
        assertFalse(table.exists())
        assertTrue(done.getValue())
        assertEquals("dz,1=北京\n", File(home, "pinyin/customphrase").readText())
        val shipped = listOf(PunctuationMapEntry(",", "，", ""))
        val maps = PunctuationMaps(data.dir) { ", ，\n".byteInputStream() }
        assertEquals(shipped, maps.load("zh_CN"))
        // the next start: done already
        table.writeText(", 、\n")
        HostConfigDir.forget()
        HostConfigDir.settle(ConfigHome.DATA)
        assertEquals(", 、\n", table.readText())
    }

    // the data not writable this start (an upgrade's external storage away): the table where it is left alone
    // and the reset not marked done, made at the start that has the data
    @Test
    @Uat("ST-145")
    fun withTheDataNotWritableThePunctuationResetWaits() {
        val done = AppPrefs.getInstance().internal.punctuationReset061
        done.setValue(false)
        File(appContext.filesDir, "engine").mkdirs()
        val table = File(external, "data/punctuation/punc.mb.zh_CN").apply { parentFile!!.mkdirs() }
        table.writeText(", 、\n")
        ShadowEnvironment.setExternalStorageState(external, Environment.MEDIA_REMOVED)
        HostConfigDir.forget()
        assertEquals(ConfigHome.Problem.EXTERNAL_AWAY, HostConfigDir.settle(ConfigHome.DATA).problem)
        assertEquals(", 、\n", table.readText())
        assertFalse(done.getValue())
        // the storage back, the next start: moved in, then the table deleted
        phoneStorage()
        val data = HostConfigDir.settle(ConfigHome.DATA)
        assertEquals(File(appContext.filesDir, "host-data"), data.dir)
        assertFalse(File(data.dir, "punctuation/punc.mb.zh_CN").exists())
        assertTrue(done.getValue())
    }

    // a table that could not be deleted: not marked done, so the next start tries again
    @Test
    @Uat("ST-145")
    fun aTableNotDeletedIsTriedAgainAtTheNextStart() {
        val done = AppPrefs.getInstance().internal.punctuationReset061
        done.setValue(false)
        val home = File(appContext.filesDir, "host-data")
        val dir = File(home, "punctuation").apply { mkdirs() }
        val table = File(dir, "punc.mb.zh_CN").apply { writeText(", 、\n") }
        try {
            // root deletes anyway: a skip there, not a failure
            assumeTrue("a directory not writable (not as root)", dir.setWritable(false) && !dir.canWrite())
            phoneStorage()
            assertTrue(HostConfigDir.settle(ConfigHome.DATA).writable)
            assertTrue(table.exists())
            assertFalse(done.getValue())
        } finally {
            dir.setWritable(true)
        }
        phoneStorage()
        HostConfigDir.settle(ConfigHome.DATA)
        assertFalse(table.exists())
        assertTrue(done.getValue())
    }
}
