/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A held ⌫ begun with something typed stops once that is gone, not running on into the field. */
class BackspaceHoldTest {

    // each repeat's turn, as the host saw things then; whether it deleted
    private fun BackspaceHold.turns(vararg composing: Boolean) = composing.map(::repeat)

    // what was typed as the hold began decides: begun typing, it stops once that is gone; begun empty, it deletes on
    @Test
    fun aHoldBegunTypingStopsOnceThatIsGone() = assertRows(
        "begun typing: \"ni\"'s two repeats take it, the third finds nothing typed and deletes nothing of the field's" to {
            val hold = BackspaceHold()
            assertEquals(listOf(true, true, false), hold.turns(true, true, false))
            assertTrue(hold.stopped)
        },
        "stopped, it stays so though another finger types while ⌫ is held" to {
            val hold = BackspaceHold()
            hold.turns(true, false)
            assertEquals(listOf(false, false), hold.turns(true, false))
        },
        "begun with nothing typed: it deletes on" to {
            val hold = BackspaceHold()
            assertEquals(listOf(true, true, true), hold.turns(false, false, false))
            assertFalse(hold.stopped)
        },
        "begun with nothing typed, a letter typed by another finger meanwhile: deleted, and the hold goes on" to {
            assertEquals(listOf(true, true, true), BackspaceHold().turns(false, true, false))
        },
        "a repeat the keyboard took (strokes on the keys taken back) counts as something typed: once gone, it stops" to {
            val hold = BackspaceHold()
            hold.taken()
            assertEquals(listOf(false), hold.turns(false))
            assertTrue(hold.stopped)
        },
        "one taken after the first turn changes nothing" to {
            val hold = BackspaceHold()
            hold.repeat(false)
            hold.taken()
            assertEquals(listOf(true), hold.turns(false))
        },
        "its press tells: pinyin typed as ⌫ went down, committed by another finger before the first repeat, deletes nothing of the field's" to {
            val hold = BackspaceHold()
            hold.press(true)
            assertEquals(listOf(false), hold.turns(false))
        },
        "its press with nothing typed, a letter typed meanwhile: deleted, and the hold goes on" to {
            val hold = BackspaceHold()
            hold.press(false)
            assertEquals(listOf(true, true), hold.turns(true, false))
        },
        "strokes taken back before the press's turn (the keyboard tells first, on the main thread): something typed" to {
            val hold = BackspaceHold()
            hold.taken()
            hold.press(false)
            assertEquals(listOf(false), hold.turns(false))
        },
        "let go and pressed again: a new hold deletes the field's text as usual" to {
            BackspaceHold().turns(true, false)
            assertEquals(listOf(true, true), BackspaceHold().turns(false, false))
        },
    )
}
