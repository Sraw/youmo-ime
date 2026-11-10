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
 * Nothing is written while the input method has input typed ([mayWrite]'s typed: its own, not what
 * handwriting shows, Engines.typedOnKeys): a move then is the key's. But for a character whose first
 * stroke is a dot (主, 六): a tap that started the input (nothing typed at its down), and a stroke
 * within [pauseMillis] after it with no other key between, takes the tap back as that first stroke
 * ([takeTapped]; told by state, not by text, as the nine keys or zhuyin show no letter of the key:
 * the app deletes the letter and puts the tap's places on the pad first). No tap waits to see what
 * follows: typing would slow down.
 */
class KeyInkGesture {

    /** How long the pen stays up before the writing ends: the pad's [InkPad.finishMillis]. */
    var pauseMillis = InkPad.FINISH_MILLIS

    // the last stroke's lift while the writing goes on, NONE outside it
    private var lastUp = NONE
    private var drawing = false

    // the last tap on a letter that started the input: its lift and places (pixels, x and y in turn)
    private var tapUp = NONE
    // whether the touch down now would start the input, if a tap
    private var startsInput = false
    private var tapPoints: FloatArray? = null
    private var tapTakable = false

    private var taken: FloatArray? = null

    // a letter tapped and sent, the engine not yet heard from since: the keyboard's [mayWrite] typed, read
    // on the UI thread, can be older than the key (Engines.typedOnKeys is the fcitx thread's), and a fast
    // dot then stroke must see the input the dot started
    private var sentTyped = false

    /** Whether a touch down at [time] is a stroke from its down: the writing goes on. */
    fun writing(time: Long): Boolean = drawing || lastUp != NONE && time - lastUp < pauseMillis

    /**
     * A touch down on a letter or a split keyboard's gap at [time], [typed] whether the input method has
     * input typed: whether it may become a stroke once it moves. With input typed, only where the tap
     * just before started it, within the pause ([takeTapped] then gives that tap's places once it does).
     */
    fun mayWrite(time: Long, typedSeen: Boolean): Boolean {
        val typed = typedSeen || sentTyped
        tapTakable = typed && tapPoints != null && time - tapUp < pauseMillis
        if (!tapTakable) forgetTap()
        startsInput = !typed
        return !typed || tapTakable
    }

    /** The touch became a stroke: the writing starts, or goes on; a tap that may be its first dot taken back. */
    fun strokeStarted() {
        drawing = true
        // the letter taken back, or none typed: nothing of it is typed now
        sentTyped = false
        if (tapTakable) taken = tapPoints
        forgetTap()
    }

    /** The stroke lifted at [time]: the pause counts from here. */
    fun strokeEnded(time: Long) {
        drawing = false
        lastUp = time
    }

    /** A tap lifted at [time], its [count] [points] (pixels): a first dot if it started the input and a stroke follows. */
    fun tapped(time: Long, points: FloatArray, count: Int) {
        forgetTap()
        sentTyped = true
        if (!startsInput) return
        tapUp = time
        tapPoints = points.copyOf(count)
    }

    /**
     * What the engine says once it has the keys sent so far (Engines.typedOnKeys): nothing typed clears
     * the mark a tap left; the caller tells it only after the engine handled the tap's key.
     */
    fun engineTyped(typed: Boolean) {
        if (!typed) sentTyped = false
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

    /** Another key touched (not a letter): the tap before is no first dot. */
    fun forgetTap() {
        tapPoints = null
        tapUp = NONE
        tapTakable = false
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
