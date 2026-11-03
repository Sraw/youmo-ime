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
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
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
    private val dir get() = ThemeFilesManager.themeDir

    @Before
    fun empty() {
        phoneStorage()
        dir.deleteRecursively()
        dir.mkdirs()
        ThemeFilesManager.probe = FakeProbe
        FakeProbe.asked.clear()
        FakeProbe.beside.clear()
        // the process's first listing (the app's start) done: a test's own .saving is not cleared under it
        ThemeFilesManager.listThemes()
    }

    @After
    fun realProbe() {
        ThemeFilesManager.probe = real
    }

    // an image is a file of "<w>x<h>" or a real PNG (its header's), a font one of "font": what an import
    // reads of them, off Android; the images it was asked about, by name
    private object FakeProbe : ThemeFilesManager.AssetProbe {
        val asked = mutableListOf<String>()

        // what was beside each image as it was read: the import's unpacked zip
        val beside = mutableSetOf<String>()

        private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

        override fun imageSize(file: File): Pair<Int, Int>? {
            asked += file.name
            beside += file.parentFile!!.list()!!
            val bytes = file.readBytes()
            if (bytes.size >= 24 && bytes.copyOf(8).contentEquals(png)) {
                return ByteBuffer.wrap(bytes).let { it.getInt(16) to it.getInt(20) }
            }
            return bytes.decodeToString().split('x').takeIf { it.size == 2 }
                ?.let { (w, h) -> w.toIntOrNull()?.let { a -> h.toIntOrNull()?.let { b -> a to b } } }
        }

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
                if (!name.endsWith("/")) zip.write(text.encodeToByteArray())
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
        assertEquals(emptyList<String>(), names().filter { it.endsWith(".json") })
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
            assertEquals(cropped, emptyList<String>(), names())
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
        assertEquals(setOf("f.json") + files("f").map { it.first }, names().toSet())
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
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun aThemeWithAFileMissingIsRefusedAndNotListed() {
        val theme = full("m")
        val crafted = zip("m.json" to json(theme), *files("m").filter { it.first != "m-fn.png" }.toTypedArray())
        val e = ThemeFilesManager.importTheme(crafted).exceptionOrNull()!!
        assertEquals(appContext.getString(R.string.exception_theme_file, "m-fn.png"), e.message)
        assertEquals(emptyList<String>(), names())
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
        assertEquals(appContext.getString(R.string.exception_theme_picture_side, "4096"), refusal(theme, *with("t-cropped.png", "4097x100")))
        assertEquals(appContext.getString(R.string.exception_theme_key_image_side, "1024"), refusal(theme, *with("t-q.png", "1025x10")))
        assertEquals(appContext.getString(R.string.exception_theme_image, "t-fn.png"), refusal(theme, *with("t-fn.png", "not an image")))
        assertEquals(appContext.getString(R.string.exception_theme_font, "t-font.ttf"), refusal(theme, *with("t-font.ttf", "not a font")))
        // the photo the cut was made from is no limit: a phone's is often past 4096
        assertNull(refusal(theme, *with("t-src", "8000x6000")))
        dir.listFiles()!!.forEach { it.delete() }
        val many = theme.look.keys.overrides + (1..400).associate { "qwerty/k$it" to KeyOverride(null, null, KeyLabel.Hide) }
        val tooMany = theme.copy(look = theme.look.copy(keys = theme.look.keys.copy(overrides = many)))
        assertEquals(appContext.getString(R.string.exception_theme_overrides, "400"), refusal(tooMany, *files("t")))
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun aZipOfTooManyEntriesIsRefusedAsItIsUnpacked() {
        val theme = ThemePreset.TransparentDark.deriveCustomNoBackground("z")
        val entries = (1..300).map { "e$it" to "" }.toTypedArray()
        assertEquals(appContext.getString(R.string.exception_theme_entries, "300"), refusal(theme, *entries))
        assertEquals(emptyList<String>(), names())
        // a directory is an entry of the count too: else empty ones would pass any number
        val dirs = (1..300).map { "d$it/" to "" }.toTypedArray()
        assertEquals(appContext.getString(R.string.exception_theme_entries, "300"), refusal(theme, *dirs))
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun anImportOverItsOwnThemeDropsTheFilesItNoLongerNames() {
        ThemeFilesManager.importTheme(zip("r.json" to json(full("r")), *files("r"))).getOrThrow()
        ThemeManager.refreshThemes()
        try {
            val plain = ThemePreset.TransparentDark.deriveCustomNoBackground("r")
            assertFalse(ThemeFilesManager.importTheme(zip("r.json" to json(plain))).getOrThrow().first)
            assertEquals(listOf("r.json"), names())
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

    // the theme dir's files, not where they are written before they are put in place (kept, empty)
    private fun names() = dir.list()!!.filter { it != ".saving" }

    // what the theme dir holds, each file's bytes by name: to see a failure left it as it was
    private fun snapshot() = dir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readText() }

    // a plain file where the themes' files are written before they are put in place: every write fails
    private fun blockWrites() = File(dir, ".saving").apply { deleteRecursively(); writeText("") }

    @Test
    fun aThemeNotWrittenLeavesItsFileAsItWas() {
        val theme = ThemePreset.TransparentDark.deriveCustomNoBackground("w")
        ThemeFilesManager.saveThemeFiles(theme)
        val before = File(dir, "w.json").readText()
        blockWrites()
        val changed = theme.copy(isDark = !theme.isDark)
        assertThrows(IOException::class.java) { ThemeFilesManager.saveThemeFiles(changed) }
        assertEquals(before, File(dir, "w.json").readText())
        // the list page's save: refused and told in the user's words (no path of the app's), not a crash
        assertFalse(ThemeManager.saveTheme(changed))
        assertEquals(appContext.getString(R.string.theme_not_saved), ShadowToast.getTextOfLatestToast())
        assertEquals(before, File(dir, "w.json").readText())
        File(dir, ".saving").delete()
        ThemeFilesManager.saveThemeFiles(changed)
        assertEquals(changed, ThemeFilesManager.listThemes().single())
        // nothing left beside the themes once it is in place
        assertEquals(emptyList<String>(), File(dir, ".saving").list()!!.toList())
    }

    @Test
    fun aMigrationThatCannotBeSavedIsListedAndSavedAtTheNextStart() {
        val legacy = LegacyLook(keyBorder = false, keyRadius = 0)
        val old = older(json(ThemePreset.TransparentDark.deriveCustomNoBackground("m")))
        val file = File(dir, "m.json").apply { writeText(old) }
        file.setLastModified(1_000_000_000_000)
        blockWrites()
        // as the app starts: no exception out of the theme list, the theme drawn as migrated
        val listed = ThemeFilesManager.listThemes(legacy).single()
        assertEquals(legacy.applyTo(ThemePreset.TransparentDark.deriveCustomNoBackground("m").look), listed.look)
        assertEquals(old, file.readText())
        File(dir, ".saving").delete()
        assertEquals(listed, ThemeFilesManager.listThemes(legacy).single())
        assertTrue("saved again", file.readText().startsWith("{\"version\":\"3.0\""))
        assertEquals(1_000_000_000_000, file.lastModified())
    }

    @Test
    fun aThemeOfALaterFormatIsSkippedAndLeftAsItIs() {
        val later = json(ThemePreset.TransparentDark.deriveCustomNoBackground("l")).replace("\"version\":\"3.0\"", "\"version\":\"3.1\"")
        File(dir, "l.json").writeText(later)
        File(dir, "n.json").writeText(json(ThemePreset.TransparentDark.deriveCustomNoBackground("n")))
        assertEquals(listOf("n"), ThemeFilesManager.listThemes(LegacyLook(keyBorder = false)).map { it.name })
        assertEquals(later, File(dir, "l.json").readText())
        // imported: said in the user's words, not the log's
        val e = ThemeFilesManager.importTheme(zip("l.json" to later)).exceptionOrNull()!!
        assertEquals(appContext.getString(R.string.exception_theme_version, "3.1"), e.localizedMessage)
    }

    @Test
    fun anImportCutShortLeavesTheThemeItWouldReplaceWhole() {
        ThemeFilesManager.importTheme(zip("r.json" to json(full("r")), *files("r"))).getOrThrow()
        ThemeManager.refreshThemes()
        try {
            val before = snapshot()
            // new files, and one of the old theme's given new bytes
            val next = full("r").let { t ->
                t.copy(look = t.look.copy(text = t.look.text.copy(keyFont = "r-other.ttf")))
            }
            val entries = arrayOf("r.json" to json(next), *files("r").map { if (it.first == "r-q.png") it.first to "48x48" else it }.toTypedArray(), "r-other.ttf" to "font")
            // nothing can be copied
            blockWrites()
            assertTrue(ThemeFilesManager.importTheme(zip(*entries)).isFailure)
            File(dir, ".saving").delete()
            assertEquals(before, snapshot())
            // the files copied, the json not: the files only the new one named are gone, the old theme's kept
            File(dir, "r.json").delete()
            File(dir, "r.json").mkdirs()
            File(dir, "r.json/in-the-way").writeText("")
            assertTrue(ThemeFilesManager.importTheme(zip(*entries)).isFailure)
            File(dir, "r.json").deleteRecursively()
            assertEquals((before - "r.json").keys, snapshot().keys)
            assertEquals(emptyList<String>(), File(dir, ".saving").list()!!.toList())
        } finally {
            dir.listFiles()!!.forEach { it.deleteRecursively() }
            ThemeManager.refreshThemes()
        }
    }

    @Test
    fun aFileNoThemeNamesIsTakenOverAndAnotherThemesIsAClash() {
        ThemeFilesManager.importTheme(zip("a.json" to json(full("a")), *files("a"))).getOrThrow()
        ThemeManager.refreshThemes()
        try {
            val before = snapshot()
            // b names a's key image
            val b = full("b").let { t -> t.copy(look = t.look.mapFiles { if (it == "b-q.png") "a-q.png" else it }) }
            val bFiles = files("b").map { if (it.first == "b-q.png") "a-q.png" to "16x16" else it }.toTypedArray()
            val e = ThemeFilesManager.importTheme(zip("b.json" to json(b), *bFiles)).exceptionOrNull()!!
            assertEquals(appContext.getString(R.string.exception_theme_file_clash, "a-q.png"), e.message)
            assertEquals(before, snapshot())
            // left by an import cut short, or a 3.0 theme's after an older backup put back its json: no theme names it
            File(dir, "c-q.png").writeText("orphan")
            ThemeFilesManager.importTheme(zip("c.json" to json(full("c")), *files("c"))).getOrThrow()
            assertEquals("64x64", File(dir, "c-q.png").readText())
        } finally {
            dir.listFiles()!!.forEach { it.deleteRecursively() }
            ThemeManager.refreshThemes()
        }
    }

    @Test
    fun keyImageAndFontNamesAreKeptByTheirFileNameOnly() {
        val t = full("p").let { t ->
            t.copy(look = t.look.mapFiles { if (it == "p-q.png" || it == "p-font.ttf") "../../$it" else it })
        }
        val imported = ThemeFilesManager.importTheme(zip("p.json" to json(t), *files("p"))).getOrThrow().second
        assertEquals(setOf("p.json") + files("p").map { it.first }, names().toSet())
        assertEquals(File(dir, "p-font.ttf").path, imported.look.text.keyFont)
        assertFalse(File(dir, "../../p-q.png").exists())
        dir.listFiles()!!.forEach { it.delete() }
        // a name that is no file's, a json's (listed as one more theme) or where files are written: refused before anything is copied
        for ((from, to) in listOf("p-q.png" to "..", "p-font.ttf" to "x.json", "p-font.ttf" to ".saving")) {
            val crafted = full("p").let { f -> f.copy(look = f.look.mapFiles { if (it == from) to else it }) }
            // a file of that name in the zip as well where it can be one (not "..", refused as it is unpacked;
            // not a second json, which the import could take for the theme's)
            val extra = if (to == ".saving") arrayOf(to to "font") else emptyArray()
            val e = ThemeFilesManager.importTheme(zip("p.json" to json(crafted), *files("p"), *extra)).exceptionOrNull()
            assertEquals("$from as $to", appContext.getString(R.string.exception_theme_file, to), e?.message)
            assertEquals("$from as $to", emptyList<String>(), names())
        }
    }

    @Test
    fun aThemesFilesInAFolderOfTheZipAreNotTaken() {
        // a Mac's archive of the files has their attributes beside them in __MACOSX/: counted, not unpacked
        val withAttributes = zip("f.json" to json(full("f")), *files("f"), "__MACOSX/" to "", "__MACOSX/._f-q.png" to "attr")
        ThemeFilesManager.importTheme(withAttributes).getOrThrow()
        assertEquals(setOf("f.json") + files("f").map { it.first }, names().toSet())
        // not even unpacked beside the theme's files
        assertTrue(FakeProbe.beside.isNotEmpty())
        assertFalse(FakeProbe.beside.toString(), "__MACOSX" in FakeProbe.beside)
        dir.listFiles()!!.forEach { it.delete() }
        // the theme's own files in a folder: not found
        val inAFolder = zip("t/f.json" to json(full("f")), *files("f").map { "t/${it.first}" to it.second }.toTypedArray())
        assertEquals(appContext.getString(R.string.exception_theme_json), ThemeFilesManager.importTheme(inAFolder).exceptionOrNull()!!.message)
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun whatThisAppAndOneBefore30ExportedIsImported() {
        // the zips the theme maker's tests read (ThemeMakerRulesTest writes them): real PNGs
        val (created, theme, migrated) = ThemeFilesManager.importTheme(ThemeMakerTestdata.file("from-app.zip").inputStream()).getOrThrow()
        assertTrue(created)
        assertFalse(migrated)
        val names = theme.look.files().map { File(it).name }
        assertEquals(setOf("from-app.json") + names, names().toSet())
        // the cuts held to the picture's side, each key image to a key's; the source photo to none
        assertEquals(names.filter { it != File(theme.look.picture!!.src).name }.sorted(), FakeProbe.asked.sorted())
        dir.listFiles()!!.forEach { it.delete() }
        FakeProbe.asked.clear()
        val (_, old, oldMigrated) = ThemeFilesManager.importTheme(ThemeMakerTestdata.file("from-app-2.1.zip").inputStream()).getOrThrow()
        assertTrue(oldMigrated)
        assertEquals(setOf("old.json", "old-cropped.png", "old-src"), names().toSet())
        assertEquals(listOf("old-cropped.png"), FakeProbe.asked)
        assertEquals(File(dir, "old-cropped.png").path, old.look.picture!!.portrait.file)
        // saved as 3.0
        assertTrue(File(dir, "old.json").readText().startsWith("{\"version\":\"3.0\""))
    }

    @Test
    fun aThemeIsExportedOnlyWithFilesOfTheThemeDir() {
        val secret = File(RuntimeEnvironment.getApplication().filesDir, "pinyin.user").apply { writeText("secret") }
        val planted = ThemePreset.TransparentDark.deriveCustomBackground("x", secret.path, secret.path)
        val out = ByteArrayOutputStream()
        val e = ThemeFilesManager.exportTheme(planted, out).exceptionOrNull()!!
        assertEquals(appContext.getString(R.string.exception_theme_file, "pinyin.user"), e.message)
        assertEquals(0, out.size())
    }

    @Test
    fun aMacsAttributesFileAtTheTopIsNotTakenForTheJson() {
        val zip = zip("._f.json" to "\u0000\u0005\u0016\u0007 attributes", "f.json" to json(full("f")), *files("f"))
        ThemeFilesManager.importTheme(zip).getOrThrow()
        assertEquals(setOf("f.json") + files("f").map { it.first }, names().toSet())
    }

    // a later build's theme, listed by none here: its json and the key image it names
    private fun laterTheme(): String {
        files("l").forEach { (name, text) -> File(dir, name).writeText(text) }
        return json(full("l")).replace("\"version\":\"3.0\"", "\"version\":\"3.1\"").also { File(dir, "l.json").writeText(it) }
    }

    @Test
    fun aFileALaterBuildsThemeNamesIsAClashThoughNotListed() {
        val later = laterTheme()
        ThemeManager.refreshThemes()
        try {
            val before = snapshot()
            val b = full("b").let { t -> t.copy(look = t.look.mapFiles { if (it == "b-q.png") "l-q.png" else it }) }
            val bFiles = files("b").map { if (it.first == "b-q.png") "l-q.png" to "16x16" else it }.toTypedArray()
            val e = ThemeFilesManager.importTheme(zip("b.json" to json(b), *bFiles)).exceptionOrNull()!!
            assertEquals(appContext.getString(R.string.exception_theme_file_clash, "l-q.png"), e.message)
            assertEquals(before, snapshot())
            assertEquals(later, File(dir, "l.json").readText())
            // a broken json protects nothing it might have named: what cannot be read names no file
            File(dir, "x.json").writeText("{\"name\":\"x\",\"keyFont\":\"c-q.png\"")
            File(dir, "c-q.png").writeText("orphan")
            ThemeFilesManager.importTheme(zip("c.json" to json(full("c")), *files("c"))).getOrThrow()
            assertEquals("64x64", File(dir, "c-q.png").readText())
        } finally {
            dir.listFiles()!!.forEach { it.deleteRecursively() }
            ThemeManager.refreshThemes()
        }
    }

    @Test
    fun aThemeOfTheSameNameNotListedIsReplacedUnlessALaterBuildMadeIt() {
        val later = laterTheme()
        ThemeManager.refreshThemes()
        try {
            val plain = ThemePreset.TransparentDark.deriveCustomNoBackground("l")
            val e = ThemeFilesManager.importTheme(zip("l.json" to json(plain))).exceptionOrNull()!!
            assertEquals(appContext.getString(R.string.exception_theme_newer_kept), e.message)
            assertEquals(later, File(dir, "l.json").readText())
            // a broken one is replaced
            File(dir, "l.json").writeText("{\"version\":")
            assertTrue(ThemeFilesManager.importTheme(zip("l.json" to json(plain))).getOrThrow().first)
            assertEquals(plain, ThemeFilesManager.listThemes().single { it.name == "l" })
            // one that reads, not listed for a file it lost: replaced, the files it had and the new one does not name gone
            ThemeFilesManager.importTheme(zip("u.json" to json(full("u")), *files("u"))).getOrThrow()
            File(dir, "u-fn.png").delete()
            ThemeManager.refreshThemes()
            ThemeFilesManager.importTheme(zip("u.json" to json(ThemePreset.TransparentDark.deriveCustomNoBackground("u")))).getOrThrow()
            assertEquals(emptyList<String>(), names().filter { it.startsWith("u") && it != "u.json" })
        } finally {
            dir.listFiles()!!.forEach { it.deleteRecursively() }
            ThemeManager.refreshThemes()
        }
    }

    @Test
    fun aThemeReplacedDeletesNoFileOfAnothers() {
        ThemeFilesManager.importTheme(zip("a.json" to json(full("a")), *files("a"))).getOrThrow()
        // u reads but is not listed (a file of it missing); it names a's key image, and a's json as its font
        val u = full("u").let { t ->
            t.copy(look = t.look.mapFiles { if (it == "u-q.png") "a-q.png" else if (it == "u-font.ttf") "a.json" else it })
        }
        File(dir, "u.json").writeText(json(u))
        files("u").filter { it.first !in setOf("u-q.png", "u-font.ttf", "u-fn.png") }.forEach { (name, text) -> File(dir, name).writeText(text) }
        ThemeManager.refreshThemes()
        try {
            assertEquals(listOf("a"), ThemeManager.getAllThemes().filterIsInstance<Theme.Custom>().map { it.name })
            val a = snapshot().filterKeys { it.startsWith("a") }
            ThemeFilesManager.importTheme(zip("u.json" to json(ThemePreset.TransparentDark.deriveCustomNoBackground("u")))).getOrThrow()
            assertEquals(a, snapshot().filterKeys { it.startsWith("a") })
            assertEquals(setOf("a", "u"), ThemeFilesManager.listThemes().map { it.name }.toSet())
            // u's own files it no longer names are gone
            assertEquals(listOf("u.json"), names().filter { it.startsWith("u") })
        } finally {
            dir.listFiles()!!.forEach { it.deleteRecursively() }
            ThemeManager.refreshThemes()
        }
    }

    @Test
    fun whatAKillLeftWhereFilesAreWrittenIsClearedAtTheFirstListing() {
        File(dir, ".saving").mkdirs()
        val left = File(dir, ".saving/0c1d").apply { writeText("half a json") }
        ThemeFilesManager.forgetSwept()
        ThemeFilesManager.listThemes()
        assertFalse(left.exists())
        // later listings leave it be: a save may be writing there
        File(dir, ".saving/0c1e").writeText("being written")
        ThemeFilesManager.listThemes()
        assertTrue(File(dir, ".saving/0c1e").exists())
        // a plain file in its place would refuse every save: gone too
        File(dir, ".saving").deleteRecursively()
        blockWrites()
        ThemeFilesManager.forgetSwept()
        ThemeFilesManager.listThemes()
        ThemeFilesManager.saveThemeFiles(ThemePreset.TransparentDark.deriveCustomNoBackground("s"))
        assertTrue(File(dir, "s.json").exists())
    }

    companion object {
        // the colours 3.0 added, from others, which 2.1 did not have
        private val DERIVED = setOf("keyBorderColor", "keyHintColor", "toolbarIconColor")
    }
}
