/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.PopupMotion
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.theme.Appear
import org.fcitx.fcitx5.android.scene.theme.Effect
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.ThemeWriter
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

    // A3: `effect=`'s two forms and none, in range, read; out of range or of another form, refused
    @Test
    fun effectsReadInRangeAndRefusedOut() {
        assertEquals(Value.OfEffect(Effect.Ripple("ring.png", 80.0, 300)), Values.read("effect", "ripple-image(ring.png, 80%, 300ms)"))
        assertEquals(Value.OfEffect(Effect.Ripple("ring.webp", 10.0, 1)), Values.read("effect", "ripple-image(ring.webp,10%,1ms)"))
        assertEquals(Value.OfEffect(Effect.Particles("star.png", 12, 24.0, 600)), Values.read("effect", "particles(star.png, 12, 24dp, 600ms)"))
        assertEquals(Value.OfEffect(Effect.Particles("star.png", 1, 0.0, 50)), Values.read("effect", "particles(star.png, 1, 0, 50ms)"))
        assertEquals(Value.OfEffect(null), Values.read("effect", "none"))
        val bad = listOf(
            "ripple-image(ring.png, 9%, 300ms)", "ripple-image(ring.png, 201%, 300ms)", "ripple-image(ring.png, 80%, 0ms)",
            "ripple-image(ring.png, 80%, 601ms)", "ripple-image(ring.png, 80%)", "ripple-image(a/b.png, 80%, 300ms)",
            "particles(star.png, 13, 24dp, 600ms)", "particles(star.png, 0, 24dp, 600ms)", "particles(star.png, 12, 65dp, 600ms)",
            "particles(star.png, 12, -1dp, 600ms)", "particles(star.png, 12, 24dp, 601ms)", "sparkles(star.png)",
        )
        for (b in bad) assertNull(Values.read("effect", b), b)
    }

    @Test
    fun effectsWrittenAndReadBack() {
        val t = theme(
            "<style select=\"key:pressed\" effect=\"ripple-image(ring.png, 72.5%, 300ms)\"/>",
            "<style select=\"key.function:pressed\" effect=\"particles(star.png, 12, 24.5dp, 600ms)\"/>",
            "<style select=\"key.space:pressed\" effect=\"none\"/>",
        )
        assertSameTheme(t, rewritten(t))
    }

    @Test
    fun aKeysEffectIsItsPressedLooksInPx() {
        val t = theme(
            "<style select=\"key:pressed\" effect=\"ripple-image(ring.png, 100%, 300ms)\"/>",
            "<style select=\"key.function:pressed\" effect=\"particles(star.png, 12, 24dp, 600ms)\"/>",
            "<style select=\"key.space:pressed\" effect=\"none\"/>",
        )
        val s = Fixtures.scene(Fixtures.portrait, theme = t)
        val q = s.keys.first { it.id == "q" }
        val face = q.shape.bounds
        // 100%: the face's half diagonal; nothing else moves
        val ripple = KeyEffect.Ripple("ring.png", kotlin.math.hypot(face.width, face.height) / 2, 300)
        assertEquals(Motion(null, null, 1.0, 0.0, ripple), q.motion)
        assertEquals(KeyEffect.Particles("star.png", 12, 24.0 * Fixtures.DENSITY, 600), s.keys.first { it.role == "backspace" }.motion?.effect)
        // none takes it away: no motion at all
        assertNull(s.keys.first { it.role == "space" }.motion)
        assertTrue("  effect ripple \"ring.png\" radius=" in SceneText.of(s), SceneText.of(s).lines().first { "effect" in it })
        assertTrue("  effect particles \"star.png\" 12 spread=63 600ms" in SceneText.of(s))
    }

    @Test
    fun particlesFlyTheSameWayForTheSameSeed() {
        val p = KeyEffect.Particles("star.png", 12, 60.0, 600)
        val a = p.ends(7)
        assertTrue(a.contentEquals(p.ends(7)))
        assertTrue(!a.contentEquals(p.ends(8)))
        assertEquals(24, a.size)
        for (i in 0 until 12) {
            val d = kotlin.math.hypot(a[2 * i], a[2 * i + 1])
            assertTrue(d in 30.0..60.0 + 1e-9, "$i: $d")
        }
        // a fixed seed's flight, the same on the JVM and in JS (kotlin.random is common code)
        assertEquals(PINNED, a.take(4).map { kotlin.math.round(it * 1000).toInt() })
    }

    @Test
    fun effectWhereItDoesNothingWarned() {
        assertEquals(emptyList(), warnings("<style select=\"key:pressed\" effect=\"particles(star.png, 3, 8dp, 300ms)\"/>"))
        assertEquals(listOf("effect= on popup does nothing: only a key moves"), warnings("<style select=\"popup\" effect=\"particles(star.png, 3, 8dp, 300ms)\"/>"))
        assertEquals(listOf("effect= on a key's label does nothing: the key moves as a whole"), warnings("<style select=\"key label\" effect=\"none\"/>"))
    }

    @Test
    fun motionWhereItDoesNothingWarned() {
        assertEquals(emptyList(), warnings("<style select=\"key\" transition=\"150ms ease\"/>", "<style select=\"key:pressed\" press-scale=\"95%\" press-sink=\"1dp\"/>"))
        // A2: a popup's and a bubble's going, their and a menu's coming up
        assertEquals(emptyList(), warnings("<style select=\"popup\" transition=\"150ms ease\" appear=\"scale 150ms\"/>", "<style select=\"preview\" transition=\"100ms linear\" appear=\"rise 80ms\"/>", "<style select=\"menu\" appear=\"fade 100ms\"/>"))
        assertEquals(listOf("transition= on menu does nothing: a menu goes at once"), warnings("<style select=\"menu\" transition=\"150ms ease\"/>"))
        assertEquals(listOf("appear= on popup in a state or a part does nothing: a popup moves as a whole"), warnings("<style select=\"popup:selected\" appear=\"scale 150ms\"/>"))
        assertEquals(listOf("press-scale= on popup does nothing: only a key moves"), warnings("<style select=\"popup\" press-scale=\"95%\"/>"))
        assertEquals(listOf("appear= on key does nothing: only a popup, a preview or a menu comes up"), warnings("<style select=\"key\" appear=\"fade 100ms\"/>"))
        assertEquals(listOf("transition= on a key's label does nothing: the key moves as a whole"), warnings("<style select=\"key label\" transition=\"150ms ease\"/>"))
        // read on a key in any state: `key` selects a pressed key too
        assertEquals(emptyList(), warnings("<style select=\"key\" press-sink=\"1dp\"/>"))
    }

    @Test
    fun appearReadInRangeAndRefusedOut() {
        assertEquals(Value.OfAppear(Appear("scale", 150)), Values.read("appear", "scale 150ms"))
        assertEquals(Value.OfAppear(Appear("rise", 250)), Values.read("appear", "rise 250ms"))
        assertEquals(Value.OfAppear(Appear("fade", 0)), Values.read("appear", "fade 0ms"))
        for (bad in listOf("fade 251ms", "slide 100ms", "fade", "100ms", "fade 100")) assertNull(Values.read("appear", bad), bad)
        assertTrue(problems("<style select=\"popup\" appear=\"fade 300ms\"/>").isNotEmpty())
        // written back as read
        val text = ThemeWriter.write(theme("<style select=\"popup\" appear=\"scale 150ms\" transition=\"120ms ease-in\"/>"))
        assertTrue("transition=\"120ms ease-in\" appear=\"scale 150ms\"" in text, text)
        val back = ThemeReader.theme(text).ok().styles.last().values
        assertEquals(Value.OfAppear(Appear("scale", 150)), back["appear"])
    }

    @Test
    fun aPopupsMotionFromItsLook() {
        fun motion(widget: String, vararg style: String) = SceneBuilder(theme(*style), Fixtures.portrait, FakeMeasurer).popupMotion(widget).ok()
        // none set: as ever
        for (w in listOf("popup", "preview", "menu")) assertNull(motion(w), w)
        val styles = arrayOf(
            "<style select=\"popup\" appear=\"scale 150ms\" transition=\"120ms ease-in\"/>",
            "<style select=\"preview\" appear=\"rise 0ms\" transition=\"90ms linear\"/>",
            "<style select=\"menu\" appear=\"fade 100ms\"/>",
        )
        assertEquals(PopupMotion(Appear("scale", 150), Transition(120, "ease-in")), motion("popup", *styles))
        // 0 ms: none
        assertEquals(PopupMotion(null, Transition(90, "linear")), motion("preview", *styles))
        assertEquals(PopupMotion(Appear("fade", 100), null), motion("menu", *styles))
        // a key's own transition is not its popup's
        assertNull(motion("popup", "<style select=\"key\" transition=\"150ms ease\"/>"))
    }
}

private val PINNED = listOf(44354, 13760, 45286, 33561)
