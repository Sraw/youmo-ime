/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Transition
import org.fcitx.fcitx5.android.scene.theme.Value
import org.fcitx.fcitx5.android.scene.theme.Values
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 060-render-p3 A1: `transition`, `press-scale`, `press-sink` read, cascaded and put in the scene as a key's Motion
class MotionTest {
    private val source = testData("material-light.xml")

    private fun withStyle(vararg style: String) = source.replace("</theme>", style.joinToString("\n", postfix = "\n</theme>"))

    private fun theme(vararg style: String): Theme = ThemeReader.theme(withStyle(*style)).ok()

    private fun warnings(vararg style: String): List<String> {
        val t = ThemeReader.theme(withStyle(*style))
        assertTrue(t is Parsed.Ok, "${t.problems()}")
        return t.warnings().map { it.message }
    }

    private fun problems(vararg style: String) = ThemeReader.theme(withStyle(*style)).problems().map { it.message }

    @Test
    fun valuesReadInRangeAndRefusedOut() {
        assertEquals(Value.OfTransition(Transition(150, "ease-out")), Values.read("transition", "150ms ease-out"))
        assertEquals(Value.OfTransition(Transition(0, "linear")), Values.read("transition", "0ms linear"))
        assertEquals(Value.OfTransition(Transition(600, "ease-in-out")), Values.read("transition", "600ms ease-in-out"))
        for (bad in listOf("601ms ease", "150ms bounce", "150 ease", "fast", "150ms", "-1ms ease")) assertNull(Values.read("transition", bad), bad)
        assertEquals(Value.Number(95.0), Values.read("press-scale", "95%"))
        assertEquals(Value.Number(85.0), Values.read("press-scale", "85%"))
        assertEquals(Value.Number(110.0), Values.read("press-scale", "110%"))
        for (bad in listOf("84%", "111%", "0.95", "95")) assertNull(Values.read("press-scale", bad), bad)
        assertEquals(Value.Number(2.0), Values.read("press-sink", "2dp"))
        assertEquals(Value.Number(0.0), Values.read("press-sink", "0"))
        for (bad in listOf("5dp", "-1dp", "2px")) assertNull(Values.read("press-sink", bad), bad)
        assertTrue(problems("<style select=\"key:pressed\" press-scale=\"80%\"/>").isNotEmpty())
        assertTrue(problems("<style select=\"key\" transition=\"700ms ease\"/>").isNotEmpty())
    }

    @Test
    fun writtenAndReadBack() {
        val t = theme("<style select=\"key\" transition=\"150ms ease-out\"/>", "<style select=\"key:pressed\" transition=\"60ms ease\" press-scale=\"95%\" press-sink=\"2.5dp\"/>")
        assertSameTheme(t, rewritten(t))
    }

    @Test
    fun aThemeWithoutMotionMovesNoKey() {
        assertTrue(Fixtures.scene(Fixtures.portrait).keys.all { it.motion == null })
        assertTrue("motion" !in SceneText.of(Fixtures.scene(Fixtures.portrait)))
    }

    @Test
    fun aKeysMotionIsItsLooks() {
        val t = theme(
            "<style select=\"key\" transition=\"150ms ease-out\"/>",
            "<style select=\"key:pressed\" transition=\"120ms ease\" press-scale=\"95%\" press-sink=\"2dp\"/>",
            "<style select=\"key.function\" transition=\"0ms linear\"/>",
            "<style select=\"key:shifted\" transition=\"200ms linear\"/>",
        )
        val s = Fixtures.scene(Fixtures.portrait, theme = t)
        val q = s.keys.first { it.id == "q" }
        // the press no longer than 80 ms; the sink in px
        assertEquals(Motion(Transition(150, "ease-out"), Transition(80, "ease"), 0.95, 2.0 * Fixtures.DENSITY), q.motion)
        // 0 ms: no transition, pressed too (key.function's after key:pressed, as specific); scaled at once
        val bs = s.keys.first { it.role == "backspace" }
        assertEquals(Motion(null, null, 0.95, 2.0 * Fixtures.DENSITY), bs.motion)
        // Shift on: its own state's transition
        val shifted = Fixtures.scene(Fixtures.portrait.copy(shift = Shift.Once), theme = t).keys.first { it.role == "shift" }
        assertEquals(Transition(200, "linear"), shifted.motion?.transition)
        assertTrue("  motion transition=150ms,ease-out press=80ms,ease scale=0.95 sink=5.25" in SceneText.of(s))
    }

    @Test
    fun aScaleWithNoTransitionIsAtOnce() {
        val q = Fixtures.scene(Fixtures.portrait, theme = theme("<style select=\"key:pressed\" press-scale=\"90%\"/>")).keys.first { it.id == "q" }
        assertEquals(Motion(null, null, 0.9, 0.0), q.motion)
        // a pressed transition and nothing to move: none
        assertNull(Fixtures.scene(Fixtures.portrait, theme = theme("<style select=\"key:pressed\" transition=\"60ms ease\"/>")).keys.first { it.id == "q" }.motion)
    }

    @Test
    fun motionWhereItDoesNothingWarned() {
        assertEquals(emptyList(), warnings("<style select=\"key\" transition=\"150ms ease\"/>", "<style select=\"key:pressed\" press-scale=\"95%\" press-sink=\"1dp\"/>"))
        assertEquals(listOf("transition= on popup is not supported by this version, ignored"), warnings("<style select=\"popup\" transition=\"150ms ease\"/>"))
        assertEquals(listOf("transition= on a key's label does nothing: the key moves as a whole"), warnings("<style select=\"key label\" transition=\"150ms ease\"/>"))
        // read on a key in any state: `key` selects a pressed key too
        assertEquals(emptyList(), warnings("<style select=\"key\" press-sink=\"1dp\"/>"))
    }
}
