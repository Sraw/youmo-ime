/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.junit.Assert.assertEquals
import org.junit.Test

/** The service's steps as text goes in over the engine, in its order, and what is kept of it: 九键's 1 in a password. */
class LastPostedTest {

    /** A password's field: not read, its edits predicted as EditingSession predicts them, and logged. */
    private class Password(private val cursor: CursorTracker) : LastPosted.Field {
        val log = mutableListOf<String>()
        override val sensitive = true
        override val composing = false

        override fun textBefore(length: Int): CharSequence? = error("read")

        override fun delete(deletion: PostedCommit.Deletion) {
            log += "delete(${deletion.count})"
            cursor.predictOffset(-deletion.count)
        }

        override fun commit(text: String) {
            log += "commit($text)"
            cursor.predict(cursor.latest.start + text.length)
        }
    }

    private val cursor = CursorTracker().apply { resetTo(3) }
    private val password = Password(cursor)
    private val askedIn = Any()
    private val posted = LastPosted()

    private fun press(replacing: String, text: String) = posted.putIn(PostedCommit(askedIn, replacing, text), password, cursor)

    // a wrong order, or none of it recorded, and 1 types ，。？
    @Test
    fun eachTakesThePlaceOfTheOneBeforeOnceTheEditorReportedIt() {
        press("", "，")
        cursor.consume(4)
        press("，", "。")
        cursor.consume(3)
        cursor.consume(4)
        press("。", "？")
        assertEquals(listOf("commit(，)", "delete(1)", "commit(。)", "delete(1)", "commit(？)"), password.log)
    }

    // the third before the editor reported the second's deletion and commit: back at 4, as after the first
    @Test
    fun onePressedBeforeTheReplacementIsReportedReplacesNothing() {
        press("", "，")
        cursor.consume(4)
        press("，", "。")
        press("。", "？")
        assertEquals(listOf("commit(，)", "delete(1)", "commit(。)", "commit(？)"), password.log)
    }

    // input restarted or finished: nothing of the field is kept
    @Test
    fun whatIsForgottenIsNotTakenBack() {
        press("", "，")
        cursor.consume(4)
        posted.forget()
        press("，", "。")
        assertEquals(listOf("commit(，)", "commit(。)"), password.log)
    }
}
