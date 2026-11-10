/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.handwriting.InkPad
import kotlin.math.hypot

/**
 * Writing on the letter keys (键盘手写, design 060-kbd-handwriting §2), as [GlideTouch] asks it: when a
 * touch is a stroke. A finger that moves [START] of a key, any way, before its long press starts one,
 * but not one going down within [TYPING_MILLIS] of a key typed (060-kbd-handwriting-start: a tap
 * dragged a little while typing is the key's); from then on the writing goes on till the pen is up [pauseMillis] (the pad's
 * [InkPad.finishMillis], HandwritingPause), and every touch on the letters in it is a stroke from its
 * down, a dot too, no long press, another finger ignored. Outside it the keys are tapped and long
 * pressed as ever.
 *
 * Nothing is written while the input method has input typed ([mayWrite]'s typed: its own, not what
 * handwriting shows, Engines.typedOnKeys): a move then is the key's. But for a character whose first
 * stroke is a dot (主, 六): a tap that started the input (nothing typed at its down, not typing), and a stroke
 * within [pauseMillis] after it, [NEAR] it, with no other key between, takes the tap back as that first stroke
 * ([takeTapped]; told by state, not by text, as the nine keys or zhuyin show no letter of the key:
 * the tap's places go on the pad first, and the engine deletes its letter if that alone is typed, InkOver). No tap waits to see what
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

    // the last key typed: its lift, NONE if none since the writing; and whether a stroke came after the last letter typed
    // (the keys pressed then, a space or backspace, are the writing's, not typing)
    private var typingUp = NONE
    private var afterInk = false

    // a letter tapped and sent, the engine not yet heard from since: the keyboard's [mayWrite] typed, read
    // on the UI thread, can be older than the key (Engines.typedOnKeys is the fcitx thread's), and a fast
    // dot then stroke must see the input the dot started
    private var sentTyped = false

    /**
     * The number the next key sent to the engine gets, counted where the keys are sent (every key, not only
     * the taps: a space or a mark sent before the tap and answered after it is not the tap's answer). A tap is
     * told ([tapped]) just before its key is sent, and remembers it. Unset (a caller that does not number its
     * keys): one past the last heard.
     */
    var nextKey: () -> Long = { heard + 1 }

    // the number of the last key the engine had taken by its last answer ([engineTyped]'s keysHeard), the
    // number of the last tap sent, whose answer alone (or a later one) replaces its mark (an older one, late,
    // leaves it), since when it is awaited (NONE: none), and what the last answer said, ignored or not
    private var heard = 0L
    private var awaited = 0L
    private var awaitedSince = NONE
    private var lastSaid = false

    /** Whether a touch down at [time] is a stroke from its down: the writing goes on. */
    fun writing(time: Long): Boolean = drawing || lastUp != NONE && time - lastUp < pauseMillis

    /**
     * A touch down at [x], [y] on a letter or a split keyboard's gap at [time], keys [keyWidth] by [keyHeight],
     * [typedSeen] whether the input method has input typed as the keyboard last heard (a letter tapped and
     * sent since counts too): whether it may become a stroke once it moves. With input typed, only where the
     * tap just before started it, within the pause and [NEAR] it ([takeTapped] then gives that tap's places
     * once it does); with none, not within [TYPING_MILLIS] of a key typed.
     */
    fun mayWrite(time: Long, typedSeen: Boolean, x: Float, y: Float, keyWidth: Float, keyHeight: Float): Boolean {
        // the tap's key not answered within the pause (one the engine never heard): its mark no longer
        // held over what the engine last said, so writing is never blocked for good
        if (awaitedSince != NONE && time - awaitedSince >= pauseMillis) {
            awaitedSince = NONE
            awaited = heard
            sentTyped = lastSaid
        }
        val typed = typedSeen || sentTyped
        val tap = tapPoints
        tapTakable = typed && tap != null && time - tapUp < pauseMillis && near(tap, x, y, keyWidth, keyHeight)
        if (!tapTakable) forgetTap()
        // typing: a stroke now is a tap dragged, and a tap no first dot
        val typing = typingUp != NONE && time - typingUp < TYPING_MILLIS
        startsInput = !typed && !typing
        return if (typed) tapTakable else !typing
    }

    // the places of a tap's [points] and the stroke at [x], [y] at most NEAR keys apart
    private fun near(points: FloatArray, x: Float, y: Float, keyWidth: Float, keyHeight: Float) =
        keyWidth > 0f && keyHeight > 0f && hypot((x - points[0]) / keyWidth, (y - points[1]) / keyHeight) <= NEAR

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
        typingUp = NONE
        afterInk = true
    }

    /**
     * A key lifted at [time] that is no letter's tap here: another key, a letter's long press, or a [letter]
     * typed where no writing may start. Typing, unless after a stroke and no letter (a space or backspace in the
     * writing).
     */
    fun keyLifted(time: Long, letter: Boolean = false) {
        if (letter) afterInk = false
        if (!afterInk) typingUp = time
    }

    /** A tap lifted at [time], its [count] [points] (pixels): a first dot if it started the input and a stroke follows. */
    fun tapped(time: Long, points: FloatArray, count: Int) {
        forgetTap()
        sentTyped = true
        awaited = nextKey()
        awaitedSince = time
        afterInk = false
        typingUp = time
        if (!startsInput) return
        tapUp = time
        tapPoints = points.copyOf(count)
    }

    /**
     * What the engine says once it has the keys sent so far (Engines.typedOnKeys): it replaces the mark a
     * tap left. The caller tells it after every answer, not only when it changes. [keysHeard] is the number
     * ([nextKey]'s) of the last key the engine had taken by this answer: one before the last tap's (a late
     * answer, to a key or context before it) leaves the tap's mark; [ALL_HEARD]
     * (the engine reset: nothing typed) replaces it whatever was sent. A tap whose key gets no answer
     * holds its mark only till the pause ([mayWrite]), so the mark cannot stick.
     */
    fun engineTyped(typed: Boolean, keysHeard: Long = ALL_HEARD) {
        lastSaid = typed
        if (keysHeard == ALL_HEARD) {
            awaited = heard
        } else {
            heard = maxOf(heard, keysHeard)
            if (keysHeard < awaited) return
        }
        awaitedSince = NONE
        sentTyped = typed
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

    companion object {
        private const val NONE = Long.MIN_VALUE

        /** An answer about every key sent ([engineTyped]): the engine reset, or a caller that does not count. */
        const val ALL_HEARD = Long.MAX_VALUE

        /**
         * How far the first stroke goes before it is one, in keys (a key's width across, its height down): about
         * 20dp across on a phone, 2.5 times Android's touch slop (8dp), where a tap dragged while typing stops;
         * written two key heights tall, under 5% of characters' first strokes are shorter (的 是 just longer;
         * Make Me a Hanzi's strokes, design 060-kbd-handwriting-start).
         */
        const val START = 0.5f

        /** How soon after a key typed no writing starts: AOSP LatinIME's for its glide after fast typing. */
        const val TYPING_MILLIS = 500L

        /**
         * How far from the tap taken back as the first dot the stroke after it goes down, in keys: a dot
         * character's second stroke within 2.5 keys written up to four key heights tall, a pinyin string's
         * second letter there one time in three.
         */
        const val NEAR = 2.5f
    }
}
