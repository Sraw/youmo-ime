/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.FormattedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code in the field against a [FakeEditor] that edits on its own: nothing the keyboard cannot prove it wrote is deleted or replaced. */
class EditingSessionCodeTest {

    private class Rig(text: String = "", cursor: Int = text.length) {
        val e = FakeEditor(text, cursor)
        val s = EditingSession(e).also { it.selection.resetTo(cursor) }
        var fellBack = 0

        init {
            s.codeFellBack = { fellBack++ }
        }

        fun write(text: String, cursor: Int = -1) = s.writeCode(FormattedText(arrayOf(text), intArrayOf(0), cursor))

        /** As onUpdateSelection brings it. */
        fun report() =
            s.onCursorUpdate(e.selectionStart, e.selectionEnd, e.composingStart, e.composingEnd)

        /** Each written and reported, as keys at a pace the editor keeps up with. */
        fun type(vararg codes: String) = codes.forEach {
            write(it)
            report()
        }

        fun reads() = e.calls.count { it.startsWith("textBeforeCursor") || it.startsWith("textAfterCursor") }
    }

    @Test
    fun `the code goes in as the field's composing text, checked by one read at its first report`() {
        val r = Rig("说：")
        assertTrue(r.write("n"))
        val first = r.report()
        val readsAfterFirst = r.reads()
        r.type("ni", "ni h", "ni hao")
        assertRows(
            "in the field as written, the composing span over it" to {
                assertEquals("说：ni hao", r.e.text)
                assertEquals(2 to 8, r.e.composingStart to r.e.composingEnd)
            },
            "a code in the field" to { assertEquals(EditingSession.Kind.CODE, r.s.kind); assertTrue(r.s.codeInField) },
            "its first report nothing for the engine" to { assertEquals(EditingSession.CursorUpdate.None, first) },
            "read once before the first write (can the field be read) and once at the first report" to { assertEquals(2, readsAfterFirst) },
            "no more reads while the reports match" to { assertEquals(readsAfterFirst, r.reads()) },
            "never fallen back" to { assertEquals(0, r.fellBack) },
        )
    }

    @Test
    fun `a code that cannot go in is not written, and the field is left as it was`() {
        val unread = Rig("abc").apply { e.revealLimit = null }
        val selected = Rig("abc").apply { e.reportSelection(0, 3); s.selection.resetTo(0, 3) }
        val voice = Rig("").apply { s.updateComposingText(FormattedText(arrayOf("你好"), intArrayOf(0), -1), provisional = true) }
        val wrote = listOf(unread.write("n"), selected.write("n"), voice.write("n"))
        assertRows(
            "a field that cannot be read: nothing written (no letter to leave), the code floats" to {
                assertFalse(wrote[0]); assertEquals("abc", unread.e.text); assertEquals(1, unread.fellBack)
                assertTrue(unread.e.calls.none { it.startsWith("setComposingText") })
            },
            "a selection the code would replace: not written, the selection kept (§6.2)" to {
                assertFalse(wrote[1]); assertEquals("abc", selected.e.text); assertEquals(0 to 3, selected.e.selectionStart to selected.e.selectionEnd)
            },
            "voice composing in the field: not written over it" to {
                assertFalse(wrote[2]); assertEquals("你好", voice.e.text); assertTrue(voice.s.provisional)
            },
        )
    }

