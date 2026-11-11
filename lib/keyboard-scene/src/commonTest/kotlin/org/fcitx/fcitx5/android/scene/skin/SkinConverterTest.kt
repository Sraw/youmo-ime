/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.baidu
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.convert
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.img
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.read
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.sogou
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.text
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkinConverterTest {
    private fun SkinConversion.file(name: String) = files.firstOrNull { it.name == name }

    private fun SkinConversion.image(name: String) = assertNotNull(file(name) as? SkinOutput.Image, "$name in ${files.map { it.name }}")

    @Test
    fun baiduOneVersionIsATheme() {
        val c = convert(baidu())
        val t = read(c)
        assertEquals("Sea Breeze", c.name)
        assertEquals("Sea Breeze", t.name)
        assertEquals("Ann", t.meta?.author)
        assertEquals("0.6.0", t.meta?.minApp)
        assertEquals("preview.png", t.meta?.preview)
        assertEquals(setOf(false), t.variants)
        assertNotNull(t.picture)
        // the board: the bar's strip (126) over the panel (600), across 1080
        val board = c.image("board.png").pixels
        assertEquals(1080 to 726, board.width to board.height)
        assertFalse(board.translucent())
        // the letter's face cut to its opaque body (150×168), the inner rect moved with it
        val key = c.image("l-key-n.png")
        assertEquals(150 to 168, key.pixels.width to key.pixels.height)
        assertFalse(key.shrink)
        assertContains(c.themeXml, "image(l-key-n.png, 24 24 24 24, 3x)")
        // the letters' gap: pitch 108, the face stretched to the 108 of its view less 12 of margin: (108 − 96) / 2 = 6 px,
        // 2dp; rows 150 apart, the view 150 high less 12: 2dp too
        assertContains(c.themeXml, """inset="2dp 2dp"""")
        // the pressed face the letters, the function keys and space share: one file
        assertEquals(1, c.files.count { it.name.endsWith("-p.png") })
        // a pressed face of its own: no tint over it
        assertContains(c.themeXml, """<style select="key:pressed" fill="image(l-key-p.png""")
        assertContains(c.themeXml, """overlay="transparent"""")
        // backspace and shift styled as the function keys: not written again
        assertContains(c.themeXml, """select="key.function" fill="image(l-key-function-n.png""")
        assertFalse(c.themeXml.contains("role=backspace"))
        assertFalse(c.themeXml.contains("role=shift"))
        assertContains(c.themeXml, """select="key.space"""")
        assertContains(c.themeXml, """select="key.enter"""")
        // candidates' colours from the bar's styles; the letter's colour from its glyph picture
        assertEquals("#222222", hexOf(t.colors["candidateText"]))
        assertEquals("#ff6600", hexOf(t.colors["first"]))
        assertEquals("#102030", hexOf(t.colors["keyText"]))
        assertEquals(mapOf(SoundKey.LETTER to "sound-letter.wav"), t.sounds)
        assertEquals(
            setOf(SkinNote.LAYOUT, SkinNote.DECORATION, SkinNote.ANIMATION, SkinNote.LETTER_IMAGES),
            c.notes,
        )
        // the theme's file names are its own
        assertEquals(c.files.map { it.name }.toSet(), c.files.map { it.name }.filter { NAME.matches(it) }.toSet())
    }

    @Test
    fun baiduLightAndDark() {
        val c = convert(baidu(listOf("light/", "dark/")))
        val t = read(c)
        assertEquals(setOf(false, true), t.variants)
        assertContains(c.themeXml, """<colors variant="dark">""")
        assertContains(c.themeXml, """select="@dark key" fill="image(d-key-n.png""")
        assertContains(c.themeXml, """select="@dark keyboard" fill="@keyboard"""")
        assertTrue(SkinNote.DARK_PICTURE in c.notes)
        assertNotNull(c.file("d-key-n.png"))
        val darkKey = t.darkColors?.get("key")
        assertEquals("#333333", hexOf(darkKey))
    }

    @Test
    fun aDarkSkinWithOneVersionIsADarkTheme() {
        val files = baidu()
        files["res/panel.png"] = img(Pixels.filled(1080, 600, 0xFF050505.toInt()))
        files["port/res/default.css"] = files.getValue("port/res/default.css").decodeToString().replace("NM_COLOR=CC336699", "NM_COLOR=000000").encodeToByteArray()
        assertEquals(setOf(true), read(convert(files)).variants)
    }

    @Test
    fun iosKeysOverlapTheirPitch() {
        // view rects 162 wide on a 112 pitch: the image drawn at its size, so the gap is the pitch less its body (150)
        val c = convert(baidu(ios = true))
        // (112 − 150) / 2 < 0: no gap across
        assertContains(c.themeXml, """inset="2dp 0dp"""")
    }

    @Test
    fun baiduWithoutItsBoardIsNoKeyboard() {
        val files = baidu()
        files.remove("port/py_26.ini")
        assertEquals(SkinError.Reason.NO_KEYBOARD, assertFailsWith<SkinError> { convert(files) }.reason)
        val noLetters = baidu()
        noLetters["port/py_26.ini"] = text("[KEY1]\nBACK_STYLE=2\nCENTER=F38\n")
        assertEquals(SkinError.Reason.NO_KEYBOARD, assertFailsWith<SkinError> { convert(noLetters) }.reason)
    }

    @Test
    fun baiduWithColoursOnly() {
        val files = baidu()
        files["port/res/default.css"] = text("[STYLE1]\nNM_COLOR=ffffff\nHL_COLOR=cccccc\n[STYLE3]\nNM_COLOR=111111\n[STYLE4]\nNM_COLOR=d0d0d0\n")
        files["port/gen.ini"] = text("[PANEL]\nBACK_STYLE=4\n")
        files["port/cand1.cnd"] = text("[CAND]\nFORE_STYLE=3\n")
        files["port/py_26.ini"] = text((0 until 26).joinToString("") { "[KEY$it]\nBACK_STYLE=1\nFORE_STYLE=3\nCENTER=${'a' + it}\n" })
        files.remove("demo.png")
        val c = convert(files)
        val t = read(c)
        assertNull(t.picture)
        assertNull(c.file("board.png"))
        assertContains(c.themeXml, """select="key" fill="#ffffff" color="#111111"""")
        assertContains(c.themeXml, """select="key:pressed" fill="#cccccc" overlay="transparent"""")
        assertEquals("#d0d0d0", hexOf(t.colors["keyboard"]))
        assertNull(t.meta?.preview)
    }

    @Test
    fun aTranslucentBoardIsLaidOnItsOwnColour() {
        val files = baidu()
        files["res/panel.png"] = img(Pixels.filled(1080, 600, 0x80336699.toInt()))
        val board = convert(files).image("board.png").pixels
        assertFalse(board.translucent())
    }

    @Test
    fun aBarWithoutABackgroundShowsThePanel() {
        val files = baidu()
        files["port/cand1.cnd"] = text("[CAND]\nFORE_STYLE=3\n")
        val board = convert(files).image("board.png").pixels
        assertEquals(board[5, 5], board[5, 126 + 5])
    }

    @Test
    fun sogouDayAndNight() {
        val c = convert(sogou())
        val t = read(c)
        assertEquals("Warm Orange", t.name)
        assertEquals("Bo", t.meta?.author)
        assertEquals(setOf(false, true), t.variants)
        assertEquals("#331100", hexOf(t.colors["keyText"]))
        assertEquals("#ff6600", hexOf(t.colors["first"]))
        // the face's 4 px margin, half of it each side at 3 px a dp: 1dp
        assertContains(c.themeXml, """inset="1dp 1dp"""")
        assertContains(c.themeXml, """select="key" fill="image(l-key-n.png, 16 16 16 16, 3x)"""")
        assertContains(c.themeXml, """select="key.enter" fill="image(l-key-enter-n.png""")
        assertContains(c.themeXml, """select="qwerty/q" fill="image(l-qwerty-q-n.png""")
        assertContains(c.themeXml, """select="key:pressed" fill="image(l-key-p.png""")
        assertContains(c.themeXml, """color="#ffffff"""")
        assertContains(c.themeXml, """<font name="skin" src="skin-font.ttf"/>""")
        assertEquals(mapOf(SoundKey.LETTER to "sound-letter.wav", SoundKey.SPACE to "sound-space.wav"), t.sounds)
        // three seconds of 48 kHz stereo: cut to one, mono, halved till it is within 100 KB
        val space = (c.file("sound-space.wav") as SkinOutput.Bytes).bytes
        assertTrue(space.size <= 100 * 1024)
        assertEquals(setOf(SkinNote.TEXT_SIZE, SkinNote.DARK_PICTURE), c.notes)
        assertEquals("preview.png", t.meta?.preview)
    }

    @Test
    fun sogouOnePeriodAndMore() {
        val one = sogou(periods = 1)
        assertEquals(setOf(false), read(convert(one)).variants)
        val three = sogou(periods = 3)
        assertTrue(SkinNote.MORE_PERIODS in convert(three).notes)
        // no period folders at all: the skin at the width's folder
        val flat = sogou(periods = 1)
        flat.keys.filter { it.startsWith("1080/status1/") }.forEach { flat[it.replace("status1/", "")] = flat.remove(it)!! }
        flat["time.ini"] = text("")
        assertEquals(setOf(false), read(convert(flat)).variants)
    }

    @Test
    fun sogouLettersAsPictures() {
        val files = sogou(periods = 1)
        val r = "1080/status1/"
        files[r + "phoneSkin.ini"] = files.getValue(r + "phoneSkin.ini").decodeToString().replace("TEXT_COLOR=0xFF331100", "X=1").encodeToByteArray()
        files[r + "a.png"] = img(Pixels(30, 30).also { it.paste(Pixels.filled(10, 20, 0xFF00FF00.toInt()), 10, 5) })
        files[r + "keys.ini"] = text("[Key_A]\nFG_IMAGES=Fg_A\n[Fg_A]\nNORMAL=a.png\n")
        val c = convert(files)
        assertEquals("#00ff00", hexOf(read(c).colors["keyText"]))
        assertTrue(SkinNote.LETTER_IMAGES in c.notes)
    }

    @Test
    fun sogouColourBackgroundAndMissingFaces() {
        val files = sogou(periods = 1)
        val r = "1080/status1/"
        files[r + "phoneSkin.ini"] = text("[RootView]\nBG_STYLE=2\nBG_COLOR=0xFF203040\n[KeyBg_Default]\nNORMAL=key.png\n")
        val c = convert(files)
        assertNull(c.file("board.png"))
        assertEquals("#203040", hexOf(read(c).colors["keyboard"]))
        files[r + "phoneSkin.ini"] = text("[RootView]\nBG_COLOR=0xFF203040\n")
        assertEquals(SkinError.Reason.NO_KEYBOARD, assertFailsWith<SkinError> { convert(files) }.reason)
    }

    @Test
    fun formatsNotRead() {
        assertEquals(SkinError.Reason.UNKNOWN, assertFailsWith<SkinError> { convert(mapOf("a.txt" to text("x"))) }.reason)
        assertEquals(SkinError.Reason.SOGOU_PC, assertFailsWith<SkinError> { convert(mapOf("skin.ini" to text("[x]"))) }.reason)
        val noSize = sogou()
        noSize.keys.filter { it.endsWith("phoneSkin.ini") }.forEach { noSize.remove(it) }
        assertEquals(SkinError.Reason.NO_KEYBOARD, assertFailsWith<SkinError> { convert(noSize) }.reason)
    }

    @Test
    fun namesAreCleaned() {
        assertEquals("a b", SkinTheme.themeName("  a\u0000\n\t b "))
        assertEquals("x-y-z", SkinTheme.themeName("x/y\\z"))
        assertEquals("hidden", SkinTheme.themeName("..hidden"))
        assertEquals("Night", SkinTheme.themeName("Night#dark#LIGHT"))
        assertEquals(64, SkinTheme.themeName("长".repeat(100)).length)
        // no name in the skin: the file's
        val c = convert(baidu(name = " "), fallback = "my skin")
        assertEquals("my skin", c.name)
        assertEquals("skin", convert(baidu(name = "#dark"), fallback = "").name)
        // quotes and markup in a name or an author are the theme's text, not its XML
        val q = convert(baidu(name = "a\"<b>&", author = "x\"y"))
        assertEquals("a\"<b>&", read(q).name)
        assertEquals("x\"y", read(q).meta?.author)
    }

    @Test
    fun imagesPastTheLimitAreNotDecoded() {
        val p = SkinFixtures.Platform(maxPixels = 100_000)
        val c = convert(baidu(), p)
        // the panel and the preview past the cap: colours instead, said so
        assertTrue(SkinNote.IMAGES in c.notes)
        assertNull(c.file("preview.png"))
        read(c)
    }

    @Test
    fun largeKeyFacesAreScaledDown() {
        val files = baidu()
        val big = Pixels(1400, 1400).also { it.paste(Pixels.filled(1200, 1200, 0xFFFFFFFF.toInt()), 100, 100) }
        files["res/big.png"] = img(big)
        files["res/big.til"] = text("[IMG1]\nSOURCE_RECT=0,0,1400,1400\nINNER_RECT=300,300,800,800\n")
        files["port/res/default.css"] = files.getValue("port/res/default.css").decodeToString().replace("NM_IMG=keys,1\nHL_IMG=keys,3", "NM_IMG=big,1").encodeToByteArray()
        val c = convert(files)
        val key = c.image("l-key-n.png").pixels
        // at most 512 a side, 3 px a dp scaled alike: 1.28 px a dp, drawn at a whole 1x, so scaled again to 400 (1200 /
        // 3); the inner rect's 200 px of 1200 alike: 67
        assertEquals(400 to 400, key.width to key.height)
        assertContains(c.themeXml, "image(l-key-n.png, 67 67 67 67, 1x)")
        read(c)
    }

    @Test
    fun aBrokenNumberIsABrokenSkinNotACrash() {
        val files = baidu()
        files["res/keys.til"] = text("[IMG1]\nSOURCE_RECT=599,259,99999,99999\nINNER_RECT=-5,-5,1,1\n[IMG2]\nSOURCE_RECT=-100,-100,5,5\n")
        read(convert(files))
        files["port/gen.ini"] = text("[PANEL]\nSIZE=-1,99999999\nBACK_STYLE=,\n[CAND]\nVIEW_RECT=a,b,c,d\n")
        read(convert(files))
    }

    // the lines of the styles of [select], one string
    private fun SkinConversion.style(select: String) = themeXml.lines().filter { "select=\"$select\"" in it }.joinToString("\n")

    @Test
    fun aKindWithoutAPressedFaceOfItsOwnKeepsThePressTint() {
        // the letters have a pressed face, Return none: pressing Return still shows (the letters' "no tint" is theirs)
        val c = convert(baidu())
        assertTrue(c.style("key:pressed").lines().any { "l-key-p.png" in it && "overlay=\"transparent\"" in it }, c.style("key:pressed"))
        val enter = c.style("key.enter:pressed")
        assertContains(enter, """fill="image(l-key-n.png, 24 24 24 24, 3x)"""")
        assertContains(enter, """overlay="@keyPressHighlight"""")
        // the tint for any key no kind names, under the kinds' own
        assertContains(c.themeXml, """<style select="key:pressed" overlay="@keyPressHighlight"/>""")
        read(c)
    }

    @Test
    fun theDarkVersionRestatesWhatTheLightOneSet() {
        // the night period without the letters' pressed face nor Q's own face: the day's never show at night
        val files = sogou()
        val night = "1080/status2/phoneSkin.ini"
        files[night] = files.getValue(night).decodeToString()
            .replace("PRESSED=key_p.png,20,86,20,136\n", "")
            .replace("[KeyBg_Q]\nNORMAL=q.png\n[Key_Q]\nBG_IMAGES=KeyBg_Q\n", "")
            .encodeToByteArray()
        val c = convert(files)
        val pressed = c.style("@dark key:pressed")
        assertContains(pressed, """fill="image(d-key-n.png""")
        assertContains(pressed, """overlay="@keyPressHighlight"""")
        assertContains(c.style("@dark qwerty/q"), """fill="image(d-key-n.png""")
        assertContains(c.style("@dark qwerty/q:pressed"), """overlay="@keyPressHighlight"""")
        read(c)
    }

    @Test
    fun theGapIsLessUpAndDownAtCompact() {
        // 2 dp up and down, as the app's own themes' at 紧凑 (40%, 1 dp at least); across as it is
        val c = convert(baidu())
        assertContains(c.themeXml, """<style select="@compact key" inset="1dp 2dp"/>""")
        read(c)
    }

    @Test
    fun aBaiduBoardSizedPastAPictureIsMadeWithinIt() {
        // a few bytes of gen.ini asking for a 4096×6144 board: put together within the picture's bounds
        val files = baidu()
        files["port/gen.ini"] = text("[PANEL]\nSIZE=4096,4096\nBACK_STYLE=4,5\n[CAND]\nLAYOUT_NAME=cand1\nVIEW_RECT=0,0,4096,2048\n")
        val board = assertNotNull(BaiduSkin(SkinFiles(files, SkinFixtures.Platform())).look(Variant.LIGHT).board)
        assertTrue(board.width <= SkinTheme.MAX_PICTURE_SIDE && board.height <= SkinTheme.MAX_PICTURE_SIDE, "${board.width}x${board.height}")
        assertTrue(board.width.toLong() * board.height <= SkinLimits.MAX_IMAGE_PIXELS * 101 / 100, "${board.width}x${board.height}")
    }

    @Test
    fun onlyLettersDrawnAsPicturesAreSaidSo() {
        // the letters' labels a colour, a function key's a picture: no letter drawn as a picture
        val files = baidu()
        files["port/py_26.ini"] = files.getValue("port/py_26.ini").decodeToString()
            .replace("FORE_STYLE=8", "FORE_STYLE=x").replace("FORE_STYLE=3", "FORE_STYLE=8").replace("FORE_STYLE=x", "FORE_STYLE=3")
            .encodeToByteArray()
        assertFalse(SkinNote.LETTER_IMAGES in convert(files).notes)
    }

    @Test
    fun aSkinIniInAnotherFormatsSkinIsNoComputerSkin() {
        // a Baidu skin that happens to hold a skin.ini: read as Baidu's, not refused as a Sogou computer skin
        assertEquals("Sea Breeze", convert(baidu() + ("skin.ini" to text("[x]"))).name)
    }

    companion object {
        private val NAME = Regex("[a-z0-9.-]+")

        fun hexOf(argb: Argb?): String? = argb?.let { SkinTheme.hex(it.value) }
    }
}
