/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.TextFormatFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/** Unicode input's temp mode contract; lines are `modules/unicode/unicodetempmode.cpp`'s. */
class UnicodeInputTest {

    companion object {
        private val DATA by lazy {
            UnicodeInputTest::class.java.getResourceAsStream("/pkgdata/unicode/charselectdata")!!.readBytes()
        }
        private const val CTRL_SHIFT = KeyStates.CTRL or KeyStates.SHIFT
    }

    private var loads = 0
    private val rig = TempModeRig(QuickPhraseTable.load(emptyList()), { loads++; DATA }, clipboard = "a中a😀")

    private fun search() = rig.press('u'.code, KeyStates.CTRL_ALT_SHIFT)
    private fun direct() = rig.press('u'.code, CTRL_SHIFT)

    @Test
    fun theDataIsReadAtTheFirstTriggerOnlyAndOnce() {
        // nothing held until Unicode is used, as fcitx's CharSelectData::load (charselectdata.cpp:76-101)
        assertEquals(0, loads)
        assertTrue(search().accepted)
        assertEquals(1, loads)
        rig.press(KeySyms.ESCAPE)
        assertTrue(search().accepted)
        assertEquals(1, loads)
    }

    @Test
    fun aSearchAlongsideTheIndexBuiltElsewhereFindsTheSame() {
        // the two may or may not overlap: this checks they agree, not that the search waits
        val data = CharSelectData(DATA)
        val started = CountDownLatch(1)
        val builder = Thread { started.countDown(); data.prepare() }.apply { start() }
        started.await()
        val found = data.find("latin small letter a")
        builder.join()
        assertTrue(found.contentEquals(data.find("latin small letter a")))
        assertTrue(0x61 in found)
    }

    @Test
    fun searchOpensOnTheClipboardsCharacters() {
        // :107-122, :342-395
        assertTrue(search().accepted)
        assertTrue(rig.unicode.isActive(rig.ic))
        assertEquals("Unicode: (Type to search unicode by code or description)", rig.aux)
        assertEquals(listOf("a", "中", "😀"), rig.page)
        val list = rig.panel.candidates!!
        assertEquals("U+0061 LATIN SMALL LETTER A", list.candidate(0).comment)
        assertEquals("U+4E2D CJK UNIFIED IDEOGRAPH-4E2D", list.candidate(1).comment)
        assertEquals(TempLayoutHint.Vertical, list.layoutHint)
        assertEquals("", list.label(0))
        // display only: Return and Alt+1 do nothing (DisplayOnlyCandidateWord)
        rig.press(KeySyms.RETURN)
        rig.press('1'.code, KeyStates.ALT)
        assertTrue(rig.unicode.isActive(rig.ic))
        assertEquals(emptyList<String>(), rig.commits())
    }

    @Test
    fun aLoneSurrogateInTheClipboardIsNotListed() {
        // :368-386: fcitx skips text that is not valid UTF-8; an unassigned one is listed by the data's name for it (F3904)
        val odd = TempModeRig(QuickPhraseTable.load(emptyList()), { DATA }, clipboard = "\uD800x\u0378")
        assertTrue(odd.press('u'.code, KeyStates.CTRL_ALT_SHIFT).accepted)
        assertEquals(listOf("x", "\u0378"), odd.page)
        assertEquals("U+0378 <not assigned>", odd.panel.candidates!!.candidate(1).comment)
    }

    @Test
    fun searchByNameAndPick() {
        search()
        rig.type("snowman")
        assertEquals("snowman", rig.preedit)
        assertEquals("Unicode: ", rig.aux)
        assertEquals(listOf("☃", "⛄", "⛇"), rig.page)
        assertEquals("A-1. ", rig.panel.candidates!!.label(0))
        assertEquals("SNOWMAN", rig.panel.candidates!!.candidate(0).comment)
        rig.press('2'.code, KeyStates.ALT)
        assertEquals(listOf("⛄"), rig.commits())
        assertFalse(rig.unicode.isActive(rig.ic))
    }

