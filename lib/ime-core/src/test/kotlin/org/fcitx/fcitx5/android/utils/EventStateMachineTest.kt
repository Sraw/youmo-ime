/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.utils.EventStateMachineTest.Key.Flag
import org.fcitx.fcitx5.android.utils.EventStateMachineTest.Key.Other
import org.fcitx.fcitx5.android.utils.EventStateMachineTest.S.A
import org.fcitx.fcitx5.android.utils.EventStateMachineTest.S.B
import org.fcitx.fcitx5.android.utils.EventStateMachineTest.S.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state machine framework itself, independent of any concrete machine in the app. */
class EventStateMachineTest {

    enum class S { A, B, C }

    enum class Key : EventStateMachine.BooleanStateKey { Flag, Other }

    private typealias Event = EventStateMachine.TransitionEvent<S, Key>

    /** An event whose transition is written out directly, for testing the machine's plumbing. */
    private class RawEvent(
        private val fn: (initial: S, current: S, useBoolean: (Key) -> Boolean?) -> S
    ) : Event {
        override fun accept(initialState: S, currentState: S, useBoolean: (Key) -> Boolean?) =
            fn(initialState, currentState, useBoolean)
    }

    private fun machine(
        initial: S = A,
        booleans: MutableMap<Key, Boolean> = mutableMapOf(),
    ) = EventStateMachine<S, Event, Key>(initial, booleans)


    // region basic transitions

    @Test
    fun startsAtTheInitialState() {
        assertEquals(B, machine(initial = B).currentState)
    }

    @Test
    fun pushAppliesTheTransitionAndNotifies() {
        val seen = mutableListOf<S>()
        val m = machine().apply { onNewStateListener = { seen += it } }

        m.push(RawEvent { _, _, _ -> B })

        assertEquals(B, m.currentState)
        assertEquals(listOf(B), seen)
    }

    /** A transition back onto the current state is a no-op: no listener call. */
    @Test
    fun pushThatDoesNotChangeStateDoesNotNotify() {
        val seen = mutableListOf<S>()
        val m = machine().apply { onNewStateListener = { seen += it } }

        m.push(RawEvent { _, current, _ -> current })

        assertEquals(A, m.currentState)
        assertTrue("listener must not fire when nothing changed", seen.isEmpty())
    }

    @Test
    fun listenerFiresOncePerActualChange() {
        val seen = mutableListOf<S>()
        val m = machine().apply { onNewStateListener = { seen += it } }

        m.push(RawEvent { _, _, _ -> B })
        m.push(RawEvent { _, _, _ -> B }) // no change
        m.push(RawEvent { _, _, _ -> C })

        assertEquals(listOf(B, C), seen)
    }

    @Test
    fun machineWorksWithoutAListener() {
        val m = machine()
        m.push(RawEvent { _, _, _ -> C })
        assertEquals(C, m.currentState)
    }

    @Test
    fun transitionSeesTheInitialStateNotJustTheCurrentOne() {
        val m = machine(initial = A)
        m.push(RawEvent { _, _, _ -> C })
        // an event can route back to wherever the machine started
        m.push(RawEvent { initial, _, _ -> initial })
        assertEquals(A, m.currentState)
    }

    // endregion

    // region boolean states

    @Test
    fun booleanStatesAreReadableAndWritable() {
        val m = machine(booleans = mutableMapOf(Flag to true))
        assertEquals(true, m.getBooleanState(Flag))
        m.setBooleanState(Flag, false)
        assertEquals(false, m.getBooleanState(Flag))
    }

    @Test
    fun unsetBooleanStateReadsAsNull() {
        assertNull(machine().getBooleanState(Flag))
    }

    @Test
    fun transitionCanReadBooleanStates() {
        val m = machine(booleans = mutableMapOf(Flag to true))
        m.push(RawEvent { _, _, useBoolean -> if (useBoolean(Flag) == true) B else C })
        assertEquals(B, m.currentState)
    }

    /** An unset key reaches the transition as `null`, not as `false`. */
    @Test
    fun transitionSeesNullForAnUnsetKey() {
        val m = machine()
        m.push(RawEvent { _, _, useBoolean -> if (useBoolean(Flag) == null) C else B })
        assertEquals(C, m.currentState)
    }

    @Test
    fun pushWithBooleansUpdatesThemBeforeTransitioning() {
        val m = machine(booleans = mutableMapOf(Flag to false))
        m.push(
            RawEvent { _, _, useBoolean -> if (useBoolean(Flag) == true) B else C },
            Flag to true,
        )
        assertEquals("the new boolean value was visible to the transition", B, m.currentState)
        assertEquals(true, m.getBooleanState(Flag))
    }

