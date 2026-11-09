/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.Radius
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Value
import org.fcitx.fcitx5.android.scene.theme.Values
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// theme-4.xsd as the engine reads it, and the cascade (060-render-v2 §5, rules B §4)
class ThemeTest {
    private val source = testData("material-light.xml")

    private fun problems(s: String) = ThemeReader.theme(s).problems().map { it.message }

    private fun withStyle(style: String) = source.replace("</theme>", "$style\n</theme>")

    @Test
    fun theMaterialLightExampleReads() {
        val t = Fixtures.materialLight
        assertEquals("MaterialLight", t.name)
        assertEquals(ThemeReader.REQUIRED_COLORS.toSet(), t.colors.keys)
        assertEquals(Argb(0xFFC0C3C4.toInt()), t.colors["keyBorder"], "@keyShadow resolved")
        assertTrue(t.styles.isNotEmpty())
    }

    @Test
    fun colourProblems() {
        assertTrue(problems(source.replace("<color name=\"toolbarIcon\" value=\"@altKeyText\"/>", "")).single().startsWith("colours missing: toolbarIcon"))
        assertTrue(problems(source.replace("value=\"@altKeyText\"/>\n    <color name=\"toolbarIcon\"", "value=\"@nothing\"/>\n    <color name=\"toolbarIcon\"")).any { it == "@nothing is no colour" })
        assertTrue(problems(source.replace("<color name=\"keyBorder\" value=\"@keyShadow\"/>", "<color name=\"keyBorder\" value=\"@keyBorder\"/>")).any { it.contains("by itself") })
        assertTrue(problems(source.replace("<colors>", "<colors>\n<color name=\"bar\" value=\"#000000\"/>")).any { it.contains("given twice") })
    }

    @Test
    fun styleProblemsHaveLines() {
        val p = ThemeReader.theme(withStyle("<style select=\"key\" fill=\"@nowhere\"/>")).problems()
        assertTrue(p.single().message.contains("@nowhere is no colour"), "$p")
        assertTrue(p.single().line > 70)
        // the colour over a picture, under an image: a colour of the theme's too
        assertTrue("@nope is no colour" in problems(withStyle("<style select=\"key\" fill=\"picture(10dp, 80%, @nope)\"/>")).single())
        assertTrue("@nope is no colour" in problems(withStyle("<style select=\"key\" fill=\"image(key.png, 1 1 1 1, @nope)\"/>")).single())
        assertTrue(problems(withStyle("<style select=\"key!\" fill=\"@key\"/>")).single().contains("no selector"))
        assertTrue(problems(withStyle("<style select=\"key\" font=\"fancy\"/>")).single().contains("no <font>"))
        assertTrue(problems(withStyle("<style select=\"key\"/>")).single().contains("sets nothing"))
        assertTrue(problems(withStyle("<style select=\"key\" radius=\"lots\"/>")).isNotEmpty())
        assertTrue(problems(source.replace("xmlns=\"https://youmo-ime/theme/4\"", "xmlns=\"x\"")).isNotEmpty())
    }

    @Test
    fun colourExpressionsAreBounded() {
        // H1: eight functions deep at most, 256 characters at most: a theme cannot make the reader recurse away
        fun nested(n: Int) = "mix(".repeat(n) + "#000000" + ", #ffffff, 50%)".repeat(n)
        assertNotNull(ColorExpr.parse(nested(ColorExpr.MAX_DEPTH)))
        assertNull(ColorExpr.parse(nested(ColorExpr.MAX_DEPTH + 1)))
        assertNull(ColorExpr.parse("#000000" + " ".repeat(ColorExpr.MAX_LENGTH)))
        assertTrue(problems(withStyle("<style select=\"key\" fill=\"${nested(40)}\"/>")).isNotEmpty())
        val deep = "<colors>" + "<x>".repeat(500)
        assertTrue(ThemeReader.theme(source.replace("<colors>", deep)).problems().isNotEmpty())
    }

    @Test
    fun tooManyIconsOrStylesAreProblems() {
        val icons = (1..201).joinToString("") { "<icon name=\"i$it\" src=\"i$it.png\"/>" }
        assertTrue(problems(source.replace("</theme>", "$icons</theme>")).any { it.contains("over 200 icons") })
    }

