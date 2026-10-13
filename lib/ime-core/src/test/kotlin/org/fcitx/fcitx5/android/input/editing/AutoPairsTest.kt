/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.core.FormattedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPairsTest {

    private class Typing(text: String, cursor: Int = text.length) {
        val editor = FakeEditor(text, cursor)
        val session = EditingSession(editor).also { it.selection.resetTo(cursor) }
        val pairs = session.pairs

        /** Typed as the service types it: the pairs first, else committed. */
        fun type(text: String) {
            for (c in text) if (!pairs.type(c.toString())) session.commitText(c.toString())
        }

        // as the service does: the pair's closing one, then the key event, here its deletion
        fun backspace() {
            pairs.backspace()
            assertTrue(session.backspace(EditorTraits(acceptsDeleteSurrounding = true)))
        }

        /** The editor reports where its cursor is, as it does after each change. */
        fun report() {
            session.onCursorUpdate(
                editor.selectionStart, editor.selectionEnd, editor.composingStart, editor.composingEnd,
                ignoreSystemCursor = false,
            )
        }

        /** The user puts the cursor at [p], and the editor reports it. */
        fun moveTo(p: Int) {
            editor.setSelection(p, p)
            report()
        }

        /** The session expects the cursor where the editor has it. */
        fun assertInStep() = assertTrue(
            "predicted ${session.selection.latest}, editor at ${editor.selectionStart}",
            session.selection.latest.rangeEquals(editor.selectionStart, editor.selectionEnd),
        )

        /** The text with `|` where the cursor is. */
        val shown get() = editor.text.substring(0, editor.selectionStart) + "|" + editor.text.substring(editor.selectionStart)
    }

    @Test
    fun anOpeningBracketPutsItsClosingOneAfterTheCursor() {
        val t = Typing("")
        t.type("（")
        assertEquals("（|）", t.shown)
        t.type("好")
        assertEquals("（好|）", t.shown)
    }

    /** Typing in a pair, what neither closes it nor opens one is committed without asking the editor. */
    @Test
    fun aCharacterThatNeitherClosesNorOpensReadsNothing() {
        val t = Typing("")
        t.type("（")
        t.editor.calls.clear()
        t.type("好")
        assertEquals("（好|）", t.shown)
        assertFalse(t.editor.calls.any { it.startsWith("text") })
    }

    @Test
    fun theClosingOneTypedStepsOverTheOnePutIn() {
        val t = Typing("")
        t.type("（好）")
        assertEquals("（好）|", t.shown)
        // nested, the innermost first
        val n = Typing("")
        n.type("（《书》）")
        assertEquals("（《书》）|", n.shown)
    }

    // English takes back the space it put after a word, the bracket typed in its place with one
    // after it: the closing one put in is stepped over, not typed again
    @Test
    fun aClosingOneCommittedWithMoreAfterItStepsOverTheOnePutIn() {
        val t = Typing("")
        t.type("(")
        t.session.commitText("see ")
        t.report()
        t.session.deleteSurrounding(1, 0, inCodePoints = true)
        t.report()
        assertTrue(t.pairs.type(") "))
        assertEquals("(see) |", t.shown)
        t.assertInStep()
        // a deletion reaching past the opening one leaves nothing to step over
        val past = Typing("")
        past.type("(")
        past.session.deleteSurrounding(1, 0, inCodePoints = true)
        past.report()
        assertFalse(past.pairs.type(") "))
    }

    @Test
    fun aClosingOneTheUserWroteIsNotSteppedOver() {
        val t = Typing("）", cursor = 0)
        t.type("）")
        assertEquals("）|）", t.shown)
    }

    @Test
    fun backspaceInAnEmptyPairTakesBoth() {
        val t = Typing("说")
        t.type("“")
        assertEquals("说“|”", t.shown)
        t.backspace()
        assertEquals("说|", t.shown)
        // not once something was typed in it
        t.type("“好")
        t.backspace()
        assertEquals("说“|”", t.shown)
        t.backspace()
        assertEquals("说|", t.shown)
    }

    @Test
    fun beforeAWordNothingIsPaired() {
        val t = Typing("abc", cursor = 0)
        t.type("(")
        assertEquals("(|abc", t.shown)
        // before a space or punctuation, as at the end
        val s = Typing(" x", cursor = 0)
        s.type("[")
        assertEquals("[|] x", s.shown)
        val p = Typing("。", cursor = 0)
        p.type("《")
        assertEquals("《|》。", p.shown)
    }

    @Test
    fun anAsciiQuoteAfterAWordClosesAndAnApostropheNeverPairs() {
        val t = Typing("say ")
        t.type("\"hi\"")
        assertEquals("say \"hi\"|", t.shown)
        val w = Typing("word")
        w.type("\"")
        assertEquals("word\"|", w.shown)
        val a = Typing("don")
        a.type("'t")
        assertEquals("don't|", a.shown)
    }

    /** A letter beyond the basic plane is two units: the quote after it sees the whole letter. */
    @Test
    fun anAsciiQuoteAfterALetterBeyondTheBasicPlaneCloses() {
        val letter = String(Character.toChars(0x20000)) // a CJK Extension B ideograph
        val t = Typing(letter)
        t.type("\"")
        assertEquals("$letter\"|", t.shown)
    }

    @Test
    fun theCursorMovedElsewhereForgetsThePairs() {
        // the editor reports the cursor the IME put there, then one the user tapped to, and back
        fun movedAwayAndBack() = Typing("").apply {
            type("（")
            report()
            moveTo(0)
            moveTo(1)
        }
        val t = movedAwayAndBack()
        t.type("）")
        assertEquals("（）|）", t.shown)
        t.assertInStep()
        // nor does Backspace there take the closing one with the opening one
        val b = movedAwayAndBack()
        b.backspace()
        assertEquals("|）", b.shown)
        b.assertInStep()
    }

    /** Nothing composed is the range (0,0), which holds 0: a move there is a move all the same. */
    @Test
    fun theCursorMovedToTheStartForgetsThePairs() {
        val t = Typing("")
        t.type("“")
        t.session.onCursorUpdate(1, 1, -1, -1, ignoreSystemCursor = false)
        t.editor.setSelection(0, 0)
        t.session.onCursorUpdate(0, 0, -1, -1, ignoreSystemCursor = false)
        assertFalse(t.pairs.quoteOpen)
        // the app deleted what was before a closing one put in: one typed there is typed, not
        // stepped over
        val d = Typing("")
        d.type("（")
        d.session.onCursorUpdate(1, 1, -1, -1, ignoreSystemCursor = false)
        d.editor.deleteSurroundingText(1, 0, inCodePoints = false)
        d.session.onCursorUpdate(0, 0, -1, -1, ignoreSystemCursor = false)
        d.type("）")
        assertEquals("）|）", d.shown)
    }

    @Test
    fun aSelectionOrAPreeditIsLeftToTheCaller() {
        val t = Typing("abc", cursor = 0)
        t.editor.setSelection(0, 3)
        t.session.selection.resetTo(0, 3)
        assertFalse(t.pairs.type("("))
        val c = Typing("")
        c.session.setComposingText(FormattedText(arrayOf("ni"), intArrayOf(0), -1))
        c.session.composing.update(0, 2)
        assertFalse(c.pairs.type("("))
        // more than one character opens no pair, and with none open steps over nothing: the caller's
        assertFalse(Typing("").pairs.type("()"))
        assertTrue(Typing("").pairs.type("("))
    }

    @Test
    fun eitherQuoteFcitxMakesStepsOverTheClosingOne() {
        val t = Typing("")
        t.type("“好")
        assertTrue(t.pairs.quoteOpen)
        // fcitx reset meanwhile: the quote key gives “ again
        t.type("“")
        assertEquals("“好”|", t.shown)
        // which fcitx now counts open: quoteOpen says so till the cursor report, where the service
        // sees it fall and resets fcitx; else the next quote would be a lone ”
        assertTrue(t.pairs.quoteOpen)
        t.session.onCursorUpdate(3, 3, -1, -1, ignoreSystemCursor = false)
        assertFalse(t.pairs.quoteOpen)
        // fcitx's own ” closed what it counted: nothing to reset
        val s = Typing("")
        s.type("“好”")
        assertFalse(s.pairs.quoteOpen)
    }

    /** A commit starting with “, a phrase or a candidate, is no quote key fcitx turned: the ” put in stays. */
    @Test
    fun aCommitStartingWithAnOpeningQuoteDoesNotStepOver() {
        val t = Typing("")
        t.type("“")
        assertFalse(t.pairs.type("“引用”"))
        assertEquals("“|”", t.shown)
        assertTrue(t.pairs.quoteOpen)
    }

    @Test
    fun aQuoteAfterPunctuationClosesAndOneAfterAnOpeningBracketOpens() {
        val t = Typing("\"Stop.")
        t.type("\"")
        assertEquals("\"Stop.\"|", t.shown)
        val o = Typing("(")
        o.type("\"")
        assertEquals("(\"|\"", o.shown)
    }

    @Test
    fun anEditorThatWillNotSayWhatFollowsGetsNoPair() {
        val t = Typing("")
        t.editor.revealLimit = null
        assertFalse(t.pairs.type("("))
    }

    @Test
    fun aPairFcitxPutInIsRememberedAsOnePutInHere() {
        val t = Typing("说")
        t.session.commitText("“”", 1)
        t.pairs.committed("“”", 1, start = 1)
        t.type("好”")
        assertEquals("说“好”|", t.shown)
    }

    @Test
    fun deletingASelectionForgetsThePairs() {
        val t = Typing("ab")
        t.type("(")
        t.session.applySelectionOffset(-3)
        t.session.deleteSelection()
        assertEquals("|)", t.shown)
        t.type(")")
        assertEquals(")|)", t.shown)
    }

    @Test
    fun aTapWithinWhatIsComposedBetweenThemKeepsThePairs() {
        val t = Typing("")
        t.type("“")
        t.session.onCursorUpdate(1, 1, -1, -1, ignoreSystemCursor = false)
        t.session.setComposingText(FormattedText(arrayOf("ni"), intArrayOf(0), -1))
        t.session.composing.update(1, 3)
        t.session.onCursorUpdate(2, 2, 1, 3, ignoreSystemCursor = false)
        assertTrue(t.pairs.quoteOpen)
        // out of it: gone
        t.session.onCursorUpdate(0, 0, 1, 3, ignoreSystemCursor = false)
        assertFalse(t.pairs.quoteOpen)
    }
}
