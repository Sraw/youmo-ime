/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.FormattedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A commit a field dropped is kept (a WebView that lost focus); slow, silent or particular editors keep nothing. */
class CommitCheckTest {

    private class Rig(text: String = "", cursor: Int = text.length, on: Boolean = true) {
        val e = FakeEditor(text, cursor)
        val s = EditingSession(e).also { it.selection.resetTo(cursor); it.commits.on = on }
        var changes = 0
        var fellBack = 0

        init {
            s.commits.changed = { changes++ }
            s.codeFellBack = { fellBack++ }
        }

        val kept get() = s.commits.kept

        /** As onUpdateSelection brings it. */
        fun report() = s.onCursorUpdate(e.selectionStart, e.selectionEnd, e.composingStart, e.composingEnd)

        fun code(text: String) = s.writeCode(FormattedText(arrayOf(text), intArrayOf(0), -1))

        fun reads() = e.calls.count { it.startsWith("textBeforeCursor") }
    }

    @Test
    fun `a commit the field took and dropped is kept, and the cursor put back`() {
        val r = Rig("说：").apply { e.dropsWrites = true }
        r.s.commitText("好")
        val waited = r.s.commits.isWaiting
        r.s.judgeCommit()
        assertRows(
            "it waits for the editor's report" to { assertTrue(waited) },
            "no report, the text before the cursor as it was: kept" to { assertEquals("好", r.kept) },
            "the field as it was" to { assertEquals("说：", r.e.text) },
            "the cursor where the editor has it, not past a text that never came" to {
                assertEquals(2, r.s.selection.latest.start); assertTrue(r.s.selection.settled)
            },
            "nothing waits any more" to { assertFalse(r.s.commits.isWaiting) },
            "told as it began to wait and as it was kept" to { assertEquals(2, r.changes) },
        )
    }

    @Test
    fun `commits typed on into a field that drops them are all kept, in order`() {
        val r = Rig().apply { e.dropsWrites = true }
        r.s.commitText("你")
        r.s.commitText("好")
        r.s.judgeCommit()
        r.s.commitText("吗")
        r.s.judgeCommit()
        assertRows(
            "judged together, then the next added" to { assertEquals("你好吗", r.kept) },
            "two reads for the first of those that waited together, before it and right after, one as each wait ended" to {
                assertEquals(6, r.reads())
            },
        )
    }

    @Test
    fun `nothing is kept in a field that reports, at once or late, or that does not report at all`() {
        val prompt = Rig("AB").apply { s.commitText("好"); report() }
        val readsAtReport = prompt.reads()
        prompt.s.judgeCommit()
        // the report not in by the time the wait is up (a page busy with its own script): read, the text is there
        val late = Rig("AB").apply { s.commitText("好"); s.judgeCommit() }
        val lateKept = late.kept
        late.report()
        val silent = Rig("AB").apply { s.commitText("你"); s.judgeCommit(); s.commitText("好"); s.judgeCommit() }
        assertRows(
            "reported: landed, and no read when the wait ends" to {
                assertEquals("", prompt.kept); assertFalse(prompt.s.commits.isWaiting); assertEquals(readsAtReport, prompt.reads())
            },
            "late: read back there, nothing kept; its report then changes nothing" to {
                assertEquals("", lateKept); assertEquals("", late.kept); assertEquals("AB好", late.e.text)
            },
            "an editor that never reports its cursor: read back each time" to { assertEquals("", silent.kept); assertEquals("AB你好", silent.e.text) },
        )
    }

