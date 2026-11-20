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

/** A pack import replacing a 3.0 theme when the disk misbehaves, and the first `.saving` sweep while an import writes. */
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

    // the built-in two-version nord, as a user pack named "mine"
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

    // the record not rewritten (full disk): not thrown (it crashed every start), kept for the next listing
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

    // the replaced json not deleted: its record kept and retried, not dropped with the theme left for good
    @Test
    fun aReplacedThemeNotDeletedIsTriedAgain() {
        mine()
        opened().commit().getOrThrow()
        try {
            // root deletes anyway: a skip there, not a failure
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

    // a delete whose json stays: told as not done, still listed
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

    // an unreadable record: ignored when replacing nothing, set aside when replacing a theme: no import blocked for good
    @Test
    fun aRecordThatDoesNotReadIsSetAsideNotWrittenOver() {
        val record = ThemeFilesManager.replacingFile().apply { writeText("old\n") }
        val bad = File(record.path + ".bad").apply { delete() }
        try {
            // root reads anyway: a skip there, not a failure
            assumeTrue("a file not readable (not as root)", record.setReadable(false) && !record.canRead())
            assertTrue(opened().commit().isSuccess)
            assertFalse(bad.exists())
            ThemeManager.refreshThemes()
            mine()
            assertTrue(opened().commit().isSuccess)
            assertTrue(bad.exists())
            assertEquals(setOf("mine"), ThemeFilesManager.replacing())
        } finally {
            record.setReadable(true)
            bad.setReadable(true)
            bad.delete()
        }
    }

    // a first sweep during an import into .saving does not sweep it; a listing does once it is done
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