    @Test
    fun spaceIsTypedReturnPicksTheCursor() {
        search()
        rig.type("red heart")
        assertEquals(listOf("❤"), rig.page)
        rig.press(KeySyms.RETURN)
        assertEquals(listOf("❤"), rig.commits())
    }

    // the 14 and 18 keys have no "+": a code typed bare is found too, ahead of the rest
    @Test
    fun aBareCodeListsItsCharacterFirst() {
        search()
        rig.type("4e2d")
        assertEquals("中", rig.page.first())
        search()
        rig.press(KeySyms.ESCAPE)
        search()
        rig.type("1F600")
        assertEquals("😀", rig.page.first())
    }

    @Test
    fun whatIsNoBareCodeIsSearchedAsBefore() {
        assertEquals(0x4F60, UnicodeInput.bareCodeOf("4f60"))
        assertEquals(0x6211, UnicodeInput.bareCodeOf("6211"))
        assertEquals(null, UnicodeInput.bareCodeOf("face"))
        assertEquals(null, UnicodeInput.bareCodeOf("4"))
        assertEquals(null, UnicodeInput.bareCodeOf("4e2dx"))
        assertEquals(null, UnicodeInput.bareCodeOf("1234567"))
        assertEquals(null, UnicodeInput.bareCodeOf("d800"))
        assertEquals(null, UnicodeInput.bareCodeOf("00"))
        assertEquals(null, UnicodeInput.bareCodeOf("1b"))
        assertEquals(null, UnicodeInput.bareCodeOf("85"))
        assertEquals(null, UnicodeInput.bareCodeOf("４e２d"))
    }

    @Test
    fun aCodeListsItsCharacterFirst() {
        // charselectdata.cpp:313-383: U+4E2D, then 0x4E2D read as decimal is not a number
        search()
        rig.type("U+4E2D")
        assertEquals("中", rig.page.first())
        search()
        rig.press(KeySyms.ESCAPE)
        search()
        // past Unicode: no character for the number (F3930). The data lists it as fcitx's find does
        // (charselectdata.cpp:336-347, the hex and the decimal reading); the list drops it, so the
        // decimal reading (110000 = U+1ADB0) is first
        assertTrue(0x110000 in CharSelectData(DATA).find("U+110000"))
        assertFalse(UnicodeInput.isValid(0x110000))
        rig.type("U+110000")
        assertEquals(String(Character.toChars(110000)), rig.page.first())
    }

    @Test
    fun pagingAndCursorInTheList() {
        search()
        rig.type("arrow")
        val list = rig.panel.candidates!!
        assertTrue(list.totalSize > 10)
        rig.press(TempKeys.DOWN)
        assertEquals(1, rig.panel.candidates!!.currentPage)
        assertEquals(-1, rig.panel.candidates!!.cursorIndex) // DonotChange: the cursor stays on page 0
        rig.press(TempKeys.UP)
        rig.press(TempKeys.UP) // first page after Down: used
        assertEquals(0, rig.panel.candidates!!.currentPage)
        rig.press(KeySyms.TAB)
        rig.press(KeySyms.TAB)
        assertEquals(2, rig.panel.candidates!!.cursorIndex)
        rig.press(KeySyms.TAB, KeyStates.SHIFT)
        val chosen = rig.panel.candidates!!.candidate(1).text
        rig.press(KeySyms.KP_ENTER)
        assertEquals(listOf(chosen), rig.commits())
    }

    @Test
    fun backspaceAndEscape() {
        search()
        rig.type("ab")
        rig.press(KeySyms.BACKSPACE)
        assertEquals("a", rig.preedit)
        assertEquals(1, rig.panel.preedit.cursor)
        rig.press(KeySyms.BACKSPACE)
        assertFalse(rig.unicode.isActive(rig.ic))
        search()
        rig.press(KeySyms.BACKSPACE)
        assertFalse(rig.unicode.isActive(rig.ic))
        search()
        assertEquals(-1, rig.panel.preedit.cursor)
        rig.press(TempKeys.LEFT) // types nothing: the list goes, the hint too (updateUI without trigger)
        assertNull(rig.panel.candidates)
        assertEquals("Unicode: ", rig.aux)
        val e = rig.press('c'.code, KeyStates.CTRL)
        assertFalse(e.accepted)
        rig.press(KeySyms.ESCAPE)
        assertFalse(rig.unicode.isActive(rig.ic))
    }