    @Test
    fun `leaving takes the code away only where it is read back as written, else lets it go as text`() {
        fun typed(before: String = "AB") = Rig(before).apply { type("n", "ni", "ni h", "ni hao") }
        val proven = typed().apply { s.leaveComposition() }
        val chosen = Rig("AB").apply { type("n", "ni", "ni h", "ni hao", "你hao") }.apply { s.leaveComposition() }
        val selection = typed().apply { e.reportSelection(2, 4); s.selection.resetTo(2, 4); s.leaveComposition() }
        val unreadable = typed().apply { e.revealLimit = null; s.leaveComposition() }
        val gone = typed().apply { e.isAvailable = false; s.leaveComposition(); e.isAvailable = true }
        assertRows(
            "proven ours: taken away, the field as before it" to {
                assertEquals("AB", proven.e.text); assertEquals(2, proven.e.selectionStart); assertFalse(proven.s.codeInField)
            },
            "what is chosen goes with the code (Q5: 你hao whole)" to { assertEquals("AB", chosen.e.text) },
            "a selection over it: nothing read, let go of as text (§4.3)" to {
                assertEquals("ABni hao", selection.e.text); assertEquals(-1, selection.e.composingStart)
            },
            "a field that will not be read: let go of as text" to {
                assertEquals("ABni hao", unreadable.e.text); assertEquals(-1, unreadable.e.composingStart)
            },
            "no connection: nothing sent, the state forgotten" to { assertEquals("ABni hao", gone.e.text); assertFalse(gone.s.codeInField) },
            "a plain leave does not float the code (the engine's ends with it)" to { assertEquals(0, proven.fellBack + unreadable.fellBack) },
        )
    }

    @Test
    fun `leaving lets go of a code the app changed or that is too far to read`() {
        val changed = Rig("AB").apply { type("n", "ni"); e.appReplace(3, 4, "X"); s.leaveComposition() }
        val far = Rig("A".repeat(5000)).apply {
            type("n", "ni")
            // the cursor moved far off with no report taken yet (the prediction kept)
            s.selection.predict(4)
            e.reportSelection(4)
            s.leaveComposition()
        }
        assertRows(
            "the app changed a letter of it: not proven, the app's text whole" to { assertEquals("ABnX", changed.e.text) },
            "more than a read away: not read, let go" to {
                assertEquals("A".repeat(5000) + "ni", far.e.text)
                assertTrue(far.e.calls.none { it.startsWith("textBeforeCursor(500") })
            },
        )
    }

    @Test
    fun `the code's first report reads it back, as section 6_3 has it`() {
        val upper = Rig("").apply { e.rewriter = { it.uppercase() } }.also { it.write("n") }.let { it to it.report() }
        val dropped = Rig("AB").also { it.write("n"); it.e.appDropSpan() }.let { it to it.report() }
        val droppedLater = Rig("AB").apply { type("n", "ni") }.also { it.e.appDropSpan() }.let { it to it.report() }
        // the span dropped between a report and the next write
        val unreportedDrop = Rig("AB").apply { type("n", "ni") }.also { it.e.appDropSpan(); it.write("ni h") }.let { it to it.report() }
        // the editor cut it short, so no report (one would be the last again); the next key's write reads first
        val cut = Rig("").apply { e.rewriter = { t -> t.take(3) }; type("n", "ni", "ni ") }
            .also { it.write("ni h") }.let { it to it.report() to it.write("ni ha") }
        assertRows(
            "an app that upper-cases it: not as written at the first report, let go of, the code floats" to {
                assertEquals("N", upper.first.e.text); assertFalse(upper.first.s.codeInField); assertEquals(1, upper.first.fellBack)
                assertEquals(EditingSession.CursorUpdate.None, upper.second)
            },
            "no span at the first report: proven, taken away, the code floats (no copy added by the next write)" to {
                assertEquals("AB", dropped.first.e.text); assertEquals(1, dropped.first.fellBack)
            },
            "the span dropped later: the same" to { assertEquals("AB", droppedLater.first.e.text); assertEquals(1, droppedLater.first.fellBack) },
            "dropped with no report before the next write: a second copy goes in, nothing deleted, the next report lets go" to {
                assertEquals("ABnini h", unreportedDrop.first.e.text); assertEquals(1, unreportedDrop.first.fellBack)
            },
            "cut short by the app: not as written at the next key, the rest left, the engine keeps its code" to {
                val (rig, report) = cut.first
                assertEquals("ni ", rig.e.text); assertEquals(1, rig.fellBack); assertFalse(cut.second)
                assertEquals(EditingSession.CursorUpdate.None, report)
            },
        )
    }

