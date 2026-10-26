/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval.parity

import org.fcitx.fcitx5.android.eval.parity.SequenceGenerator.Companion.DEFAULT_IMS
import org.fcitx.fcitx5.android.eval.parity.SequenceGenerator.Companion.T9
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceGeneratorTest {
    private val sequences = SequenceGenerator(DEFAULT_IMS, 7).generate(2000)

    @Test
    fun aSeedGivesTheSameSequences() {
        assertEquals(sequences.take(50), SequenceGenerator(DEFAULT_IMS, 7).generate(50))
        assertNotEquals(sequences.take(50), SequenceGenerator(DEFAULT_IMS, 8).generate(50).map { it.copy(id = it.id.replace("r8-", "r7-")) })
    }

    @Test
    fun idsAreUniqueAndCarryTheSeed() {
        assertEquals(sequences.size, sequences.map { it.id }.toSet().size)
        assertTrue(sequences.all { it.id.startsWith("r7-") })
    }

    @Test
    fun everyKindOfStepComesUp() {
        val ops = sequences.flatMap { s -> s.steps.map { it.op } }.toSet()
        // pickText is the uat sequences' own: a random one picks by place
        assertEquals(Step.OPS - "pickText", ops)
        assertEquals(DEFAULT_IMS.toSet(), sequences.flatMap { s -> s.steps.filter { it.op == "ime" }.map { it.arg } + s.im }.toSet())
        assertEquals(Step.FIELDS.toSet(), sequences.flatMap { s -> s.steps.filter { it.op == "field" }.map { it.arg } }.toSet())
        assertTrue(sequences.all { it.steps.size in 4..20 && it.field == "text" })
    }

    /** What each step's keys are, given where the sequence is: the driver can do every step on the screen it leads to. */
    @Test
    fun stepsFitTheScreenTheyAreOn() {
        for (s in sequences) {
            var im = s.im
            var field = s.field
            for (step in s.steps) {
                when (step.op) {
                    "type" -> {
                        val keys = step.arg!!
                        val nine = im == T9 && field == "text"
                        when {
                            keys == "," || keys == "." -> assertTrue("$s: a mark on ${if (nine) "the nine keys" else field}", !nine && field in setOf("text", "password"))
                            nine -> assertTrue("$s: $keys", keys.all { it in '2'..'9' })
                            else -> assertTrue("$s: $keys", keys.all { it in 'a'..'z' })
                        }
                    }
                    "ime" -> {
                        assertNotEquals("$s: switch in a password field", "password", field)
                        assertTrue(step.arg != T9 || field == "text")
                        im = step.arg!!
                    }
                    "field" -> {
                        assertTrue("$s: the nine keys in a $field field", im != T9 || step.arg == "text")
                        field = step.arg!!
                    }
                    "app" -> field = "text"
                    else -> checkToolStep(s, step, im, field)
                }
            }
        }
    }

    private fun checkToolStep(s: Sequence, step: Step, im: String, field: String) {
        when (step.op) {
            "pick", "bksp" -> assertTrue(step.arg!!.toInt() in 1..3)
            "hw" -> assertTrue(step.arg in Step.HW_KEYS)
            "toggle" -> assertTrue("$s: a toggle in $im $field", im != SequenceGenerator.ENGLISH && field == "text" && step.arg in Step.TOGGLES)
            "quick", "unicode" -> assertTrue("$s: ${step.op} in $im $field", im != T9 && field == "text")
        }
    }

    @Test
    fun noToolbarStepAfterAHardwareKey() {
        for (s in sequences) {
            val hw = s.steps.indexOfFirst { it.op == "hw" }
            if (hw < 0) continue
            assertTrue("$s", s.steps.drop(hw).none { it.op in setOf("toggle", "quick", "unicode") })
        }
    }

    @Test
    fun tablesTypeCodesWithoutTheWildcard() {
        val codes = sequences.filter { it.im == SequenceGenerator.WUBI }
            .flatMap { s -> s.steps.takeWhile { it.op != "ime" && it.op != "field" && it.op != "app" }.filter { it.op == "type" && it.arg!!.length > 1 } }
        assertTrue(codes.isNotEmpty())
        assertTrue(codes.all { st -> st.arg!!.length <= 4 && 'z' !in st.arg!! })
    }
}
