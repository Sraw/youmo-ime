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
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
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

/**
 * FB12 (dev/design/061-theme-separator-migration.md §3): the packs installed before 0.6.1 given the app's 分词 in their
 * qwerty's Shift's place, once an install; those of a backup of before 0.6.1 at the start after its restore; never one
 * imported after, and never a start lost to it.
 */
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

    // a pack of [name] with 0.6.0's qwerty, as one imported then (or [qwerty]'s bytes)
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
        // the pack's time kept: its place in the list, and its layouts' cache key
        assertEquals(at, themeXml.lastModified())
        assertTrue(marker.isFile)
        // as the app takes it: drawn, and listed after the blank-key check of its new stamp
        val report = ThemePackLayouts.report(file.parentFile!!, listOf("qwerty.xml"), { SceneLayouts.parseBuiltIn(appContext, it) }, BuiltInLayouts(appContext)::text)
        assertEquals(listOf("qwerty"), report.used)
        File(packs, "mine/.checked").delete()
        assertEquals(listOf("mine#light", "mine#dark"), ThemeFilesManager.listPacks().map { it.name })
        // an import after: as it is written, though the start runs it again
        val later = old("later")
        val bytes = later.readBytes()
        run()
        assertArrayEquals(bytes, later.readBytes())
        assertFalse(later.separated())
        // exported, the file as it is now
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

    // A4: not before the themes can be written (the phone not unlocked yet, the storage away): nothing, no marker, the next
    // start that can does it
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

    // a pack that fails is left as it was and the start goes on: the rest given it, the marker written, no retry
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

    // killed in the middle: no marker, the next start gives the rest it, the one done not again, what was left cleared
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

    // what is not UTF-8 is not written back (its other bytes would change); a byte-order mark kept
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

    // a file the app does not draw: as it is
    @Test
    fun aFileTheAppDoesNotUseIsLeft() {
        placed(sample())
        val file = File(packs, "mine/layouts/qwerty.xml")
        run()
        assertEquals("<layout/>", file.readText())
        assertTrue(marker.isFile)
    }

    // PR-63: the packs a backup of before 0.6.1 restored given it at the next start, the phone's others not
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
        // the next start: nothing more
        val bytes = phones.readBytes()
        run()
        assertArrayEquals(bytes, phones.readBytes())
    }

    // PR-63: a backup of before 0.6.1 (its code below 0.6.1's lowest, 5130) records its packs for the next start, added to
    // what an earlier restore without a start between recorded; one of 0.6.1, any ABI, or after does not
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
        // upstream's (fcitx5-android's, its own codes; it has no packs of these): not looked at
        val upstream = LEGACY_APPLICATION_ID_ROOT + BuildConfig.APPLICATION_ID.removePrefix(BuildConfig.APPLICATION_ID_ROOT)
        UserDataManager.import(backupZip(*packs("p"), versionCode = 300L, packageName = upstream), install("upstream")).getOrThrow()
        assertFalse(restored.exists())
    }

    // restored from a backup of 0.6.0, then a pack of the same name imported before the next start: as it is written
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

    // an Error out of a start (a StackOverflowError, an OOM, not caught): the next does it once more, marker and list
    // first, and the one after that nothing; never a start lost to it each time
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
        // the list path as well: once more, not again
        ThemeSeparators.restoredBeforeSeparator(listOf("a"))
        assertThrows(Killed::class.java) { run() }
        assertThrows(Killed::class.java) { run() }
        run()
        assertEquals(4, calls)
        assertFalse(trying.exists() || restored.exists())
    }

    // an Exception caught (the marker not written: a full disk, say) is not a start that died: the next tries in full,
    // not as the one more after a crash
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

    // a byte-order mark counted as the app reads it (ThemePackLayouts.read: the file's length): a file the rewrite puts a
    // few bytes over with it is left, as it would no longer be drawn
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

    // a pack of a name on the list imported before the next start (the restored one deleted, or replaced): as written
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

    // an upgrade's first start gives every pack it, those of a restore before it too: the list gone with it
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
        // Versions.calculateVersionCode: 0.6.0 for arm64, 0.6.1 for each ABI
        const val V060 = 5122L
        val V061 = listOf(5130L, 5132L, 5134L)
    }

    private fun ByteArray.indexOf(b: Int) = indices.first { this[it] == b.toByte() }
}