    @Test
    fun `an editor that changes, hides or repeats the text is not said to have dropped it`() {
        val upper = Rig("ab").apply { e.rewriter = { it.uppercase() }; s.commitText("c"); s.judgeCommit() }
        val cut = Rig("12345").apply { e.rewriter = { it.take(6) }; s.commitText("678"); s.judgeCommit() }
        val unread = Rig("AB").apply { e.dropsWrites = true; e.revealLimit = null; s.commitText("好"); s.judgeCommit() }
        val unreadLater = Rig("AB").apply { e.dropsWrites = true; s.commitText("好"); e.revealLimit = null; s.judgeCommit() }
        val row = Rig("哈".repeat(40)).apply { e.dropsWrites = true; s.commitText("哈"); s.judgeCommit() }
        val off = Rig("AB", on = false).apply { e.dropsWrites = true; s.commitText("好"); s.judgeCommit() }
        val empty = Rig("AB").apply { e.dropsWrites = true; s.commitText(""); s.judgeCommit() }
        assertRows(
            "upper-cased on its way in: the text before the cursor changed" to { assertEquals("", upper.kept) },
            "cut short by the field's length: part went in, not judged" to { assertEquals("", cut.kept); assertEquals("123456", cut.e.text) },
            "a field that will not be read: not waited for" to { assertEquals("", unread.kept); assertFalse(unread.s.commits.isWaiting) },
            "one that stopped answering by the time the wait is up: not known, nothing kept" to { assertEquals("", unreadLater.kept) },
            "what was committed already ends the text before the cursor: cannot be told, nothing kept" to { assertEquals("", row.kept) },
            "not checked (a password, a field of no type): no read, nothing kept" to { assertEquals("", off.kept); assertEquals(0, off.reads()) },
            "an empty commit is not one" to { assertEquals("", empty.kept); assertEquals(0, empty.reads()) },
        )
    }

    @Test
    fun `a field of exactly as much text as is read, full or dropping, is told apart`() {
        val live = Rig("x".repeat(CommitCheck.READ)).apply { s.commitText("好"); s.judgeCommit() }
        val dead = Rig("x".repeat(CommitCheck.READ)).apply { e.dropsWrites = true; s.commitText("好"); s.judgeCommit() }
        val long = "好".repeat(CommitCheck.READ * 3)
        val deadLong = Rig("AB").apply { e.dropsWrites = true; s.commitText(long); s.judgeCommit() }
        assertRows(
            "went in" to { assertEquals("", live.kept) },
            "dropped" to { assertEquals("好", dead.kept) },
            "a text longer than is read, dropped: kept whole" to { assertEquals(long, deadLong.kept) },
        )
    }

    @Test
    fun `what is kept is written by taking it once the field answers, and stays kept if it does not`() {
        val r = Rig("AB").apply { e.dropsWrites = true; s.commitText("好"); s.judgeCommit() }
        // tapped while the field still drops: taken, committed, judged again
        r.s.commitText(r.s.commits.take())
        val takenWhileWaiting = r.kept
        r.s.judgeCommit()
        val still = r.kept
        r.e.dropsWrites = false
        r.s.commitText(r.s.commits.take())
        r.report()
        assertRows(
            "taken: not shown while its commit waits" to { assertEquals("", takenWhileWaiting) },
            "dropped again: kept again" to { assertEquals("好", still) },
            "written where the cursor is, once" to { assertEquals("AB好", r.e.text); assertEquals(3, r.e.selectionStart) },
            "nothing kept" to { assertEquals("", r.kept) },
        )
    }

    @Test
    fun `what is kept goes when the user types on and it lands, when the field changes, never by a report alone`() {
        fun kept() = Rig("AB").apply { e.dropsWrites = true; s.commitText("好"); s.judgeCommit(); e.dropsWrites = false }
        val typedOn = kept().apply { s.commitText("吗"); report() }
        val typedOnSilently = kept().apply { s.commitText("吗"); s.judgeCommit() }
        val reported = kept().apply { e.reportSelection(1); report() }
        // typed on, the field tapped before the wait is up: the report is not the commit's, nothing read; what was kept stays
        val tappedMeanwhile = Rig("AB").apply {
            e.dropsWrites = true; s.commitText("你"); s.judgeCommit()
            s.commitText("好"); e.reportSelection(1); report(); s.judgeCommit()
        }
        val forgotten = kept().apply { s.commits.forget() }
        val turnedOff = kept().apply { s.commits.on = false }
        assertRows(
            "something else typed and reported in: the kept text is let go" to { assertEquals("", typedOn.kept); assertEquals("AB吗", typedOn.e.text) },
            "the same where it is read back in" to { assertEquals("", typedOnSilently.kept) },
            "the cursor moved (the field tapped): still kept" to { assertEquals("好", reported.kept) },
            "a report that is not the commit's while it waits: not judged, the older text still kept" to { assertEquals("你", tappedMeanwhile.kept) },
            "another field, the keyboard put away" to { assertEquals("", forgotten.kept) },
            "kept through a check turned off (the same field said anew), till forgotten" to { assertEquals("好", turnedOff.kept) },
        )
    }

