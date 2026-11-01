/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.os.Environment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * An imported theme's json names its files; a crafted one must not lead out of the theme dir,
 * nor plant a theme there whose image paths name other files. A saved one names its images
 * only, found by name in the theme dir, wherever that dir was when it was saved.
 */
@RunWith(RobolectricTestRunner::class)
// the real application: the manager and its error messages go through appContext
@Config(application = FcitxApplication::class)
class ThemeFilesManagerTest {

    // the manager's own: each test's application has another
    private val dir get() = ThemeFilesManager.newCustomBackgroundImages().second.parentFile!!

    @Before
    fun empty() {
        phoneStorage()
        dir.deleteRecursively()
        dir.mkdirs()
    }

    private fun theme(name: String, cropped: String, src: String) =
        Json.encodeToString(CustomThemeSerializer, ThemePreset.TransparentDark.deriveCustomBackground(name, cropped, src))

    // an older version's json, which listing saves again as the current one
    private fun older(json: String) =
        JsonObject(Json.parseToJsonElement(json).jsonObject + ("version" to JsonPrimitive("2.0"))).toString()

    private fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
            }
        }
    }.toByteArray().inputStream()

    @Test
    fun aThemeNamedLikeAPathIsRefused() {
        val json = Json.encodeToString(CustomThemeSerializer, ThemePreset.TransparentDark.deriveCustomNoBackground("../x"))
        assertTrue(ThemeFilesManager.importTheme(zip("x.json" to json)).isFailure)
        assertFalse(File(dir.parentFile, "x.json").exists())
    }

    @Test
    fun anImageIsKeptByItsFileNameInTheThemeDir() {
        val imported = ThemeFilesManager.importTheme(
            zip("t.json" to theme("t", "../../t-cropped.png", "../../t-src"), "t-cropped.png" to "c", "t-src" to "s")
        ).getOrThrow().second.backgroundImage!!
        assertEquals(File(dir, "t-cropped.png").path, imported.croppedFilePath)
        assertEquals(File(dir, "t-src").path, imported.srcFilePath)
        assertEquals("s", File(dir, "t-src").readText())
        assertFalse(File(dir, "../../t-src").exists())
        assertEquals(listOf("t"), ThemeFilesManager.listThemes().map { it.name })
    }

    @Test
    fun anImageNamedLikeAThemeJsonIsRefused() {
        // each names the other as an image: whichever is read first, the other would be planted
        val crafted = zip(
            "a.json" to theme("a", "b.json", "a.png"),
            "b.json" to theme("b", "b.png", "a.json"),
            "a.png" to "",
            "b.png" to "",
        )
        assertTrue(ThemeFilesManager.importTheme(crafted).isFailure)
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.endsWith(".json") })
    }

    @Test
    fun aThemeIsTrustedOnlyWithItsImagesInTheThemeDir() {
        val secret = File(RuntimeEnvironment.getApplication().filesDir, "pinyin.user").apply { writeText("") }
        File(dir, "planted.json").writeText(theme("planted", secret.path, secret.path))
        for (image in listOf("m-cropped.png", "m-src", "o-cropped.png", "o-src", "y-cropped.png", "y-src")) {
            File(dir, image).writeText("")
        }
        File(dir, "mine.json").writeText(theme("mine", File(dir, "m-cropped.png").path, File(dir, "m-src").path))
        // a backup from the package id before the renaming: the images came along, the paths did not
        val old = "/storage/emulated/0/Android/data/org.fcitx.fcitx5.android/files/theme"
        File(dir, "moved.json").writeText(theme("moved", "$old/o-cropped.png", "$old/o-src"))
        // its images are found as moved's are, and as an older version's listing would save it again, as ../y.json
        File(dir, "escape.json").writeText(older(theme("../y", "$old/y-cropped.png", "$old/y-src")))
        val themes = ThemeFilesManager.listThemes().associateBy { it.name }
        assertEquals(setOf("mine", "moved"), themes.keys)
        assertFalse(File(dir.parentFile, "y.json").exists())
        assertEquals(File(dir, "m-src").path, themes.getValue("mine").backgroundImage!!.srcFilePath)
        assertEquals(File(dir, "o-src").path, themes.getValue("moved").backgroundImage!!.srcFilePath)
    }

    // as listThemes takes an image: a file of that name in dir
    private fun present(vararg names: String): (File) -> Boolean = { it.parentFile == dir && it.name in names }

    @Test
    fun anImageIsFoundByItsFileNameInTheThemeDir() {
        val old = "/storage/emulated/0/Android/data/org.fcitx.fcitx5.android/files/theme"
        val image = ThemePreset.TransparentDark.deriveCustomBackground("t", "$old/t-cropped.png", "$old/t-src")
            .backgroundImage!!
        val here = image.copy(croppedFilePath = File(dir, "t-cropped.png").path, srcFilePath = File(dir, "t-src").path)
        val both = present("t-cropped.png", "t-src")
        assertEquals(here, image.foundIn(dir, both))
        assertEquals(here, here.foundIn(dir, both))
        // as a json is saved now
        assertEquals(here, image.copy(croppedFilePath = "t-cropped.png", srcFilePath = "t-src").foundIn(dir, both))
        assertNull(image.foundIn(dir, present("t-cropped.png")))
        assertNull(image.foundIn(dir, present("t-src")))
    }

    @Test
    fun aThemeIsSavedWithItsImagesNamesAndListedWithTheirPathsInTheThemeDir() {
        val theme = ThemePreset.TransparentDark.deriveCustomBackground(
            "s", File(dir, "s-cropped.png").apply { writeText("") }.path, File(dir, "s-src").apply { writeText("") }.path
        )
        ThemeFilesManager.saveThemeFiles(theme)
        val saved = Json.decodeFromString(CustomThemeSerializer, File(dir, "s.json").readText()).backgroundImage!!
        assertEquals("s-cropped.png" to "s-src", saved.croppedFilePath to saved.srcFilePath)
        assertEquals(listOf(theme), ThemeFilesManager.listThemes())
    }

    @Test
    fun aThemeWhoseImagesMovedIsListedWithoutBeingWrittenAgain() {
        File(dir, "o-cropped.png").writeText("")
        File(dir, "o-src").writeText("")
        val old = "/storage/emulated/0/Android/data/org.fcitx.fcitx5.android/files/theme"
        val json = theme("moved", "$old/o-cropped.png", "$old/o-src")
        File(dir, "moved.json").writeText(json)
        repeat(2) {
            assertEquals(File(dir, "o-src").path, ThemeFilesManager.listThemes().single().backgroundImage!!.srcFilePath)
        }
        assertEquals(json, File(dir, "moved.json").readText())
    }

    @Test
    fun themesSavedAgainByAMigrationKeepTheirOrder() {
        for ((name, modified) in listOf("a" to 1_000_000_000_000, "b" to 2_000_000_000_000)) {
            val json = Json.encodeToString(CustomThemeSerializer, ThemePreset.TransparentDark.deriveCustomNoBackground(name))
            File(dir, "$name.json").apply { writeText(older(json)) }.setLastModified(modified)
        }
        assertEquals(listOf("b", "a"), ThemeFilesManager.listThemes().map { it.name })
        assertTrue("saved again", File(dir, "a.json").readText().contains("\"version\":\"2.1\""))
        assertEquals(listOf("b", "a"), ThemeFilesManager.listThemes().map { it.name })
        assertEquals(1_000_000_000_000, File(dir, "a.json").lastModified())
    }

    @Test
    fun aThemeWithAnImageRefusedLeavesNoFileBehind() {
        // the src image is in the zip; the cropped one is a path, or not in it
        for (cropped in listOf("..", "t-cropped.png")) {
            val crafted = zip("t.json" to theme("t", cropped, "t-src"), "t-src" to "s")
            assertTrue(cropped, ThemeFilesManager.importTheme(crafted).isFailure)
            assertEquals(cropped, emptyList<String>(), dir.list()!!.toList())
        }
    }

    // A4: an upgraded install started without its external storage, the themes not moved yet: nothing
    // saved, deleted or imported, and a theme an older version wrote is listed but not saved again
    @Test
    @Uat("PR-42")
    fun themesNotSettledAreNotWritten() {
        val external = appContext.getExternalFilesDir(null)!!
        dir.deleteRecursively()
        File(appContext.filesDir, "engine").mkdirs()
        ShadowEnvironment.setExternalStorageState(external, Environment.MEDIA_REMOVED)
        HostConfigDir.forget()
        try {
            assertFalse(ThemeFilesManager.writable)
            val theme = ThemePreset.TransparentDark.deriveCustomNoBackground("n")
            val notSettled = appContext.getString(R.string.user_data_not_settled)
            assertEquals(notSettled, assertThrows(IOException::class.java) { ThemeFilesManager.saveThemeFiles(theme) }.message)
            assertEquals(notSettled, assertThrows(IOException::class.java) { ThemeFilesManager.deleteThemeFiles(theme) }.message)
            // the list page lists, replaces or removes a theme by these: refused, no ghost theme, list unchanged
            val before = ThemeManager.getAllThemes().map { it.name }
            assertFalse(ThemeManager.saveTheme(theme))
            assertFalse(ThemeManager.deleteTheme(theme.name))
            assertEquals(before, ThemeManager.getAllThemes().map { it.name })
            assertTrue(ThemeFilesManager.importTheme(zip("t.json" to Json.encodeToString(CustomThemeSerializer, theme))).isFailure)
            val json = older(Json.encodeToString(CustomThemeSerializer, ThemePreset.TransparentDark.deriveCustomNoBackground("o")))
            val file = File(HostConfigDir.inUse(ConfigHome.THEME).dir.apply { mkdirs() }, "o.json").apply { writeText(json) }
            assertEquals(listOf("o"), ThemeFilesManager.listThemes().map { it.name })
            assertEquals(json, file.readText())
        } finally {
            File(appContext.filesDir, "engine").deleteRecursively()
            phoneStorage()
        }
    }
}
