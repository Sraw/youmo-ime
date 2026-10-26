/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.voice.VoiceTailEnter.End
import org.fcitx.fcitx5.android.input.voice.VoiceTailEnter.Key
import org.fcitx.fcitx5.android.input.voice.VoiceTailEnter.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTailEnterTest {

    @Test
    @Uat("VO-28")
    fun onlyEnterWaitsAfterTheLift() {
        val t = VoiceTailEnter()
        assertEquals(Press.NOW, t.press(Key.BACKSPACE, released = true, over = false))
        assertFalse(t.waiting)
        assertEquals(Press.AFTER_TEXT, t.press(Key.ENTER, released = true, over = false))
        assertTrue(t.waiting)
        // backspace while Enter waits still acts at once, the Enter still waiting
        assertEquals(Press.NOW, t.press(Key.BACKSPACE, released = true, over = false))
        assertTrue(t.waiting)
    }

    @Test
    @Uat("VO-28")
    fun enterWaitsOnceHoweverOftenPressed() {
        val t = VoiceTailEnter()
        repeat(3) { assertEquals(Press.AFTER_TEXT, t.press(Key.ENTER, released = true, over = false)) }
        assertTrue(t.ended(End.TYPED, sameField = true))
        // one Enter: told once, then nothing left waiting
        assertFalse(t.ended(End.TYPED, sameField = true))
    }

    // an Enter waiting since the lift, the session over as [End]: whether it is sent; never left waiting
    @Test
    @Uat("VO-28")
    fun theWaitingEnterActsOnlyWhenTheTextIsInOrNothingCame() = assertRows(
        "the text typed: sent after it" to { ends(End.TYPED, sameField = true, acts = true) },
        "nothing heard: sent, as the user asked" to { ends(End.NOTHING, sameField = true, acts = true) },
        "the session dropped: nothing of it in, so no Enter" to { ends(End.DROPPED, sameField = true, acts = false) },
        "given up after 15 s: it would send a stale draft" to { ends(End.GAVE_UP, sameField = true, acts = false) },
        "another field: the Enter was meant for the first" to { ends(End.TYPED, sameField = false, acts = false) },
        "another field, nothing heard: the same" to { ends(End.NOTHING, sameField = false, acts = false) },
    )

    private fun ends(end: End, sameField: Boolean, acts: Boolean) {
        val t = VoiceTailEnter()
        t.press(Key.ENTER, released = true, over = false)
        assertEquals(acts, t.ended(end, sameField))
        assertFalse(t.waiting)
        // nothing pressed since: none sent
        assertFalse(t.ended(End.TYPED, sameField = true))
    }

    @Test
    @Uat("VO-28")
    fun beforeTheLiftOrAfterTheEndEnterActsAtOnce() {
        val t = VoiceTailEnter()
        assertEquals(Press.NOW, t.press(Key.ENTER, released = false, over = false))
        assertEquals(Press.NOW, t.press(Key.ENTER, released = true, over = true))
        assertFalse(t.waiting)
    }
}
