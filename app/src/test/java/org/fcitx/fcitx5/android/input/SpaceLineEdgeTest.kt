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
 * yet; once it has, the lines not landed yet are sent, never past the line the user went to. The clock stands still under Robolectric,
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

    // an editor that applies a line at once: Up to the end of the line above, Down to the end of the line below
    private val atOnce: (EditText, Int) -> Unit = { f, code ->
        val s = f.text.toString()
        val at = f.selectionStart
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            f.setSelection(s.lastIndexOf('\n', at - 1).coerceAtLeast(0))
        } else {
            val nl = s.indexOf('\n', at)
            val end = if (nl < 0) -1 else s.indexOf('\n', nl + 1)
            f.setSelection(if (nl < 0 || end < 0) s.length else end)
        }
    }

    private fun slide(text: String, at: Int, editor: (EditText, Int) -> Unit, sym: Int, times: Int): Int =
        field(text, at, editor).use { h ->
            repeat(times) { line(h, sym) }
            h.field.selectionStart
        }

    @Test
    fun aRushedLineGoesToTheEdgeOnlyWhileNoneLanded() {
        val up = FcitxKeyMapping.FcitxKey_Up
        val down = FcitxKeyMapping.FcitxKey_Down
        assertRows(
            "three Ups from the third line, none landed: the third goes to the start" to {
                assertEquals(0, slide("aaa\nbbb\nccc", 11, later, up, 3))
            },
            "two Downs from the first line, none landed: the second goes to the end, the text's length" to {
                assertEquals(7, slide("aaa\nbbb", 0, later, down, 2))
            },
            // the edge jump took a read already moved for stale and skipped the line the user went to;
            // then the line was held instead and the cursor stopped a line short
            "a rushed line the editor already applied does not skip to the edge: two Ups from the third line, the first landed, the second is sent, onto the first line" to {
                assertEquals(3, slide("aaa\nbbb\nccc", 11, atOnce, up, 2))
            },
            // KB-71 on the emulator: the editor applies each Up before the next sample's read (the key and the
            // read go to it in order), so no read is stale; counting sent lines as unlanded held the second
            // and the cursor stopped on the first line, not the start
            "three Ups from inside the third line, each landed before the next read: onto the very start" to {
                assertEquals(0, slide("aaa\nbbb\nccc", 9, atOnce, up, 3))
            },
            "two Downs from the first line, the first landed: the second is sent, onto the third line" to {
                assertEquals(11, slide("aaa\nbbb\nccc", 0, atOnce, down, 2))
            },
            "Ups past the first line once lines landed: held at the very start, no jump past a line" to {
                assertEquals(0, slide("aaa\nbbb", 7, atOnce, up, 4))
            },
            "Downs past the last line once lines landed: held at the very end" to {
                assertEquals(7, slide("aaa\nbbb", 0, atOnce, down, 4))
            },
        )
    }
}
