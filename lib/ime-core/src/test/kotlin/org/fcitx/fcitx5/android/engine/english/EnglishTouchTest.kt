/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.pinyin.Near
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a key was tapped, read by the full keyboard's English (061-neighbour-english). */
class EnglishTouchTest {

    private val data = EnglishDecoderTest.data()

    // w tapped [dx] across from its middle, e right of it
    private fun onW(dx: Float) = Touch(dx, 0f, 40f, 56f, listOf(Near('q', -1f, 0f), Near('e', 1f, 0f)))

    // hwllo, w tapped at [w] (none: no place), corrected by space where a slip costs no more than 2
    private fun hwllo(w: Touch?): String {
        val s = EnglishSession(data, maxCorrection = 2f)
        s.apply(Action.Key('h'))
        // a key typed and taken back: the places stay with their keys
        s.apply(Action.Key('x', touch = onW(0.45f)))
        s.apply(Action.Backspace)
        s.apply(Action.Key('w', touch = w))
        "llo".forEach { s.apply(Action.Key(it)) }
        return s.apply(Action.Key(' ')).commit
    }

    @Test
    @Uat("EN-30")
    fun whereAKeyWasTappedSaysWhetherItWasTheOneBeside() {
        // no place (a hardware key): a key beside costs as one beside (1), corrected
        assertEquals("hello ", hwllo(null))
        // w's edge by e: a slip, corrected; its middle: no likelier e than any key (3), left as typed
        assertEquals("hello ", hwllo(onW(0.45f)))
        assertEquals("hwllo ", hwllo(onW(0f)))
    }
}