    @Test
    fun `a report behind the latest write judges nothing`() {
        // each: written, a report one key behind (the first letter's), then the latest's, then left
        val behind = Rig("AB").apply { write("n"); write("ni") }
        val behindReport = behind.s.onCursorUpdate(3, 3, 2, 3)
        val behindIn = behind.s.codeInField
        behind.report()
        behind.s.leaveComposition()
        // 五笔 before app text alike to the code: a report sized by the first letter's span took the app's g for the code's end
        val wubi = Rig("ABgo", cursor = 2).apply { write("g"); write("gg") }
        wubi.s.onCursorUpdate(3, 3, 2, 3)
        val wubiIn = wubi.s.codeInField
        wubi.s.leaveComposition()
        val english = Rig("see a book", cursor = 4).apply { write("a"); write("a a") }
        english.s.onCursorUpdate(5, 5, 4, 5)
        val englishIn = english.s.codeInField
        english.s.leaveComposition()
        assertRows(
            "pinyin: still the field's code, not fallen back" to {
                assertEquals(EditingSession.CursorUpdate.None, behindReport); assertTrue(behindIn); assertEquals(0, behind.fellBack)
            },
            "pinyin: the latest's report checks it, leaving takes it away" to { assertEquals("AB", behind.e.text) },
            "wubi: still in, then taken away whole, the app's go whole" to {
                assertTrue(wubiIn); assertEquals("ABgo", wubi.e.text); assertEquals("aaaa", wubi.e.owners)
            },
            "English: the same before the app's a" to {
                assertTrue(englishIn); assertEquals("see a book", english.e.text); assertEquals("a".repeat(10), english.e.owners)
            },
        )
    }

    @Test
    fun `a report is the latest write's only by the span that write left`() {
        // the cursor in the code, the code longer after it: two writes, the cursor the same for both
        val longer = Rig("AB").apply { type("n", "ni"); write("ni", cursor = 1); write("nia", cursor = 1) }
        val older = longer.s.onCursorUpdate(3, 3, 2, 4)
        val olderIn = longer.s.codeInField
        val latest = longer.report()
        // a slow app: each report a key behind, six keys
        val codes = listOf("n", "ni", "ni h", "ni ha", "ni hao", "ni hao m")
        val slow = Rig("AB").apply {
            write(codes[0])
            codes.zipWithNext { last, next ->
                write(next)
                s.onCursorUpdate(2 + last.length, 2 + last.length, 2, 2 + last.length)
            }
            report()
        }
        assertRows(
            "the first write's report, the cursor where the second left it: older, nothing judged" to {
                assertEquals(EditingSession.CursorUpdate.None, older); assertTrue(olderIn); assertEquals(0, longer.fellBack)
            },
            "the second's: the code's, still in the field" to {
                assertEquals(EditingSession.CursorUpdate.None, latest); assertTrue(longer.s.codeInField); assertEquals("ABnia", longer.e.text)
            },
            "reports only behind are no editor that never reports: still in, nothing floats" to {
                assertTrue(slow.s.codeInField); assertEquals(0, slow.fellBack); assertEquals("ABni hao m", slow.e.text)
            },
        )
    }

