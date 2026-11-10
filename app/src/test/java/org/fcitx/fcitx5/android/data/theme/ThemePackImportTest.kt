/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import androidx.preference.PreferenceManager
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.scene.look.Crop
import org.fcitx.fcitx5.android.scene.look.Insets
import org.fcitx.fcitx5.android.scene.look.KeyFill
import org.fcitx.fcitx5.android.scene.look.KeyLabel
import org.fcitx.fcitx5.android.scene.look.KeyOverride
import org.fcitx.fcitx5.android.scene.look.KeyPressed
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.Picture
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeCards
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemePackDialogs
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        ThemeFilesManager.replacingFile().delete()
        // the theme in use the normal mode's, not the day's or night's
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeFilesManager.listThemes()
        ThemeManager.refreshThemes()
    }

    @After
    fun realProbe() {
        ThemeFilesManager.probe = real
        ThemePackFiles.beforeInPlace = null
        ThemeManager.prefs.followSystemDayNightTheme.setValue(ThemeManager.prefs.followSystemDayNightTheme.defaultValue)
        ThemeManager.setNormalModeTheme(ThemeManager.prefs.normalModeTheme.defaultValue)
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

    // [more] over the sample's own of their names
    private fun sample(xml: String = sampleXml, vararg more: Pair<String, String>) = zip(*(sampleEntries(xml).toMap() + more).toList().toTypedArray())

    private fun sampleEntries(xml: String) = arrayOf(
        "theme.xml" to xml, "preview.png" to "100x50", "photo.jpg" to "3000x2000", "p.webp" to "1080x720", "l.webp" to "2000x700",
        "enter.png" to "64x64", "round.ttf" to "font", "star.svg" to svg, "layouts/" to "", "layouts/qwerty.xml" to "<layout/>",
        // what it does not name, and a Mac's attributes: not taken
        "readme.txt" to "hello", "__MACOSX/._enter.png" to "attr",
    )

    private fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) zip.write(text.encodeToByteArray())
            }
        }
    }.toByteArray()

    private fun open(bytes: ByteArray, themes: List<Theme> = ThemeManager.getAllThemes()) = ThemeFilesManager.openTheme(bytes.inputStream(), themes)

    private fun opened(bytes: ByteArray) = open(bytes).getOrThrow() as ThemeFilesManager.Opened.Pack

    // imported and listed, as the settings' list does after ([ThemeManager.packPlaced])
    private fun placed(bytes: ByteArray) = opened(bytes).commit().getOrThrow().also { ThemeManager.packPlaced(it[0].file) }

    private fun refusal(bytes: ByteArray, themes: List<Theme> = ThemeManager.getAllThemes()) = open(bytes, themes).exceptionOrNull()!!.message

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
            setOf("theme.xml", "preview.png", "photo.jpg", "p.webp", "l.webp", "enter.png", "round.ttf", "star.svg", "layouts/qwerty.xml", ".checked"),
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

    // the theme maker's save (060-render-p3 M1: web/theme-maker/test/maker.test.mjs writes it) is a pack the app takes
    @Test
    fun theMakersSaveImports() {
        val pack = opened(ThemeMakerTestdata.file("from-maker.theme").readBytes())
        assertEquals("nord-mine", pack.staged.name)
        assertEquals(emptyList<String>(), pack.staged.warnings)
        val versions = pack.commit().getOrThrow()
        assertEquals(listOf("nord-mine#light", "nord-mine#dark"), versions.map { it.name })
        assertEquals(ThemeManager.builtin("nord#dark").palette(), versions[1].look.palette)
    }

    // and its save of a picture: a real PNG, decoded as the app does
    @Test
    fun theMakersPictureSaveImports() {
        ThemeFilesManager.probe = real
        val picture = opened(ThemeMakerTestdata.file("from-maker-picture.theme").readBytes())
        assertEquals(emptyList<String>(), picture.staged.warnings)
        assertEquals(listOf("nord-mine#light", "nord-mine#dark"), picture.commit().getOrThrow().map { it.name })
    }

    // and its save of the app's own 3.0 export converted (Return an image, its label hidden, as 3.0 drew it):
    // a theme the app took stays one it takes
    @Test
    fun theMakersConvertedSaveImports() {
        ThemeFilesManager.probe = real
        val converted = opened(ThemeMakerTestdata.file("from-maker-30.theme").readBytes())
        assertEquals("from-app", converted.staged.name)
        assertEquals(listOf("from-app"), converted.commit().getOrThrow().map { it.name })
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
        // its layouts said too (T8b): its qwerty.xml does not read
        assertEquals(meta + ctx.getString(R.string.theme_pack_layouts_unused, "qwerty"), ThemePackDialogs.confirmText(ctx, pack).lines())
        val versions = pack.commit().getOrThrow()
        ThemeManager.refreshThemes()
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
        // a file the theme names out of its folder: `..` is no file name to the engine's reader since R20
        val out = xml(before = """<font name="f" src=".."/>""", styles = """<style select="key" font="f"/>""")
        assertTrue(refusal(zip("theme.xml" to out))!!.contains("src=\"..\" is not a file name"))
        // a folder of its own but layouts/, or one in layouts/ (its files would not be taken)
        assertEquals(appContext.getString(R.string.exception_theme_folder, "images"), refusal(zip("theme.xml" to xml(), "images/a.png" to "1x1")))
        assertEquals(
            appContext.getString(R.string.exception_theme_folder, "layouts/more"),
            refusal(zip("theme.xml" to xml(), "layouts/a.xml" to "<layout/>", "layouts/more/b.xml" to "<layout/>")),
        )
        assertEquals(appContext.getString(R.string.exception_theme_folder, "layouts/more"), refusal(zip("theme.xml" to xml(), "layouts/more/" to "")))
        nothingLeft()
    }

    @Test
    fun aPackZippedInItsFolderIsToldItsThemeXmlMustBeAtTheTop() {
        val top = appContext.getString(R.string.exception_theme_xml_top)
        assertEquals(top, refusal(zip("mine/" to "", "mine/theme.xml" to xml(), "mine/preview.png" to "100x50")))
        // a Mac's copy of it is not one
        assertNotEquals(top, refusal(zip("__MACOSX/mine/theme.xml" to "attr", "x.txt" to "")))
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
        // where the engine finds it first: the board, the key
        assertEquals(appContext.getString(R.string.exception_theme_hidden_required, "qwerty/shift"), refusal(zip("theme.xml" to hidden)))
        nothingLeft()
    }

    @Test
    fun aPackOfABuiltInsNameIsRefused() {
        val clash = appContext.getString(R.string.exception_theme_name_clash)
        assertEquals(clash, refusal(zip("theme.xml" to xml(name = "pixel"))))
        assertEquals(clash, refusal(zip("theme.xml" to xml(name = "MaterialLight"))))
        assertEquals(appContext.getString(R.string.exception_theme_pack_name, "x#dark"), refusal(zip("theme.xml" to xml(name = "x#dark"))))
        // a 3.0 theme of one's own imported over a pack: the settings would name both by it
        opened(sample()).commit().getOrThrow()
        ThemeManager.refreshThemes()
        val again = Json.encodeToString(CustomThemeSerializer, TestThemes.TransparentDark.deriveCustomNoBackground("mine#dark"))
        assertEquals(clash, ThemeFilesManager.importTheme(zip("t.json" to again).inputStream()).exceptionOrNull()!!.message)
    }

    @Test
    fun aPackReplacesTheUsersOwnTheme30OfItsName() {
        // the user's 3.0 theme named as one of its versions, its picture a file no other theme names, in use
        File(dir, "own.png").writeText("10x10")
        val own = TestThemes.TransparentDark.deriveCustomNoBackground("mine#light")
        val withFile = own.copy(look = own.look.copy(picture = Picture(File(dir, "own.png").path, Crop(File(dir, "own.png").path, null, 0), null, 70, 0)))
        ThemeFilesManager.saveThemeFiles(withFile)
        ThemeManager.refreshThemes()
        ThemeManager.setNormalModeTheme(ThemeManager.getTheme("mine#light")!!)
        val pack = opened(sample())
        assertEquals(listOf("mine#light"), pack.customs.map { it.name })
        assertTrue(pack.replaces)
        assertTrue(appContext.getString(R.string.theme_pack_replaces) in ThemePackDialogs.confirmText(appContext, pack).lines())
        val versions = pack.commit().getOrThrow()
        ThemeManager.packPlaced(versions[0].file)
        // its json and its own file gone; the setting naming it names the pack's version now
        assertFalse(File(dir, "mine#light.json").exists())
        assertFalse(File(dir, "own.png").exists())
        assertEquals(versions[0], ThemeManager.getTheme("mine#light"))
        assertEquals(versions[0], ThemeManager.activeTheme)
        // a 3.0 theme of another name left be
        assertTrue(opened(sample(xml(name = "other"))).customs.isEmpty())
    }

    @Test
    fun aPackOfTwoVersionsReplacingATheme30OfItsBareNameKeepsTheSlotsNamingIt() {
        // the user's dark 3.0 theme `mine`, in use; the pack `mine` of two versions has no version named so
        ThemeFilesManager.saveThemeFiles(TestThemes.TransparentDark.deriveCustomNoBackground("mine"))
        ThemeManager.refreshThemes()
        ThemeManager.setNormalModeTheme(ThemeManager.getTheme("mine")!!)
        val pack = opened(sample())
        assertEquals(listOf("mine"), pack.customs.map { it.name })
        val versions = pack.commit().getOrThrow()
        // placed, not listed yet (the list page's packPlaced to come, on the main thread): the theme still there, found,
        // the replacement recorded
        assertTrue(File(dir, "mine.json").exists())
        assertTrue(ThemeManager.getTheme("mine") is Theme.Custom)
        assertEquals(setOf("mine"), ThemeFilesManager.replacing())
        // the keyboard told once, of the pack's version (not first of the theme it replaces, whose files go)
        val told = mutableListOf<Theme>()
        val listener = ThemeManager.OnThemeChangeListener { told += it }
        ThemeManager.addOnChangedListener(listener)
        ThemeManager.packPlaced(versions[0].file)
        ThemeManager.removeOnChangedListener(listener)
        assertEquals(listOf(versions.single { it.isDark }), told)
        assertEquals(emptySet<String>(), ThemeFilesManager.replacing())
        // its json gone; the setting naming it names the pack's dark version, not the default theme, the copy for
        // before the first unlock too
        assertFalse(File(dir, "mine.json").exists())
        assertEquals("mine#dark", ThemeManager.prefs.normalModeTheme.getValue().name)
        assertEquals(versions.single { it.isDark }, ThemeManager.activeTheme)
        val beforeUnlock = PreferenceManager.getDefaultSharedPreferences(appContext.createDeviceProtectedStorageContext())
        assertEquals("mine#dark", beforeUnlock.getString(ThemeManager.prefs.normalModeTheme.key, null))
    }

    @Test
    fun aReplacementAKillCutShortIsFinishedAtTheNextStart() {
        ThemeFilesManager.saveThemeFiles(TestThemes.TransparentDark.deriveCustomNoBackground("mine"))
        ThemeManager.refreshThemes()
        ThemeManager.setNormalModeTheme(ThemeManager.getTheme("mine")!!)
        // placed, the process killed before the list had it: the 3.0 theme and the pack both there
        opened(sample()).commit().getOrThrow()
        assertTrue(File(dir, "mine.json").exists())
        // the next start's listing finishes it: the slot names the pack's version, the json gone, one theme listed
        ThemeManager.refreshThemes()
        assertFalse(File(dir, "mine.json").exists())
        assertEquals("mine#dark", ThemeManager.prefs.normalModeTheme.getValue().name)
        assertTrue(ThemeManager.getAllThemes().none { it is Theme.Custom && it.name == "mine" })
        assertEquals(listOf("mine#light", "mine#dark"), ThemeManager.Packs.map { it.name })
    }

    // D1: a 3.0 theme beside a pack of its name no import replaced (an older backup restored over the phone's pack):
    // left be as listed, its json, its picture and the slot naming it kept (it is found by the name, as before)
    @Test
    fun aTheme30RestoredBesideAPackOfItsNameIsLeftBe() {
        opened(sample()).commit().getOrThrow()
        ThemeManager.refreshThemes()
        assertEquals(emptySet<String>(), ThemeFilesManager.replacing())
        File(dir, "own.png").writeText("10x10")
        val own = TestThemes.TransparentDark.deriveCustomNoBackground("mine")
        val picture = Picture(File(dir, "own.png").path, Crop(File(dir, "own.png").path, null, 0), null, 70, 0)
        ThemeFilesManager.saveThemeFiles(own.copy(look = own.look.copy(picture = picture)))
        ThemeManager.refreshThemes()
        ThemeManager.setNormalModeTheme(ThemeManager.getTheme("mine")!!)
        ThemeManager.refreshThemes()
        ThemeManager.finishReplacementsAtStart()
        assertTrue(File(dir, "mine.json").exists())
        assertTrue(File(dir, "own.png").exists())
        assertEquals("mine", ThemeManager.prefs.normalModeTheme.getValue().name)
        assertTrue(ThemeManager.getTheme("mine") is Theme.Custom)
        assertEquals(listOf("mine#light", "mine#dark"), ThemeManager.Packs.map { it.name })
    }

    // a replacement not placed: the 3.0 theme kept, its record gone (the import failed) or dropped at the next start
    // (a kill before the place), not while an import may be placing it
    @Test
    fun aReplacementNotPlacedKeepsTheTheme30() {
        ThemeFilesManager.saveThemeFiles(TestThemes.TransparentDark.deriveCustomNoBackground("mine"))
        ThemeManager.refreshThemes()
        ThemePackFiles.beforeInPlace = { throw java.io.IOException("full") }
        assertTrue(opened(sample()).commit().isFailure)
        ThemePackFiles.beforeInPlace = null
        assertEquals(emptySet<String>(), ThemeFilesManager.replacing())
        ThemeManager.refreshThemes()
        assertTrue(File(dir, "mine.json").exists())
        ThemeFilesManager.replacingFile().writeText("mine\n")
        ThemeManager.refreshThemes()
        assertEquals(setOf("mine"), ThemeFilesManager.replacing())
        assertTrue(File(dir, "mine.json").exists())
        ThemeManager.finishReplacementsAtStart()
        assertEquals(emptySet<String>(), ThemeFilesManager.replacing())
        assertTrue(File(dir, "mine.json").exists())
        assertTrue(ThemeManager.getTheme("mine") is Theme.Custom)
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
        assertEquals(versions.map { it.copy(stamp = 0) }, again.map { it.copy(stamp = 0) })
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
        // drawn as LookTheme draws the 3.0 theme, its candidates' font and weight with it (T6a)
        assertEquals(LookTheme.xml(custom.look, "c", true), pack.staged.source)
        assertEquals(mapOf(LookTheme.KEY_FONT to "k.ttf", LookTheme.CANDIDATE_FONT to "c.ttf"), pack.staged.theme.fonts)
        val imported = pack.commit().getOrThrow().single()
        assertEquals(custom.look.palette, imported.look.palette)
        assertEquals(File(packs, "c/c.ttf").path, imported.look.text.candidateFont)
        assertEquals(700, imported.look.text.candidateWeight)
        assertEquals(File(packs, "c/k.ttf").path, imported.look.text.keyFont)
    }

    // 3.0's most single keys (each its own pressed face and label): more styles than a hand-written file may have
    @Test
    fun aTheme30OfTheMostSingleKeysIsExportedToAPackThatImports() {
        File(dir, "k.png").writeText("64x64")
        File(dir, "d.png").writeText("64x64")
        // keys of one face share its styles: each its own (its slices'), of the two files
        val overrides = (0 until Keys.MAX_OVERRIDES).associate { i ->
            val insets = Insets(1 + i % 20, 1 + i / 20, 1, 1)
            val face = KeyFill.Image(NineSlice(File(dir, "k.png").path, insets, 1))
            "b$i/k" to KeyOverride(face, KeyPressed.Image(NineSlice(File(dir, "d.png").path, insets, 1)), KeyLabel.Hide)
        }
        val custom = TestThemes.TransparentDark.deriveCustomNoBackground("big").let {
            it.copy(look = it.look.copy(keys = it.look.keys.copy(overrides = overrides)))
        }
        val out = ByteArrayOutputStream()
        ThemeFilesManager.exportTheme(custom, out).getOrThrow()
        val pack = opened(out.toByteArray())
        assertTrue("${pack.staged.theme.styles.size}", pack.staged.theme.styles.size > ThemeReader.MAX_STYLES)
        val imported = pack.commit().getOrThrow().single()
        assertEquals(listOf(imported), ThemeFilesManager.listPacks())
        assertEquals(custom.look.palette, imported.look.palette)
    }

    // a 3.0 theme's source photo is not held to a picture's limit, a pack's is: exported as its portrait cut
    @Test
    fun aTheme30WhosePhotoIsPastAPicturesLimitIsExportedWithItsCut() {
        File(dir, "src.jpg").writeText("6000x4000")
        File(dir, "p.png").writeText("1080x720")
        val custom = TestThemes.TransparentDark.deriveCustomNoBackground("photo").let {
            it.copy(look = it.look.copy(picture = Picture(File(dir, "src.jpg").path, Crop(File(dir, "p.png").path, null, 0), null, 70, 0)))
        }
        val out = ByteArrayOutputStream()
        ThemeFilesManager.exportTheme(custom, out).getOrThrow()
        val pack = opened(out.toByteArray())
        assertEquals("p.png", pack.staged.theme.picture!!.src)
        assertEquals(File(packs, "photo/p.png").path, pack.commit().getOrThrow().single().look.picture!!.portrait.file)
    }

    @Test
    fun aPacksSourcePhotoIsHeldToAPicturesLimit() {
        val side = ThemeAssetLimits.MAX_PICTURE_SIDE
        val big = refusal(sample(sampleXml, "photo.jpg" to "${side + 1}x10"))
        assertEquals(appContext.getString(R.string.exception_theme_picture_side, side.toString()), big)
        assertEquals(appContext.getString(R.string.exception_theme_image, "photo.jpg"), refusal(sample(sampleXml, "photo.jpg" to "photo")))
        nothingLeft()
    }

    @Test
    fun aPackFolderNotListedIsReplacedUnlessALaterBuilds() {
        val kept = appContext.getString(R.string.exception_theme_newer_kept)
        File(packs, "mine").mkdirs()
        // a later build's not read here (a newer app's, a later format's): kept, said so (one that reads is listed)
        val later = listOf(
            xml(meta = """<meta min-app="99.0.0"/>""", styles = "<later/>"),
            """<theme xmlns="${ThemeReader.NAMESPACE}" name="mine" version="5.0"><sound/></theme>""",
        )
        for (text in later) {
            File(packs, "mine/theme.xml").writeText(text)
            ThemeManager.refreshThemes()
            assertEquals(kept, refusal(sample()))
            assertEquals(text, File(packs, "mine/theme.xml").readText())
        }
        // one past a pack's theme.xml limit is not read whole to tell: replaced
        val huge = "<!--" + "x".repeat(ThemeAssetLimits.MAX_THEME_XML_BYTES.toInt()) + "--></theme>"
        File(packs, "mine/theme.xml").writeText(later[0].replace("</theme>", huge))
        ThemeManager.refreshThemes()
        opened(sample()).apply { assertTrue(replaces) }.discard()
        // one cut short (a restore killed half way: a file it names missing), or that no longer reads: not
        // listed, so not to be deleted; imported again, replaced and listed
        val cutShort: () -> Unit = { File(packs, "mine/round.ttf").delete() }
        val broken: () -> Unit = { File(packs, "mine/theme.xml").writeText("<theme") }
        for (spoil in listOf(cutShort, broken)) {
            File(packs, "mine").deleteRecursively()
            placed(sample())
            spoil()
            ThemeManager.refreshThemes()
            assertTrue(ThemeManager.Packs.none { it.file == "mine" })
            val pack = opened(sample())
            assertTrue(pack.replaces)
            pack.commit().getOrThrow().also { ThemeManager.packPlaced(it[0].file) }
            assertEquals(listOf("mine#light", "mine#dark"), ThemeManager.Packs.map { it.name })
        }
    }

    @Test
    fun aNewPackPastTheMostIsRefusedOneReplacingIsNot() {
        val most = (1..ThemeAssetLimits.MAX_PACKS).map { Theme.Pack("p$it", false, "p$it", File(packs, "p$it").path, 0) }
        assertEquals(appContext.getString(R.string.exception_theme_packs, ThemeAssetLimits.MAX_PACKS.toString()), refusal(sample(), most))
        nothingLeft()
        placed(sample())
        val mine = ThemeManager.Packs
        assertTrue(open(sample(), most.drop(1) + mine).isSuccess)
    }

    @Test
    fun aListingReadsTheNewestMostPacks() {
        val source = xml(name = "p0")
        (0..ThemeAssetLimits.MAX_PACKS).forEach { i ->
            File(packs, "p$i").mkdirs()
            File(packs, "p$i/theme.xml").writeText(source.replace("name=\"p0\"", "name=\"p$i\""))
            File(packs, "p$i").setLastModified(1_000_000_000_000L + i * 1000L)
        }
        val listed = ThemeFilesManager.listPacks().map { it.file }.distinct()
        assertEquals(ThemeAssetLimits.MAX_PACKS, listed.size)
        assertFalse("p0" in listed)
    }

    // a restored backup's, a later build's: listed as an import would take it, or not at all
    @Test
    fun aPackAnImportWouldRefuseIsNotListed() {
        fun planted(folder: String, text: String) {
            File(packs, folder).mkdirs()
            File(packs, "$folder/theme.xml").writeText(text)
        }
        planted("pixel", xml(name = "pixel"))
        planted("MaterialLight", xml(name = "MaterialLight"))
        planted("x#dark", xml(name = "x#dark"))
        planted("hides", xml(name = "hides", styles = """<style select="key.function" hidden="true"/>"""))
        assertEquals(emptyList<Theme.Pack>(), ThemeFilesManager.listPacks())
        ThemeManager.refreshThemes()
        // the built-in theme of that name still the one the settings name
        assertTrue(ThemeManager.getTheme("pixel#dark") is Theme.Builtin)
    }

    @Test
    fun aPackWhoseFileNoLongerReadsIsDrawnAsTheBuiltInTheme() {
        val dark = opened(sample()).commit().getOrThrow()[1]
        File(packs, "mine/theme.xml").writeText("<theme")
        assertEquals("pixel", dark.scene.name)
        assertEquals(ThemeManager.builtin("pixel#dark").palette(), dark.look.palette)
    }

    @Test
    fun aPacksBarAndPanelsAreDrawnFromItsOwnFile() {
        val dark = placed(sample(xml(styles = """<style select="candidate" gap="7dp"/><style select="bar" height="120%"/>""")))[1]
        assertEquals(7.0, SceneBar.of(appContext, dark).gapDp, 0.0)
        // the bar's height (T6b) its file's too, in use
        ThemeManager.setNormalModeTheme(dark)
        assertEquals(120.0, SceneBar.heightPercent(appContext), 0.0)
    }

    @Test
    fun aPackInUseReplacedIsToldAsChanged() {
        val first = placed(sample())
        ThemeManager.setNormalModeTheme(first[1])
        val told = mutableListOf<Theme>()
        val listener = ThemeManager.OnThemeChangeListener { told += it }
        ThemeManager.addOnChangedListener(listener)
        try {
            placed(sample(xml(styles = """<style select="candidate" gap="7dp"/>""")))
            assertEquals(listOf("mine#dark"), told.map { it.name }.distinct())
            assertEquals(7.0, SceneBar.of(appContext, ThemeManager.activeTheme).gapDp, 0.0)
        } finally {
            ThemeManager.removeOnChangedListener(listener)
        }
    }

    @Test
    fun aPackIsReadAgainOnlyWhenTheSystemsColoursChange() {
        // a pack of system() colours, in use
        val monet = BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_monet).replace("name=\"monet\"", "name=\"mine\"")
        val dark = placed(zip("theme.xml" to monet))[1]
        ThemeManager.setNormalModeTheme(dark)
        val told = mutableListOf<Theme>()
        val listener = ThemeManager.OnThemeChangeListener { told += it }
        ThemeManager.addOnChangedListener(listener)
        val config = appContext.resources.configuration
        try {
            // turned, say: the same colours, nothing read or drawn anew
            val scene = (ThemeManager.activeTheme as Theme.Pack).scene
            ThemeManager.onSystemPlatteChange(config)
            assertEquals(emptyList<Theme>(), told)
            assertSame(scene, (ThemeManager.activeTheme as Theme.Pack).scene)
            // the wallpaper's colours changed: read again with them, drawn anew
            val now = ThemePackFiles.systemColors
            ThemePackFiles.systemColors = SystemColors(mapOf("primary" to Argb(0xff123456.toInt())), emptyMap())
            ThemeManager.onSystemPlatteChange(config)
            assertEquals(listOf("mine#dark"), told.map { it.name })
            assertEquals(now, ThemePackFiles.systemColors)
            assertNotEquals(true, scene === (ThemeManager.activeTheme as Theme.Pack).scene)
        } finally {
            ThemeManager.removeOnChangedListener(listener)
        }
    }

    @Test
    fun aKillBetweenAPlacesRenamesLosesNoPack() {
        placed(sample())
        val before = tree(File(packs, "mine"))
        // failing between them: the pack it replaced put back
        ThemePackFiles.beforeInPlace = { throw java.io.IOException("killed") }
        assertTrue(opened(sample(xml(styles = """<style select="candidate" gap="7dp"/>"""))).commit().isFailure)
        ThemePackFiles.beforeInPlace = null
        assertEquals(before, tree(File(packs, "mine")))
        assertEquals(emptyList<String>(), File(packs, ".saving").list().orEmpty().toList())
        // killed between them: the one aside put back at the next start's listing, the new one half written cleared
        File(packs, "mine").renameTo(File(packs, ".saving/old-1c2d/mine").also { it.parentFile!!.mkdirs() })
        File(packs, ".saving/new-3e4f").mkdirs()
        File(packs, ".saving/new-3e4f/theme.xml").writeText("half")
        ThemeFilesManager.forgetSwept()
        ThemeFilesManager.listThemes()
        assertEquals(listOf("mine#light", "mine#dark"), ThemeFilesManager.listPacks().map { it.name })
        assertEquals(before, tree(File(packs, "mine")))
        assertFalse(File(packs, ".saving").exists())
        // one aside whose place another took: cleared, not put over it
        File(packs, ".saving/old-5a6b/mine").mkdirs()
        File(packs, ".saving/old-5a6b/mine/theme.xml").writeText("older")
        ThemeFilesManager.forgetSwept()
        ThemeFilesManager.listPacks()
        assertEquals(before, tree(File(packs, "mine")))
        assertFalse(File(packs, ".saving").exists())
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