    @Test
    fun colourFunctions() {
        val black = Argb.of(255, 0, 0, 0)
        val white = Argb.of(255, 255, 255, 255)
        val grey = Argb.of(255, 100, 100, 100)
        fun c(text: String) = assertNotNull(ColorExpr.parse(text), text).resolve(mapOf("w" to white, "k" to black, "g" to grey)::get)
        assertEquals(Argb.of(255, 50, 50, 50), c("darker(@g, 50%)"))
        assertEquals(Argb.of(255, 178, 178, 178), c("lighter(@g, 50%)"))
        assertEquals(Argb.of(128, 255, 255, 255), c("alpha(@w, 50%)"), "alpha sets, not multiplies")
        assertEquals(Argb.of(255, 26, 26, 26), c("mix(@k, @w, 10%)"))
        assertEquals(Argb(0x33000000), c("#33000000"))
        assertEquals(Argb(0xFF102030.toInt()), c("#102030"))
        assertEquals(Argb.TRANSPARENT, c("transparent"))
        assertNull(c("@missing"))
        assertNull(ColorExpr.parse("darker(@g)"))
        assertNull(ColorExpr.parse("mix(@g, 10%)"))
        assertNull(ColorExpr.parse("#12345"))
        assertEquals("#80ffffff", Argb.of(128, 255, 255, 255).toString())
    }

    @Test
    fun values() {
        assertEquals(Inset(7.0, 3.0, 7.0, 3.0), Values.inset("7dp 3dp"))
        assertEquals(Inset(1.0, 2.0, 3.0, 4.0), Values.inset("1dp 2dp 3dp 4dp"))
        assertEquals(Radius(List(4) { 4.0 }), Values.radius("4dp"))
        assertEquals(true, Values.radius("50%")?.half)
        assertEquals(0.0, Values.radius("0")?.corners?.first())
        // on both targets alike (Kotlin/JS refused a name that only began with "system")
        assertEquals(Value.Word("systemui"), Values.read("font", "systemui"))
        assertEquals(Value.Word("system"), Values.read("font", "system"))
        assertEquals(1.0, Values.shadow("0 1dp 0 @keyShadow")?.dy)
        assertEquals(0.0, Values.shadow("none")?.dy)
        assertNotNull(Values.fill("linear(90deg, @a 0%, @b 100%)") as? Fill.Linear)
        assertEquals(Fill.Image("key.png", listOf(1, 2, 3, 4), 3), Values.fill("image(key.png, 1 2 3 4, 3x)"))
        assertNotNull(Values.fill("picture") as? Fill.Picture)
        // theme 3.0's key colour over the picture, under the image (where it is not there)
        assertEquals(Fill.Picture(10.0, 80.0, ColorExpr.parse("@key")), Values.fill("picture(10dp, 80%, @key)"))
        assertEquals(Fill.Picture(10.0, 80.0, ColorExpr.parse("#4d465a6b")), Values.fill("picture(10dp, 80%, #4d465a6b)"))
        assertEquals(Fill.Image("key.png", listOf(1, 2, 3, 4), 3, ColorExpr.parse("@altKey")), Values.fill("image(key.png, 1 2 3 4, 3x, @altKey)"))
        assertEquals(Fill.Image("key.png", listOf(1, 2, 3, 4), 1, ColorExpr.parse("@key")), Values.fill("image(key.png, 1 2 3 4, @key)"))
        assertNull(Values.fill("picture(10dp, 80%, no colour)"))
        assertNull(Values.fill("image(key.png, 1 2 3 4, 3x, no colour)"))
        assertEquals(Value.Number(700.0), Values.read("weight", "700"))
        assertNull(Values.read("weight", "heavy"))
        assertNull(Values.read("no-such", "1"))
    }

