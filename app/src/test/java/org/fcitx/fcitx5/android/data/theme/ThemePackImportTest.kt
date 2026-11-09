/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeCards
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemePackDialogs
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// an image is a file of "<w>x<h>", a font one of "font": what an import reads of them, off Android
private object PackProbe : ThemeFilesManager.AssetProbe {
    override fun imageSize(file: File): Pair<Int, Int>? = file.readText().split('x').takeIf { it.size == 2 }
        ?.let { (w, h) -> w.toIntOrNull()?.let { a -> h.toIntOrNull()?.let { b -> a to b } } }

    override fun fontReads(file: File) = file.readText() == "font"
}

/**
 * A theme 4.0 pack (060-render-p3 T8a) imported: shown first, taken whole into `v4/<name>/`, a 3.0 theme still
 * imported at once; refused for what it must not be (a zip bomb, a path out, an SVG that is more than paths, a
 * newer app's, keys left blank, a built-in's name), and out again as it came in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemePackImportTest {

    private val dir get() = ThemeFilesManager.themeDir
    private val packs get() = File(dir, ThemePackFiles.FOLDER)
    private val unpacking get() = File(appContext.cacheDir, "theme-import")
    private val real = ThemeFilesManager.probe

    @Before
    fun empty() {
        phoneStorage()
        dir.deleteRecursively()
        dir.mkdirs()
        ThemeFilesManager.probe = PackProbe
        ThemeFilesManager.listThemes()
        ThemeManager.refreshThemes()
    }

    @After
    fun realProbe() {
        ThemeFilesManager.probe = real
        dir.deleteRecursively()
        ThemeManager.refreshThemes()
    }

    // a built-in theme's file of two versions, as a user's of [name]: [meta] before its colours, [before] ahead of
    // its first style (a picture, fonts), [styles] and [icons] at its end
    private val nord by lazy { BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_nord) }

    private fun xml(name: String = "mine", meta: String = "", before: String = "", styles: String = "", icons: String = ""): String {
        var x = nord.replace("name=\"nord\"", "name=\"$name\"").replace("<colors>", "$meta<colors>")
        val first = x.indexOf("<style ")
        x = x.substring(0, first) + before + x.substring(first)
        return x.replace("</theme>", "$styles$icons</theme>")
    }

    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M0 0L24 24Z" fill="#fff"/></svg>"""

    private val sampleXml = xml(
        meta = """<meta author="Ann" version="1.2" license="CC-BY-4.0" min-app="0.5.0" preview="preview.png"/>""",
        before = """<picture src="photo.jpg" brightness="80%"><cut orientation="portrait" file="p.webp"/>""" +
            """<cut orientation="landscape" file="l.webp"/></picture><font name="round" src="round.ttf"/>""",
        styles = """<style select="key.enter" fill="image(enter.png, 4 4 4 4)"/><style select="key" font="round"/>""",
        icons = """<icon name="star" src="star.svg"/>""",
    )

    private fun sample(xml: String = sampleXml, vararg more: Pair<String, String>) = zip(
        "theme.xml" to xml, "preview.png" to "100x50", "photo.jpg" to "photo", "p.webp" to "1080x720", "l.webp" to "2000x700",
        "enter.png" to "64x64", "round.ttf" to "font", "star.svg" to svg, "layouts/" to "", "layouts/qwerty.xml" to "<layout/>",
        // what it does not name, and a Mac's attributes: not taken
        "readme.txt" to "hello", "__MACOSX/._enter.png" to "attr", *more,
    )

    private fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) zip.write(text.encodeToByteArray())
            }
        }
    }.toByteArray()

    private fun open(bytes: ByteArray) = ThemeFilesManager.openTheme(bytes.inputStream())

    private fun opened(bytes: ByteArray) = open(bytes).getOrThrow() as ThemeFilesManager.Opened.Pack

    private fun refusal(bytes: ByteArray) = open(bytes).exceptionOrNull()!!.message

    private fun tree(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readText() }

    private fun nothingLeft() {
        assertEquals(emptyMap<String, String>(), tree(packs))
        assertEquals(emptyList<String>(), unpacking.list().orEmpty().toList())
    }

    @Test
    fun aPackIsShownFirstThenTakenWholeIntoItsFolder() {
        val pack = opened(sample())
        val staged = pack.staged
        assertEquals("mine", staged.name)
        assertEquals("Ann", staged.theme.meta!!.author)
        assertEquals("1.2", staged.theme.meta!!.version)
        assertEquals("CC-BY-4.0", staged.theme.meta!!.license)
        assertEquals("preview.png", staged.preview!!.name)
        assertEquals(listOf("qwerty.xml"), staged.layouts)
        assertEquals(emptyList<String>(), staged.warnings)
        assertFalse(pack.replaces)
        // nothing taken till the user does
        assertFalse(packs.exists() && packs.list()!!.isNotEmpty())
        val versions = pack.commit().getOrThrow()
        assertEquals(listOf("mine#light", "mine#dark"), versions.map { it.name })
        val folder = File(packs, "mine")
        assertEquals(
            setOf("theme.xml", "preview.png", "photo.jpg", "p.webp", "l.webp", "enter.png", "round.ttf", "star.svg", "layouts/qwerty.xml"),
            tree(folder).keys,
        )
        assertEquals(sampleXml, File(folder, "theme.xml").readText())
        assertEquals(emptyList<String>(), unpacking.list().orEmpty().toList())
        assertEquals(versions, ThemeFilesManager.listPacks())
        // drawn from its folder: its files by path, its colours the file's versions'
        val dark = versions[1]
        assertEquals(File(folder, "round.ttf").path, dark.drawn.fonts["round"])
        assertEquals(File(folder, "round.ttf").path, dark.look.text.keyFont)
        assertEquals(File(folder, "p.webp").path, dark.look.picture!!.portrait.file)
        assertEquals(80, dark.look.picture!!.brightness)
        assertEquals(listOf(File(folder, "enter.png").path), ThemePackFiles.images(dark.drawn).map { it.file })
        assertEquals(ThemeManager.builtin("nord#dark").palette(), dark.look.palette)
        assertEquals(ThemeManager.builtin("nord#light").keyTextColor, versions[0].keyTextColor)
        // listed and found by the settings' names, its versions one card
        ThemeManager.refreshThemes()
        assertEquals(dark, ThemeManager.getTheme("mine#dark"))
        assertEquals(listOf(versions[0]), ThemeCards(ThemeManager.getAllThemes()).shown.filterIsInstance<Theme.Pack>())
        // imported again: it replaces the one there
        assertTrue(opened(sample()).also { it.commit().getOrThrow() }.replaces)
        // deleted as one, from either version
        ThemeManager.deleteTheme("mine#dark")
        assertFalse(folder.exists())
        assertEquals(emptyList<Theme.Pack>(), ThemeManager.Packs)
    }

    @Test
    fun aPackIsDrawnFromItsOwnFileAndItsFolder() {
        val dark = opened(sample()).commit().getOrThrow()[1]
        val look = ThemeManager.shownLook(dark)
        // the engine's theme its own file as read (not a look's written again), kept as the looks' are
        val drawn = SceneThemes.of(dark, look)!!
        assertSame(dark.drawn, drawn)
        assertSame(drawn, SceneThemes.of(dark, look))
        assertSame(SceneThemes.cascades(drawn), SceneThemes.cascades(drawn))
        // its fonts by their names, each its file in its folder
        val asked = mutableListOf<String>()
        val before = ThemeAssets.decoder
        ThemeAssets.decoder = object : ThemeAssets.Decoder by before {
            override fun typeface(path: String) = null.also { asked += path }
        }
        try {
            SceneFonts.of(dark, look).typeface("round", 400)
        } finally {
            ThemeAssets.decoder = before
        }
        assertEquals(listOf(File(packs, "mine/round.ttf").path), asked)
    }

    @Test
    fun theImportAndInfoShowWhatThePackIsAndWhatItWarnsOf() {
        val ctx = appContext
        val meta = listOf(
            ctx.getString(R.string.theme_info_author, "Ann"),
            ctx.getString(R.string.theme_info_version, "1.2"),
            ctx.getString(R.string.theme_info_license, "CC-BY-4.0"),
            ctx.getString(R.string.theme_info_format, "4.0"),
        )
        val pack = opened(sample())
        assertEquals(meta, ThemePackDialogs.confirmText(ctx, pack).lines())
        val versions = pack.commit().getOrThrow()
        assertEquals(meta.joinToString("\n"), ThemePackDialogs.infoText(ctx, versions[0]))
        assertEquals(File(packs, "mine/preview.png"), ThemePackDialogs.preview(versions[0]))
        assertEquals("mine", ThemePackDialogs.title(versions[1]))
        // one to replace, keys hard to read: said
        val low = xml().replaceFirst("""<color name="keyText" value="#2e3440"/>""", """<color name="keyText" value="#e5e9f0"/>""")
        val told = ThemePackDialogs.confirmText(ctx, opened(zip("theme.xml" to low))).lines()
        assertEquals(ctx.getString(R.string.theme_pack_replaces), told[1])
        assertEquals(ctx.getString(R.string.theme_pack_warnings), told[2])
        // a 3.0 theme's: its format
        val custom = TestThemes.TransparentDark.deriveCustomNoBackground("c")
        assertEquals(ctx.getString(R.string.theme_info_format, "3.0"), ThemePackDialogs.infoText(ctx, custom))
    }

    @Test
    fun aTheme30IsImportedAtOnceAsBefore() {
        val json = Json.encodeToString(CustomThemeSerializer, TestThemes.TransparentDark.deriveCustomNoBackground("old"))
        val opened = open(zip("old.json" to json)).getOrThrow() as ThemeFilesManager.Opened.Json
        assertTrue(opened.imported.created)
        assertTrue(File(dir, "old.json").isFile)
        nothingLeft()
    }

    @Test
    fun aZipBombIsRefusedAsItIsUnpacked() {
        // a file past the limit, small zipped: stopped at the limit, nothing of it kept
        val bomb = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("theme.xml"))
                zip.write(sampleXml.encodeToByteArray())
                zip.putNextEntry(ZipEntry("enter.png"))
                val zeros = ByteArray(1 shl 16)
                repeat(((ThemeAssetLimits.MAX_FILE_BYTES shr 16) + 1).toInt()) { zip.write(zeros) }
            }
        }.toByteArray()
        assertTrue(bomb.size < ThemeAssetLimits.MAX_FILE_BYTES / 100)
        assertEquals(appContext.getString(R.string.exception_theme_file_size, (ThemeAssetLimits.MAX_FILE_BYTES / (1024 * 1024)).toString()), refusal(bomb))
        nothingLeft()
    }

    @Test
    fun aPathOutOfThePackIsRefused() {
        // an entry out of where it is unpacked
        assertTrue(open(zip("theme.xml" to sampleXml, "../out.txt" to "x")).exceptionOrNull() is SecurityException)
        assertFalse(File(unpacking, "out.txt").exists())
        // a file the theme names out of its folder (the engine's file names may be `..`)
        val out = xml(before = """<font name="f" src=".."/>""", styles = """<style select="key" font="f"/>""")
        assertEquals(appContext.getString(R.string.exception_theme_file, ".."), refusal(zip("theme.xml" to out)))
        // a folder of its own but layouts/
        assertEquals(appContext.getString(R.string.exception_theme_folder, "images"), refusal(zip("theme.xml" to xml(), "images/a.png" to "1x1")))
        nothingLeft()
    }

    @Test
    fun anIconThatIsMoreThanFilledPathsIsRefused() {
        val withIcon = xml(icons = """<icon name="star" src="star.svg"/>""")
        val script = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>"""
        assertTrue(refusal(zip("theme.xml" to withIcon, "star.svg" to script))!!.startsWith(appContext.getString(R.string.exception_theme_icon, "star.svg")))
        // entities of its own (billion laughs, XXE): the engine's reader has no DTD
        val xxe = """<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg xmlns="http://www.w3.org/2000/svg">&x;</svg>"""
        assertTrue(refusal(zip("theme.xml" to withIcon, "star.svg" to xxe))!!.startsWith(appContext.getString(R.string.exception_theme_icon, "star.svg")))
        val big = svg.replace("M0 0L24 24Z", "M0 0" + "L1 1".repeat(ThemeAssetLimits.MAX_ICON_BYTES.toInt() / 4) + "Z")
        assertEquals(
            appContext.getString(R.string.exception_theme_icon_size, (ThemeAssetLimits.MAX_ICON_BYTES / 1024).toString()),
            refusal(zip("theme.xml" to withIcon, "star.svg" to big)),
        )
        nothingLeft()
    }

    @Test
    fun aPackOfANewerAppIsRefusedSayingWhichItNeeds() {
        val newer = xml(meta = """<meta min-app="99.0.0"/>""")
        assertEquals(appContext.getString(R.string.exception_theme_min_app, "99.0.0"), refusal(zip("theme.xml" to newer)))
        // one that does not read: the engine's reasons
        assertTrue(refusal(zip("theme.xml" to "<theme"))!!.startsWith(appContext.getString(R.string.exception_theme_invalid, "")))
        nothingLeft()
    }

    @Test
    fun aPackLeavingTheKeysOneCannotTypeWithoutBlankIsRefused() {
        val hidden = xml(styles = """<style select="key.function" hidden="true"/>""")
        val line = hidden.lines().indexOfFirst { "hidden=\"true\"" in it } + 1
        assertEquals(appContext.getString(R.string.exception_theme_hidden_required, line.toString()), refusal(zip("theme.xml" to hidden)))
        nothingLeft()
    }

    @Test
    fun aPackOfABuiltInsOrAnotherThemesNameIsRefused() {
        val clash = appContext.getString(R.string.exception_theme_name_clash)
        assertEquals(clash, refusal(zip("theme.xml" to xml(name = "pixel"))))
        assertEquals(clash, refusal(zip("theme.xml" to xml(name = "MaterialLight"))))
        assertEquals(appContext.getString(R.string.exception_theme_pack_name, "x#dark"), refusal(zip("theme.xml" to xml(name = "x#dark"))))
        // a 3.0 theme named as one of its versions: the settings would name both by it
        val json = Json.encodeToString(CustomThemeSerializer, TestThemes.TransparentDark.deriveCustomNoBackground("mine#light"))
        ThemeFilesManager.importTheme(zip("t.json" to json).inputStream()).getOrThrow()
        ThemeManager.refreshThemes()
        assertEquals(clash, refusal(sample()))
        File(dir, "mine#light.json").delete()
        ThemeManager.refreshThemes()
        // and the other way round
        opened(sample()).commit().getOrThrow()
        ThemeManager.refreshThemes()
        val again = Json.encodeToString(CustomThemeSerializer, TestThemes.TransparentDark.deriveCustomNoBackground("mine#dark"))
        assertEquals(clash, ThemeFilesManager.importTheme(zip("t.json" to again).inputStream()).exceptionOrNull()!!.message)
    }

    @Test
    fun aPacksTooManyOrForeignLayoutsAreRefused() {
        val many = (1..ThemeAssetLimits.MAX_LAYOUTS + 1).map { "layouts/l$it.xml" to "<layout/>" }.toTypedArray()
        assertEquals(
            appContext.getString(R.string.exception_theme_layouts, ThemeAssetLimits.MAX_LAYOUTS.toString()),
            refusal(zip("theme.xml" to xml(), *many)),
        )
        assertEquals(appContext.getString(R.string.exception_theme_layout, "run.sh"), refusal(zip("theme.xml" to xml(), "layouts/run.sh" to "x")))
        nothingLeft()
    }

    @Test
    fun keysHardToReadAreToldNotRefused() {
        val low = xml().replaceFirst("""<color name="keyText" value="#2e3440"/>""", """<color name="keyText" value="#e5e9f0"/>""")
        val warnings = opened(zip("theme.xml" to low)).staged.warnings
        val light = appContext.getString(R.string.theme_pack_light)
        assertEquals(listOf(appContext.getString(R.string.theme_pack_low_contrast, light)), warnings)
    }

    @Test
    fun aPackExportedIsImportedAgainAsItWas() {
        val versions = opened(sample()).commit().getOrThrow()
        val before = tree(File(packs, "mine"))
        val out = ByteArrayOutputStream()
        ThemeFilesManager.exportTheme(versions[1], out).getOrThrow()
        val entries = ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals("theme.xml", entries.first())
        File(packs, "mine").deleteRecursively()
        val again = opened(out.toByteArray()).commit().getOrThrow()
        assertEquals(versions, again)
        assertEquals(before, tree(File(packs, "mine")))
    }

    @Test
    fun aTheme30IsExportedAsATheme40DrawnAsItWas() {
        File(dir, "k.ttf").writeText("font")
        File(dir, "c.ttf").writeText("font")
        val custom = TestThemes.TransparentDark.deriveCustomNoBackground("c").let {
            it.copy(look = it.look.copy(text = it.look.text.copy(keyFont = File(dir, "k.ttf").path, candidateFont = File(dir, "c.ttf").path, candidateWeight = 700)))
        }
        val out = ByteArrayOutputStream()
        ThemeFilesManager.exportTheme(custom, out).getOrThrow()
        val pack = opened(out.toByteArray())
        // drawn as LookTheme draws the 3.0 theme, its candidates' font kept as candidate's
        assertEquals(LookTheme.theme(custom.look, "c", true).styles.size + 1, pack.staged.theme.styles.size)
        assertEquals(mapOf(LookTheme.KEY_FONT to "k.ttf", ThemePackFiles.CANDIDATE_FONT to "c.ttf"), pack.staged.theme.fonts)
        val imported = pack.commit().getOrThrow().single()
        assertEquals(custom.look.palette, imported.look.palette)
        assertEquals(File(packs, "c/c.ttf").path, imported.look.text.candidateFont)
        assertEquals(700, imported.look.text.candidateWeight)
        assertEquals(File(packs, "c/k.ttf").path, imported.look.text.keyFont)
    }

    @Test
    fun whatAKillLeftOfAPackBeingWrittenIsClearedAtTheFirstListing() {
        File(packs, ".saving/0c1d").apply { mkdirs() }.resolve("theme.xml").writeText("half")
        ThemeFilesManager.forgetSwept()
        ThemeFilesManager.listThemes()
        assertEquals(emptyList<Theme.Pack>(), ThemeFilesManager.listPacks())
        assertFalse(File(packs, ".saving").exists())
        // one whose theme.xml does not read, or is another's name: not listed, left as it is
        File(packs, "broken").mkdirs()
        File(packs, "broken/theme.xml").writeText("<theme")
        File(packs, "other").mkdirs()
        File(packs, "other/theme.xml").writeText(xml(name = "else"))
        assertEquals(emptyList<Theme.Pack>(), ThemeFilesManager.listPacks())
        assertTrue(File(packs, "broken/theme.xml").exists())
    }
}
