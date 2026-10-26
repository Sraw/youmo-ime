/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The start after an import of the user's data puts the settings without a UI back, once; the
 * first start deletes the notification channels an older version made, once, and the native host's
 * synced data.
 */
@RunWith(RobolectricTestRunner::class)
class FcitxApplicationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sp = RuntimeEnvironment.getApplication().getSharedPreferences("fcitx-application-test", Context.MODE_PRIVATE)

    // reset once already, as every start after the first: only the import's marker resets again
    private fun prefs() = AppPrefs(sp).also {
        it.resetHiddenSettings()
        sp.edit { putBoolean("ignore_system_cursor", true) }
    }

    @Before
    fun empty() {
        sp.edit { clear() }
    }

    @Test
    fun anImportsMarkerResetsTheSettingsAgainAndGoes() {
        val prefs = prefs()
        val marker = folder.newFile("user_data_imported")
        FcitxApplication.resetHiddenSettings(prefs, directBoot = false) { marker }
        assertFalse(prefs.advanced.ignoreSystemCursor.getValue())
        assertFalse(marker.exists())
    }

    @Test
    fun withoutAMarkerNothingIsResetAgain() {
        val prefs = prefs()
        FcitxApplication.resetHiddenSettings(prefs, directBoot = false) { folder.root.resolve("user_data_imported") }
        assertTrue(prefs.advanced.ignoreSystemCursor.getValue())
    }

    @Test
    fun inDirectBootTheMarkerIsLeftForTheNextStart() {
        val prefs = prefs()
        val marker = folder.newFile("user_data_imported")
        FcitxApplication.resetHiddenSettings(prefs, directBoot = true) { marker }
        assertTrue(prefs.advanced.ignoreSystemCursor.getValue())
        assertTrue(marker.exists())
    }

    // the starts after need not ask the system for the channels: the first left none
    @Test
    fun theNotificationChannelsAnOlderVersionMadeGoAtTheFirstStartAlone() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("setup", "setup", NotificationManager.IMPORTANCE_LOW))
        FcitxApplication.deleteNotificationChannels(manager, sp)
        assertTrue(manager.notificationChannels.isEmpty())
        manager.createNotificationChannel(NotificationChannel("later", "later", NotificationManager.IMPORTANCE_LOW))
        FcitxApplication.deleteNotificationChannels(manager, sp)
        assertEquals(listOf("later"), manager.notificationChannels.map { it.id })
    }

    private class NativeRow(val why: String, val dir: String, val run: (File) -> Unit)

    // 0.3.x's descriptor.json as DataManager wrote it: a file's SHA-256, "" for a directory
    private fun descriptor(dir: File, files: List<String>, dirs: List<String>) {
        val entries = (files.map { "\"$it\": \"ab12\"" } + dirs.map { "\"$it\": \"\"" }).joinToString()
        dir.resolve("descriptor.json").writeText("""{"sha256": "f00", "files": {$entries}, "symlinks": {}}""")
    }

    @Test
    fun theNativeHostsSyncedDataGoesOnceAndNothingBesideIt() {
        assertRows(
            listOf(
                NativeRow("a whole sync: its files and directories go, a file beside them and the app's own data stay", "whole") { dir ->
                    val listed = listOf("README.md", "learn/TSCharacters.txt", "usr/share/fcitx5/punctuation/punc.mb.zh_CN", "usr/share/opencc/s2t.json")
                    listed.forEach { dir.resolve(it).apply { parentFile!!.mkdirs() }.writeText("x") }
                    descriptor(dir, listed, listOf("learn", "usr", "usr/share", "usr/share/fcitx5", "usr/share/fcitx5/punctuation", "usr/share/opencc"))
                    val beside = dir.resolve("usr/share/fcitx5/mine.txt").apply { writeText("keep") }
                    val prefs = dir.resolve("shared_prefs/prefs.xml").apply { parentFile!!.mkdirs(); writeText("keep") }
                    FcitxApplication.deleteNativeData(dir)
                    listed.forEach { assertFalse(it, dir.resolve(it).exists()) }
                    assertFalse(dir.resolve("descriptor.json").exists())
                    assertFalse(dir.resolve("learn").exists())
                    assertFalse(dir.resolve("usr/share/opencc").exists())
                    assertEquals("keep", beside.readText())
                    assertEquals("keep", prefs.readText())
                    // later starts: no descriptor, nothing touched
                    dir.resolve("README.md").writeText("new")
                    FcitxApplication.deleteNativeData(dir)
                    assertTrue(dir.resolve("README.md").exists())
                },
                // F4114
                NativeRow("a start killed halfway: some listed files gone, the descriptor still there; the next start does the rest", "halfway") { dir ->
                    val left = dir.resolve("usr/share/opencc/s2t.json").apply { parentFile!!.mkdirs(); writeText("x") }
                    descriptor(dir, listOf("README.md", "usr/share/opencc/s2t.json"), listOf("usr", "usr/share", "usr/share/opencc"))
                    FcitxApplication.deleteNativeData(dir)
                    assertFalse(left.exists())
                    assertFalse(dir.resolve("usr").exists())
                    assertFalse(dir.resolve("descriptor.json").exists())
                },
                // F4096: one that will not go is logged and left, tried once (the descriptor goes)
                NativeRow("a file that will not go (a directory where a file was listed)", "stuck") { dir ->
                    val stuck = dir.resolve("README.md").apply { mkdirs(); resolve("inside").writeText("x") }
                    descriptor(dir, listOf("README.md"), emptyList())
                    FcitxApplication.deleteNativeData(dir)
                    assertTrue(stuck.exists())
                    assertFalse(dir.resolve("descriptor.json").exists())
                },
            ),
            { it.why },
        ) { row -> row.run(folder.newFolder(row.dir)) }
    }

    @Test
    fun anUnreadableDescriptorLeavesEverything() {
        val dir = folder.newFolder("user_de")
        val readme = dir.resolve("README.md").apply { writeText("x") }
        dir.resolve("descriptor.json").writeText("{not json")
        FcitxApplication.deleteNativeData(dir)
        assertTrue(readme.exists())
        assertTrue(dir.resolve("descriptor.json").exists())
    }
}
