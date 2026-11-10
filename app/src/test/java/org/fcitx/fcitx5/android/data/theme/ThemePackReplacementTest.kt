/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 060-render-p3 T10a: a pack's import replacing a 3.0 theme of its name when the disk does not do as asked (a record
 * not written again, a json not deleted), and the first sweep of `.saving` while an import writes there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemePackReplacementTest {

    private val dir get() = ThemeFilesManager.themeDir
    private val packs get() = File(dir, ThemePackFiles.FOLDER)

    @Before
    fun empty() {
        phoneStorage()
        dir.deleteRecursively()
        dir.mkdirs()
        ThemeFilesManager.replacingFile().delete()
        ThemeFilesManager.listThemes()
        ThemeManager.refreshThemes()
    }

    @After
    fun noneLeft() {
        ThemePackFiles.beforeInPlace = null
        dir.setWritable(true)
        dir.deleteRecursively()
        ThemeFilesManager.replacingFile().delete()
        ThemeManager.refreshThemes()
    }

    // the built-in nord, of two versions, as a pack of the user's named "mine"
    private fun opened(): ThemeFilesManager.Opened.Pack {
        val xml = BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_nord)
            .replace("name=\"nord\"", "name=\"mine\"")
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry(ThemePackFiles.THEME_XML)); it.write(xml.encodeToByteArray()) }
        }.toByteArray()
        return ThemeFilesManager.openTheme(zip.inputStream()).getOrThrow() as ThemeFilesManager.Opened.Pack
    }

    private fun mine() {
        ThemeFilesManager.saveThemeFiles(TestThemes.TransparentDark.deriveCustomNoBackground("mine"))
        ThemeManager.refreshThemes()
    }

    // the record not written again (a full disk) as one replacement finishes and another is left: not thrown (at the
    // app's start that crashed every start), the record kept for the next listing
    @Test
    fun aRecordNotWrittenAgainIsLeftForTheNextListing() {
        mine()
        ThemeFilesManager.replacingFile().writeText("gone\nmine\n")
        val blocked = File(ThemeFilesManager.replacingFile().path + ".new").apply { mkdirs(); resolve("x").writeText("") }
        try {
            ThemeManager.refreshThemes()
            assertEquals(setOf("gone", "mine"), ThemeFilesManager.replacing())
        } finally {
            blocked.deleteRecursively()
        }
    }

    // the replaced theme's json not deleted: its record kept and tried again, not dropped with the theme left beside
    // the pack for good
    @Test
    fun aReplacedThemeNotDeletedIsTriedAgain() {
        mine()
        opened().commit().getOrThrow()
        try {
            // root deletes anyway: a skip there (F4987), not a failure
            assumeTrue("a directory not writable (not as root)", dir.setWritable(false) && !dir.canWrite())
            ThemeManager.refreshThemes()
            assertTrue(File(dir, "mine.json").exists())
            assertEquals(setOf("mine"), ThemeFilesManager.replacing())
        } finally {
            dir.setWritable(true)
        }
        ThemeManager.refreshThemes()
        assertFalse(File(dir, "mine.json").exists())
        assertEquals(emptySet<String>(), ThemeFilesManager.replacing())
    }

    // the user's delete whose json is not deleted: told as not done, still listed (not gone till the next start)
    @Test
    fun aDeleteThatDidNotDeleteKeepsTheThemeListed() {
        mine()
        try {
            assumeTrue("a directory not writable (not as root)", dir.setWritable(false) && !dir.canWrite())
            assertFalse(ThemeManager.deleteTheme("mine"))
            assertTrue(ThemeManager.getAllThemes().any { it is Theme.Custom && it.name == "mine" })
        } finally {
            dir.setWritable(true)
        }
    }

    // a record that does not read is not taken as empty: kept as it is for the next listing, not thrown
    @Test
    fun aRecordThatDoesNotReadIsKept() {
        mine()
        val record = ThemeFilesManager.replacingFile().apply { delete(); mkdirs() }
        try {
            ThemeManager.refreshThemes()
            assertTrue(record.isDirectory)
            assertTrue(File(dir, "mine.json").exists())
        } finally {
            record.deleteRecursively()
        }
    }

    // Q1: a process's first sweep while an import writes its pack in .saving (a start not settled, then a listing):
    // not swept under it, swept at a listing once it is done
    @Test
    fun aFirstSweepWhileAPackIsWrittenLeavesItBe() {
        val pack = opened()
        File(packs, ".saving/left-by-a-kill").mkdirs()
        ThemePackFiles.beforeInPlace = {
            ThemeFilesManager.forgetSwept()
            ThemeManager.refreshThemes()
        }
        assertTrue(pack.commit().isSuccess)
        ThemePackFiles.beforeInPlace = null
        assertTrue(File(packs, ".saving/left-by-a-kill").exists())
        ThemeFilesManager.listPacks()
        assertFalse(File(packs, ".saving").exists())
        assertEquals(listOf("mine#light", "mine#dark"), ThemeFilesManager.listPacks().map { it.name })
    }
}
