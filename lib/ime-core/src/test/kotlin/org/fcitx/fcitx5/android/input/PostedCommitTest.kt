/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.PostedCommit.Deletion
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where text the app puts in after a wait goes, and what of the field it reads and deletes there. */
class PostedCommitTest {

    private val field = Any()

    private val cursor = CursorTracker().apply { resetTo(3) }

    // 九键's 1 pressed with the cursor at 3: ， put in, the cursor predicted after it
    private val comma = PostedCommit(field, "", "，").also {
        cursor.predict(4)
        it.committed(cursor)
    }

    // pressed again soon: 。 in its place
    private val period = PostedCommit(field, "，", "。")

    private val notRead: (Int) -> CharSequence? = { error("read") }

    private fun PostedCommit.takeBackUnread(last: PostedCommit? = comma, composing: Boolean = false) =
        takeBack(sensitive = true, composing = composing, cursor = cursor, last = last, textBefore = notRead)

    @Test
    fun itGoesInTheFieldItWasAskedForInAloneThoughTheScreenTurned() {
        val asked = PostedCommit(field, "", "a")
        // a turn restarts input with the EditorInfo the field started with
        assertTrue(asked.lands(field))
        assertFalse(asked.lands(Any()))
        // the field finished, no other started: still queued, it is dropped
        assertFalse(asked.lands(null))
        assertFalse(PostedCommit(null, "", "a").lands(null))
    }

    @Test
    fun inASensitiveFieldTheMarkGoesUnreadOnceTheEditorReportedItsCommit() {
        // not reported yet: it stays, and both are typed
        assertNull(period.takeBackUnread())
        cursor.consume(4)
        assertEquals(Deletion(1, inCodePoints = false), period.takeBackUnread())
        // something composed: the cursor is not after the mark alone
        assertNull(period.takeBackUnread(composing = true))
        // its units, as the editor counts them
        val emoji = PostedCommit(field, "", "😀").also {
            cursor.predict(6)
            it.committed(cursor)
        }
        cursor.consume(6)
        assertEquals(Deletion(2, inCodePoints = false), PostedCommit(field, "😀", "。").takeBackUnread(last = emoji))
    }

    @Test
    fun inASensitiveFieldOnlyTheTextPutInLastIsTakenBack() {
        cursor.consume(4)
        // a paste since, say
        assertNull(PostedCommit(field, "。", "？").takeBackUnread())
        assertNull(period.takeBackUnread(last = null))
    }

    // the editor did otherwise than the commit predicted (moved the cursor to the end, say), and
    // reported it after 1 was pressed again, too late for 九键 to start over
    @Test
    fun inASensitiveFieldAReportNoneExpectedSinceTheMarkWentInDeletesNothing() {
        cursor.consume(9)
        assertNull(period.takeBackUnread())
    }

    // the press found ， reported, nothing pending, and then the editor moved the cursor
    @Test
    fun inASensitiveFieldAReportNoneExpectedAfterTheMarksDeletesNothing() {
        cursor.consume(4)
        cursor.consume(9)
        assertNull(period.takeBackUnread())
    }

    // fcitx still busy with the press, the IME edited at once (the backspace swipe): the cursor
    // is not after the mark, reported or not
    @Test
    fun inASensitiveFieldAnEditSinceTheMarkWentInDeletesNothing() {
        cursor.consume(4)
        cursor.predict(5)
        assertNull(period.takeBackUnread())
        cursor.consume(5)
        assertNull(period.takeBackUnread())
    }

    // fcitx busy (starting, or reloading): 1 pressed again before the first ， went in, which the
    // editor then reported
    @Test
    fun inASensitiveFieldTheMarkPutInOnlyAfterThePressGoesToo() {
        val busy = CursorTracker().apply { resetTo(3) }
        val second = PostedCommit(field, "，", "。")
        val first = PostedCommit(field, "", "，")
        busy.predict(4)
        first.committed(busy)
        busy.consume(4)
        assertEquals(Deletion(1, inCodePoints = false), second.takeBack(sensitive = true, composing = false, cursor = busy, last = first, textBefore = notRead))
    }

    @Test
    fun elsewhereTheTextBeforeTheCursorIsReadToKnowAndDeletedInCodePoints() {
        // whatever the editor reported: what is there tells, read as getTextBeforeCursor gives it, no more than asked
        assertEquals(Deletion(1, inCodePoints = true), period.takeBack(sensitive = false, composing = false, cursor = cursor, last = null) { "ab，".takeLast(it) })
        // the field refused it, or the cursor moved
        assertNull(period.takeBack(sensitive = false, composing = false, cursor = cursor, last = null) { "a" })
        assertNull(period.takeBack(sensitive = false, composing = false, cursor = cursor, last = null) { null })
        assertEquals(Deletion(1, inCodePoints = true), PostedCommit(field, "😀", "。").takeBack(sensitive = false, composing = false, cursor = cursor, last = null) { "😀" })
    }

    @Test
    fun nothingToReplaceIsNeitherReadNorDeleted() {
        cursor.consume(4)
        assertNull(comma.takeBack(sensitive = false, composing = false, cursor = cursor, last = null, textBefore = notRead))
        assertNull(comma.takeBackUnread())
    }
}