    @Test
    fun `a word composing in the field, finished in place, is kept where the field dropped it`() {
        fun word(drops: Boolean) = Rig("AB").apply {
            e.dropsWrites = drops
            s.updateComposingText(FormattedText(arrayOf("hello"), intArrayOf(0), -1))
            s.commitText("hello")
            s.judgeCommit()
        }
        val live = word(false)
        val dead = word(true)
        assertRows(
            "there already: finished in place, nothing kept" to { assertEquals("", live.kept); assertEquals("ABhello", live.e.text) },
            "never went in: kept" to { assertEquals("hello", dead.kept); assertEquals("AB", dead.e.text) },
        )
    }

    @Test
    fun `the code in the field, then its commit`() {
        val live = Rig("AB").apply {
            code("h"); report(); code("ha"); report(); code("hao"); report()
            s.commitText("好")
            report()
        }
        // the screen turned, the view lost focus: the code's writes and the strip's commit are dropped
        val dead = Rig("AB").apply {
            e.dropsWrites = true
            code("h")
            code("ha")
        }
        val floated = dead.fellBack
        dead.s.commitText("好")
        dead.s.judgeCommit()
        assertRows(
            "a field that takes it: the code replaced by the commit, nothing kept" to { assertEquals("AB好", live.e.text); assertEquals("", live.kept) },
            "a field that drops it: the code floats at the second key" to { assertEquals(1, floated) },
            "and the commit is kept" to { assertEquals("好", dead.kept); assertEquals("AB", dead.e.text) },
        )
    }

    @Test
    fun `a commit made after the one a wait was timed for has its own wait`() {
        // the timer was set for the first commit; the next made just before it runs is not judged then (reads may lag a frame)
        fun next(between: Rig.() -> Unit) = Rig("AB").let { r ->
            var now = 0L
            r.s.commits.clock = { now }
            r.s.commitText("你")
            r.between()
            now = CommitCheck.WAIT_MS - 5
            r.s.commitText("好")
            now = CommitCheck.WAIT_MS
            val atFirstTimer = r.s.commits.waitLeft()
            now = 2 * CommitCheck.WAIT_MS - 5
            atFirstTimer to r.s.commits.waitLeft()
        }
        val reported = next { report() }
        val anotherField = next { s.commits.forget() }
        val firstAlone = Rig("AB").let { r ->
            var now = 100L
            r.s.commits.clock = { now }
            r.s.commitText("你")
            now += CommitCheck.WAIT_MS
            r.s.commits.waitLeft()
        }
        assertRows(
            "the one before reported: when its timer runs, the new one has nearly all its wait left" to {
                assertEquals(CommitCheck.WAIT_MS - 5 to 0L, reported)
            },
            "the field left meanwhile: the same" to { assertEquals(CommitCheck.WAIT_MS - 5 to 0L, anotherField) },
            "a commit alone: its wait up on time" to { assertEquals(0L, firstAlone) },
            "nothing waiting: nothing left to wait" to { assertEquals(0L, Rig().s.commits.waitLeft()) },
        )
    }

