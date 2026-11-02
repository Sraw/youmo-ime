/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.os.Environment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.input.theme.Insets
import org.fcitx.fcitx5.android.input.theme.KeyFill
import org.fcitx.fcitx5.android.input.theme.KeyLabel
import org.fcitx.fcitx5.android.input.theme.KeyOverride
import org.fcitx.fcitx5.android.input.theme.KeyPressed
import org.fcitx.fcitx5.android.input.theme.LegacyLook
import org.fcitx.fcitx5.android.input.theme.NineSlice
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
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
        ThemeFilesManager.probe = FakeProbe
    }

    @After
    fun realProbe() {
        ThemeFilesManager.probe = real
    }

    // an image is a file of "<w>x<h>", a font one of "font": what an import reads of them, off Android
    private object FakeProbe : ThemeFilesManager.AssetProbe {
        override fun imageSize(file: File) = file.readText().split('x').takeIf { it.size == 2 }
            ?.let { (w, h) -> w.toIntOrNull()?.let { a -> h.toIntOrNull()?.let { b -> a to b } } }

        override fun fontReads(file: File) = file.readText() == "font"
    }

    private val real = ThemeFilesManager.probe

    private fun theme(name: String, cropped: String, src: String) =
        Json.encodeToString(CustomThemeSerializer, ThemePreset.TransparentDark.deriveCustomBackground(name, cropped, src))

    // the 2.1 json of [json]'s theme (its colours and picture, the keys the settings'), as the app
    // before the look wrote it: which listing saves again as the current one
    private fun older(json: String): String {
        val theme = Json.decodeFromString(CustomThemeSerializer, json)
        val colours = Json.parseToJsonElement(Json.encodeToString(CustomThemeSerializer, theme)).jsonObject
            .getValue("palette").jsonObject
        return buildJsonObject {
            put("version", "2.1")
            put("name", theme.name)
            put("isDark", theme.isDark)
            val bg = theme.backgroundImage
            if (bg == null) put("backgroundImage", JsonNull) else putJsonObject("backgroundImage") {
                put("croppedFilePath", bg.croppedFilePath)
                put("srcFilePath", bg.srcFilePath)
                put("brightness", bg.brightness)
                put("cropRect", JsonNull)
                put("cropRotation", bg.cropRotation)
            }
            colours.forEach { (k, v) -> if (k !in DERIVED) put(k, v) }
        }.toString()
    }

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
            zip("t.json" to theme("t", "../../t-cropped.png", "../../t-src"), "t-cropped.png" to "1080x720", "t-src" to "s")
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
        val here = ThemePreset.TransparentDark.deriveCustomBackground("t", File(dir, "t-cropped.png").path, File(dir, "t-src").path)
        val both = present("t-cropped.png", "t-src")
        assertEquals(here, image.foundIn(dir, both))
        assertEquals(here, here.foundIn(dir, both))
        // as a json is saved now
        assertEquals(here, ThemePreset.TransparentDark.deriveCustomBackground("t", "t-cropped.png", "t-src").foundIn(dir, both))
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
        assertTrue("saved again", File(dir, "a.json").readText().contains("\"version\":\"3.0\""))
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
    // a 3.0 theme with a picture, two key images (one of them twice) and a font
    private fun full(name: String): Theme.Custom {
        val base = ThemePreset.TransparentDark.deriveCustomBackground(name, "$name-cropped.png", "$name-src")
        val slice = NineSlice("$name-q.png", Insets(4, 4, 4, 4), 2)
        val look = base.look.copy(
            keys = base.look.keys.copy(
                function = base.look.keys.function.copy(pressed = KeyPressed.Image(NineSlice("$name-fn.png", Insets(0, 0, 0, 0), 1))),
                overrides = mapOf(
                    "qwerty/q" to KeyOverride(KeyFill.Image(slice), null, KeyLabel.Hide),
                    "qwerty/w" to KeyOverride(KeyFill.Image(slice), null, KeyLabel.Show),
                    // a newer app's board: kept, drawn by none here
                    "k20/r1c1" to KeyOverride(null, KeyPressed.Color(5), KeyLabel.Show),
                ),
            ),
            text = base.look.text.copy(keyFont = "$name-font.ttf"),
        )
        return base.copy(look = look)
    }

    private fun files(name: String) = arrayOf(
        "$name-cropped.png" to "1080x720", "$name-src" to "photo", "$name-q.png" to "64x64",
        "$name-fn.png" to "32x32", "$name-font.ttf" to "font",
    )

    private fun json(theme: Theme.Custom) = Json.encodeToString(CustomThemeSerializer, theme)

    @Test
    fun aThemeOfManyFilesIsImportedExportedAndDeletedWhole() {
        val theme = full("f")
        val imported = ThemeFilesManager.importTheme(zip("f.json" to json(theme), *files("f"))).getOrThrow().second
        assertEquals(setOf("f.json") + files("f").map { it.first }, dir.list()!!.toSet())
        assertEquals(theme.look.mapFiles { File(dir, it).path }, imported.look)
        assertEquals(theme.look.keys.overrides.keys, imported.look.keys.overrides.keys)
        assertEquals(listOf(imported), ThemeFilesManager.listThemes())
        // out again: each file once, by name, in the order the json names them (picture, kinds, overrides, fonts), then the json
        val out = ByteArrayOutputStream()
        ThemeFilesManager.exportTheme(imported, out).getOrThrow()
        val entries = java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z ->
            generateSequence { z.nextEntry?.name }.toList()
        }
        assertEquals(listOf("f-src", "f-cropped.png", "f-fn.png", "f-q.png", "f-font.ttf", "f.json"), entries)
        ThemeFilesManager.deleteThemeFiles(imported)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun aThemeWithAFileMissingIsRefusedAndNotListed() {
        val theme = full("m")
        val crafted = zip("m.json" to json(theme), *files("m").filter { it.first != "m-fn.png" }.toTypedArray())
        val e = ThemeFilesManager.importTheme(crafted).exceptionOrNull()!!
        assertEquals(appContext.getString(R.string.exception_theme_file, "m-fn.png"), e.message)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        // one saved, a file then lost: not listed (it would draw a key with nothing)
        ThemeFilesManager.importTheme(zip("m.json" to json(theme), *files("m"))).getOrThrow()
        File(dir, "m-q.png").delete()
        assertEquals(emptyList<Theme.Custom>(), ThemeFilesManager.listThemes())
    }

    private fun refusal(theme: Theme.Custom, vararg entries: Pair<String, String>) =
        ThemeFilesManager.importTheme(zip("t.json" to json(theme), *entries)).exceptionOrNull()?.message

    @Test
    fun filesPastTheLimitsAreRefused() {
        val theme = full("t")
        fun with(name: String, text: String) = files("t").map { if (it.first == name) name to text else it }.toTypedArray()
        assertEquals(appContext.getString(R.string.exception_theme_picture_side), refusal(theme, *with("t-cropped.png", "4097x100")))
        assertEquals(appContext.getString(R.string.exception_theme_key_image_side), refusal(theme, *with("t-q.png", "1025x10")))
        assertEquals(appContext.getString(R.string.exception_theme_image, "t-fn.png"), refusal(theme, *with("t-fn.png", "not an image")))
        assertEquals(appContext.getString(R.string.exception_theme_font, "t-font.ttf"), refusal(theme, *with("t-font.ttf", "not a font")))
        // the photo the cut was made from is no limit: a phone's is often past 4096
        assertNull(refusal(theme, *with("t-src", "8000x6000")))
        dir.listFiles()!!.forEach { it.delete() }
        val many = theme.look.keys.overrides + (1..400).associate { "qwerty/k$it" to KeyOverride(null, null, KeyLabel.Hide) }
        val tooMany = theme.copy(look = theme.look.copy(keys = theme.look.keys.copy(overrides = many)))
        assertEquals(appContext.getString(R.string.exception_theme_overrides), refusal(tooMany, *files("t")))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun aZipOfTooManyEntriesIsRefusedAsItIsUnpacked() {
        val theme = ThemePreset.TransparentDark.deriveCustomNoBackground("z")
        val entries = (1..300).map { "e$it" to "" }.toTypedArray()
        assertEquals(appContext.getString(R.string.exception_theme_entries), refusal(theme, *entries))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun anImportOverItsOwnThemeDropsTheFilesItNoLongerNames() {
        ThemeFilesManager.importTheme(zip("r.json" to json(full("r")), *files("r"))).getOrThrow()
        ThemeManager.refreshThemes()
        try {
            val plain = ThemePreset.TransparentDark.deriveCustomNoBackground("r")
            assertFalse(ThemeFilesManager.importTheme(zip("r.json" to json(plain))).getOrThrow().first)
            assertEquals(listOf("r.json"), dir.list()!!.toList())
        } finally {
            dir.listFiles()!!.forEach { it.delete() }
            ThemeManager.refreshThemes()
        }
    }

    @Test
    fun theOldKeySettingsGoIntoAnOlderThemeOnly() {
        val legacy = LegacyLook(keyBorder = false, keyRadius = 0)
        File(dir, "old.json").writeText(older(json(ThemePreset.TransparentDark.deriveCustomNoBackground("old"))))
        File(dir, "new.json").writeText(json(ThemePreset.TransparentDark.deriveCustomNoBackground("new")))
        val themes = ThemeFilesManager.listThemes(legacy).associateBy { it.name }
        assertEquals(legacy.applyTo(themes.getValue("new").look), themes.getValue("old").look)
        assertTrue(themes.getValue("new").look.keys.blocks)
        // saved with them: read again, without the settings, the same
        assertEquals(themes.getValue("old"), ThemeFilesManager.listThemes().single { it.name == "old" })
    }

    companion object {
        // the colours 3.0 added, from others, which 2.1 did not have
        private val DERIVED = setOf("keyBorderColor", "keyHintColor", "toolbarIconColor")
    }
}
