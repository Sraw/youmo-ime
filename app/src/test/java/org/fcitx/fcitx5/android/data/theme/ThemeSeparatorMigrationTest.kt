/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.backupZip
import org.fcitx.fcitx5.android.core.directBoot
import org.fcitx.fcitx5.android.core.dirsUnder
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.LEGACY_APPLICATION_ID_ROOT
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.layout.ThemeLayoutsToList
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
import java.util.zip.ZipInputStream

/** Old packs get the app's 分词 once per install or restore; never one imported later, and never a start lost to it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeSeparatorMigrationTest : ThemePackFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    private val v060 = File("../lib/keyboard-scene/src/commonTest/data/qwerty-060.xml").readText()

    private val realMarker = ThemeSeparators.separatorsFile
    private val realRestored = ThemeSeparators.restoredFile
    private val realTrying = ThemeSeparators.tryingFile
    private val marker get() = File(folder.root, "no_backup/theme-separator-added")
    private val restored get() = File(folder.root, "no_backup/theme-separator-restored")
    private val trying get() = File(folder.root, "no_backup/theme-separator-trying")

    @Before
    fun noBackup() {
        ThemeSeparators.separatorsFile = { marker }
        ThemeSeparators.restoredFile = { restored }
        ThemeSeparators.tryingFile = { trying }
    }

    @After
    fun realNoBackup() {
        ThemeSeparators.separatorsFile = realMarker
        ThemeSeparators.restoredFile = realRestored
        ThemeSeparators.tryingFile = realTrying
    }

    private fun old(name: String, qwerty: String = v060): File {
        placed(sample(sampleXml.replace("name=\"mine\"", "name=\"$name\""), "layouts/qwerty.xml" to qwerty))
        return File(packs, "$name/layouts/qwerty.xml")
    }

    private fun File.separated() = "id=\"apostrophe\"" in readText()

    private fun run() = ThemeSeparators.addSeparatorsOnce()

    @Test
    @Uat("ST-143")
    fun thePacksInstalledAreGivenItOnceAndOnesImportedAfterAreNot() {
        val file = old("mine")
        val themeXml = File(packs, "mine/theme.xml")
        val at = themeXml.lastModified()
        run()
        assertTrue(file.separated())
        assertTrue("when=\"!table !composing\"" in file.readText())
        // the pack's time kept: its list place and its layouts' cache key
        assertEquals(at, themeXml.lastModified())
        assertTrue(marker.isFile)
        // listed after the blank-key check of its new stamp
        val made = ThemeLayoutsToList.convert(file.parentFile!!, "mine", null).single()
        assertEquals("qwerty", made.first)
        assertTrue("when=\"!table !composing\"" in (made.second as PackLayouts.Converted.Done).text)
        File(packs, "mine/.checked").delete()
        assertEquals(listOf("mine#light", "mine#dark"), ThemeFilesManager.listPacks().map { it.name })
        // an import after: as written, though the start runs again
        val later = old("later")
        val bytes = later.readBytes()
        run()
        assertArrayEquals(bytes, later.readBytes())
        assertFalse(later.separated())
        val pack = ThemeFilesManager.listPacks().first { it.file == "mine" }
        val out = ByteArrayOutputStream()
        ThemeFilesManager.exportTheme(pack, out).getOrThrow()
        val exported = ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name.endsWith("layouts/qwerty.xml") }.let { zip.readBytes().decodeToString() }
        }
        assertEquals(file.readText(), exported)
    }

    @Test
    fun aNewInstallMarksItAtOnce() {
        run()
        assertTrue(marker.isFile)
        val later = old("later")
        run()
        assertFalse(later.separated())
    }

    // not before themes can be written: nothing, no marker; the next able start does it
    @Test
    fun notWhileTheThemesCannotBeWritten() {
        val file = old("mine")
        directBoot(true)
        HostConfigDir.forget()
        try {
            assertFalse(ThemeFilesManager.writable)
            run()
            assertFalse(marker.parentFile!!.exists())
        } finally {
            directBoot(false)
            phoneStorage()
        }
        assertFalse(file.separated())
        run()
        assertTrue(file.separated())
    }

    // a failing pack is left and the start goes on: the rest done, the marker written, no retry
    @Test
    fun aPackThatFailsIsLeftAndTheStartGoesOn() {
        val a = old("a")
        val b = old("b")
        val before = mapOf(a to a.readBytes(), b to b.readBytes())
        var calls = 0
        ThemePackFiles.beforeInPlace = { if (calls++ == 0) throw IllegalStateException("a test's") }
        run()
        val (failed, given) = listOf(a, b).partition { it.readBytes().contentEquals(before.getValue(it)) }
        assertEquals(1, failed.size)
        assertTrue(given.single().separated())
        assertTrue(marker.isFile)
        assertEquals(emptyList<String>(), File(packs, ".saving").list().orEmpty().toList())
    }

    private class Killed : Error()

    // killed midway: no marker; the next start does the rest and clears leftovers
    @Test
    fun aKillInTheMiddleIsFinishedAtTheNextStart() {
        val a = old("a")
        val b = old("b")
        var calls = 0
        ThemePackFiles.beforeInPlace = { if (calls++ == 1) throw Killed() }
        assertThrows(Killed::class.java) { run() }
        assertFalse(marker.exists())
        val done = listOf(a, b).single { it.separated() }
        val doneBytes = done.readBytes()
        ThemePackFiles.beforeInPlace = null
        File(packs, ".saving/sep-left").apply { parentFile!!.mkdirs() }.writeText("half")
        ThemeFilesManager.forgetSwept()
        run()
        assertTrue(a.separated() && b.separated())
        assertArrayEquals(doneBytes, done.readBytes())
        assertFalse(File(packs, ".saving/sep-left").exists())
        assertTrue(marker.isFile)
    }

    // non-UTF-8 is not written back (other bytes would change); a BOM is kept
    @Test
    fun notUtf8IsLeftAndAByteOrderMarkKept() {
        val gbk = old("gbk")
        val bytes = v060.replace("<!-- 码表时", "<!-- \u0001码表时").encodeToByteArray().let { b ->
            val i = b.indexOf(1)
            b.copyOfRange(0, i) + "分体".toByteArray(charset("GBK")) + b.copyOfRange(i + 1, b.size)
        }
        gbk.writeBytes(bytes)
        val bom = old("bom", "﻿" + v060)
        run()
        assertArrayEquals(bytes, gbk.readBytes())
        assertTrue(bom.readText().startsWith("﻿"))
        assertTrue(bom.separated())
    }

    @Test
    fun aFileTheAppDoesNotUseIsLeft() {
        placed(sample())
        val file = File(packs, "mine/layouts/qwerty.xml")
        run()
        assertEquals("<layout/>", file.readText())
        assertTrue(marker.isFile)
    }

    // packs a pre-0.6.1 backup restored get it at the next start, the phone's others not
    @Test
    @Uat("PR-63")
    fun thePacksRestoredFromABackupOfBeforeAreGivenItAtTheNextStart() {
        run()
        val phones = old("phones")
        val restoredPack = old("restored")
        ThemeSeparators.restoredBeforeSeparator(listOf("restored", "gone"))
        run()
        assertTrue(restoredPack.separated())
        assertFalse(phones.separated())
        assertFalse(restored.exists())
        val bytes = phones.readBytes()
        run()
        assertArrayEquals(bytes, phones.readBytes())
    }

    // a pre-0.6.1 backup records its packs for the next start, added to an earlier restore's
    @Test
    @Uat("PR-63")
    fun aRestoreOfABackupOfBefore061RecordsItsPacks() {
        fun packs(vararg names: String) = (names.map { "external/theme/v4/$it/theme.xml" to "<theme/>" } + ("external/theme/v4/.saving/x" to "x")).toTypedArray()
        fun install(name: String) = File(folder.root, name).let { dirsUnder(it, data = File(it, "files/host-data"), theme = File(it, "files/host-theme")) }
        UserDataManager.import(backupZip(*packs("p", "q"), versionCode = V060), install("v060")).getOrThrow()
        assertEquals(listOf("p", "q"), restored.readLines().sorted())
        UserDataManager.import(backupZip(*packs("q", "r"), versionCode = V060), install("v060b")).getOrThrow()
        assertEquals(listOf("p", "q", "r"), restored.readLines().sorted())
        restored.delete()
        for (code in V061) {
            UserDataManager.import(backupZip(*packs("p"), versionCode = code), install("v$code")).getOrThrow()
            assertFalse("$code", restored.exists())
        }
        // upstream's (its own codes, no packs): not looked at
        val upstream = LEGACY_APPLICATION_ID_ROOT + BuildConfig.APPLICATION_ID.removePrefix(BuildConfig.APPLICATION_ID_ROOT)
        UserDataManager.import(backupZip(*packs("p"), versionCode = 300L, packageName = upstream), install("upstream")).getOrThrow()
        assertFalse(restored.exists())
    }

    // restored, then a same-named pack imported before the next start: as written
    @Test
    fun aPackImportedOverOneAnOldBackupRestoredIsNotGivenIt() {
        run()
        val install = File(folder.root, "phone").let { dirsUnder(it, data = File(it, "files/host-data"), theme = File(it, "files/host-theme")) }
        UserDataManager.import(backupZip("external/theme/v4/mine/theme.xml" to "<theme/>", versionCode = V060), install).getOrThrow()
        assertEquals(listOf("mine"), restored.readLines())
        val file = old("mine")
        run()
        assertFalse(file.separated())
    }

    // an Error out of a start: the next does it once more, marker and list first, the one after nothing
    @Test
    fun anErrorThatEndsTheStartCostsOneStartMoreNotEvery() {
        old("a")
        old("b")
        ThemeSeparators.restoredBeforeSeparator(listOf("a"))
        var calls = 0
        ThemePackFiles.beforeInPlace = { calls++; throw Killed() }
        assertThrows(Killed::class.java) { run() }
        assertFalse(marker.exists())
        assertTrue(trying.exists())
        assertThrows(Killed::class.java) { run() }
        assertTrue(marker.isFile)
        assertFalse(restored.exists())
        run()
        assertEquals(2, calls)
        assertFalse(trying.exists())
        ThemeSeparators.restoredBeforeSeparator(listOf("a"))
        assertThrows(Killed::class.java) { run() }
        assertThrows(Killed::class.java) { run() }
        run()
        assertEquals(4, calls)
        assertFalse(trying.exists() || restored.exists())
    }

    // a caught Exception (marker not written) is no dead start: the next tries in full
    @Test
    fun anExceptionCaughtIsNotTakenForAStartThatDied() {
        val a = old("a")
        val blocked = File(folder.root, "blocked").apply { writeText("a file, not a directory") }
        ThemeSeparators.separatorsFile = { File(blocked, "theme-separator-added") }
        run()
        assertTrue(a.separated())
        assertFalse(trying.exists())
        ThemeSeparators.separatorsFile = { marker }
        val b = old("b")
        run()
        assertTrue(b.separated())
        assertTrue(marker.isFile)
        assertFalse(trying.exists())
    }

    // the BOM counted as the app reads it: a rewrite pushing it over the limit is left undone
    @Test
    fun overTheLimitWithItsByteOrderMarkIsLeft() {
        val probe = old("probe")
        val size = probe.length()
        run()
        val grows = probe.length() - size
        marker.delete()
        val limit = ThemeAssetLimits.MAX_LAYOUT_BYTES
        val padding = "<!--  -->\n".length
        // without the mark 65534 bytes once rewritten, under the limit; with it 65537, over
        val text = v060.replaceFirst("\n", "\n<!-- " + "x".repeat((limit - 2 - grows - size - padding).toInt()) + " -->\n")
        val big = old("big", "\uFEFF" + text)
        assertTrue(big.length() <= limit)
        assertEquals(limit - 2 + 3 - grows, big.length())
        val bytes = big.readBytes()
        run()
        assertArrayEquals(bytes, big.readBytes())
    }

    @Test
    fun aPackImportedOverOneRestoredIsNotGivenIt() {
        run()
        ThemeSeparators.restoredBeforeSeparator(listOf("mine", "other"))
        val file = old("mine")
        assertEquals(listOf("other"), restored.readLines())
        run()
        assertFalse(file.separated())
        ThemeSeparators.restoredBeforeSeparator(listOf("mine"))
        old("mine")
        assertFalse(restored.exists())
    }

    // an upgrade's first start gives every pack it, a prior restore's too
    @Test
    fun theFirstStartClearsTheList() {
        val file = old("mine")
        ThemeSeparators.restoredBeforeSeparator(listOf("mine"))
        run()
        assertTrue(file.separated())
        assertTrue(marker.isFile)
        assertFalse(restored.exists())
    }

    private companion object {
        // 0.6.0 for arm64, 0.6.1 for each ABI
        const val V060 = 5122L
        val V061 = listOf(5130L, 5132L, 5134L)
    }

    private fun ByteArray.indexOf(b: Int) = indices.first { this[it] == b.toByte() }
}