    @Test
    fun `a report none predicted, as section 4_3 has it`() {
        val selected = Rig("AB").apply { type("n", "ni") }.also { it.e.appSelect(0, 4) }.let { it to it.report() }
        val inside = Rig("AB").apply { type("n", "ni", "ni h") }.also { it.e.appSelect(3) }.let { it to it.report() }
        val outside = Rig("ABCD", cursor = 2).apply { type("n", "ni") }.also { it.e.appSelect(6) }.let { it to it.report() }
        val noSpan = Rig("ABCD", cursor = 2).apply { type("n", "ni") }.also { it.e.appDropSpan(); it.e.appSelect(6) }.let { it to it.report() }
        val appBefore = Rig("AB").apply { type("n", "ni") }.also { it.e.appReplace(0, 0, "XY") }.let { it to it.report() }
        // 五笔's gg after the app ended the span unreported: written beside the g, as the editor's own span
        val gg = Rig("AB").apply { type("g"); e.appDropSpan(); write("gg") }.let { it to it.report() }
        assertRows(
            "a selection over it: let go of as text, the engine's dropped, nothing read" to {
                assertEquals("ABni", selected.first.e.text); assertEquals(0 to 4, selected.first.e.selectionStart to selected.first.e.selectionEnd)
                assertEquals(EditingSession.CursorUpdate.FocusOutIn, selected.second); assertEquals(0, selected.first.fellBack)
            },
            "a tap inside it, read back as written: the engine's cursor moved there" to {
                assertEquals(EditingSession.CursorUpdate.MovePreeditCursor(1, inside.first.s.cursorUpdateIndex), inside.second)
                assertTrue(inside.first.s.codeInField)
            },
            "a tap outside it, read back as written, the editor's span where it was written: taken away, the cursor where it went" to {
                assertEquals("ABCD", outside.first.e.text); assertEquals(4, outside.first.e.selectionStart)
                assertEquals(EditingSession.CursorUpdate.FocusOutIn, outside.second)
            },
            "the same with no span reported: the offsets are a guess, let go of" to {
                assertEquals("ABniCD", noSpan.first.e.text); assertEquals(EditingSession.CursorUpdate.FocusOutIn, noSpan.second)
            },
            "the app put text before it: not where it was written, let go of, the app's text whole" to {
                assertEquals("XYABni", appBefore.first.e.text); assertFalse(appBefore.first.s.codeInField)
                assertEquals(EditingSession.CursorUpdate.None, appBefore.second)
            },
            "the editor's span another than the code's, read back alike: let go of, the engine keeps its code, which floats" to {
                assertEquals("ABggg", gg.first.e.text); assertEquals("aakkk", gg.first.e.owners)
                assertEquals(EditingSession.CursorUpdate.None, gg.second); assertEquals(1, gg.first.fellBack)
            },
        )
    }

    @Test
    fun `a report none predicted, the span gone or the app's text come since`() {
        val insideNoSpan = Rig("AB").apply { type("n", "ni", "ni h") }.also { it.e.appDropSpan(); it.e.appSelect(3) }.let { it to it.report() }
        // a tap before it, and the app's text put at the start before the tap's report came
        val tapThenApp = Rig("AB").apply {
            type("n", "ni")
            e.appSelect(0)
            e.appReplace(0, 0, "X")
        }.let { it to it.s.onCursorUpdate(0, 0, 2, 4) }
        assertRows(
            "a tap inside it with no span reported: let go of, not moved into (the next write would add a copy)" to {
                assertEquals("ABni h", insideNoSpan.first.e.text); assertFalse(insideNoSpan.first.s.codeInField)
                assertEquals(EditingSession.CursorUpdate.FocusOutIn, insideNoSpan.second)
            },
            "a tap out of it, the app's text before it since: the editor's own span replaced, the app's text whole" to {
                assertEquals("XAB", tapThenApp.first.e.text); assertEquals("aaa", tapThenApp.first.e.owners)
                assertEquals(EditingSession.CursorUpdate.FocusOutIn, tapThenApp.second)
            },
        )
    }

    @Test
    fun `an address bar's own completion after the code keeps it there, in a URL or email field alone`() {
        fun completed(url: Boolean) = Rig("").apply {
            s.inlineCompletion = url
            type("b", "ba")
            e.appReplace(2, 2, "idu.com")
            e.appSelect(2, 9)
        }.let { it to it.report() }
        val (url, urlUpdate) = completed(true)
        val (plain, plainUpdate) = completed(false)
        url.write("bai")
        // the app's completion selected with its span over the code gone: a commit would replace the selection
        val noSpan = Rig("").apply {
            s.inlineCompletion = true
            type("b", "ba")
            e.appReplace(2, 2, "idu.com")
            e.appDropSpan()
            e.appSelect(2, 9)
        }.let { it to it.report() }
        assertRows(
            "a URL field: the code stays, the completion is the app's" to {
                assertEquals(EditingSession.CursorUpdate.None, urlUpdate); assertTrue(url.s.codeInField)
            },
            "the next key replaces the code alone" to { assertEquals("baiidu.com", url.e.text) },
            "another field: the user's selection, the code let go of" to {
                assertEquals(EditingSession.CursorUpdate.FocusOutIn, plainUpdate); assertFalse(plain.s.codeInField)
                assertEquals("baidu.com", plain.e.text)
            },
            "a URL field, no span over the code reported: the same, let go of" to {
                assertEquals(EditingSession.CursorUpdate.FocusOutIn, noSpan.second); assertFalse(noSpan.first.s.codeInField)
                assertEquals("baidu.com", noSpan.first.e.text)
            },
        )
    }