    @Test
    fun `anything else done to the field while a commit waits leaves it unjudged`() {
        // a late reporter: the commit went in then was deleted or the cursor moved back before the wait; a read shows the old text
        fun after(change: EditingSession.() -> Unit) = Rig("说").apply {
            s.commitText("你")
            report()
            s.commitText("好")
            s.change()
            s.judgeCommit()
        }.kept
        val direct = EditorTraits(acceptsDeleteSurrounding = true)
        assertRows(
            "Backspace" to { assertEquals("", after { backspace(direct) }) },
            "a deletion fcitx asks for" to { assertEquals("", after { deleteSurrounding(1, 0, inCodePoints = true) }) },
            "the cursor set back" to { assertEquals("", after { select(2, 2) }) },
            "the cursor moved by an offset" to { assertEquals("", after { applySelectionOffset(-1, -1) }) },
        )
    }

    @Test
    fun `a commit made after something else was done to the field waits on its own`() {
        // a field that drops: 你 committed, Backspace at once (that one not judged), 好 within the wait
        val r = Rig("AB").apply { e.dropsWrites = true }
        r.s.commitText("你")
        r.s.backspace(EditorTraits(acceptsDeleteSurrounding = true))
        val waitingAfterTheDeletion = r.s.commits.isWaiting
        val readsBefore = r.reads()
        r.s.commitText("好")
        val readForIt = r.reads() - readsBefore
        r.s.judgeCommit()
        assertRows(
            "the deletion ends the wait unjudged" to { assertFalse(waitingAfterTheDeletion) },
            "the next commit reads for itself, before it and right after" to { assertEquals(2, readForIt) },
            "and is kept" to { assertEquals("好", r.kept) },
        )
    }

    @Test
    fun `what is kept, asked to be written while a commit waits, stays till it is taken`() {
        fun asked(end: Rig.() -> Unit) = Rig("AB").apply {
            e.dropsWrites = true
            s.commitText("你")
            s.judgeCommit()
            e.dropsWrites = false
            s.commitText("好")
            s.commits.toWrite = true
            changes = 0
            end()
        }
        val reported = asked { report() }
        val readBackIn = asked { s.judgeCommit() }
        val actedOn = asked { s.commits.moved() }
        val taken = asked { report() }.let { it.s.commits.take() to it.s.commits.toWrite }
        assertRows(
            "the commit waiting reported: still kept, the service told to write it" to {
                assertEquals("你", reported.kept); assertEquals(1, reported.changes)
            },
            "read back in when its wait is up: still kept" to { assertEquals("你", readBackIn.kept) },
            "the field acted on meanwhile: still kept, told" to { assertEquals("你", actedOn.kept); assertEquals(1, actedOn.changes) },
            "taken: the ask done with" to { assertEquals("你" to false, taken) },
            "forgotten with the field" to { assertFalse(asked { s.commits.forget() }.s.commits.toWrite) },
        )
    }

    @Test
    fun `after a commit that landed the next is not read before, and one dropped then is still kept`() {
        val r = Rig("说：")
        r.s.commitText("你")
        r.report()
        r.e.dropsWrites = true
        r.s.commitText("好")
        val readsBefore = r.reads()
        r.s.judgeCommit()
        assertRows(
            "reads before the first commit and right after it alone: the second's text before the cursor known" to {
                assertEquals(2, readsBefore)
            },
            "dropped, no report, the text before the cursor as the first left it: kept" to { assertEquals("好", r.kept) },
            "the field as the first left it" to { assertEquals("说：你", r.e.text) },
        )
    }

