/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.pet

/**
 * Which frame of which state a pet shows, from the kinds of key pressed and the time, which the caller passes: no
 * clock of its own, nothing made per key or frame. A state the pet lacks is never entered; one played through goes
 * back to idle; asleep after [SLEEP_MS] without a key.
 */
class PetMachine(pet: Pet) {
    private val sheets: Array<PetState?> = Array(NAMES.size) { pet.states[NAMES[it]] }
    private val fps = IntArray(NAMES.size) { i -> sheets[i]?.fps?.coerceAtMost(PetLimits.fps(NAMES[i])) ?: 1 }

    private var state = IDLE
    private var start = 0L
    private var last = 0L
    private var shown = false
    // the same key again while it plays: one more round once this one ends, not a start over
    private var again = false
    // asleep, stopped on its last frame
    private var held = false
    // what a wake goes on to
    private var then = IDLE
    private var frozen = 0

    init {
        require(has(IDLE)) { "a pet without idle" }
    }

    val current: PetState.Name get() = NAMES[state]

    /** Shown anew: idle from its first frame, asleep [SLEEP_MS] on. */
    fun shown(nowMs: Long) {
        shown = true
        last = nowMs
        enter(IDLE, nowMs)
    }

    /** Hidden: stopped where it is, no time kept. */
    fun hidden() {
        shown = false
    }

    fun on(trigger: PetTrigger, nowMs: Long) {
        if (!shown) return
        advance(nowMs)
        last = nowMs
        val target = trigger.state.ordinal.takeIf { has(it) }
        when {
            state == SLEEP && has(WAKE) -> {
                enter(WAKE, nowMs)
                then = target ?: IDLE
            }
            state == WAKE -> if (target != null) then = target
            state == SLEEP -> enter(target ?: IDLE, nowMs)
            target == null -> {}
            target == state -> again = true
            else -> enter(target, nowMs)
        }
    }

    /** The state's index times 256 plus the frame's, as [stateOf] and [frameOf] read it. */
    fun frame(nowMs: Long): Int {
        if (!shown) return frozen
        advance(nowMs)
        val s = sheets[state]!!
        val f = if (held) s.frames - 1 else ((elapsed(nowMs) * fps[state] / SECOND) % s.frames).toInt()
        frozen = (state shl STATE_SHIFT) or f
        return frozen
    }

    /** When the frame next changes or the pet falls asleep, whichever is sooner; [NONE] where neither will. */
    fun nextFrameAtMs(nowMs: Long): Long {
        if (!shown) return NONE
        advance(nowMs)
        val s = sheets[state]!!
        val turns = !held && (s.frames > 1 || end() != Long.MAX_VALUE)
        val frame = if (turns) boundary(elapsed(nowMs) * fps[state] / SECOND + 1) else Long.MAX_VALUE
        val sleep = if (has(SLEEP) && state != SLEEP) last + SLEEP_MS else Long.MAX_VALUE
        return minOf(frame, sleep).takeIf { it != Long.MAX_VALUE } ?: NONE
    }

    private fun has(i: Int) = sheets[i] != null

    private fun elapsed(nowMs: Long) = (nowMs - start).coerceAtLeast(0)

    // where frame [k] begins: frame(now) reads floor(elapsed × fps / 1000), so this is its least time
    private fun boundary(k: Long) = start + (k * SECOND + fps[state] - 1) / fps[state]

    private fun enter(s: Int, at: Long) {
        state = s
        start = at
        again = false
        held = false
    }

    // when the state's playing ends; idle and a looping one's never
    private fun end(): Long {
        val s = sheets[state]!!
        val rounds = when {
            held || state == IDLE -> return Long.MAX_VALUE
            state == SLEEP -> if (s.loop) SLEEP_ROUNDS else 1
            state == WAKE || !s.loop -> 1
            else -> return Long.MAX_VALUE
        }
        return boundary(s.frames.toLong() * rounds)
    }

    private fun advance(nowMs: Long) {
        while (true) {
            val end = end()
            val sleepAt = if (has(SLEEP) && state != SLEEP) last + SLEEP_MS else Long.MAX_VALUE
            if (sleepAt <= nowMs && sleepAt <= end) {
                enter(SLEEP, sleepAt)
                continue
            }
            if (end > nowMs) return
            when {
                state == SLEEP -> held = true
                again -> {
                    start = end
                    again = false
                }
                state == WAKE -> enter(then, end)
                else -> enter(IDLE, end)
            }
        }
    }

    companion object {
        const val NONE = -1L
        const val SLEEP_MS = 30_000L
        const val SLEEP_ROUNDS = 3
        private const val STATE_SHIFT = 8
        private const val FRAME_MASK = 0xFF
        private const val SECOND = 1000L
        private val NAMES = PetState.Name.entries
        private val IDLE = PetState.Name.IDLE.ordinal
        private val SLEEP = PetState.Name.SLEEP.ordinal
        private val WAKE = PetState.Name.WAKE.ordinal

        fun stateOf(frame: Int): PetState.Name = NAMES[frame ushr STATE_SHIFT]

        fun frameOf(frame: Int): Int = frame and FRAME_MASK
    }
}
