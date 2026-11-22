/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.engine.Content
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.skin.BaiduFixtures.append
import org.fcitx.fcitx5.android.scene.skin.BaiduFixtures.edit
import org.fcitx.fcitx5.android.scene.skin.BaiduFixtures.labelled
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.baidu
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.convert
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.face
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.img
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.read
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.text
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaiduClassicTest {
    private fun SkinConversion.lines(select: String) = themeXml.lines().filter { "select=\"$select\"" in it }

    private fun SkinConversion.has(select: String, attr: String) = assertTrue(lines(select).any { attr in it }, "$attr on $select in\n$themeXml")

    private fun skin(files: Map<String, ByteArray>) = BaiduSkin(SkinFiles(files, SkinFixtures.Platform()))

    private fun look(files: Map<String, ByteArray>) = skin(files).look(Variant.LIGHT)

    // with the app's layouts, as the app converts
    private fun withLayouts(files: Map<String, ByteArray>) = SkinFixtures.convertWith(skin(files))

    // the app's text over its key at the standard height, as the engine has them
    private val r0 = Content.DEFAULT_TEXT_DP / (LayoutCheck.keysDp(LayoutCheck.SCREENS.first(), compact = false) / 4.0)

    // a FONT_SIZE for a letter's key 150 high, and the scale it is
    private fun size(percent: Int) = (percent / 100.0 * r0 * 150).roundToInt()
    private fun percent(size: Int) = (size / 150.0 / r0 * 100).roundToInt()

    private fun fnv(s: String): ULong {
        var h = 0xcbf29ce484222325uL
        for (b in s.encodeToByteArray()) h = (h xor b.toUByte().toULong()) * 0x100000001b3uL
        return h
    }

    @Test
    fun aSkinWithoutTheNewSettingsIsWrittenAsBefore() {
        assertEquals(ONE_VERSION, convert(baidu()).themeXml)
        val two = convert(baidu(listOf("light/", "dark/"))).themeXml
        assertEquals(8281 to 0x7aef7d19d80a559buL, two.encodeToByteArray().size to fnv(two))
        val ios = convert(baidu(ios = true)).themeXml
        assertEquals(4375 to 0x930fb47f923e1c17uL, ios.encodeToByteArray().size to fnv(ios))
    }

    @Test
    fun aPressedLabelColoursItsIconToo() {
        convert(baidu()).has("key.function:pressed", """icon-color="#ff0000"""")
    }

    @Test
    fun theSecondForeColourIsTheMarks() {
        val c = convert(labelled())
        assertContains(c.lines("key mark").single(), """color="#80445566"""")
        assertContains(c.lines("key:pressed mark").single(), """color="#80aa0000"""")
        assertEquals("#112233", read(c).colors["keyText"]?.let { SkinTheme.hex(it.value) })
        // a picture second: no mark
        assertTrue(convert(baidu()).lines("key mark").isEmpty())
    }

    @Test
    fun aLettersTextSizeIsItsScaleOnLettersAlone() {
        for (p in listOf(120, 88)) {
            val n = size(p)
            val c = convert(labelled("FONT_SIZE=$n"))
            assertContains(c.lines("key.letter").single(), """scale="${percent(n)}%"""")
            assertFalse(c.lines("key").any { "scale" in it })
            assertFalse(SkinNote.TEXT_SIZE in c.notes)
            read(c)
        }
        // near the app's: not written
        assertTrue(convert(labelled("FONT_SIZE=${size(103)}")).lines("key.letter").isEmpty())
        // past what a theme takes: held to it, and said
        val big = convert(labelled("FONT_SIZE=${size(150)}"))
        assertContains(big.lines("key.letter").single(), """scale="130%"""")
        assertContains(big.notes.keys, SkinNote.TEXT_SIZE)
        read(big)
    }

    @Test
    fun lettersOfSizesOfTheirOwnTakeTheMedianAndSaySo() {
        val files = labelled("FONT_SIZE=${size(110)}")
        files.append("port/res/default.css", "[STYLE12]\nNM_COLOR=112233\nFONT_SIZE=${size(125)}\n")
        files.edit("port/py_26.ini", "FORE_STYLE=10,11\nCENTER=q\n", "FORE_STYLE=12,11\nCENTER=q\n")
        val c = convert(files)
        assertContains(c.lines("key.letter").single(), """scale="${percent(size(110))}%"""")
        assertContains(c.notes.keys, SkinNote.TEXT_SIZE)
    }

    @Test
    fun aFunctionKeysTextSizeIsItsOwn() {
        val files = baidu()
        files.edit("port/res/default.css", "[STYLE3]\n", "[STYLE3]\nFONT_SIZE=${size(115)}\n")
        val c = convert(files)
        c.has("key.function", """scale="${percent(size(115))}%"""")
        assertTrue(c.lines("key.letter").isEmpty())
    }

    @Test
    fun aFontWeightIsBoldOrLight() {
        assertContains(convert(labelled("FONT_WEIGHT=700")).lines("key.letter").single(), """weight="700"""")
        assertContains(convert(labelled("FONT_WEIGHT=300")).lines("key.letter").single(), """weight="300"""")
        assertTrue(convert(labelled("FONT_WEIGHT=500")).lines("key.letter").isEmpty())
        assertFalse(convert(labelled("FONT_WEIGHT=700")).lines("key").any { "weight" in it })
    }

    private fun animated(anims: String, style: String = "PRESS_ANIM=1"): MutableMap<String, ByteArray> {
        val files = baidu()
        files["port/res/anim.ini"] = text(anims)
        files.append("port/res/default.css", "[STYLE12]\n$style\n")
        files.edit("port/py_26.ini", "FORE_STYLE=8\nCENTER=", "FORE_STYLE=8\nANIM_STYLE=12\nCENTER=")
        return files
    }

    @Test
    fun aPressScaleIsTakenWithItsTimeAtMost80Ms() {
        val c = convert(animated("[ANIM1]\nTYPE=4\nFROM=100,100\nTO=90,90\nDURATION=255\n"))
        val pressed = c.lines("key:pressed").single { "press-scale" in it }
        assertContains(pressed, """press-scale="90%"""")
        assertContains(pressed, """transition="80ms ease-out"""")
        // the enter key's back style names the same one: carried over, not said
        assertFalse(SkinNote.ANIMATION in c.notes)
        read(c)
        // one step of a list that moves, the rest a fade that does not
        val listed = convert(animated("[ANIM1]\nBUILD_LIST=2,3\n[ANIM2]\nFROM=255\nTO=255\n[ANIM3]\nFROM=100,100\nTO=95,95\nDURATION=40\n"))
        assertContains(listed.lines("key:pressed").single { "press-scale" in it }, """transition="40ms ease-out"""")
    }

    @Test
    fun aPressSinkIsTakenInDp() {
        // 6 skin pixels of 1080 across a 360 dp phone
        val c = convert(animated("[ANIM1]\nTYPE=2\nFROM_PX=0,0\nTO_PX=0,6\nDURATION=50\n"))
        val pressed = c.lines("key:pressed").single { "press-sink" in it }
        assertContains(pressed, """press-sink="2dp"""")
        assertContains(pressed, """transition="50ms ease-out"""")
        // sideways, or past 4dp: not a sink
        assertTrue(convert(animated("[ANIM1]\nFROM_PX=0,0\nTO_PX=6,0\n")).lines("key:pressed").none { "press-sink" in it })
        assertTrue(convert(animated("[ANIM1]\nFROM_PX=0,0\nTO_PX=0,30\n")).lines("key:pressed").none { "press-sink" in it })
    }

    @Test
    fun whatMovesOtherwiseIsSaid() {
        val particles = convert(animated("[ANIM1]\nCATEGORY=3\n"))
        assertContains(particles.notes.keys, SkinNote.ANIMATION)
        assertTrue(particles.lines("key:pressed").none { "press-scale" in it })
        val fade = convert(animated("[ANIM1]\nFROM=0\nTO=255\n"))
        assertContains(fade.notes.keys, SkinNote.ANIMATION)
        // the keyboard's showing
        val files = animated("[ANIM1]\nFROM=100,100\nTO=90,90\n[ANIM2]\nFROM=0,0\nTO=100,100\n")
        files.edit("port/res/default.css", "[STYLE4]\n", "[STYLE4]\nSHOW_ANIM=2\n")
        val shown = convert(files)
        assertContains(shown.notes.keys, SkinNote.KEYBOARD_ANIMATION)
        assertFalse(SkinNote.ANIMATION in shown.notes)
    }

    // a board's keys, row by row from [x0]: an action and its width, 150 high; letters of fore style [fore]
    private fun board(vararg rows: Pair<Int, List<Pair<String, Int>>>, fore: String = "8"): ByteArray {
        val s = StringBuilder()
        var n = 0
        rows.forEachIndexed { y, (x0, keys) ->
            var x = x0
            for ((action, w) in keys) {
                val letter = BaiduNames.letter(action) != null
                s.append("[KEY${++n}]\nVIEW_RECT=$x,${y * 150},$w,150\nBACK_STYLE=${if (letter) 1 else 2}\nFORE_STYLE=${if (letter) fore else "3"}\nCENTER=$action\n")
                x += w
            }
        }
        return text(s.toString())
    }

    private fun letters(s: String, w: Int) = s.map { it.toString() to w }

    private val qwerty = board(
        0 to letters("qwertyuiop", 108),
        54 to letters("asdfghjkl", 108),
        0 to listOf("F10" to 162) + letters("zxcvbnm", 108) + listOf("F36" to 162),
        0 to listOf("F1" to 120, "F6" to 120, "，" to 100, "F38" to 480, "。" to 100, "F39" to 160),
    )

    @Test
    fun theBoardsAreTheAppsLayoutsWhereTheyFit() {
        val files = baidu()
        files["port/py_26.ini"] = qwerty
        // nine keys named by a letter each, the skin's own column of marks beside them
        files["port/py_9.ini"] = board(
            173 to listOf("'" to 238, "b" to 238, "e" to 238, "F36" to 193),
            173 to listOf("h" to 238, "k" to 238, "n" to 238, "F40" to 193),
            173 to listOf("q" to 238, "u" to 238, "x" to 238, "0" to 193),
            0 to listOf("F1" to 173, "F6" to 151, "F7" to 100, "F38" to 462, "F39" to 194),
        ).decodeToString().let { text(it + "[KEY99]\nVIEW_RECT=0,0,173,450\nBACK_STYLE=2\n") }
        val c = withLayouts(files)
        assertEquals(setOf("qwerty", "nine"), c.layouts.keys)
        assertFalse(SkinNote.LAYOUT in c.notes || SkinNote.LAYOUT_SOME in c.notes)
        read(c, c.themeXmlWithLayouts)
        val l = look(files)
        assertEquals(listOf("qwerty", "nine"), l.layouts.map { it.id })
        assertEquals(SkinAction.Letter('2'), l.layouts[1].rows[0][1].action)
    }

    private fun k18(fore: String) = board(
        0 to letters("qetyuop", 154),
        0 to letters("adghkl", 180),
        0 to listOf("'" to 180) + letters("zxvnm", 151) + listOf("F36" to 145),
        0 to listOf("F1" to 140, "F6" to 120, "，" to 100, "F38" to 440, "。" to 100, "F39" to 180),
        fore = fore,
    )

    @Test
    fun eighteenKeysAreTheGroupedLayout() {
        // Baidu's 18 keys are its py_9's, each named by one letter of its group
        val files = baidu()
        files["port/py_26.ini"] = qwerty
        files["port/py_9.ini"] = k18("8")
        val c = withLayouts(files)
        assertContains(c.layouts.keys, "k18")
        // its letters drawn as pictures: not stickers a theme has for 18 keys
        assertContains(c.notes.keys, SkinNote.LETTER_IMAGES)
        val labels = baidu()
        labels["port/py_26.ini"] = qwerty
        labelled(files = labels)["port/py_9.ini"] = k18("10,11")
        assertFalse(SkinNote.LETTER_IMAGES in withLayouts(labels).notes)
    }

    @Test
    fun fourteenKeysAreTheirGroupedLayout() {
        val files = baidu()
        files["port/py_26.ini"] = qwerty
        // a group a key, named by one of its letters
        files["port/py_14.ini"] = board(
            0 to letters("qetuo", 216),
            0 to letters("adgjl", 216),
            0 to listOf("'" to 162) + letters("zcbm", 189) + listOf("F36" to 162),
            0 to listOf("F1" to 140, "F6" to 120, "，" to 100, "F38" to 440, "。" to 100, "F39" to 180),
        )
        val c = withLayouts(files)
        assertEquals(setOf("qwerty", "k14"), c.layouts.keys)
        assertFalse(SkinNote.LAYOUT in c.notes || SkinNote.LAYOUT_SOME in c.notes)
    }

    @Test
    fun aBoardOfKeysTheAppLacksIsSaid() {
        val c = withLayouts(baidu())
        assertTrue(c.layouts.isEmpty())
        assertContains(c.notes.keys, SkinNote.LAYOUT)
    }

    @Test
    fun theToolbarsPicturesAreTheAppsIconsAndTheRestCounted() {
        val files = baidu()
        val icon = img(face(60, 60, 4, 0xFF336699.toInt()))
        for (n in listOf("Emoji_Normal", "EmojiTranslation_Normal", "Emoji_Select", "Weather_Normal", "Setting_Normal")) {
            files["res/logo/1.0/toolbarMenuItem_${n}_iphone@3x.png"] = icon
        }
        files.append("port/cand1.cnd", "[ICON1]\nKEY=F9\nFORE_STYLE=1\n[ICON2]\nKEY=F31\nFORE_STYLE=1\n")
        val l = look(files)
        assertNotNull(l.icons["emoji"])
        assertNotNull(l.icons["input-method-settings"])
        assertNotNull(l.icons["candidates-expand"])
        // Weather and the logo menu
        assertEquals(listOf("2"), l.notes[SkinNote.TOOLBAR_ICONS])
        // all matched, a pressed picture among them: said, with no count
        val matched = baidu()
        matched["res/logo/1.0/toolbarMenuItem_Emoji_Normal_iphone@3x.png"] = icon
        matched["res/logo/1.0/toolbarMenuItem_Emoji_Select_iphone@3x.png"] = icon
        assertEquals(emptyList(), look(matched).notes[SkinNote.TOOLBAR_ICONS])
        assertNull(look(baidu()).notes[SkinNote.TOOLBAR_ICONS])
    }

    @Test
    fun shiftsStatesAreItsTipsPictures() {
        val files = baidu()
        files.edit("port/py_26.ini", "CENTER=F10\n", "CENTER=F10\nSTAT_STYLE=S1_1|S2_2|S14_3\n")
        files.append("port/py_26.ini", "[TIP1]\nFORE_STYLE=1\n[TIP2]\nFORE_STYLE=2\n[TIP3]\nFORE_STYLE=9\n")
        val l = look(files)
        assertNotNull(l.icons["shift-once"])
        assertNotNull(l.icons["shift-locked"])
    }

    // 26 letters under [root] each a picture of its own, in [color] (null: one of each letter's), pressed in red
    private fun stickers(files: MutableMap<String, ByteArray>, root: String = "", color: Int? = null) {
        labelled(root = root, files = files)
        val atlas = Pixels(26 * 40, 80)
        val til = StringBuilder()
        val css = StringBuilder()
        for (i in 0 until 26) {
            atlas.paste(Pixels.filled(10 + i % 10, 30, color ?: (0xFF000000.toInt() or (i * 9))), i * 40 + 10, 5)
            atlas.paste(Pixels.filled(20, 30, 0xFFFF0000.toInt() or (i * 9)), i * 40 + 10, 45)
            til.append("[IMG${i + 1}]\nSOURCE_RECT=${i * 40},0,40,40\n[IMG${i + 27}]\nSOURCE_RECT=${i * 40},40,40,40\n")
            css.append("[STYLE${30 + i}]\nNM_IMG=letter,${i + 1}\nHL_IMG=letter,${i + 27}\n")
            val c = 'a' + i
            files.edit(root + "port/py_26.ini", "FORE_STYLE=10,11\nCENTER=$c\n", "FORE_STYLE=${30 + i},11\nCENTER=$c\n")
        }
        files[root + "res/letter.png"] = img(atlas)
        files[root + "res/letter.til"] = text(til.toString())
        files.append(root + "port/res/default.css", css.toString())
    }

    @Test
    fun lettersEachOfAPictureOfItsOwnAreStickers() {
        val files = SkinFixtures.baidu()
        stickers(files)
        val l = look(files)
        assertEquals(('a'..'z').toSet(), l.letterImages.keys)
        assertNotNull(l.letterImages.getValue('a').pressed)
        val c = convert(files)
        assertFalse(SkinNote.LETTER_IMAGES in c.notes)
        assertContains(c.themeXml, "sticker")
        // the same in both versions: stickers still
        val both = SkinFixtures.baidu(listOf("light/", "dark/"))
        for (r in listOf("light/", "dark/")) stickers(both, r, 0xFF2A2A40.toInt())
        assertContains(convert(both).themeXml, "sticker")
    }

    @Test
    fun lettersDrawnAnewForTheDarkAreTextInEachsColour() {
        // dark letters on the light version, light ones on the dark: one sticker for both would be dark on dark
        val files = SkinFixtures.baidu(listOf("light/", "dark/"))
        stickers(files, "light/", 0xFF2A2A40.toInt())
        stickers(files, "dark/", 0xFFD0D0B8.toInt())
        val c = convert(files)
        assertFalse("sticker" in c.themeXml)
        assertContains(c.notes.keys, SkinNote.LETTER_IMAGES)
        val t = read(c)
        assertEquals("#2a2a40", t.colors["keyText"]?.let { SkinTheme.hex(it.value) })
        assertEquals("#d0d0b8", t.variant(true).colors["keyText"]?.let { SkinTheme.hex(it.value) })
    }

    @Test
    fun aDarkFoldersBoardIsTheDarkPicture() {
        assertContains(convert(baidu(listOf("light/", "dark/"))).themeXml, """<picture variant="dark"""")
    }

    private companion object {
        // theme.xml of SkinFixtures.baidu() as the converter wrote it before key sizes, marks, presses and boards
        const val ONE_VERSION = """<?xml version="1.0" encoding="UTF-8"?>
<theme xmlns="https://youmo-ime/theme/4" name="Sea Breeze" variants="light" version="4.0">
  <meta author="Ann" version="1" min-app="0.6.0" preview="preview.png"/>
  <colors>
    <color name="background" value="#b0bcc8"/>
    <color name="bar" value="#336699"/>
    <color name="keyboard" value="#b0bcc8"/>
    <color name="key" value="#ffffff"/>
    <color name="keyText" value="#102030"/>
    <color name="candidateText" value="#222222"/>
    <color name="candidateLabel" value="alpha(#222222, 70%)"/>
    <color name="candidateComment" value="alpha(#222222, 70%)"/>
    <color name="altKey" value="#8899aa"/>
    <color name="altKeyText" value="#222222"/>
    <color name="accentKey" value="#ffffff"/>
    <color name="accentKeyText" value="#222222"/>
    <color name="keyPressHighlight" value="#1f000000"/>
    <color name="keyShadow" value="#33000000"/>
    <color name="popupBackground" value="#ffffff"/>
    <color name="popupText" value="#222222"/>
    <color name="spaceBar" value="#8899aa"/>
    <color name="divider" value="alpha(#102030, 12%)"/>
    <color name="clipboardEntry" value="#ffffff"/>
    <color name="genericActiveBackground" value="#ff6600"/>
    <color name="genericActiveForeground" value="#ffffff"/>
    <color name="keyBorder" value="transparent"/>
    <color name="keyHint" value="alpha(#102030, 60%)"/>
    <color name="toolbarIcon" value="#222222"/>
    <color name="first" value="#ff6600"/>
  </colors>
  <picture src="board.png">
    <cut orientation="portrait" file="board.png"/>
  </picture>
  <style select="keyboard" fill="picture"/>
  <style select="bar" fill="picture"/>
  <style select="panel" fill="@keyboard"/>
  <style select="key" radius="0" border="none" shadow="none" color="@keyText" icon-color="@keyText" press="tint" inset="2dp 2dp"/>
  <style select="@compact key" inset="1dp 2dp"/>
  <style select="key:pressed" overlay="@keyPressHighlight"/>
  <style select="key" fill="image(l-key-n.png, 24 24 24 24, 3x)" color="#102030"/>
  <style select="key:pressed" fill="image(l-key-p.png, 0 0 0 0, 3x)" overlay="transparent"/>
  <style select="key.function" fill="image(l-key-function-n.png, 24 24 24 24, 3x)" color="#222222" icon-color="#222222"/>
  <style select="key.function:pressed" fill="image(l-key-p.png, 0 0 0 0, 3x)" overlay="transparent" color="#ff0000" icon-color="#ff0000"/>
  <style select="key.space" fill="image(l-key-function-n.png, 24 24 24 24, 3x)" color="#222222" icon-color="#222222"/>
  <style select="key.space:pressed" fill="image(l-key-p.png, 0 0 0 0, 3x)" overlay="transparent" color="#ff0000" icon-color="#ff0000"/>
  <style select="key.enter" fill="image(l-key-n.png, 24 24 24 24, 3x)" color="#222222" icon-color="#222222"/>
  <style select="key.enter:pressed" fill="image(l-key-n.png, 24 24 24 24, 3x)" overlay="@keyPressHighlight" color="#ff0000" icon-color="#ff0000"/>
  <style select="key mark, key shortcut" color="@keyHint" icon-color="@keyHint"/>
  <style select="candidate" color="@candidateText"/>
  <style select="candidate:first" color="@first"/>
  <style select="candidate label" color="@candidateLabel"/>
  <style select="candidate comment" color="@candidateComment"/>
  <style select="candidates-divider" fill="transparent"/>
  <style select="candidates-panel" fill="@keyboard"/>
  <style select="preedit" color="#ff6600" cursor-color="@first"/>
  <style select="toolbar" icon-color="@toolbarIcon"/>
  <style select="toolbar:selected" icon-color="@first"/>
  <style select="preview" fill="image(l-key-n.png, 24 24 24 24, 3x)" color="@popupText"/>
  <style select="popup" fill="@popupBackground" radius="6dp" shadow="0 2dp 6dp #33000000" color="@popupText"/>
  <style select="popup:selected" fill="@genericActiveBackground" color="@genericActiveForeground"/>
  <style select="menu" fill="@popupBackground" radius="6dp" color="@popupText" icon-color="@popupText"/>
  <style select="menu:selected" fill="@genericActiveBackground" color="@genericActiveForeground" icon-color="@genericActiveForeground"/>
  <style select="clipboard" fill="@clipboardEntry" color="@keyText"/>
  <style select="picker categories" fill="@altKey" color="@altKeyText"/>
  <style select="editing" fill="@altKey"/>
  <style select="voice mic" fill="@first" icon-color="@genericActiveForeground"/>
  <sounds>
    <sound key="letter" src="sound-letter.wav"/>
  </sounds>
</theme>
"""
    }
}
