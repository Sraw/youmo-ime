/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.editing.CodeInField.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where 编码行 输入框里 sends the code. */
class CodeInFieldTest {

    private fun started(traits: EditorTraits = EditorTraits()) = CodeInField().apply { startInput(traits, restarting = false) }

    private fun CodeInField.route(
        inField: Boolean = true,
        code: Boolean = true,
        selectionEmpty: Boolean = true,
        othersComposing: Boolean = false,
        text: String = "ni",
        cursor: Int = 3,
    ) = route(inField, text.takeIf { code }, CursorRange(cursor, if (selectionEmpty) cursor else cursor + 2), othersComposing)

    @Test
    fun `the fields that take the code, as the user answered Q3`() {
        assertRows(
            "a plain text field" to { assertTrue(CodeInField.takes(EditorTraits())) },
            "a URL field (an address bar): in, its completion apart (§6.5)" to { assertTrue(CodeInField.takes(EditorTraits(isUri = true))) },
            "an email field" to { assertTrue(CodeInField.takes(EditorTraits(isEmail = true))) },
            "a field that asks not to be learned from" to { assertTrue(CodeInField.takes(EditorTraits(isSensitive = true))) },
            "a terminal (TYPE_NULL): no composing text" to { assertFalse(CodeInField.takes(EditorTraits(isRawKeyInput = true))) },
            "a password, hidden or shown" to { assertFalse(CodeInField.takes(EditorTraits(isPassword = true, isSensitive = true))) },
            "a number's, phone's or date's" to { assertFalse(CodeInField.takes(EditorTraits(isNumber = true))) },
        )
    }

    @Test
    fun `a code goes where it starts and stays, the field to the strip only`() {
        assertRows(
            "输入框里, a field that takes it, nothing selected: the field" to { assertEquals(Route.FIELD, started().route()) },
            "another 编码行: the strip" to { assertEquals(Route.STRIP, started().route(inField = false)) },
            "a field that does not take it: the strip" to { assertEquals(Route.STRIP, started(EditorTraits(isPassword = true)).route()) },
            "a selection as it starts: the strip (§6.2)" to { assertEquals(Route.STRIP, started().route(selectionEmpty = false)) },
            "voice composing in the field: the strip" to { assertEquals(Route.STRIP, started().route(othersComposing = true)) },
            "the cursor not known (an editor that has said nothing): the strip" to { assertEquals(Route.STRIP, started().route(cursor = -1)) },
            "a keyboard's own character in the code (private use, §4.2): the strip" to {
                assertEquals(Route.STRIP, started().route(text = "n\uE000"))
                assertEquals(Route.STRIP, started().route(text = "n" + String(Character.toChars(0xF0200))))
            },
            "one turning up in a code in the field: the strip from then on, the input's next code in the field" to {
                val c = started()
                c.route()
                val turned = c.route(text = "ni\uE000")
                val after = c.route()
                c.route(code = false)
                assertEquals(listOf(Route.STRIP, Route.STRIP, Route.FIELD), listOf(turned, after, c.route()))
            },
            "a first write the field did not take: this code floats, the next one in the field" to {
                val c = started()
                c.route()
                c.floats()
                val now = c.route()
                c.route(code = false)
                assertEquals(listOf(Route.STRIP, Route.FIELD), listOf(now, c.route())); assertFalse(c.fallenBack)
            },
            "no code (a temp mode's, the marks on offer, none typed): no route" to { assertNull(started().route(code = false)) },
            "kept as it was decided while the code lasts, a selection made since aside" to {
                val c = started()
                c.route()
                assertEquals(Route.FIELD, c.route(selectionEmpty = false))
            },
            "fallen back: the strip, never back to the field while it lasts" to {
                val c = started()
                c.route()
                c.fallBack()
                assertEquals(Route.STRIP, c.route())
            },
            "a strip code stays on the strip though the selection went" to {
                val c = started()
                c.route(selectionEmpty = false)
                assertEquals(Route.STRIP, c.route())
            },
            "the next code decides anew" to {
                val c = started()
                c.route(selectionEmpty = false)
                c.route(code = false)
                assertEquals(Route.FIELD, c.route())
            },
        )
    }

    @Test
    fun `a fall-back lasts the input, restarts too, and ends with it`() {
        val c = started()
        c.fallBack()
        c.route(code = false)
        val afterCode = c.route()
        c.route(code = false)
        c.startInput(EditorTraits(), restarting = true)
        val afterRestart = c.route()
        c.route(code = false)
        c.finishInput()
        c.startInput(EditorTraits(), restarting = false)
        val nextInput = c.route()
        assertRows(
            "the next code of the input floats" to { assertEquals(Route.STRIP, afterCode) },
            "input restarted (the app's own restart, §6.4): still floats" to { assertEquals(Route.STRIP, afterRestart) },
            "the next input: the field again" to { assertEquals(Route.FIELD, nextInput) },
        )
    }

    @Test
    fun `the field's later word on what it is counts, as a browser's view starts`() {
        val c = started(EditorTraits())
        c.startInputView(EditorTraits(isPassword = true))
        assertEquals(Route.STRIP, c.route())
        c.route(code = false)
        c.startInputView(EditorTraits())
        assertEquals(Route.FIELD, c.route())
    }
}
