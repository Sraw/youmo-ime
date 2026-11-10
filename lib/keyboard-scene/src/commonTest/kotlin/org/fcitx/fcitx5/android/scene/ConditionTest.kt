/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.scene.layout.Condition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// layout-1.xsd's `when=` (README layout #8): terms and-ed, ", " groups or-ed, `!` negates
class ConditionTest {
    private val env = Env(1080.0, 720.0, 2.625)

    private fun holds(text: String, e: Env = env) = Condition.parse(text).ok().holds(e.facts)

    @Test
    fun semicolonKeyRuleA1OrGroups() {
        val c = "ime=shuangpin scheme-types=semicolon !split, table-extra=semicolon"
        val shuangpin = env.copy(input = Input(ime = "shuangpin", scheme = "Ziranma", schemeTypes = setOf("semicolon")))
        assertTrue(holds(c, shuangpin))
        assertFalse(holds(c, shuangpin.copy(split = true)), "not split")
        assertFalse(holds(c, env), "pinyin")
        assertTrue(holds(c, env.copy(input = Input(ime = "table", table = "wubi", tableExtra = "semicolon"))))
        assertFalse(holds(c, env.copy(input = Input(ime = "table", table = "wubi", tableExtra = "star"))))
    }

    @Test
    fun valuesAndBareValuedNames() {
        assertTrue(holds("field=url|email", env.copy(input = Input(field = "email"))))
        assertFalse(holds("field=url|email", env))
        assertTrue(holds("!field=url|email", env))
        assertTrue(holds("table", env.copy(input = Input(table = "wubi"))))
        assertFalse(holds("table", env))
        assertTrue(holds("scheme-types", env.copy(input = Input(schemeTypes = setOf("semicolon")))))
        assertFalse(holds("scheme-types", env))
        assertTrue(holds("action=search", env.copy(input = Input(action = "search"))))
        assertTrue(holds("scheme=Ziranma", env.copy(input = Input(scheme = "Ziranma"))))
        assertFalse(holds("split-side=left", env))
    }

    @Test
    fun flags() {
        val all = env.copy(
            compact = true, landscape = true, split = true, floating = 0.85, oneHanded = SplitKeyboard.Dock.LEFT, shift = Shift.Lock,
            input = Input(roots = true, voice = true, holdVoice = true, langKey = true, composing = true, lookingUp = true),
        )
        for (flag in Condition.FLAGS) {
            // one thumb's keyboard is only while the letters do not split (SplitKeyboard.placement), nor float
            assertTrue(holds(flag, if (flag == "one-handed") all.copy(split = false, floating = null) else all), flag)
            assertFalse(holds(flag, env.copy(input = Input(langKey = false))), flag)
        }
        // a floating keyboard is never one-handed: it is placed by its own (FloatingKeyboard)
        assertFalse(holds("one-handed", env.copy(oneHanded = SplitKeyboard.Dock.LEFT, floating = 0.85)))
        assertTrue(holds("shifted", env.copy(shift = Shift.Once)))
        assertFalse(holds("caps-lock", env.copy(shift = Shift.Once)))
    }

    @Test
    fun malformedConditionsAreProblems() {
        assertIs<Parsed.Failed>(Condition.parse(""))
        assertIs<Parsed.Failed>(Condition.parse("ime=pin yin"))
        assertIs<Parsed.Failed>(Condition.parse("weather=rain"))
        assertIs<Parsed.Failed>(Condition.parse("composing=yes"))
        assertEquals("!split, ime=a|b", Condition.parse("!split, ime=a|b").ok().toString())
    }
}
