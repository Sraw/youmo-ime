/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.host.HostEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The space's slide up and down sent in one go (SpaceCursor's rushed lines), as the service takes
 * the lines: past the first (last) line to the field's start (end) while the editor has not moved
 * yet, never past the line the user went to once it has. The clock stands still under Robolectric,
 * so every line here counts as rushed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class SpaceLineEdgeTest {

    private fun line(h: KeyboardHarness, sym: Int) =
        h.emit(HostEvent.Key(sym, KeyStates.Virtual.states.toInt(), 0, false, 0))

    // a multi-line field holding [text], the cursor at [at]; [onLine] what the editor does with a line key
    private fun field(text: String, at: Int, onLine: (EditText, Int) -> Unit): KeyboardHarness =
        KeyboardHarness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) { f ->
            f.setText(text)
            f.setSelection(at)
            f.setOnKeyListener { v, code, e ->
                val isLine = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                if (isLine && e.action == KeyEvent.ACTION_DOWN) onLine(v as EditText, code)
                isLine
            }
        }

    // an editor that applies the keys later: none landed while the slide sends
    private val later: (EditText, Int) -> Unit = { _, _ -> }

    // an editor that applies a line at once: Up to the end of the line above
    private val atOnce: (EditText, Int) -> Unit = { f, code ->
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            val s = f.text.toString()
            f.setSelection(s.lastIndexOf('\n', f.selectionStart - 1).coerceAtLeast(0))
        }
    }

    @Test
    fun aRushedLinePastTheFirstOrLastLineGoesToTheFieldsEdge() {
        assertRows(
            "three Ups from the third line, none landed: the third goes to the start" to {
                field("aaa\nbbb\nccc", at = 11, later).use { h ->
                    repeat(3) { line(h, FcitxKeyMapping.FcitxKey_Up) }
                    assertEquals(0, h.field.selectionStart)
                }
            },
            "two Downs from the first line, none landed: the second goes to the end, the text's length" to {
                field("aaa\nbbb", at = 0, later).use { h ->
                    repeat(2) { line(h, FcitxKeyMapping.FcitxKey_Down) }
                    assertEquals(7, h.field.selectionStart)
                }
            },
        )
    }

    // the edge jump took a read already moved for stale and skipped the line the user went to
    @Test
    fun aRushedLineTheEditorAlreadyAppliedDoesNotSkipToTheEdge() {
        field("aaa\nbbb\nccc", at = 11, atOnce).use { h ->
            repeat(2) { line(h, FcitxKeyMapping.FcitxKey_Up) }
            // the first Up landed at the second line's end; the second is held, not sent to the start
            assertEquals(7, h.field.selectionStart)
        }
    }
}