    @Test
    fun selectorsAndSpecificityRuleB4() {
        fun s(t: String) = assertNotNull(Selector.parse(t), t)
        val byName = s("qwerty/q")
        val byRole = s("*/enter")
        val byKind = s("key.enter")
        val board = s("qwerty/*")
        val plain = s("key")
        // named key over */role over kind over board/* over key (KeyStyleResolver's order)
        val order = listOf(byName, byRole, byKind, board, plain).map { it.specificity }
        assertEquals(order.sortedDescending(), order)
        assertTrue(byRole.specificity > byKind.specificity)
        assertTrue(s("key mark").specificity > plain.specificity, "a part counts like an element")
        assertTrue(s("@flat key").specificity > plain.specificity)
        assertEquals(s("zhuyin/.").key, ".", "a key name's first character is its own")
        assertEquals(setOf("pressed"), s("key.enter:pressed").states)
        assertEquals(setOf("lang"), s("key[role=lang]").roles)
        assertNull(Selector.parse("key mark extra"))
        assertNull(Selector.parse("bar mark"))
        assertNull(Selector.parse("key:hovered"))
        assertNull(Selector.parse("gizmo"))
        assertNull(Selector.parse("*/Enter"))
        assertEquals(2, Selector.parseList("preview, popup")?.size)
        val target = Selector.Target("key", "qwerty", "q", null, setOf("letter"), emptySet())
        assertTrue(byName.matches(target, emptySet()))
        assertTrue(board.matches(target, emptySet()))
        assertTrue(!s("@flat key").matches(target, emptySet()))
        assertTrue(!byRole.matches(target, emptySet()))
        assertTrue(s("*/enter").matches(target.copy(role = "enter"), emptySet()))
        // L3, as CSS: a selector's qualifiers all hold (AND), a list's selectors any (OR)
        val both = s("key[role=enter][role=lang]")
        assertTrue(!both.matches(target.copy(role = "enter"), emptySet()), "one key is not two roles")
        assertTrue(s("key.function.alt-text").matches(target.copy(classes = setOf("function", "alt-text")), emptySet()))
        assertTrue(!s("key.function.alt-text").matches(target.copy(classes = setOf("function")), emptySet()))
        assertTrue(s("key:on:pressed").matches(target.copy(states = setOf("on", "pressed")), emptySet()))
        assertTrue(!s("key:on:pressed").matches(target.copy(states = setOf("pressed")), emptySet()))
        val list = assertNotNull(Selector.parseList("*/enter, */lang"))
        assertTrue(list.any { it.matches(target.copy(role = "lang"), emptySet()) })
    }

    @Test
    fun keyNamesEscapeAsInCss() {
        fun s(t: String) = assertNotNull(Selector.parse(t), t)
        // `\` makes the next character the name's: the comma key, not two selectors
        val comma = assertNotNull(Selector.parseList("zhuyin/\\,, key.enter"))
        assertEquals(2, comma.size)
        assertEquals(",", comma[0].key)
        assertEquals(",", s("zhuyin/\\,:pressed").key)
        assertEquals(setOf("pressed"), s("zhuyin/\\,:pressed").states)
        // the star key against every key of the board
        val star = s("number/\\*")
        assertEquals("*", star.key)
        val every = s("number/*")
        assertNull(every.key)
        val starKey = Selector.Target("key", "number", "*", null, setOf("function"))
        val plusKey = starKey.copy(key = "+")
        assertTrue(star.matches(starKey, emptySet()))
        assertTrue(!star.matches(plusKey, emptySet()))
        assertTrue(every.matches(plusKey, emptySet()))
        assertEquals("\\", s("number/\\\\").key)
        assertEquals("a.b", s("x/a\\.b").key, "an escaped dot is no class")
        assertEquals("/", s("zhuyin//").key)
        assertEquals(":", s("number/:").key, "a name's first character is its own, escaped or not")
        // a lone `\` at the end, an escape outside a key's name
        assertNull(Selector.parseList("number/\\"))
        assertNull(Selector.parse("ke\\y"))
        assertNull(Selector.parse("number/a.b\\c"))
        assertNull(Selector.parse("\\*/enter"))
    }

    @Test
    fun aSlotIsNoneOfItsBoardsEveryKey() {
        val pad = Selector.Target("key", "handwriting", "pad", null, setOf("letter"), slot = true)
        assertTrue(!assertNotNull(Selector.parse("handwriting/*")).matches(pad, emptySet()))
        assertTrue(assertNotNull(Selector.parse("handwriting/pad")).matches(pad, emptySet()))
        assertTrue(assertNotNull(Selector.parse("key.letter")).matches(pad, emptySet()))
    }