    @Test
    fun directTypesAHexCode() {
        // :250-296
        assertTrue(direct().accepted)
        assertEquals("U+", rig.preedit)
        assertEquals(TextFormatFlag.DontCommit.flag, rig.panel.preedit.flags[0])
        rig.type("4e")
        rig.press(KeySyms.KP_0 + 2)
        rig.press('D'.code)
        rig.press('g'.code) // not hex: ignored
        assertEquals("U+4e2D", rig.preedit)
        assertEquals(6, rig.panel.preedit.cursor)
        assertTrue(rig.press('x'.code, KeyStates.CTRL).accepted) // every key is used
        rig.press(KeySyms.SPACE)
        assertEquals(listOf("中"), rig.commits())
        assertFalse(rig.unicode.isActive(rig.ic))
    }

    @Test
    fun directRefusesWhatIsNoCharacter() {
        direct()
        rig.type("110000") // the last 0 would pass 10FFFF: dropped
        assertEquals("U+11000", rig.preedit)
        rig.press(KeySyms.BACKSPACE)
        rig.press(KeySyms.BACKSPACE)
        rig.press(KeySyms.BACKSPACE)
        rig.press(KeySyms.BACKSPACE)
        rig.press(KeySyms.BACKSPACE)
        rig.press(KeySyms.RETURN) // empty: stays
        assertTrue(rig.unicode.isActive(rig.ic))
        rig.type("d8")
        rig.type("00") // a surrogate: the second 0 dropped
        assertEquals("U+d80", rig.preedit)
        rig.press(KeySyms.BACKSPACE)
        rig.press(KeySyms.BACKSPACE)
        rig.press(KeySyms.BACKSPACE)
        rig.type("0")
        rig.press(KeySyms.RETURN) // U+0000: nothing committed
        assertEquals(emptyList<String>(), rig.commits())
        direct()
        rig.press(KeySyms.BACKSPACE)
        assertFalse(rig.unicode.isActive(rig.ic))
        direct()
        rig.press(KeySyms.ESCAPE)
        assertFalse(rig.unicode.isActive(rig.ic))
    }

    @Test
    fun directWithClientPreedit() {
        rig.ic.capabilities = Capability.PREEDIT
        direct()
        assertEquals(TextFormatFlag.DontCommit.flag or TextFormatFlag.Underline.flag, rig.panel.clientPreedit.flags[0])
        assertTrue(rig.pipeline.invokeAction(rig.ic, InvokeActionEvent(InvokeActionEvent.Action.LeftClick, 0)).accepted)
    }

    @Test
    fun noDataNoMode() {
        val none = TempModeRig(QuickPhraseTable.load(emptyList()), { null })
        val e = none.press('u'.code, KeyStates.CTRL_ALT_SHIFT)
        assertFalse(e.filtered)
        assertFalse(none.unicode.trigger(none.ic))
        assertFalse(none.unicode.isActive(none.ic))
    }

    @Test
    fun theKeyboardButtonAndTaps() {
        assertTrue(rig.unicode.trigger(rig.ic))
        rig.type("snowman")
        assertTrue(rig.unicode.selectCandidate(rig.ic, 2))
        assertEquals(listOf("⛇"), rig.commits())
        assertFalse(rig.unicode.selectCandidate(rig.ic, 0))
    }

    @Test
    fun codeOfReadsAsStoi() {
        assertEquals(0x41, UnicodeInput.codeOf("0".repeat(25) + "41"))
        assertNull(UnicodeInput.codeOf("80000000"))
        assertNull(UnicodeInput.codeOf("dfff"))
        assertEquals(0x10FFFF, UnicodeInput.codeOf("10ffff"))
    }
}
