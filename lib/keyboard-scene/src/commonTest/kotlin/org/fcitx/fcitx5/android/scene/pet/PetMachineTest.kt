/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.pet

import org.fcitx.fcitx5.android.scene.pet.PetState.Name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The pet's states by kind of key and time: each played once and back to idle, asleep after 30 s, nothing scheduled where nothing moves. */
class PetMachineTest {

    private fun pet(vararg states: Pair<Name, PetState>) = Pet(Pet.Place.KEYBOARD, Pet.Align.TOP_END, 40.0, 0.0, 0.0, mapOf(*states))

    // 4 frames at 4 fps: a frame each 250 ms
    private val idle = Name.IDLE to PetState("i.png", 4, 4, 4, true)
    // 6 frames at 12 fps: 500 ms
    private val typing = Name.TYPING to PetState("t.png", 6, 6, 12, false)
    private val delete = Name.DELETE to PetState("d.png", 2, 2, 10, false)
    // 4 frames at 2 fps: 2 s a round
    private val sleep = Name.SLEEP to PetState("s.png", 4, 2, 2, true)
    private val wake = Name.WAKE to PetState("w.png", 2, 2, 4, false)

    private fun PetMachine.at(nowMs: Long): Pair<Name, Int> = frame(nowMs).let { PetMachine.stateOf(it) to PetMachine.frameOf(it) }

    private fun shown(vararg states: Pair<Name, PetState>) = PetMachine(pet(*states)).also { it.shown(0) }

    @Test
    fun idleLoopsFromItsFirstFrame() {
        val m = shown(idle)
        assertEquals(Name.IDLE to 0, m.at(0))
        assertEquals(Name.IDLE to 1, m.at(250))
        assertEquals(Name.IDLE to 3, m.at(999))
        assertEquals(Name.IDLE to 0, m.at(1000))
        assertEquals(250, m.nextFrameAtMs(0))
        assertEquals(1250, m.nextFrameAtMs(1000))
        assertEquals(1250, m.nextFrameAtMs(1249))
    }

    @Test
    fun aKeyPlaysItsStateOnceThenIdle() {
        val m = shown(idle, typing)
        m.on(PetTrigger.TYPING, 1000)
        assertEquals(Name.TYPING to 0, m.at(1000))
        assertEquals(Name.TYPING to 5, m.at(1499))
        assertEquals(1500, m.nextFrameAtMs(1490))
        assertEquals(Name.IDLE to 0, m.at(1500))
        assertEquals(Name.IDLE to 1, m.at(1750))
    }

    @Test
    fun theSameKeyAgainGoesOnAndPlaysOnceMore() {
        val m = shown(idle, typing)
        m.on(PetTrigger.TYPING, 1000)
        m.on(PetTrigger.TYPING, 1250)
        assertEquals(Name.TYPING to 3, m.at(1250))
        assertEquals(Name.TYPING to 0, m.at(1500))
        assertEquals(Name.TYPING to 5, m.at(1999))
        assertEquals(Name.IDLE to 0, m.at(2000))
    }

    @Test
    fun anotherKeyStartsItsOwnFromFrameZero() {
        val m = shown(idle, typing, delete)
        m.on(PetTrigger.TYPING, 1000)
        m.on(PetTrigger.DELETE, 1250)
        assertEquals(Name.DELETE to 0, m.at(1250))
        assertEquals(Name.DELETE to 1, m.at(1350))
        assertEquals(Name.IDLE to 0, m.at(1450))
    }

    @Test
    fun aStateThePetLacksChangesNothing() {
        val m = shown(idle, typing)
        m.on(PetTrigger.TYPING, 1000)
        m.on(PetTrigger.ENTER, 1100)
        assertEquals(Name.TYPING to 1, m.at(1100))
        m.on(PetTrigger.SPACE, 2000)
        assertEquals(Name.IDLE to 2, m.at(2000))
    }

    @Test
    fun itFallsAsleepAfterThirtySecondsLoopsThriceAndHolds() {
        val m = shown(idle, sleep)
        assertEquals(30_000, m.nextFrameAtMs(29_950))
        assertEquals(Name.SLEEP to 0, m.at(30_000))
        assertEquals(Name.SLEEP to 1, m.at(30_500))
        assertEquals(Name.SLEEP to 3, m.at(35_999))
        assertEquals(Name.SLEEP to 3, m.at(36_000))
        assertEquals(Name.SLEEP to 3, m.at(90_000))
        assertEquals(PetMachine.NONE, m.nextFrameAtMs(36_000))
    }