    @Test
    fun `an app that ends the composing text before its report comes has the region asked where the editor can say`() {
        // a search box's posted clearComposingText: the span gone after the first letter, its report on its way
        fun cleared(snapshots: Boolean) = Rig("AB").apply {
            e.snapshots = snapshots
            write("n")
            e.appDropSpan()
        }.let { it to it.write("ni") }
        val (told, toldWrote) = cleared(snapshots = true)
        val (blind, blindWrote) = cleared(snapshots = false)
        val kept = Rig("AB").apply { e.snapshots = true; write("n") }.let { it to it.write("ni") }
        // another composing span than the code's (the app's own)
        val other = Rig("AB").apply { e.snapshots = true; write("n"); e.appDropSpan(); e.setComposingRegion(0, 1) }.let { it to it.write("ni") }
        assertRows(
            "the editor says the span is gone: the letters read back taken away, the code floats, no copy left" to {
                assertFalse(toldWrote); assertEquals("AB", told.e.text); assertEquals(1, told.fellBack); assertFalse(told.s.codeInField)
            },
            "it cannot say (before Android 13): written as before, at most one letter left beside it, nothing deleted" to {
                assertTrue(blindWrote); assertEquals("ABnni", blind.e.text); assertEquals("aakkk", blind.e.owners)
                assertFalse("composingRegion()" in blind.e.calls)
            },
            "the span the code's: written, the region asked once" to {
                assertTrue(kept.second); assertEquals("ABni", kept.first.e.text); assertEquals(1, kept.first.e.calls.count { it == "composingRegion()" })
            },
            "another span: let go of, nothing deleted" to {
                assertFalse(other.second); assertEquals("ABn", other.first.e.text); assertEquals(1, other.first.fellBack)
            },
        )
    }

    @Test
    fun `an editor that never reports has the code read before each write, and taken away after a few`() {
        val r = Rig("AB")
        val wrote = listOf("n", "ni", "ni h", "ni ha", "ni hao").map { r.write(it) }
        assertRows(
            "written while read back as written" to { assertEquals(listOf(true, true, true, true, false), wrote) },
            "four writes with no report: taken away, proven, the code floats" to { assertEquals("AB", r.e.text); assertEquals(1, r.fellBack) },
            "read before each write after the first" to { assertEquals(1 + 4, r.reads()) },
        )
    }

    @Test
    fun `input restarted over the code`() {
        val again = Rig("AB").apply { type("n", "ni"); s.codeRestarted(again = true) }
        val other = Rig("AB").apply { type("n", "ni"); s.codeRestarted(again = false) }
        // another field under the same view, its text the same where the code was: never read, never deleted
        val lookalike = Rig("AB").apply {
            type("n", "ni")
            e.appDropSpan()
            e.calls.clear()
            s.codeRestarted(again = false)
        }
        assertRows(
            "the input method's own restart (the screen turned): proven, taken away" to { assertEquals("AB", again.e.text); assertEquals(0, again.fellBack) },
            "any other: let go of as text, the field's code floats from now on (§6.4)" to {
                assertEquals("ABni", other.e.text); assertEquals(1, other.fellBack); assertFalse(other.s.codeInField)
            },
            "the counter-case: text equal to the code where it was is not deleted, not even read" to {
                assertEquals("ABni", lookalike.e.text); assertEquals(listOf("finishComposingText()"), lookalike.e.calls)
            },
            "a restart with no code does nothing" to {
                val r = Rig("AB")
                r.s.codeRestarted(again = false)
                assertTrue(r.e.calls.isEmpty()); assertEquals(0, r.fellBack)
            },
        )
    }

