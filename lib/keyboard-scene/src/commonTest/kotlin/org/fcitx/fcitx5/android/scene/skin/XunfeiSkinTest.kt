/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.convertWith
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.read
import org.fcitx.fcitx5.android.scene.skin.XunfeiFixtures.BALLOON
import org.fcitx.fcitx5.android.scene.skin.XunfeiFixtures.CAND
import org.fcitx.fcitx5.android.scene.skin.XunfeiFixtures.FUNCTION
import org.fcitx.fcitx5.android.scene.skin.XunfeiFixtures.FUNCTION_PRESSED
import org.fcitx.fcitx5.android.scene.skin.XunfeiFixtures.KEY
import org.fcitx.fcitx5.android.scene.skin.XunfeiFixtures.KEY_PRESSED
import org.fcitx.fcitx5.android.scene.skin.XunfeiFixtures.TOP
import org.fcitx.fcitx5.android.scene.skin.XunfeiFixtures.letterColor
import org.fcitx.fcitx5.android.scene.skin.XunfeiFixtures.store
import org.fcitx.fcitx5.android.scene.skin.XunfeiFixtures.utf8
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class XunfeiSkinTest {
    private fun skin(files: Map<String, ByteArray>, platform: SkinPlatform = SkinFixtures.Platform()): XunfeiSkin {
        assertTrue(XunfeiSkin.accepts(files.keys), "not taken: ${files.keys}")
        return XunfeiSkin(SkinFiles(files, platform))
    }

    private fun look(files: Map<String, ByteArray>) = skin(files).look(Variant.LIGHT)

    private fun SkinFill?.argb(): Int? = when (this) {
        is SkinFill.Color -> argb
        is SkinFill.Image -> pixels.opaqueBounds()?.let { pixels[it.left, it.top] }
        null -> null
    }

    @Test
    fun theLargestSizeNotPast1080IsRead() {
        assertEquals(720, look(store()).width)
        assertEquals(letterColor(720), look(store()).keys.getValue("key").color)
        assertEquals(720, look(store(sizes = listOf(480, 720, 1440))).width)
        // all past it: the smallest
        assertEquals(1440, look(store(sizes = listOf(2160, 1440))).width)
    }

    @Test
    fun aSizeWithOnlySomeImagesIsNotTheOneRead() {
        val l = look(store(sizes = listOf(480), partial = listOf(720)))
        assertEquals(480, l.width)
        assertEquals(letterColor(480), l.keys.getValue("key").color)
        // its own images, at its own size: 33×48 times two
        val face = l.keys.getValue("key").fill as SkinFill.Image
        assertEquals(66 to 96, face.pixels.width to face.pixels.height)
    }

    @Test
    fun withTheTopFolderAndWithout() {
        val wrapped = convertWith(skin(store()))
        val bare = convertWith(skin(store(top = "")))
        assertEquals("春日", wrapped.name)
        assertEquals(wrapped.themeXml, bare.themeXml)
        assertEquals("测试", read(bare).meta?.author)
    }

    @Test
    fun theAppsOwnSkinsAndOtherFilesAreNotTaken() {
        // the app's built-in kind: info.ini and numbered styles
        assertFalse(XunfeiSkin.accepts(setOf("skin/info.ini", "skin/720/layout/style.ini", "skin/720/layout/image.ini")))
        assertFalse(XunfeiSkin.accepts(setOf("info.ini", "1/style.ini")))
        // a keyboard's style.ini is no size folder's
        assertFalse(XunfeiSkin.accepts(store().filterKeys { !it.startsWith(TOP + "480/") && !it.startsWith(TOP + "720/") }.keys))
        // a size folder without its image.ini
        assertFalse(XunfeiSkin.accepts(store().filterKeys { !it.endsWith("0/layout/image.ini") }.keys))
        // theme_info.ini in one of two folders
        assertFalse(XunfeiSkin.accepts(store().keys + "other/readme.txt"))
        assertFalse(XunfeiSkin.accepts(SkinFixtures.sogou().keys))
    }

    @Test
    fun thePreviewIsThePreviewSectionsFile() {
        val c = convertWith(skin(store()))
        assertNotNull(c.files.firstOrNull { it.name == "preview.png" })
        assertEquals("preview.png", read(c).meta?.preview)
        assertNull(skin(store(preview = false)).preview)
        // named, its file not there
        assertNull(skin(store().filterKeys { !it.endsWith("720/res/shot.png") }).preview)
    }

    @Test
    fun threeEncodings() {
        val gbk = byteArrayOf(0xB4.toByte(), 0xBA.toByte(), 0xC8.toByte(), 0xD5.toByte())
        // GBK's 春日 is no UTF-8: the platform's legacy reading, here a fake one of those two characters
        val platform = object : SkinPlatform by SkinFixtures.Platform() {
            override fun legacyText(bytes: ByteArray): String = buildString {
                var i = 0
                while (i < bytes.size) {
                    val b = bytes[i].toInt() and 0xFF
                    if (b < 0x80) append(b.toChar()) else append(if (b == 0xB4) '春' else '日').also { i++ }
                    i++
                }
            }
        }
        val files = store()
        files[TOP + "theme_info.ini"] = "[THEME_INFO]\r\nNAME=".encodeToByteArray() + gbk + "\r\nAUTHOR=A\r\n".encodeToByteArray()
        assertEquals("春日", skin(files, platform).name)
        val utf16 = "[THEME_INFO]\r\nNAME=春日\r\n"
        files[TOP + "theme_info.ini"] = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            utf16.flatMap { listOf((it.code and 0xFF).toByte(), (it.code shr 8).toByte()) }.toByteArray()
        assertEquals("春日", skin(files).name)
        files[TOP + "theme_info.ini"] = utf8("[THEME_INFO]\nNAME=春日\n")
        assertEquals("春日", skin(files).name)
    }

    @Test
    fun theStretchBandIsTheNineSlicesInsets() {
        val face = look(store()).keys.getValue("key").fill as SkinFill.Image
        // 99×144 at 720, the band x 30–69, y 45–99
        assertEquals(Pixels.Box(30, 45, 30, 45), face.insets)
        // one axis only: the other stretched whole
        val across = look(store()).hintFill as SkinFill.Image
        assertEquals(Pixels.Box(45, 45, 45, 60), across.insets)
        val files = store()
        val ini = files.getValue(TOP + "720/layout/image.ini").decodeToString()
        files[TOP + "720/layout/image.ini"] = utf8(ini.removePrefix("\uFEFF").replace("\r\n", "\n").replace("Y_DIVS=45,99\n", "").replace("X_DIVS=45,105", "X_DIVS=45,999"))
        val l = look(files)
        assertEquals(Pixels.Box(30, 0, 30, 0), (l.keys.getValue("key").fill as SkinFill.Image).insets)
        // a band past the picture: none, the whole stretched
        assertEquals(Pixels.Box(0, 45, 0, 60), (l.hintFill as SkinFill.Image).insets)
    }

    @Test
    fun pressedFacesAndTheFunctionKeysFallback() {
        val l = look(store())
        val key = l.keys.getValue("key")
        val fn = l.keys.getValue("key.function")
        assertEquals(KEY, key.fill.argb())
        assertEquals(KEY_PRESSED, key.pressed.argb())
        assertEquals(FUNCTION, fn.fill.argb())
        // no Style_Keyboard_Key_1_Press: the letters' pressed face, the same image
        assertSame(key.pressed, fn.pressed)
        assertEquals(0xFFB0C4FF.toInt(), fn.color)
        assertEquals(key.pressedColor, fn.pressedColor)
        val own = look(store { it["Style_Keyboard_Key_1_Press"] = mutableListOf("IMAGE=Image_Fn_Down") })
        assertEquals(FUNCTION_PRESSED, own.keys.getValue("key.function").pressed.argb())
        // a function key styled as the letters is not written again
        val alike = look(store { it.remove("Style_Keyboard_Key_1"); it.remove("Style_Keyboard_Key_Fore_1") })
        assertNull(alike.keys["key.function"])
    }

    @Test
    fun withoutStylePy9TheLayoutBgImage() {
        val l = look(store { it.remove("Style_Py_9") })
        val board = assertNotNull(l.board)
        assertEquals(720, board.width)
        // the bar's strip over the board's blue
        assertEquals(CAND, l.barColor)
        // a strip the skin stretches: stretched to the skin's width
        val files = store { it.remove("Style_Py_9") }
        files[TOP + "720/res/board.png"] = SkinFixtures.img(SkinFixtures.gradient(10, 200, XunfeiFixtures.BLUE, KEY))
        // 562 high, the bar's 83 on top
        assertEquals(720 to 645, assertNotNull(look(files).board).let { it.width to it.height })
        // a whole picture a little narrow: in proportion
        files[TOP + "720/res/board.png"] = SkinFixtures.img(SkinFixtures.gradient(700, 560, XunfeiFixtures.BLUE, KEY))
        assertEquals(720 to 659, assertNotNull(look(files).board).let { it.width to it.height })
    }

    @Test
    fun aTallThinPictureIsMadeShortFirst() {
        // stretched across first, a row of 720 for each of its 200 000: hundreds of megabytes
        val files = store { it.remove("Style_Py_9") }
        files[TOP + "720/res/board.png"] = SkinFixtures.img(Pixels.filled(1, 200_000, XunfeiFixtures.BLUE))
        files[TOP + "720/res/bar.png"] = SkinFixtures.img(Pixels.filled(1, 200_000, CAND))
        val l = look(files)
        assertEquals(720 to 645, assertNotNull(l.board).let { it.width to it.height })
        assertEquals(CAND, l.barColor)
        read(convertWith(skin(files)))
    }

    @Test
    fun aSkinOfOnlyLargeSizesIsStretchedTo1080() {
        val files = store(sizes = listOf(1440)) { it.remove("Style_Py_9") }
        files[TOP + "1440/res/board.png"] = SkinFixtures.img(Pixels.filled(1, 1, XunfeiFixtures.BLUE))
        val l = look(files)
        assertEquals(1440, l.width)
        assertEquals(1080, assertNotNull(l.board).width)
    }

    @Test
    fun aSizeFolderWithALeadingZeroIsNone() {
        val files = store(sizes = listOf(480))
        for ((n, b) in store(sizes = listOf(720))) if (n.startsWith(TOP + "720/")) files[n.replace("/720/", "/0720/")] = b
        assertEquals(480, look(files).width)
        assertFalse(XunfeiSkin.accepts(files.keys.filterNot { it.startsWith(TOP + "480/") }.toSet()))
    }

    @Test
    fun aStylePastTheSectionsReadIsNotRead() {
        val files = store()
        val st = files.getValue(TOP + "720/layout/style.ini").decodeToString().removePrefix("\uFEFF")
        files[TOP + "720/layout/style.ini"] = utf8((0 until 5000).joinToString("") { "[S$it]\nA=1\n" } + st.replace("\r\n", "\n"))
        assertNull(look(files).keys.getValue("key").color)
    }

    @Test
    fun theComposingTextUnreadOnTheBarIsNotKept() {
        assertEquals(0xFFFFCC00.toInt(), look(store()).preedit)
        val light = look(store { it["Style_Cand_Default"] = mutableListOf("BG_COLOR=0xFFFFF8E0") })
        assertNull(light.preedit)
    }

    @Test
    fun aStyleWithItsImageCommentedOutIsItsColour() {
        val l = look(store { it["Style_Cand_Default"] = mutableListOf("#IMAGE=Image_Bar", "BG_COLOR=0xFF203050") })
        assertEquals(0xFF203050.toInt(), l.barColor)
        // only a colour for the board: no picture
        val plain = look(store { it["Style_Py_9"] = mutableListOf("#IMAGE=Image_Layout_Bg", "BG_COLOR=0xFF0D1B3E") })
        assertEquals(0xFF0D1B3E.toInt(), plain.boardColor)
        assertNull(plain.board)
    }

    @Test
    fun missingStylesAndImagesDoNotBreakIt() {
        val c = convertWith(skin(store { it.clear() }))
        read(c)
        assertContains(c.themeXml, """min-app="0.6.0"""")
        // an image named, its file not there
        val files = store()
        files.remove(TOP + "720/res/key.png")
        val l = look(files)
        assertNull(l.keys.getValue("key").fill)
        assertContains(l.notes.keys, SkinNote.IMAGES)
        read(convertWith(skin(files)))
        // no size folder: no keyboard
        assertEquals(
            SkinError.Reason.NO_KEYBOARD,
            assertFailsWith<SkinError> { XunfeiSkin(SkinFiles(mapOf(TOP + "theme_info.ini" to utf8("[THEME_INFO]\n")), SkinFixtures.Platform())) }.reason,
        )
    }

    @Test
    fun aSkinOfTheShopIsATheme() {
        val c = convertWith(skin(store()))
        val t = read(c)
        assertEquals("春日", t.name)
        assertEquals("0.6.0", t.meta?.minApp)
        assertNotNull(t.picture)
        fun hex(name: String) = t.colors[name]?.let { SkinTheme.hex(it.value) }
        assertEquals("#ffffff", hex("keyText"))
        assertEquals("#b0c4ff", hex("altKeyText"))
        assertEquals("#f0e68c", hex("candidateText"))
        assertEquals("#3fc1e8", hex("first"))
        assertContains(c.themeXml, """<style select="preedit" color="#ffcc00"""")
        // the pressed face its own image: lighter, no tint over it
        assertContains(c.themeXml, """<style select="key:pressed" fill="image(l-key-p.png""")
        assertContains(c.themeXml, """select="key.function" fill="image(l-key-function-n.png""")
        // the balloon's image and colour
        assertContains(c.themeXml, """<style select="preview" fill="image(""")
        assertEquals("#112233", hex("popupText"))
        assertEquals(BALLOON, look(store()).hintFill.argb())
    }

    @Test
    fun theNotes() {
        assertEquals(
            listOf(SkinNote.LAYOUT, SkinNote.KEY_ICONS, SkinNote.KEYBOARD_ANIMATION, SkinNote.FRAME_ANIMATION, SkinNote.NO_DARK),
            convertWith(skin(store())).notes.keys.toList(),
        )
        assertEquals(listOf(SkinNote.LAYOUT, SkinNote.KEY_ICONS, SkinNote.NO_DARK), look(store(animation = false)).notes.keys.toList())
        val other = store()
        other[TOP + "layout/layout_test/480/layout/animation.ini"] = utf8("[Animation_Tap]\nANIM_TYPE=2\n")
        assertEquals(listOf(SkinNote.LAYOUT, SkinNote.KEY_ICONS, SkinNote.ANIMATION, SkinNote.NO_DARK), look(other).notes.keys.toList())
    }
}
