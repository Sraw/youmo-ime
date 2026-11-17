/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A talk's text handed over whole ([VoiceAsk]): a commit of the talk before landing late is not this one's. */
class VoiceAskTest {

    private val ask = VoiceAsk()

    // [talk]'s commit of [text] in, as the service lands it, on [ask]: the talk's text if it is over and all in
    private fun land(talk: Int, text: String, ask: VoiceAsk = this.ask): String? {
        ask.voice(talk) { ask.putIn(text) }
        return ask.landed(talk, inField = true)
    }

    @Test
    fun aTalksCommitsAllInHandItOverOnce() {
        ask.began()
        val talk = ask.posting()
        ask.posting()
        assertNull(land(talk, "我找"))
        assertNull(ask.ended())
        assertEquals("我找徐静", land(talk, "徐静"))
    }

    // a talk's commit landing after the next talk began, the talk before ended either way: none of the next one's, which
    // is handed over whole as its own lands (R26 F8367: the next talk began while fcitx was busy with the last's commit)
    @Test
    fun aCommitOfTheTalkBeforeLandsUncountedAndUngathered() {
        fun row(why: String, between: (VoiceAsk) -> Unit) = why to {
            // each row's own
            val ask = VoiceAsk()
            ask.began()
            val first = ask.posting()
            between(ask)
            ask.began()
            val second = ask.posting()
            assertNull("the first's commit, late: none of the second's", land(first, "旧", ask))
            assertNull("the second's commit not in yet", ask.ended())
            assertEquals("新", land(second, "新", ask))
        }
        assertRows(
            row("the talk over (nothing in to hand over), the next begun in the field") { assertNull(it.ended()) },
            row("the field left (reset) before the next talk") { it.reset() },
        )
    }
}
