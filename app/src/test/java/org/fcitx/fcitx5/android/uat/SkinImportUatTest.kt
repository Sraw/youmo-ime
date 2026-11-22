/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.BitmapFactory
import android.graphics.Color
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.layout.CustomLayouts
import org.fcitx.fcitx5.android.data.layout.ThemeLayoutsToList
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.data.theme.ThemePackFixture
import org.fcitx.fcitx5.android.scene.skin.SkinNote
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemePackDialogs
import org.fcitx.fcitx5.android.uat.SkinUatSamples.zip
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Other input methods' skins imported in the app (uat/14-settings.md ST-200–214): each sample as the device script makes
 * it, through the app's own codec, conversion, import dialog's text and placing, as the settings' import does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class SkinImportUatTest : ThemePackFixture() {

    @Before
    fun realFiles() {
        // the pictures are real PNGs, the font a real one
        ThemeFilesManager.probe = real
        ThemeFilesManager.versionName = "0.6.3"
    }

    private fun open(files: Map<String, ByteArray>, name: String) = open(zip(files), name)

    private fun open(bytes: ByteArray, name: String) = ThemeFilesManager.openSkin(bytes.inputStream(), name, ThemeManager.getAllThemes())

    private fun opened(files: Map<String, ByteArray>, name: String) = open(files, name).getOrThrow()

    private val ThemeFilesManager.Opened.Pack.xml get() = File(staged.from, ThemePackFiles.THEME_XML).readText()

    private fun ThemeFilesManager.Opened.Pack.confirm() = ThemePackDialogs.confirmText(appContext, this)

    private fun placed(pack: ThemeFilesManager.Opened.Pack, withLayouts: Boolean = false) =
        pack.commit(withLayouts).getOrThrow().also { ThemeManager.packPlaced(it[0].file) }

    private fun placedXml(name: String) = File(packs, "$name/theme.xml").readText()

    private fun minApp(xml: String) = Regex("""min-app="([^"]+)"""").find(xml)?.groupValues?.get(1)

    // the line of theme.xml's style for [selector]
    private fun line(xml: String, selector: String) = xml.lines().filter { """select="$selector"""" in it }

    private fun exported(pack: org.fcitx.fcitx5.android.data.theme.Theme.Pack): Set<String> {
        val out = ByteArrayOutputStream()
        ThemePackFiles.export(pack, out)
        val names = HashSet<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z -> while (true) names += (z.nextEntry ?: break).name }
        return names
    }

    private fun pixel(file: File, x: Int, y: Int) = BitmapFactory.decodeFile(file.path).getPixel(x, y)

    private fun s(id: Int, vararg args: Any) = appContext.getString(id, *args)

    private fun note(id: Int, vararg args: Any) = "· " + s(id, *args)

    @Test
    @Uat("ST-200")
    fun baiduTextSizeWeightMarkAndPressedIconColorComeOver() {
        val pack = opened(SkinUatSamples.textSized(68), "uat-skin2.bds")
        val xml = pack.xml
        val letter = line(xml, "key.letter").joinToString()
        val scale = Regex("""scale="(\d+)%"""").find(letter)?.groupValues?.get(1)?.toInt()
        assertTrue(letter, scale != null && scale in 115..125)
        assertTrue(letter, """weight="700"""" in letter)
        assertTrue(xml, line(xml, "key mark").any { "#00aa00" in it })
        assertTrue(xml, line(xml, "key.function:pressed").any { """icon-color="#ffff00"""" in it })
        assertFalse(SkinNote.TEXT_SIZE in pack.notes)
        assertFalse(pack.confirm(), pack.confirm().contains(s(R.string.theme_pack_skin_note_text_size)))
    }

    @Test
    @Uat("ST-201")
    fun rasterIconsAndThePressedIconAreTheSkins() {
        val pack = opened(SkinUatSamples.withIcons(), "uat-skin-icons.bds")
        val xml = pack.xml
        // each of the skin's, its own colour; backspace's pressed another picture
        val icons = Regex("""<icon name="([^"]+)" src="([^"]+)"""").findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        for (name in listOf("backspace", "shift", "shift-once", "shift-locked", "enter")) assertNotNull("$name in $icons", icons[name])
        val colors = SkinUatSamples.ICON_COLORS
        for ((name, i) in listOf("backspace" to 0, "shift" to 2, "shift-once" to 3, "shift-locked" to 4, "enter" to 5)) {
            assertEquals(name, colors[i], pixel(File(pack.staged.from, icons.getValue(name)), 16, 24))
        }
        val pressed = icons.entries.single { it.value != icons["backspace"] && it.key.startsWith("backspace") }
        assertTrue(xml, line(xml, "key[role=backspace]:pressed icon").any { "content=\"icon(${pressed.key})\"" in it })
        assertEquals(colors[1], pixel(File(pack.staged.from, pressed.value), 16, 24))
        assertEquals("0.6.3", minApp(xml))
        // 信息: needs 0.6.3
        val theme = placed(pack)[0]
        assertEquals("0.6.3", theme.scene.meta?.minApp)
        val info = ThemePackDialogs.infoText(appContext, theme).lines().last()
        assertEquals(appContext.getString(R.string.theme_info_format_min_app, "4.0", "0.6.3"), info)
    }

    @Test
    @Uat("ST-202")
    fun toolbarIconsThatMatchComeOverAndTheRestAreCounted() {
        val pack = opened(SkinUatSamples.withToolbar(), "uat-skin-toolbar.bdi")
        assertEquals(listOf("1"), pack.notes[SkinNote.TOOLBAR_ICONS])
        assertTrue(pack.confirm(), pack.confirm().contains(note(R.string.theme_pack_skin_note_toolbar_icons_count, 1)))
        val xml = pack.xml
        for (name in listOf("emoji", "clipboard", "input-method-settings")) assertTrue("$name: $xml", Regex("""<icon name="$name" src=""").containsMatchIn(xml))
        // the pressed pictures not taken
        assertFalse(xml, Regex("""<icon name="emoji[^"]*-pressed""").containsMatchIn(xml))
    }

    @Test
    @Uat("ST-203")
    fun anEncryptedSogouSkinImportsLikeItsPlainCopy() {
        val files = SkinUatSamples.sogou()
        val plain = opened(files, "uat-sogou.ssf")
        val sealed = open(SkinUatSamples.sogouSealed(files), "uat-sogou-enc.ssf").getOrThrow()
        assertEquals(plain.confirm(), sealed.confirm())
        assertFalse(sealed.confirm().contains("加密"))
        fun tree(p: ThemeFilesManager.Opened.Pack) = p.staged.from.walkTopDown().filter { it.isFile }.associate { it.relativeTo(p.staged.from).path to it.readBytes().toList() }
        assertEquals(tree(plain), tree(sealed))
        // one broken: read as no skin, not as encrypted
        val broken = SkinUatSamples.sogouSealed(files).let { it.copyOf(it.size - 16) }
        assertEquals(s(R.string.exception_skin_sogou_encrypted), open(broken, "uat-bad.ssf").exceptionOrNull()?.message)
    }

    @Test
    @Uat("ST-204")
    fun aSogouSkinsFontDrawsTheLettersAndIsExported() {
        val pack = opened(SkinUatSamples.sogou(font = SkinUatSamples.font), "uat-sogou-font.ssf")
        val xml = pack.xml
        val font = Regex("""<font name="([^"]+)" src="([^"]+)"""").find(xml)
        assertNotNull(xml, font)
        // the keys' text in the skin's own font, not merely some font
        assertTrue(xml, xml.lines().any { "<style " in it && """font="${font!!.groupValues[1]}"""" in it })
        val theme = placed(pack)[0]
        assertTrue(exported(theme).contains(font!!.groupValues[2]))
    }

    @Test
    @Uat("ST-205")
    fun skinLayoutsAreOfferedUncheckedAndLeftOut() {
        val pack = opened(SkinUatSamples.withLayouts(), "uat-skin-layout.bds")
        assertTrue(pack.fromSkin)
        assertEquals(listOf("nine.xml", "qwerty.xml"), pack.staged.layouts.sorted())
        val offered = appContext.resources.getQuantityString(R.plurals.theme_pack_skin_layouts, 2, 2, "uat 布局皮肤 · 26 键, uat 布局皮肤 · 九键")
        val text = pack.confirm()
        assertTrue(text, text.contains(offered.substringBefore("：")))
        // the dialog's box is ticked as ThemeListFragment's isChecked = !fromSkin (ThemeImportLayoutsTest); taken as left
        placed(pack, withLayouts = false)
        assertFalse(File(packs, "uat 布局皮肤/layouts").exists())
        assertEquals("0.6.0", minApp(placedXml("uat 布局皮肤")))
    }

    @Test
    @Uat("ST-206")
    fun checkedSkinLayoutsAreListedAndKeepTheAppsKeys() {
        val pack = opened(SkinUatSamples.withLayouts(), "uat-skin-layout.bds")
        val text = pack.confirm()
        // the nine keys lacking 0: listed all the same, said once in the skin's notes
        assertTrue(text, pack.notes.containsKey(SkinNote.LAYOUT_LACKS))
        val noZero = s(R.string.theme_pack_skin_note_layout_lacks, "九键", s(R.string.layout_lacks_digits, "0"))
        assertTrue(text, text.contains("· $noZero"))
        assertFalse(text, pack.staged.layoutReport.listed.any { text.contains(s(R.string.theme_pack_layouts_lacks, it)) })
        placed(pack, withLayouts = true)
        assertTrue(File(packs, "uat 布局皮肤/layouts/qwerty.xml").isFile)
        assertEquals("0.6.3", minApp(placedXml("uat 布局皮肤")))
        // the app's keys: no popup or mark of the skin's written in
        val qwerty = File(packs, "uat 布局皮肤/layouts/qwerty.xml").readText()
        assertFalse(qwerty, Regex("""\b(popup|alt)=""").containsMatchIn(qwerty))
        try {
            val made = ThemeLayoutsToList.run(ThemeFilesManager.listPacks().first { it.file == "uat 布局皮肤" }, dir)!!
            // in 外观 → 布局, by the names the dialog said
            assertEquals(setOf("uat 布局皮肤 · 26 键", "uat 布局皮肤 · 九键"), CustomLayouts.list(dir).map { it.name }.toSet())
            assertEquals(made.map { it.done.id }.toSet(), CustomLayouts.list(dir).map { it.id }.toSet())
        } finally {
            CustomLayouts.forgetAll()
            dir.deleteRecursively()
        }
    }

    @Test
    @Uat("ST-207")
    fun aBdaSkinImportsWithItsNineSliceFaces() {
        val pack = opened(SkinUatSamples.bda(), "uat-skin.bda")
        val text = pack.confirm()
        assertFalse(text, text.contains(s(R.string.exception_skin_unsupported, "").trim()))
        assertTrue(text, pack.notes.containsKey(SkinNote.FRAME_ANIMATION))
        val xml = pack.xml
        assertTrue(xml, xml.contains("#6a1b9a"))
        // the face cut to its body, stretched between its inner rect: its corners kept, not drawn out
        val face = Regex("""fill="image\((l-key-n\.png), (\d+) (\d+) (\d+) (\d+)""").find(line(xml, "key.letter").joinToString())
        assertNotNull(xml, face)
        assertTrue(xml, face!!.groupValues.drop(2).all { it.toInt() > 0 })
        val bmp = BitmapFactory.decodeFile(File(pack.staged.from, face.groupValues[1]).path)
        assertEquals(Color.TRANSPARENT, bmp.getPixel(0, 0))
        assertEquals(SkinUatSamples.BDA_FACE, bmp.getPixel(bmp.width / 2, bmp.height / 2))
    }

    @Test
    @Uat("ST-208")
    fun aTrimeThemeOfIncludesAndPatchesImports() {
        val pack = opened(SkinUatSamples.trime(), "uat-trime-include.zip")
        assertTrue(pack.xml, pack.xml.contains("#ff0000"))
        assertEquals(listOf("1"), pack.notes[SkinNote.INCLUDES])
        assertTrue(pack.confirm(), pack.confirm().contains(note(R.string.theme_pack_skin_note_includes_count, 1)))
    }

    @Test
    @Uat("ST-209")
    fun theDarkPictureIsDrawnInDarkModeAndExported() {
        val pack = opened(SkinUatSamples.baidu(listOf("light/", "dark/"), name = "uat 深色皮肤"), "uat-skin-dark.bds")
        val xml = pack.xml
        assertTrue(xml, xml.contains("""<picture variant="dark""""))
        val board = File(pack.staged.from, "board.png")
        val dark = File(pack.staged.from, "board-dark.png")
        fun light(c: Int) = Color.red(c) + Color.green(c) + Color.blue(c)
        assertTrue(light(pixel(dark, 100, 100)) < light(pixel(board, 100, 100)))
        val theme = placed(pack)[0]
        // the dark version's look draws the dark picture, the light one's the light
        val dir = File(packs, theme.file).path
        assertTrue(ThemePackFiles.scene(dir, dark = true).shownPicture!!.src.endsWith("board-dark.png"))
        assertTrue(ThemePackFiles.scene(dir, dark = false).shownPicture!!.src.endsWith("board.png"))
        assertTrue(exported(theme).containsAll(listOf("board.png", "board-dark.png")))
    }

    @Test
    @Uat("ST-210")
    fun letterStickersReplaceTheLettersOnTwentySixKeysOnly() {
        val pack = opened(SkinUatSamples.withLetters(), "uat-skin-letters.bds")
        assertFalse(SkinNote.LETTER_IMAGES in pack.notes)
        val xml = pack.xml
        val stickers = xml.lines().filter { "sticker" in it }
        assertTrue(xml, stickers.isNotEmpty())
        for (c in 'a'..'z') {
            assertTrue("$c", File(pack.staged.from, "st-$c.png").isFile)
            assertTrue("$c", File(pack.staged.from, "st-$c-p.png").isFile)
        }
        // on the 26 keys' letters only: the nine keys draw their own in the skin's colour
        assertTrue(stickers.joinToString("\n"), stickers.all { "select=\"qwerty/" in it })
        assertEquals("0.6.3", minApp(xml))
    }

    @Test
    @Uat("ST-211")
    fun anIflytekSkinImportsWithItsFacesAndColors() {
        val pack = opened(SkinUatSamples.xunfei(), "uat-skin.it")
        assertEquals("uat 讯飞", pack.staged.name)
        val xml = pack.xml
        assertTrue(xml, xml.contains("#ffffff"))
        assertTrue(pack.notes.containsKey(SkinNote.NO_DARK))
        assertTrue(pack.notes.containsKey(SkinNote.LAYOUT))
        // the pressed face lighter than the key's
        val faces = Regex("""image\(([^,]+\.png)""").findAll(xml).map { it.groupValues[1] }.toSet()
        fun middle(name: String) = BitmapFactory.decodeFile(File(pack.staged.from, name).path).let { it.getPixel(it.width / 2, it.height / 2) }
        val colors = faces.filter { File(pack.staged.from, it).isFile }.map(::middle).toSet()
        assertTrue("$colors", SkinUatSamples.XUNFEI_KEY in colors && SkinUatSamples.XUNFEI_KEY_PRESSED in colors)
    }

    @Test
    @Uat("MN-27")
    fun aSkinsPressShrinkComesOverOnBackspace() {
        val xml = opened(SkinUatSamples.pressing(), "uat-skin-press.bds").xml
        val pressed = line(xml, "key[role=backspace]:pressed")
        assertTrue(xml, pressed.any { """press-scale="90%"""" in it && "transition=" in it })
        assertTrue(xml, xml.lines().none { "press-scale" in it && "backspace" !in it })
    }

    @Test
    @Uat("ST-213")
    fun plainConversionsKeepMinApp060AndNewOnesWrite063() {
        val plain = opened(SkinUatSamples.baidu(), "uat-skin.bds")
        assertEquals("0.6.0", minApp(plain.xml))
        val icons = opened(SkinUatSamples.withIcons(), "uat-skin-icons.bds")
        assertEquals("0.6.3", minApp(icons.xml))
        // exported and taken by an older app: 0.6.0's by 0.6.2, 0.6.3's refused
        val themes = listOf(placed(plain)[0], placed(icons)[0])
        val bytes = themes.map { ByteArrayOutputStream().also { o -> ThemePackFiles.export(it, o) }.toByteArray() }
        assertFalse(ThemePackFiles.minAppRefused("0.6.0", "0.6.2"))
        assertTrue(ThemePackFiles.minAppRefused("0.6.3", "0.6.2"))
        ThemeFilesManager.versionName = "0.6.2"
        File(packs, themes[0].file).deleteRecursively()
        File(packs, themes[1].file).deleteRecursively()
        ThemeManager.refreshThemes()
        assertTrue(open(bytes[0]).isSuccess)
        val refused = open(bytes[1]).exceptionOrNull()?.message
        assertNotNull(refused)
        assertTrue(refused, refused!!.contains("0.6.3"))
        // refused, the list as it was: the 0.6.3 one not put in
        ThemeManager.refreshThemes()
        assertFalse(File(packs, themes[1].file).exists())
        assertTrue(ThemeManager.getAllThemes().none { it.name == themes[1].name })
    }

    @Test
    @Uat("ST-214")
    fun unsupportedSkinsSayWhichAndAreNotImported() {
        val formats = mapOf(
            "uat-shouxin.zip" to R.string.skin_format_shouxin,
            "uat-gboard.zip" to R.string.skin_format_gboard,
            "x.cskin" to R.string.skin_format_cang,
            "x.apk" to R.string.skin_format_apk,
        )
        for ((name, bytes) in SkinUatSamples.unsupported()) {
            val said = open(bytes, name).exceptionOrNull()?.message
            assertEquals(name, s(R.string.exception_skin_unsupported, s(formats.getValue(name))), said)
        }
        nothingLeft()
    }
}
