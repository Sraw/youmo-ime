/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.BaiduFixtures.bda
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.read
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.text
import org.fcitx.fcitx5.android.scene.skin.SkinTheme.Companion.hex
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class BaiduBdaTest {
    private fun skin(files: Map<String, ByteArray>) = BaiduBdaSkin(SkinFiles(files, SkinFixtures.Platform()))

    private fun convert(files: Map<String, ByteArray>) = SkinFixtures.convertWith(skin(files))

    private fun SkinConversion.lines(select: String) = themeXml.lines().filter { "select=\"$select\"" in it }

    private fun SkinConversion.has(select: String, attr: String) = assertTrue(lines(select).any { attr in it }, "$attr on $select in\n$themeXml")

    private fun colour(c: SkinConversion, name: String) = read(c).colors[name]?.let { hex(it.value) }

    @Test
    fun aBdaIsATheme() {
        val c = convert(bda())
        val t = read(c)
        assertEquals("Plum", t.name)
        assertEquals(setOf(false), t.variants)
        assertNotNull(t.picture)
        // the face cut to its body (6 px of margin), the inner rect's insets moved with it; 1080 across: 3 px a dp
        c.has("key", "fill=\"image(l-key-n.png, 24 24 24 24, 3x)\"")
        c.has("key:pressed", "fill=\"image(l-key-p.png")
        c.has("key.function", "fill=\"image(l-key-function-n.png, 24 24 24 24, 3x)\"")
        // the first text style the letters' colours, the second their mark's
        assertEquals(hex(BaiduFixtures.LETTER), colour(c, "keyText"))
        c.has("key:pressed", "color=\"${hex(BaiduFixtures.LETTER_PRESSED)}\"")
        assertContains(c.lines("key mark").single(), "color=\"${hex(BaiduFixtures.MARK)}\"")
        assertContains(c.lines("key:pressed mark").single(), "color=\"${hex(BaiduFixtures.MARK_PRESSED)}\"")
        // space's face a colour style
        c.has("key.space", "fill=\"${hex(BaiduFixtures.SPACE)}\"")
        c.has("key.space:pressed", "fill=\"${hex(BaiduFixtures.SPACE_PRESSED)}\"")
        // 64 px over a key 1080 / 360 × 58.5 high, the app's 23 dp over 58.5: 93%
        assertContains(c.lines("key.letter").single(), "scale=\"93%\"")
        assertFalse(c.lines("key").any { "scale" in it })
        // the palette before the bar's own styles
        assertEquals(hex(BaiduFixtures.LABEL), colour(c, "candidateText"))
        assertEquals(hex(BaiduFixtures.BRAND), colour(c, "first"))
        assertEquals(setOf(SkinNote.PANELS, SkinNote.NO_DARK, SkinNote.TEXT_SIZE), c.notes.keys)
    }

    @Test
    fun functionKeysPicturesAreTheirIcons() {
        val l = skin(bda()).look(Variant.LIGHT)
        val back = assertNotNull(l.icons["backspace"])
        assertContains(back.normal.argb.toList(), BaiduFixtures.ICON)
        assertContains(assertNotNull(back.pressed).argb.toList(), BaiduFixtures.ICON_PRESSED)
        assertNull(assertNotNull(l.icons["enter"]).pressed)
        assertNotNull(l.icons["candidates-expand"])
        // shift's picture holds a place only
        assertNull(l.icons["shift"])
        val c = convert(bda())
        assertContains(c.themeXml, "<icon name=\"backspace\"")
        assertContains(c.themeXml, "<icon name=\"candidates-expand\"")
        // its pressed one the same but white: its colour
        c.has("key[role=backspace]:pressed", "icon-color=\"#ffffff\"")
    }

    @Test
    fun aKeysStatesAndVariantsAreNotItsLook() {
        val l = skin(bda(letters = "Q")).look(Variant.LIGHT)
        assertEquals(BaiduFixtures.LETTER, l.keys.getValue("key").color)
        assertTrue(l.keys.getValue("key.space").fill is SkinFill.Color)
    }

    @Test
    fun withoutAPaletteTheBarsTextIsItsStyles() {
        val c = convert(bda(palette = false))
        assertEquals(hex(BaiduFixtures.LETTER), colour(c, "candidateText"))
        assertEquals(hex(BaiduFixtures.FUNCTION), colour(c, "first"))
    }

    @Test
    fun aGifIsSaidAndNotDecoded() {
        val files = bda(gif = true)
        val c = convert(files)
        assertContains(c.notes.keys, SkinNote.FRAME_ANIMATION)
        assertFalse(SkinNote.IMAGES in c.notes)
        assertFalse(c.files.any { "gif" in it.name })
    }

    @Test
    fun aFontAndAnimationsAreSaid() {
        val files = bda(font = true)
        files["port/animationConfig"] = byteArrayOf(8, 1)
        val c = convert(files)
        assertContains(c.notes.keys, SkinNote.FONT)
        assertContains(c.notes.keys, SkinNote.ANIMATION)
    }

    @Test
    fun aKeysNameIsItsActionStateAndVariant() {
        val k = assertNotNull(BaiduNames.key("KEY_F38_S34_14"))
        assertEquals(Triple("F38", 34, "14"), Triple(k.action, k.state, k.variant))
        assertEquals("q", BaiduNames.key("KEY_Q")?.action)
        assertEquals("，", BaiduNames.key("KEY_，")?.action)
        assertEquals("'" to 4, BaiduNames.key("KEY_'_S4")?.let { it.action to it.state })
        assertNull(BaiduNames.key("CAND_F9"))
        assertEquals(SkinAction.Letter('9'), BaiduNames.action("x", nine = true))
        assertEquals(SkinAction.Letter('1'), BaiduNames.action("'", nine = true))
        assertEquals(SkinAction.Role("apostrophe"), BaiduNames.action("'", nine = false))
        assertEquals(SkinAction.Role("baidu-F62"), BaiduNames.action("F62", nine = false))
        assertEquals(SkinAction.None, BaiduNames.action("", nine = false))
        // F7 the emoji panel, F76 the clipboard's
        assertEquals("emoji", BaiduNames.icon("F7"))
        assertNull(BaiduNames.icon("F76"))
    }

    @Test
    fun aPictureManyKeysShareIsReadOnce() {
        val plain = SkinFixtures.Platform().also { BaiduBdaSkin(SkinFiles(bda(), it)).look(Variant.LIGHT) }.decoded
        val platform = SkinFixtures.Platform()
        val files = bda(blank = 10_000)
        val start = TimeSource.Monotonic.markNow()
        val look = BaiduBdaSkin(SkinFiles(files, platform)).look(Variant.LIGHT)
        // each key's picture scanned anew: minutes
        assertTrue(start.elapsedNow() < 5.seconds, "${start.elapsedNow()}")
        assertEquals(plain + 1, platform.decoded)
        assertNotNull(look.icons["backspace"])
        assertEquals(BaiduFixtures.LETTER, look.keys.getValue("key").color)
    }

    @Test
    fun aBdaIsToldByItsConfigUnderAnyFolder() {
        assertTrue(BaiduBdaSkin.accepts(setOf("Info.txt", "port/appearanceConfig")))
        assertFalse(BaiduBdaSkin.accepts(SkinFixtures.baidu().keys))
        val nested = bda().mapKeys { "plum/" + it.key }
        assertEquals("Plum", skin(nested).name)
        assertEquals(BaiduFixtures.LETTER, skin(nested).look(Variant.LIGHT).keys.getValue("key").color)
    }

    @Test
    fun aConfigTooBigBrokenOrWithoutPinyinIsNotATheme() {
        val big = bda().also { it["port/appearanceConfig"] = ByteArray(BaiduBdaSkin.MAX_CONFIG_BYTES + 1) }
        assertEquals(SkinError.Reason.TOO_BIG, assertFailsWith<SkinError> { skin(big).look(Variant.LIGHT) }.reason)
        val group = bda().also { it["port/appearanceConfig"] = byteArrayOf(0x0B, 0) }
        assertEquals(SkinError.Reason.BROKEN, assertFailsWith<SkinError> { skin(group).look(Variant.LIGHT) }.reason)
        val numbers = bda().also { it["port/appearanceConfig"] = BaiduFixtures.proto { message(4) { string(1, "num_9"); message(2) { } } } }
        assertEquals(SkinError.Reason.NO_KEYBOARD, assertFailsWith<SkinError> { skin(numbers).look(Variant.LIGHT) }.reason)
        val noInfo = bda().also { it.remove("Info.txt"); it["port/appearanceConfig"] = text("") }
        assertEquals(SkinError.Reason.NO_KEYBOARD, assertFailsWith<SkinError> { skin(noInfo).look(Variant.LIGHT) }.reason)
    }
}