    @Test
    fun pushWithSeveralBooleansAppliesAllOfThem() {
        val m = machine()
        m.push(
            RawEvent { _, _, use -> if (use(Flag) == true && use(Other) == true) B else C },
            Flag to true,
            Other to true,
        )
        assertEquals(B, m.currentState)
    }

    @Test
    fun booleanStateSurvivesAcrossPushes() {
        val m = machine()
        m.setBooleanState(Flag, true)
        m.push(RawEvent { _, _, _ -> B })
        assertEquals(true, m.getBooleanState(Flag))
    }

    // endregion

    // region unsafeJump

    @Test
    fun unsafeJumpSetsTheStateAndNotifies() {
        val seen = mutableListOf<S>()
        val m = machine().apply { onNewStateListener = { seen += it } }

        m.unsafeJump(C)

        assertEquals(C, m.currentState)
        assertEquals(listOf(C), seen)
    }

    /** Unlike `push`, `unsafeJump` does not compare against the current state. */
    @Test
    fun unsafeJumpToTheSameStateStillNotifies() {
        val seen = mutableListOf<S>()
        val m = machine(initial = A).apply { onNewStateListener = { seen += it } }

        m.unsafeJump(A)

        assertEquals(listOf(A), seen)
    }

    // endregion

    // region the transition DSL

    private fun dsl(block: TransitionBuildBlock<S, Key>): Event = BuildTransitionEvent(block)

    @Test
    fun dslTransitionsOnlyFromTheMatchingSourceState() {
        val event = dsl { from(A) transitTo B }

        val fromA = machine(initial = A).apply { push(event) }
        assertEquals(B, fromA.currentState)

        val fromC = machine(initial = C).apply { push(event) }
        assertEquals("no rule for C, so it stays put", C, fromC.currentState)
    }

    @Test
    fun dslOnGuardsTheTransitionWithABooleanState() {
        val event = dsl { from(A) transitTo B on (Flag to true) }

        val enabled = machine(booleans = mutableMapOf(Flag to true)).apply { push(event) }
        assertEquals(B, enabled.currentState)

        val disabled = machine(booleans = mutableMapOf(Flag to false)).apply { push(event) }
        assertEquals(A, disabled.currentState)
    }

    @Test
    fun dslOnGuardFailsClosedForAnUnsetKey() {
        val event = dsl { from(A) transitTo B on (Flag to true) }
        val m = machine().apply { push(event) }
        assertEquals("null != true, so the guard does not pass", A, m.currentState)
    }

    @Test
    fun dslOnFAllowsACompoundGuard() {
        val event = dsl {
            from(A) transitTo B onF { it(Flag) == true && it(Other) == false }
        }

        val matching = machine(booleans = mutableMapOf(Flag to true, Other to false))
        matching.push(event)
        assertEquals(B, matching.currentState)

        val notMatching = machine(booleans = mutableMapOf(Flag to true, Other to true))
        notMatching.push(event)
        assertEquals(A, notMatching.currentState)
    }

    /**
     * When several rules match, the first declared one wins. Concrete machines rely on this
     * for priority -- see `KawaiiBarStateMachine.WindowDetached`.
     */
    @Test
    fun dslTakesTheFirstMatchingRuleWhenSeveralApply() {
        val event = dsl {
            from(A) transitTo B
            from(A) transitTo C
        }
        val m = machine().apply { push(event) }
        assertEquals(B, m.currentState)
    }

    @Test
    fun dslGuardedRuleDeclaredFirstBeatsAnUnguardedFallback() {
        val event = dsl {
            from(A) transitTo B on (Flag to true)
            from(A) transitTo C
        }

        val guardPasses = machine(booleans = mutableMapOf(Flag to true)).apply { push(event) }
        assertEquals("the guarded rule wins", B, guardPasses.currentState)

        val guardFails = machine(booleans = mutableMapOf(Flag to false)).apply { push(event) }
        assertEquals("falls through to the unguarded rule", C, guardFails.currentState)
    }

    @Test
    fun dslWithNoRulesLeavesTheStateAlone() {
        val m = machine().apply { push(dsl { }) }
        assertEquals(A, m.currentState)
    }

    /** `accept` replaces the declarative rules entirely. */
    @Test
    fun dslRawAcceptOverridesDeclaredRules() {
        val event = dsl {
            from(A) transitTo B
            accept { _, _, _ -> C }
        }
        val m = machine().apply { push(event) }
        assertEquals(C, m.currentState)
    }

    @Test
    fun dslRulesAreReusableAcrossMachines() {
        val event = dsl { from(A) transitTo B }
        repeat(3) {
            val m = machine().apply { push(event) }
            assertEquals(B, m.currentState)
        }
    }

    // endregion
}
