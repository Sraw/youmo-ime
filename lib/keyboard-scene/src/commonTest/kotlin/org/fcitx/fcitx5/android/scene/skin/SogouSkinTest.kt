/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.convert
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.img
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.sogou
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.text
import org.fcitx.fcitx5.android.scene.skin.SogouFixtures.phoneSkin
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SogouSkinTest {
    private val r = "1080/status1/"

    private fun glyph(color: Int) = img(Pixels(48, 48).also { it.paste(Pixels.filled(30, 20, color), 9, 14) })

    // with the app's layouts, which the converter is not handed here
    private fun placed(files: Map<String, ByteArray>) = SkinFixtures.convertWith(SogouSkin(SkinFiles(files, SkinFixtures.Platform())))

    private fun names(c: SkinConversion) = c.files.map { it.name }.toSet()

    @Test
    fun functionKeysIconsAndShiftsThree() {
        val files = sogou(periods = 1)
        for (n in listOf("del", "del_p", "s1", "s2", "s3", "en")) files[r + "$n.png"] = glyph(0xFFFFFFFF.toInt())
        phoneSkin(
            files,
            "[Key_Backspace_Qwerty]\nICON=del.png\nICON_PRESSED=del_p.png\n[Key_Shift_Qwerty]\nICONS=s1.png,s2.png,s3.png\n" +
                "[Key_SwitchToEN]\nICON=en.png\n[Key_Emotion]\nICON=transparent.png\n",
        )
        val c = convert(files)
        val got = names(c)
        for (n in listOf("backspace", "shift", "shift-once", "shift-locked", "language")) assertTrue("icon-$n.png" in got, "$n in $got")
        assertFalse("icon-emoji.png" in got)
        assertFalse(SkinNote.KEY_ICONS in c.notes)
        // a picture named and not there
        phoneSkin(files, "[Key_Enter_Qwerty]\nICON=gone.png\n")
        assertTrue(SkinNote.KEY_ICONS in convert(files).notes)
    }

    @Test
    fun lettersAsPicturesAreStickersOnTheTwentySix() {
        val files = sogou(periods = 1)
        files[r + "a.png"] = glyph(0xFF00FF00.toInt())
        files[r + "a_p.png"] = glyph(0xFF0000FF.toInt())
        files[r + "b.png"] = glyph(0xFF00FF00.toInt())
        files[r + "c.png"] = glyph(0xFF00FF00.toInt())
        files[r + "keys.ini"] = text("[Key_A]\nFG_IMAGES=Fg_A\n[Fg_A]\nNORMAL=a.png\nPRESSED=a_p.png\nNORMAL_ON=A.png\n[Key_B_PY]\nFG_IMAGES=Fg_B\n[Fg_B]\nNORMAL=b.png\n")
        phoneSkin(files, "[Key_C]\nICON=c.png\n[Key_D]\nICON=transparent.png\n")
        val c = convert(files)
        assertContains(c.themeXml, """select="qwerty/a" sticker="st-a.png"""")
        assertContains(c.themeXml, """select="qwerty/a:pressed" sticker="st-a-p.png"""")
        assertTrue(setOf("st-a.png", "st-b.png", "st-c.png").all { it in names(c) })
        assertFalse("st-d.png" in names(c))
        assertFalse(SkinNote.LETTER_IMAGES in c.notes)
        // the nine keys' pictures are not the 26's: only their colour
        files[r + "keys.ini"] = text(files.getValue(r + "keys.ini").decodeToString() + "[Key_ABC]\nFG_IMAGES=Fg_B\n")
        assertTrue(SkinNote.LETTER_IMAGES in convert(files).notes)
    }

    @Test
    fun theNightIsThePeriodHoldingMidnight() {
        val files = sogou(periods = 3)
        files["time.ini"] = text(
            "[1080]\nSTATUS_LIST=status1,status2,status3\n[status1]\nSTART=6\nEND=17\n[status2]\nSTART=17\nEND=23\n[status3]\nSTART=23,0\nEND=24,6\n",
        )
        files["1080/status3/bg.png"] = img(Pixels.filled(1080, 700, 0xFF0000FF.toInt()))
        val c = convert(files)
        val dark = assertNotNull(c.files.firstOrNull { it.name == "board-dark.png" } as? SkinOutput.Image).pixels
        assertEquals(0xFF0000FF.toInt(), dark[dark.width / 2, dark.height - 5])
        assertTrue(SkinNote.MORE_PERIODS in c.notes)
    }

    @Test
    fun placesFromTheSkinsLayouts() {
        val files = sogou(periods = 1)
        files["1080/port/26.ini"] = text(SogouFixtures.QWERTY)
        files["1080/port/9.ini"] = text(SogouFixtures.NINE)
        val c = placed(files)
        assertEquals(setOf("qwerty", "nine"), c.layouts.keys)
        assertFalse(SkinNote.LAYOUT in c.notes)
        assertFalse(SkinNote.LAYOUT_SOME in c.notes)
        // in tenths of a key on the 26 (a gap of 0.01 is 0.1), hundredths of the width on the nine (the side is H_OFFSET)
        val q = c.layouts.getValue("qwerty")
        assertContains(q, """<row indent="0.05" end-indent="0.05">""")
        assertContains(q, """<gap width="0.1"/>""")
        assertTrue(Regex("""<key id="numbers"[^>]* width="1.1"/>""").containsMatchIn(q))
        val n = c.layouts.getValue("nine")
        assertContains(n, """<slot id="side" kind="nine-side" width="17"""")
        assertContains(n, """<row indent="0.5">""")
        assertTrue(Regex("""<key id="2"[^>]* width="22"""").containsMatchIn(n))
        // none at all: the app's places, nothing said
        assertTrue(placed(sogou(periods = 1)).layouts.isEmpty())
    }

    @Test
    fun aLayoutNotHoldingTogetherIsLeftOut() {
        for (broken in listOf("W=1.0" to "W=0", "W=1.0" to "W=x", "H=0.23" to "H=9", "W=0.09" to "W=-1")) {
            val files = sogou(periods = 1)
            files["1080/port/26.ini"] = text(SogouFixtures.QWERTY.replaceFirst(broken.first, broken.second))
            assertTrue(placed(files).layouts.isEmpty(), "$broken")
        }
        val rows = SogouFixtures.QWERTY.replace("ROWS=ROW1,ROW2,ROW3,ROW4", "ROWS=" + (1..9).joinToString(",") { "ROW1" })
        assertNull(SogouBoards.read(SkinFiles(mapOf("720/port/26.ini" to text(rows)), SkinFixtures.Platform())).firstOrNull())
    }

    private fun boards(vararg files: Pair<String, String>) =
        SogouBoards.read(SkinFiles(files.associate { (k, v) -> k to text(v) }, SkinFixtures.Platform()))

    @Test
    fun whereTheKeysAre() {
        val q = boards("1080/port/26.ini" to SogouFixtures.QWERTY).single().rows
        // heights to the keyboard's (0.6 of its width), places in its width; a gap after each key
        val a = q[1][0]
        assertEquals(listOf(0.055, 0.156, 0.09, 0.138), listOf(a.x, a.y, a.w, a.h).map { (it * 1000).roundToInt() / 1000.0 })
        val space = q[3].single { it.action == SkinAction.Role("space") }
        assertEquals(0.375, (space.x * 1000).roundToInt() / 1000.0)
        assertEquals(0.25, space.w)
        // a row without V_OFFSET under the one before; a key 0 wide a hole; a key's own X
        val own = SogouFixtures.QWERTY.replace("V_OFFSET=0.26\n", "").replace("[Key_Apostrophe_Shift]\nW=0.14", "[Key_Apostrophe_Shift]\nW=0\n[Key_Z]\nX=0.5")
        val r = boards("1080/port/26.ini" to own).single().rows
        assertEquals(((q[0][0].y + q[0][0].h) * 1000).roundToInt(), (r[1][0].y * 1000).roundToInt())
        assertEquals(8, r[2].size)
        assertEquals(0.5, r[2][0].x)
    }

    @Test
    fun theWidestFolderThatReads() {
        // 1080's not holding together: the next widest; only a 720 one, though the skin is read at 1080
        val broken = SogouFixtures.QWERTY.replace("W=1.0", "W=x")
        assertEquals(1, boards("1080/port/26.ini" to broken, "720/port/26.ini" to SogouFixtures.QWERTY, "480/port/26.ini" to broken).size)
        assertEquals(1, boards("720/port/26.ini" to SogouFixtures.QWERTY).size)
        assertTrue(boards("1080/port/26.ini" to broken).isEmpty())
    }

    @Test
    fun keysByTheirNames() {
        assertEquals(SkinAction.Letter('h'), SogouBoards.action("Key_H_PY"))
        assertEquals(SkinAction.Letter('q'), SogouBoards.action("Key_Q"))
        assertEquals(SkinAction.Letter('2'), SogouBoards.action("Key_ABC"))
        assertEquals(SkinAction.Letter('0'), SogouBoards.action("Key_Edits"))
        assertEquals(SkinAction.Role("shift"), SogouBoards.action("Key_Apostrophe_Shift"))
        assertEquals(SkinAction.Role("numbers"), SogouBoards.action("Key_Digits"))
        assertEquals(SkinAction.Role("comma"), SogouBoards.action("Key_UDSymbol1_Qwerty"))
        assertEquals(SkinAction.Role("Key_Voice"), SogouBoards.action("Key_Voice"))
    }
}
