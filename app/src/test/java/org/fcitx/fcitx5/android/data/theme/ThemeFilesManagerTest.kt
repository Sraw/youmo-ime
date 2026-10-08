/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * An imported theme's json names its files; a crafted one must not lead out of the theme dir,
 * nor plant a theme there whose image paths name other files.
 */
@RunWith(RobolectricTestRunner::class)
// the real application: the manager and its error messages go through appContext
@Config(application = FcitxApplication::class)
class ThemeFilesManagerTest {

    // the manager's own, made once for the class: each test's application has another external dir
    private val dir = ThemeFilesManager.newCustomBackgroundImages().second.parentFile!!

    @Before
    fun empty() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    private fun theme(name: String, cropped: String, src: String) =
        Json.encodeToString(CustomThemeSerializer, ThemePreset.TransparentDark.deriveCustomBackground(name, cropped, src))

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
        // its images are found as moved's are, so listing it would save it again, as ../y.json
        File(dir, "escape.json").writeText(theme("../y", "$old/y-cropped.png", "$old/y-src"))
        val themes = ThemeFilesManager.listThemes().associateBy { it.name }
        assertEquals(setOf("mine", "moved"), themes.keys)
        assertFalse(File(dir.parentFile, "y.json").exists())
        assertEquals(File(dir, "m-src").path, themes.getValue("mine").backgroundImage!!.srcFilePath)
        assertEquals(File(dir, "o-src").path, themes.getValue("moved").backgroundImage!!.srcFilePath)
    }
}