    @Test
    fun cascadeKindRoleNameAndScenesRuleB4() {
        val t = ThemeReader.theme(withStyle("<style select=\"*/enter\" fill=\"#ff0000\"/>\n<style select=\"qwerty/enter\" radius=\"9dp\"/>")).ok()
        val c = Cascade(t, emptySet())
        val enter = Selector.Target("key", "qwerty", "enter", "enter", setOf("enter"), emptySet())
        val look = KeyLook(c.of(enter), t)
        assertEquals(Fill.Solid(ColorExpr.Literal(Argb(0xFFFF0000.toInt()))), look.fill, "*/enter over key.enter")
        assertEquals(9.0, look.radius.corners.first(), "a name given only a radius keeps the rest")
        assertEquals("tint", look.press)
        val pressed = KeyLook(c.of(enter.copy(states = setOf("pressed"))), t)
        assertEquals(Argb(0x1F000000), pressed.overlay)
        val flat = KeyLook(Cascade(t, setOf("flat")).of(enter), t)
        assertEquals("circle", flat.shape)
        assertEquals(Inset(4.0, 3.0, 4.0, 3.0), KeyLook(Cascade(t, setOf("landscape")).of(enter), t).inset)
    }

    @Test
    fun partsInheritTheKeysTextLook() {
        val t = Fixtures.materialLight
        val c = Cascade(t, emptySet())
        val enter = Selector.Target("key", "qwerty", "enter", "enter", setOf("enter"), emptySet())
        assertEquals(Argb(0xFFFFFFFF.toInt()), TextLook(c.of(enter.copy(part = "label")), t).color, "key.enter's colour, inherited")
        assertEquals(Argb(0xFFFFFFFF.toInt()), TextLook(c.of(enter.copy(part = "mark")), t).color, "key.enter mark")
        val q = Selector.Target("key", "qwerty", "q", null, setOf("letter"), emptySet())
        assertEquals(t.colors["keyHint"], TextLook(c.of(q.copy(part = "mark")), t).color, "key mark over the inherited")
        assertEquals(t.colors["keyText"], TextLook(c.of(q.copy(part = "label")), t).color)
        assertEquals(400, TextLook(c.of(q.copy(part = "label")), t).weight)
        assertEquals(1.0, TextLook(c.of(q.copy(part = "label")), t).scale)
        assertEquals(false, TextLook(c.of(q.copy(part = "label")), t).hidden)
    }
    // Cascade.of keeps a target by its general form, a key no selector names as any key: what it gives
    // must be what a cascade keeping nothing would, whatever was asked before
    @Test
    fun keptLooksAreEachKeysOwn() {
        val t = ThemeReader.theme(
            withStyle(
                "<style select=\"qwerty/q\" fill=\"#00ff00\"/>\n" +
                    "<style select=\"@flat qwerty/w\" radius=\"7dp\"/>\n" +
                    "<style select=\"qwerty/e label\" color=\"#123456\"/>",
            ),
        ).ok()
        fun key(name: String, part: String? = null) = Selector.Target("key", "qwerty", name, null, setOf("letter"), emptySet(), part)
        val asked = listOf(key("a"), key("q"), key("w"), key("e"), key("a", "label"), key("e", "label"), key("q", "label"), key("w", "mark"))
        for (scene in listOf(emptySet(), setOf("flat"))) {
            val c = Cascade(t, scene)
            // asked in one order and the other, each against a cascade that has seen nothing
            val one = asked.associateWith { c.of(it) }
            val other = Cascade(t, scene).let { o -> asked.reversed().associateWith { o.of(it) } }
            for (k in asked) {
                val fresh = Cascade(t, scene).of(k)
                assertEquals(fresh, one[k], "$k in $scene")
                assertEquals(fresh, other[k], "$k in $scene, the other order")
            }
            // a named key and an unnamed one mixed: q's fill is q's alone
            assertEquals(Fill.Solid(ColorExpr.Literal(Argb(0xFF00FF00.toInt()))), KeyLook(c.of(key("q")), t).fill)
            assertEquals(KeyLook(c.of(key("a")), t).fill, KeyLook(c.of(key("r")), t).fill)
            // named only under @flat: w is any letter elsewhere
            val wRadius = KeyLook(c.of(key("w")), t).radius.corners.first()
            if ("flat" in scene) assertEquals(7.0, wRadius) else assertEquals(KeyLook(c.of(key("a")), t).radius, KeyLook(c.of(key("w")), t).radius)
            // one key's part overridden: e's label, not a's nor q's
            assertEquals(Argb(0xFF123456.toInt()), TextLook(c.of(key("e", "label")), t).color)
            assertEquals(TextLook(c.of(key("a", "label")), t).color, TextLook(c.of(key("q", "label")), t).color)
            assertTrue(TextLook(c.of(key("a", "label")), t).color != Argb(0xFF123456.toInt()))
        }
    }
}