    @Test
    fun aKeyPutsOffSleep() {
        val m = shown(idle, typing, sleep)
        m.on(PetTrigger.TYPING, 20_000)
        assertEquals(Name.IDLE, m.at(49_999).first)
        assertEquals(Name.SLEEP to 0, m.at(50_000))
        // a key it has no state for still counts as a key
        val n = shown(idle, sleep)
        n.on(PetTrigger.ENTER, 20_000)
        assertEquals(Name.IDLE, n.at(49_999).first)
    }

    @Test
    fun aSleepThatDoesNotLoopPlaysOnceAndHolds() {
        val m = shown(idle, Name.SLEEP to PetState("s.png", 4, 4, 2, false))
        assertEquals(Name.SLEEP to 3, m.at(31_999))
        assertEquals(Name.SLEEP to 3, m.at(32_000))
        assertEquals(PetMachine.NONE, m.nextFrameAtMs(32_000))
    }

    @Test
    fun asleepAKeyWakesItFirst() {
        val m = shown(idle, typing, sleep, wake)
        m.on(PetTrigger.TYPING, 40_000)
        assertEquals(Name.WAKE to 0, m.at(40_000))
        assertEquals(Name.WAKE to 1, m.at(40_250))
        // keys during the wake choose what follows it
        m.on(PetTrigger.ENTER, 40_300)
        assertEquals(Name.TYPING to 0, m.at(40_500))
        assertEquals(Name.IDLE to 0, m.at(41_000))
    }

    @Test
    fun asleepWithoutWakeTheKeysStateAtOnce() {
        val m = shown(idle, typing, sleep)
        m.on(PetTrigger.TYPING, 40_000)
        assertEquals(Name.TYPING to 0, m.at(40_000))
        val n = shown(idle, sleep)
        n.on(PetTrigger.DELETE, 40_000)
        assertEquals(Name.IDLE to 0, n.at(40_000))
        val w = shown(idle, sleep, wake)
        w.on(PetTrigger.SPACE, 40_000)
        assertEquals(Name.WAKE to 0, w.at(40_000))
        assertEquals(Name.IDLE to 0, w.at(40_500))
    }

    @Test
    fun hiddenItStopsAndShownItStartsOverAtIdle() {
        val m = shown(idle, typing, sleep)
        m.on(PetTrigger.TYPING, 1000)
        assertEquals(Name.TYPING to 2, m.at(1200))
        m.hidden()
        assertEquals(PetMachine.NONE, m.nextFrameAtMs(1300))
        assertEquals(Name.TYPING to 2, m.at(100_000))
        m.on(PetTrigger.TYPING, 100_000)
        m.shown(200_000)
        assertEquals(Name.IDLE to 0, m.at(200_000))
        assertEquals(Name.IDLE, m.at(229_999).first)
        assertEquals(Name.SLEEP, m.at(230_000).first)
    }

    @Test
    fun aStillIdleSchedulesOnlyItsSleep() {
        val still = Name.IDLE to PetState("i.png", 1, 1, 8, true)
        assertEquals(PetMachine.NONE, shown(still).nextFrameAtMs(0))
        assertEquals(PetMachine.NONE, shown(still, typing).nextFrameAtMs(10))
        val m = shown(still, typing, sleep)
        assertEquals(30_000, m.nextFrameAtMs(0))
        m.on(PetTrigger.TYPING, 5000)
        assertEquals(5084, m.nextFrameAtMs(5000))
        assertEquals(35_000, m.nextFrameAtMs(5500))
    }

    @Test
    fun idleAndSleepPlayAtMostEightFramesASecond() {
        val m = shown(Name.IDLE to PetState("i.png", 24, 24, 30, true))
        assertEquals(125, m.nextFrameAtMs(0))
        assertEquals(Name.IDLE to 1, m.at(125))
        val t = shown(idle, Name.TYPING to PetState("t.png", 24, 24, 30, false))
        t.on(PetTrigger.TYPING, 0)
        assertEquals(34, t.nextFrameAtMs(0))
    }

    @Test
    fun aPetWithoutIdleIsAMistake() {
        assertFailsWith<IllegalArgumentException> { PetMachine(pet(typing)) }
    }
}