    @Test
    fun `read before a commit again where the field may not be as the last commit left it`() {
        // read, a commit is read right after it too: two reads
        fun readsForSecond(between: Rig.() -> Unit, second: Rig.() -> Unit = { s.commitText("好") }) =
            Rig("说：").run {
                s.commitText("你")
                report()
                between()
                val at = reads()
                second()
                reads() - at
            }
        assertRows(
            "nothing between: not read" to { assertEquals(0, readsForSecond({})) },
            "the cursor put elsewhere by the user" to { assertEquals(2, readsForSecond({ e.setSelection(1, 1); report() })) },
            "a deletion" to { assertEquals(2, readsForSecond({ s.deleteSurrounding(1, 0, inCodePoints = false); report() })) },
            "a code composing in the field, the commit replacing it" to {
                assertEquals(2, readsForSecond({ code("hao"); report() }))
            },
            "the last commit not a plain insertion (the cursor left inside it)" to {
                assertEquals(2, Rig("说：").run { s.commitText("（）", 1); report(); val at = reads(); s.commitText("好"); reads() - at })
            },
            "another field" to { assertEquals(2, readsForSecond({ s.commits.forget() })) },
            "the cursor away and back where the last commit left it" to {
                assertEquals(2, readsForSecond({ e.setSelection(1, 1); report(); e.setSelection(3, 3); report() }))
            },
            "the app's rewrite in place, of the same length, reported at the same cursor" to {
                assertEquals(2, readsForSecond({ e.appReplace(0, 1, "讲"); report() }))
            },
            "a commit judged and kept between" to {
                assertEquals(2, readsForSecond({ e.dropsWrites = true; s.commitText("好"); s.judgeCommit(); e.dropsWrites = false }))
            },
            "checking turned off and on again" to { assertEquals(2, readsForSecond({ s.commits.on = false; s.commits.on = true })) },
        )
    }

    @Test
    fun `the known text before the cursor is what a read there would give`() {
        // a commit that lands, then one the field drops: kept, and the reads made before the judging
        fun keptAfterLanded(text: String, limit: Int = Int.MAX_VALUE) = Rig(text).run {
            e.revealLimit = limit
            s.commitText("你")
            report()
            e.dropsWrites = true
            s.commitText("好")
            val reads = reads()
            s.judgeCommit()
            kept to reads
        }
        // two commits before one report, then one more that lands and one dropped
        val merged = Rig("说：").run {
            s.commitText("你")
            s.commitText("好")
            report()
            s.commitText("吗")
            report()
            e.dropsWrites = true
            s.commitText("呢")
            val reads = reads()
            s.judgeCommit()
            kept to reads
        }
        assertRows(
            "40 units before the cursor: the last 32 known" to { assertEquals("好" to 2, keptAfterLanded("a".repeat(40))) },
            "a pair cut at the 32nd unit back, as a read cuts it" to {
                assertEquals("好" to 2, keptAfterLanded("x😀" + "a".repeat(30)))
            },
            "commits judged together: known after them all" to { assertEquals("呢" to 2, merged) },
            "an editor that gives less before the cursor than asked: read each time, still kept" to {
                assertEquals("好" to 4, keptAfterLanded("a".repeat(40), limit = 10))
            },
        )
    }

    @Test
    @Uat("LC-36")
    fun `a field that uses the text and puts its buffer back is not said to have dropped it`() {
        // a terminal's field takes the text and empties itself in one batch edit, no report; the read right after saw it go in
        fun used(text: String) = Rig(text).apply {
            s.commitText("ls")
            e.appSetsText(text)
            s.judgeCommit()
        }
        val empty = used("")
        val prompt = used("> ")
        // one taken away within the write itself reads as dropped: kept, but no more than KEPT_MAX units, a pair not cut
        val first = "😀".repeat(100)
        val second = "x" + "😀".repeat(100)
        val within = Rig().apply {
            e.rewriter = { "" }
            s.commitText(first)
            s.judgeCommit()
            s.commitText(second)
            s.judgeCommit()
        }
        assertRows(
            "emptied: nothing kept" to { assertEquals("", empty.kept); assertEquals("", empty.e.text) },
            "put back to its prompt: nothing kept" to { assertEquals("", prompt.kept) },
            "taken away within the write: kept, its end, starting at a whole pair" to {
                assertEquals((first + second).takeLast(CommitCheck.KEPT_MAX - 1), within.kept)
            },
        )
    }
}
