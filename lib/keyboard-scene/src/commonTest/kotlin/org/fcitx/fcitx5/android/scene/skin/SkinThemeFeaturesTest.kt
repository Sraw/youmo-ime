/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Uat
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
import kotlin.test.assertNull
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
        assertTrue(c.lines("key[role=shift]:shifted:pressed icon").isEmpty())
        assertContains(x, """<icon name="emoji" src="icon-emoji.png"/>""")
        val emoji = c.files.first { it.name == "icon-emoji.png" } as SkinOutput.Image
        assertEquals(256, emoji.pixels.width)
        assertEquals(setOf(SkinNote.KEY_ICONS), c.notes.keys)
        assertContains(x, """min-app="0.6.3"""")
        val t = read(c)
        assertNotNull(t.icons["enter-pressed"])
    }

    @Test
    fun aShiftsPressedPictureIsOnlyTheUnshiftedOnes() {
        val l = look()
        l.icon("shift", face(64, 64, 8, white), gradient(64, 64, white, blue))
        val c = convertWith(source(l))
        assertContains(c.themeXml, """<style select="key[role=shift]:pressed icon" content="icon(shift-pressed)"/>""")
        // `:pressed` selects a shifted or locked one too: those keep their own picture
        assertContains(c.themeXml, """<style select="key[role=shift]:shifted:pressed icon" content="icon(shift-once)"/>""")
        assertContains(c.themeXml, """<style select="key[role=shift]:locked:pressed icon" content="icon(shift-locked)"/>""")
        read(c)
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
    @Uat("ST-217")
    fun everyIconAndStickerFitsThePackWithOrWithoutKeyFaces() {
        // why, whether 48 faces at the key side's cap (about 50 MB, so the icons and stickers no longer all fit) go in first
        val rows = listOf(
            "icons and stickers alone" to false,
            "48 faces at MAX_KEY_SIDE first: they count against the pack too" to true,
        )
        assertRows(rows, { it.first }) { (why, faces) ->
            val l = look()
            if (faces) for ((i, ch) in ('a'..'x').withIndex()) {
                val side = SkinTheme.MAX_KEY_SIDE
                l.keys["qwerty/$ch"] = SkinKey(
                    SkinFill.Image(gradient(side, side, white, 0xFF000000.toInt() or i), null),
                    SkinFill.Image(gradient(side, side, grey, 0xFF000000.toInt() or (i shl 8)), null),
                    0xFF000000.toInt(), null, icons = false,
                )
            }
            for (n in SkinIcons.NAMES) l.icon(n, gradient(256, 256, white, blue), gradient(256, 256, blue, white))
            for (c in 'a'..'z') l.letterImage(c, gradient(256, 256, white, grey), gradient(256, 256, grey, white))
            val c = convertWith(source(l))
            assertEquals(if (faces) 48 else 0, c.files.count { it.name.startsWith("l-qwerty-") }, why)
            val bytes = c.files.sumOf {
                when (it) {
                    is SkinOutput.Bytes -> it.bytes.size.toLong()
                    is SkinOutput.Image -> SkinTheme.pngBound(it.pixels.width, it.pixels.height).let { b -> if (it.shrink) minOf(b, it.maxBytes) else b }
                }
            }
            assertTrue(bytes <= SkinTheme.MAX_PACK_BYTES - SkinTheme.MAX_THEME_XML_BYTES, "$why: $bytes")
            // and theme.xml
            assertTrue(c.files.size + 1 <= SkinTheme.MAX_PACK_ENTRIES, "$why: ${c.files.size}")
            assertTrue(c.themeXml.encodeToByteArray().size <= SkinTheme.MAX_THEME_XML_BYTES, why)
            // the ones left out told of, not the import refused
            if (faces) assertTrue(SkinNote.KEY_ICONS in c.notes || SkinNote.TOOLBAR_ICONS in c.notes || SkinNote.LETTER_IMAGES in c.notes, "$why: ${c.notes}")
            read(c)
        }
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

    private fun bound(w: Int, h: Int): Long = SkinTheme.pngBound(w, h)

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
    @Uat("ST-217")
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
        assertNull(c.notes[SkinNote.LAYOUT_EXTRA])
        assertNull(c.notes[SkinNote.LAYOUT_LACKS])
        // a row of the skin's own under the keys: left out and said so
        val tools = SkinLook(1080).apply { layouts += SkinBoard("qwerty", qwertyBoard().rows + listOf(listOf(SkinKeyBox(0.0, 600.0, 1080.0, 100.0, Role("emoji")))), 1080.0) }
        val withTools = convertWith(source(tools))
        assertEquals(setOf("qwerty"), withTools.layouts.keys)
        assertEquals(listOf("qwerty"), withTools.notes[SkinNote.LAYOUT_EXTRA])
        assertNull(withTools.notes[SkinNote.LAYOUT_LACKS])
        // one without ⇧: written, and what it lacks said with the board's id
        val noShift = qwertyBoard().rows.mapIndexed { i, r -> if (i == 2) r.filter { it.action != Role("shift") } else r }
        val lacking = convertWith(source(SkinLook(1080).apply { layouts += SkinBoard("qwerty", noShift, 1080.0) }))
        assertEquals(setOf("qwerty"), lacking.layouts.keys)
        assertEquals(listOf("qwerty\tshift"), lacking.notes[SkinNote.LAYOUT_LACKS])
        // a nine-key board without 0, alone: its min-app, and the theme's with it, an app's that takes one lacking keys
        val nine = SkinLook(1080).apply {
            fun row(y: Double, keys: List<Pair<SkinAction, Double>>, x0: Double = 172.8): List<SkinKeyBox> {
                var x = x0
                return keys.map { (a, w) -> SkinKeyBox(x, y, w, 150.0, a).also { x += w } }
            }
            fun digits(y: Double, s: String, last: SkinAction) = row(y, s.map { Letter(it) to 237.6 } + listOf(last to 194.4))
            val bottom = listOf("symbols" to 172.8, "numbers" to 151.2, "emoji" to 100.0, "space" to 461.6, "enter" to 194.4).map { Role(it.first) to it.second }
            layouts += SkinBoard("nine", listOf(digits(0.0, "123", Role("backspace")), digits(150.0, "456", Role("reset")), digits(300.0, "789", Role("emoji")), row(450.0, bottom, 0.0)), 1080.0)
        }
        val noZero = convertWith(source(nine))
        assertEquals(listOf("nine\tdigits\t0"), noZero.notes[SkinNote.LAYOUT_LACKS])
        assertContains(noZero.layouts.getValue("nine"), """min-app="0.6.3"""")
        assertContains(noZero.themeXmlWithLayouts, """min-app="0.6.3"""")
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
