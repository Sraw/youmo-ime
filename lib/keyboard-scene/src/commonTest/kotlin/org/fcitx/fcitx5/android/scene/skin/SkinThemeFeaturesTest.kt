/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.skin.SkinAction.Letter
import org.fcitx.fcitx5.android.scene.skin.SkinAction.Role
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.convertWith
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.face
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.gradient
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.read
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.source
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkinThemeFeaturesTest {
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFF808080.toInt()
    private val blue = 0xFF336699.toInt()

    private fun look(dark: Boolean = false): SkinLook = SkinLook(1080).apply {
        board = gradient(1080, 600, if (dark) 0xFF202020.toInt() else 0xFFE0E0E0.toInt(), if (dark) 0xFF101010.toInt() else 0xFFC0C0C0.toInt())
        keys["key"] = SkinKey(SkinFill.Color(white), SkinFill.Color(grey), 0xFF000000.toInt(), null, icons = false)
        keys["key.function"] = SkinKey(SkinFill.Color(grey), SkinFill.Color(white), white, 0xFF000000.toInt(), icons = true)
    }

    private fun SkinConversion.lines(select: String) = themeXml.lines().filter { "select=\"$select\"" in it }

    @Test
    fun nothingNewIsAsBefore() {
        val c = convertWith(source(look()))
        assertContains(c.themeXml, """min-app="0.6.0"""")
        assertEquals(c.themeXml, c.themeXmlWithLayouts)
        assertTrue(c.layouts.isEmpty())
        assertFalse("<icon " in c.themeXml || "sticker" in c.themeXml || "variant=\"dark\" src" in c.themeXml)
        assertEquals(listOf("board.png"), c.files.map { it.name })
        read(c)
    }

    @Test
    fun aKeysIconsAndThePressedOnes() {
        val l = look()
        l.icon("backspace", face(64, 64, 8, white), face(64, 64, 8, grey))
        l.icon("enter", face(64, 64, 8, white), gradient(64, 64, white, blue))
        l.icon("shift-locked", face(64, 64, 8, blue), gradient(64, 64, white, blue))
        l.icon("emoji", gradient(300, 300, white, blue), null)
        l.icon("not-a-name", face(64, 64, 8, white), null)
        val c = convertWith(source(l))
        val x = c.themeXml
        assertContains(x, """<icon name="backspace" src="icon-backspace.png" tint="true"/>""")
        assertContains(x, """<style select="key[role=backspace]" icon-color="#ffffff"/>""")
        assertContains(x, """<style select="key[role=backspace]:pressed" icon-color="#808080"/>""")
        assertContains(x, """<icon name="enter-pressed" src="icon-enter-p.png"/>""")
        assertContains(x, """<style select="key.enter:pressed icon" return-icon-newline="icon(enter-pressed)"/>""")
        // a state's icon only in its state, a bar's drawn as it is
        assertContains(x, """<style select="key[role=shift]:locked" icon-color="#336699"/>""")
        assertFalse("icon-shift-locked-p.png" in c.files.map { it.name })
        assertContains(x, """<icon name="emoji" src="icon-emoji.png"/>""")
        val emoji = c.files.first { it.name == "icon-emoji.png" } as SkinOutput.Image
        assertEquals(256, emoji.pixels.width)
        assertEquals(setOf(SkinNote.KEY_ICONS), c.notes.keys)
        assertContains(x, """min-app="0.6.3"""")
        val t = read(c)
        assertNotNull(t.icons["enter-pressed"])
    }

    @Test
    fun lettersAsStickers() {
        val l = look()
        l.letterImage('A', face(300, 300, 10, blue), face(300, 300, 10, grey))
        l.letterImage('b', face(100, 100, 10, blue), face(100, 100, 10, blue))
        l.letterImage('1', face(100, 100, 10, blue), null)
        val c = convertWith(source(l))
        assertEquals(listOf("""<style select="qwerty/a" sticker="st-a.png"/>"""), c.lines("qwerty/a").map { it.trim() })
        assertEquals(1, c.lines("qwerty/a label").size)
        assertEquals(1, c.lines("qwerty/a:pressed").size)
        assertTrue(c.lines("qwerty/b:pressed").isEmpty())
        assertEquals(256, (c.files.first { it.name == "st-a.png" } as SkinOutput.Image).pixels.width)
        read(c)
    }

    @Test
    fun aKeysTextAndPressAsTheSkinHasThem() {
        val l = look()
        l.keys["key"] = SkinKey(
            SkinFill.Color(white), SkinFill.Color(grey), 0xFF000000.toInt(), null, icons = false,
            pressedIconColor = blue, markColor = grey, pressedMarkColor = blue,
            scale = 110, weight = 700, pressScale = 95, transitionMs = 60, pressSink = 2.0,
        )
        val c = convertWith(source(l))
        val key = c.lines("key").single { "scale" in it }
        assertContains(key, """scale="110%"""")
        assertContains(key, """weight="700"""")
        val pressed = c.lines("key:pressed").single { "press-scale" in it }
        assertContains(pressed, """icon-color="#336699"""")
        assertContains(pressed, """press-scale="95%"""")
        assertContains(pressed, """transition="60ms ease-out"""")
        assertContains(pressed, """press-sink="2dp"""")
        assertEquals(1, c.lines("key mark").size)
        assertEquals(1, c.lines("key:pressed mark").size)
        assertContains(c.themeXml, """min-app="0.6.0"""")
        read(c)
    }

    @Test
    fun aDarkBoardOfItsOwn() {
        val l = look()
        l.darkPicture = gradient(1080, 600, 0xFF101010.toInt(), 0xFF000000.toInt())
        // one version: no dark version to draw it in, and nothing wrong with the picture to tell of
        val one = convertWith(source(l))
        assertFalse(SkinNote.DARK_PICTURE in one.notes || "board-dark.png" in one.files.map { it.name })
        val two = convertWith(source(look(), look(dark = true)))
        assertContains(two.themeXml, """<picture variant="dark" src="board-dark.png">""")
        assertTrue("board-dark.png" in two.files.map { it.name })
        read(two)
    }

    @Test
    fun everyIconAndStickerFitsThePack() {
        val l = look()
        for (n in SkinIcons.NAMES) l.icon(n, gradient(256, 256, white, blue), gradient(256, 256, blue, white))
        for (c in 'a'..'z') l.letterImage(c, gradient(256, 256, white, grey), gradient(256, 256, grey, white))
        val c = convertWith(source(l))
        assertTrue(c.files.size + 1 <= SkinTheme.MAX_PACK_ENTRIES, "${c.files.size}")
        assertTrue(c.files.size <= SkinTheme.MAX_PACK_ENTRIES - 1)
        assertTrue(c.themeXml.encodeToByteArray().size <= SkinTheme.MAX_THEME_XML_BYTES)
        read(c)
    }

    @Test
    fun aSecondBoardIsMadeSmallAndOneLikeTheFirstIsNone() {
        val big = SkinLook(1080).apply { board = gradient(3000, 200, white, grey) }
        val bigDark = SkinLook(1080).apply { board = gradient(3000, 200, grey, 0xFF000000.toInt()) }
        val c = convertWith(source(big, bigDark))
        assertEquals(SkinTheme.DARK_PICTURE_SIDE, (c.files.first { it.name == SkinTheme.DARK_BOARD_FILE } as SkinOutput.Image).pixels.width)
        val small = SkinLook(1080).apply { board = gradient(800, 200, white, grey) }
        val smallDark = SkinLook(1080).apply { board = gradient(1600, 400, grey, 0xFF000000.toInt()) }
        assertEquals(800, (convertWith(source(small, smallDark)).files.first { it.name == SkinTheme.DARK_BOARD_FILE } as SkinOutput.Image).pixels.width)
        val same = SkinLook(1080).apply { board = gradient(800, 200, white, grey) }
        val sameAgain = convertWith(source(small, same))
        assertFalse(SkinTheme.DARK_BOARD_FILE in sameAgain.files.map { it.name } || SkinNote.DARK_PICTURE in sameAgain.notes)
    }

    @Test
    fun aKeysNumbersPastWhatAThemeTakesAreHeldToIt() {
        val l = look()
        l.keys["key"] = SkinKey(
            SkinFill.Color(white), SkinFill.Color(grey), 0xFF000000.toInt(), null, icons = false,
            scale = 140, weight = 500, pressScale = 120, transitionMs = 900, pressSink = -1.0,
        )
        l.keys["key.function"] = SkinKey(SkinFill.Color(grey), null, white, null, icons = true, scale = 10, pressScale = 10, pressSink = Double.NaN)
        val c = convertWith(source(l))
        val key = c.lines("key").single { "scale" in it }
        assertContains(key, """scale="130%"""")
        assertFalse("weight" in key)
        val pressed = c.lines("key:pressed").single { "press-scale" in it }
        assertContains(pressed, """press-scale="110%"""")
        assertContains(pressed, """transition="600ms ease-out"""")
        assertContains(pressed, """press-sink="0dp"""")
        assertContains(c.lines("key.function").single { "scale" in it }, """scale="85%"""")
        val fnPressed = c.lines("key.function:pressed").single { "press-scale" in it }
        assertFalse("press-sink" in fnPressed)
        read(c)
    }

    @Test
    fun returnsIconsInColoursOfTheirOwnAreDrawnAsTheyAre() {
        val two = look().apply {
            icon("enter", face(64, 64, 8, white), null)
            icon("search", face(64, 64, 8, blue), null)
        }
        val c = convertWith(source(two))
        assertContains(c.themeXml, """<icon name="enter" src="icon-enter.png"/>""")
        assertContains(c.themeXml, """<icon name="search" src="icon-search.png"/>""")
        assertTrue(c.lines("key.enter").none { "icon-color" in it } && c.lines("key.enter:pressed").none { "icon-color" in it })
        read(c)
        val one = look().apply {
            icon("enter", face(64, 64, 8, white), null)
            icon("search", face(64, 64, 8, white), null)
        }
        val o = convertWith(source(one))
        assertContains(o.themeXml, """<icon name="search" src="icon-search.png" tint="true"/>""")
        assertEquals(1, o.lines("key.enter").count { "icon-color" in it })
    }

    // a PNG's most, as the converter counts it
    private fun bound(w: Int, h: Int): Long = (4L * w * h + h).let { it + it / 1000 + 1024 }

    // the pack filled by a font but for [room] bytes: what is left out after it, in order
    private fun filled(room: Long, dark: Boolean = true): SkinConversion {
        val l = look()
        val taken = bound(1080, 600) + room
        l.font = "f.ttf" to ByteArray((SkinTheme.MAX_PACK_BYTES - SkinTheme.MAX_THEME_XML_BYTES - taken).toInt())
        l.icon("backspace", face(64, 64, 8, white), gradient(64, 64, white, blue))
        l.icon("clipboard", face(64, 64, 8, white), null)
        l.letterImage('a', face(100, 100, 10, blue), null)
        return convertWith(if (dark) source(l, look(dark = true)) else source(l))
    }

    @Test
    fun whatThePackHasNoRoomForIsLeftOutInItsOrderAndToldOf() {
        val none = filled(room = 0)
        assertTrue(none.files.none { it.name == SkinTheme.DARK_BOARD_FILE || it.name.startsWith("icon-") || it.name.startsWith("st-") })
        assertTrue(listOf(SkinNote.DARK_PICTURE, SkinNote.KEY_ICONS, SkinNote.TOOLBAR_ICONS, SkinNote.LETTER_IMAGES).all { it in none.notes })
        // the dark picture first
        val dark = filled(room = bound(1080, 600) + 1000)
        assertTrue(dark.files.any { it.name == SkinTheme.DARK_BOARD_FILE })
        assertTrue(dark.files.none { it.name.startsWith("icon-") || it.name.startsWith("st-") })
        assertTrue(SkinNote.DARK_PICTURE !in dark.notes && SkinNote.KEY_ICONS in dark.notes)
        // then icons at rest, then the pressed ones, then stickers
        val icons = filled(room = bound(1080, 600) + 2 * bound(64, 64) + 1000)
        assertEquals(setOf("icon-backspace.png", "icon-clipboard.png"), icons.files.map { it.name }.filter { it.startsWith("icon-") || it.startsWith("st-") }.toSet())
        assertTrue(SkinNote.KEY_ICONS in icons.notes && SkinNote.TOOLBAR_ICONS !in icons.notes && SkinNote.LETTER_IMAGES in icons.notes)
        val all = filled(room = bound(1080, 600) + 3 * bound(64, 64) + bound(100, 100) + 1000)
        assertTrue(listOf("icon-backspace-p.png", "st-a.png").all { n -> all.files.any { it.name == n } })
        assertTrue(listOf(SkinNote.DARK_PICTURE, SkinNote.KEY_ICONS, SkinNote.TOOLBAR_ICONS, SkinNote.LETTER_IMAGES).none { it in all.notes })
        read(all)
    }

    @Test
    fun anIconOrALetterNotTheAppsIsNotKept() {
        val l = SkinLook(1080)
        l.icon("F999", face(64, 64, 8, white), null)
        l.letterImage('1', face(64, 64, 8, white), null)
        l.letterImage('Q', gradient(600, 600, white, blue), null)
        l.letterImage('q', face(64, 64, 8, white), null)
        assertTrue(l.icons.isEmpty())
        assertEquals(setOf(SkinNote.KEY_ICONS), l.notes.keys)
        assertEquals(setOf('q'), l.letterImages.keys)
        assertEquals(SkinTheme.MAX_STICKER_SIDE, l.letterImages.getValue('q').normal.width)
    }

    private fun qwertyBoard(): SkinBoard {
        fun row(y: Double, x0: Double, keys: List<Pair<SkinAction, Double>>): List<SkinKeyBox> {
            var x = x0
            return keys.map { (a, w) -> SkinKeyBox(x, y, w, 150.0, a).also { x += w } }
        }
        return SkinBoard(
            "qwerty",
            listOf(
                row(0.0, 0.0, "qwertyuiop".map { Letter(it) to 108.0 }),
                row(150.0, 54.0, "asdfghjkl".map { Letter(it) to 108.0 }),
                row(300.0, 0.0, listOf(Role("shift") to 162.0) + "zxcvbnm".map { Letter(it) to 108.0 } + listOf(Role("backspace") to 162.0)),
                row(450.0, 0.0, listOf("symbols" to 120.0, "numbers" to 120.0, "comma" to 100.0, "space" to 480.0, "period" to 100.0, "enter" to 160.0).map { Role(it.first) to it.second }),
            ),
            1080.0,
        )
    }

    @Test
    fun boardsThatTakeAndOnesThatDoNot() {
        val l = look()
        l.layouts += qwertyBoard()
        l.layouts += SkinBoard("nine", qwertyBoard().rows, 1080.0)
        val c = convertWith(source(l))
        assertEquals(setOf("qwerty"), c.layouts.keys)
        assertEquals(listOf("nine"), c.notes[SkinNote.LAYOUT_SOME])
        val qwerty = (LayoutReader.layout(c.layouts.getValue("qwerty")) as Parsed.Ok).value
        val min = LayoutCheck.minApp(qwerty)
        assertEquals(min, qwerty.meta?.minApp)
        assertContains(c.themeXml, """min-app="0.6.0"""")
        assertContains(c.themeXmlWithLayouts, """min-app="$min"""")
        read(c, c.themeXmlWithLayouts)
        // none that take: the note as before, and the import makes no layouts without the app's
        val none = SkinLook(1080).apply { layouts += SkinBoard("nine", qwertyBoard().rows, 1080.0) }
        assertEquals(setOf(SkinNote.LAYOUT), convertWith(source(none)).notes.keys.filter { it == SkinNote.LAYOUT || it == SkinNote.LAYOUT_SOME }.toSet())
        assertTrue(convertWith(source(l), layouts = null).layouts.isEmpty())
        // a board a reader got wrong leaves out its layout, not the skin
        val nan = SkinLook(1080).apply { layouts += SkinBoard("qwerty", qwertyBoard().rows.map { r -> r.map { SkinKeyBox(Double.NaN, it.y, it.w, it.h, it.action) } }, 1080.0) }
        val broken = convertWith(source(nan))
        assertTrue(broken.layouts.isEmpty() && SkinNote.LAYOUT in broken.notes)
        read(broken)
    }
}
