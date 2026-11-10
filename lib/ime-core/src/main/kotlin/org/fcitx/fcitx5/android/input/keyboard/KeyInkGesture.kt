/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.handwriting.InkPad

/**
 * Writing on the letter keys (键盘手写, design 060-kbd-handwriting §2), as [GlideTouch] asks it: when a
 * touch is a stroke. A finger that moves [GlideGesture.STROKE] of a key, any way, before its long
 * press starts one; from then on the writing goes on till the pen is up [pauseMillis] (the pad's
 * [InkPad.finishMillis], HandwritingPause), and every touch on the letters in it is a stroke from its
 * down, a dot too, no long press, another finger ignored. Outside it the keys are tapped and long
 * pressed as ever.
 *
 * Nothing is written while something is typed ([down]'s preedit): a move then is the key's. But for
 * a character whose first stroke is a dot (主, 六): a tap that typed a letter that is all the input,
 * and a stroke within [pauseMillis] after it, takes the tap back as that first stroke ([takeTapped]:
 * the app deletes the letter and puts the tap's places on the pad first). No tap waits to see what
 * follows: typing would slow down.
 */
class KeyInkGesture {

    /** How long the pen stays up before the writing ends: the pad's [InkPad.finishMillis]. */
    var pauseMillis = InkPad.FINISH_MILLIS

    // the last stroke's lift while the writing goes on, NONE outside it
    private var lastUp = NONE
    private var drawing = false

    // the last tap on a letter: its letter, lift and places (pixels, x and y in turn)
    private var tapLetter: Char? = null
    private var tapUp = NONE
    private var tapPoints: FloatArray? = null
    private var tapTakable = false

    private var taken: FloatArray? = null

    /** Whether a touch down at [time] is a stroke from its down: the writing goes on. */
    fun writing(time: Long): Boolean = drawing || lastUp != NONE && time - lastUp < pauseMillis

    /**
     * A touch down on a letter ([letter], null in a split keyboard's gap) at [time], [preedit] what is
     * typed: whether it may become a stroke once it moves. With something typed, only where that is the
     * letter just tapped, within the pause ([takeTapped] then gives that tap's places once it does).
     */
    fun mayWrite(time: Long, preedit: String): Boolean {
        tapTakable = tapLetter != null && time - tapUp < pauseMillis && preedit.equals(tapLetter.toString(), ignoreCase = true)
        if (!tapTakable) forgetTap()
        return preedit.isEmpty() || tapTakable
    }

    /** The touch became a stroke: the writing starts, or goes on; a tap that may be its first dot taken back. */
    fun strokeStarted() {
        drawing = true
        if (tapTakable) taken = tapPoints
        forgetTap()
    }

    /** The stroke lifted at [time]: the pause counts from here. */
    fun strokeEnded(time: Long) {
        drawing = false
        lastUp = time
    }

    /** A tap on [letter] (null if it typed none) lifted at [time], its [count] [points] (pixels): a first dot if a stroke follows. */
    fun tapped(letter: Char?, time: Long, points: FloatArray, count: Int) {
        tapLetter = letter
        tapUp = time
        tapPoints = points.copyOf(count)
        tapTakable = false
    }

    /** The places of the tap taken back as this stroke's first dot, once: delete its letter, then put them on the pad. */
    fun takeTapped(): FloatArray? = taken.also { taken = null }

    /** A stroke taken back at [time] (backspace, [InkPad.undo]): the pause counts from here, as the pad's. */
    fun undone(time: Long) {
        if (lastUp != NONE) lastUp = time
    }

    /** The writing over before its pause (the piece ended: a pick, space, the pad wiped). */
    fun ended() {
        drawing = false
        lastUp = NONE
        forgetTap()
    }

    private fun forgetTap() {
        tapLetter = null
        tapPoints = null
        tapUp = NONE
        tapTakable = false
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