    @Test
    fun `the code ends as the engine's does`() {
        val committed = Rig("AB").apply { type("n", "ni", "ni h", "ni hao"); s.commitText("你好") }
        val cleared = Rig("AB").apply { type("n", "ni"); write("") }
        // the app changed a letter of it, its report not come when the last letter is deleted
        val clearedChanged = Rig("AB").apply { type("n", "ni"); e.appReplace(3, 4, "X"); write("") }
        val voice = Rig("AB").apply {
            s.updateComposingText(FormattedText(arrayOf("你好"), intArrayOf(0), -1), provisional = true)
            report()
            e.calls.clear()
            s.backspace(EditorTraits())
        }
        var deletedHere = true
        val backspace = Rig("AB").apply { type("n", "ni"); deletedHere = s.backspace(EditorTraits()) }
        assertRows(
            "a commit replaces it" to { assertEquals("AB你好", committed.e.text); assertFalse(committed.s.codeInField) },
            "the code emptied (its last letter deleted): taken away, proven" to { assertEquals("AB", cleared.e.text); assertFalse(cleared.s.codeInField) },
            "emptied, not as written: let go of, no empty write over the app's letter" to {
                assertEquals("ABnX", clearedChanged.e.text); assertFalse(clearedChanged.s.codeInField)
            },
            "a Backspace over voice's composing text finishes it as before (kept as typed), not taken away" to {
                assertEquals("AB你好", voice.e.text); assertEquals(listOf("finishComposingText()"), voice.e.calls.take(1))
                assertTrue(voice.e.calls.toString(), voice.e.calls.none { it.startsWith("setComposingText") || it.startsWith("setComposingRegion") })
            },
            "a Backspace the engine passed on with a stale code: taken away, proven, before it deletes" to {
                val leave = backspace.e.calls.indexOf("deleteSurroundingText(2, 0, inCodePoints=false)")
                assertTrue(backspace.e.calls.toString(), leave >= 0 && backspace.e.calls[leave - 1] == "finishComposingText()")
                // the code alone gone; the deletion is the caller's key event, so the app's B stays
                assertEquals("AB", backspace.e.text)
                assertFalse(deletedHere)
            },
        )
    }

    @Test
    fun `a commit lands at the cursor, or where the code it replaces starts`() {
        val voice = Rig("AB").apply { s.updateComposingText(FormattedText(arrayOf("你好"), intArrayOf(0), -1), provisional = true) }
        assertRows(
            "nothing composing: the cursor" to { assertEquals(2, Rig("AB").s.commitStart) },
            "the code in the field: its start (a pair the engine commits recorded there)" to {
                assertEquals(2, Rig("AB").apply { type("n", "ni") }.s.commitStart)
            },
            "voice's text: not told" to { assertEquals(null, voice.s.commitStart) },
        )
    }

    @Test
    fun `a read of the context leaves the code out`() {
        val r = Rig("说：").apply { type("n", "ni", "ni h", "ni hao") }
        val mid = Rig("说：").apply { type("n", "ni", "ni h", "ni hao"); e.appSelect(4); report() }
        val none = Rig("说：")
        assertRows(
            "before the cursor at the code's end: what was there before the code" to { assertEquals("说：", r.s.beforeCode("说：ni hao")) },
            "after the cursor at the end: nothing of it" to { assertEquals("后", r.s.afterCode("后")) },
            "the cursor inside the code: its halves dropped either side" to {
                assertEquals("说：", mid.s.beforeCode("说：ni").toString()); assertEquals("", mid.s.afterCode(" hao").toString())
            },
            "no code: the read as it is" to { assertEquals("说：", none.s.beforeCode("说：")); assertEquals(null, none.s.beforeCode(null)) },
        )
    }
}
