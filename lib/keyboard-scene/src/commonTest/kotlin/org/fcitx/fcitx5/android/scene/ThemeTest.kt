/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Border
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes
import org.fcitx.fcitx5.android.scene.theme.Meta
import org.fcitx.fcitx5.android.scene.theme.Radius
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Shadow
import org.fcitx.fcitx5.android.scene.theme.Shown
import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Twin
import org.fcitx.fcitx5.android.scene.theme.Value
import org.fcitx.fcitx5.android.scene.theme.Values
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    // 060-render-p3 T1: the format's frame

    private fun warnings(s: String): List<String> {
        val t = ThemeReader.theme(s)
        assertTrue(t is Parsed.Ok, "${t.problems()}")
        return t.warnings().map { it.message }
    }

    private val root = "dark=\"false\""

    @Test
    fun lightAndDarkInOneTheme() {
        val both = source.replace(root, "variants=\"light dark\"").replace(
            "</colors>",
            "</colors>\n  <colors variant=\"dark\">\n    <color name=\"key\" value=\"#101010\"/>\n  </colors>",
        )
        val t = ThemeReader.theme(both).getOr { error("$it") }
        assertEquals(setOf(false, true), t.variants)
        assertEquals(false, t.dark)
        val light = Fixtures.materialLight
        assertEquals(light.colors, t.colors, "the light version is drawn until one is picked")
        val dark = t.variant(dark = true)
        assertEquals(true, dark.dark)
        assertEquals(Argb(0xFF101010.toInt()), dark.colors["key"])
        assertEquals(light.colors["keyText"], dark.colors["keyText"], "not given: the light one's")
        assertEquals(t.colors, dark.variant(dark = false).colors)
        // dark="true" is variants="dark"; a theme of one version is itself either way
        val onlyDark = ThemeReader.theme(source.replace(root, "dark=\"true\"")).getOr { error("$it") }
        assertEquals(setOf(true), onlyDark.variants)
        assertTrue(onlyDark.variant(dark = false) === onlyDark)
        assertEquals(setOf(true), ThemeReader.theme(source.replace(root, "variants=\"dark\"")).getOr { error("$it") }.variants)
    }

    @Test
    fun darkColoursInheritButAreChecked() {
        fun withDark(colors: String, variants: String = "variants=\"light dark\"") = source.replace(root, variants)
            .replace("</colors>", "</colors>\n<colors variant=\"dark\">$colors</colors>")
        // a light colour naming another resolves against the dark one: keyBorder is @keyShadow
        val t = ThemeReader.theme(withDark("<color name=\"keyShadow\" value=\"#123456\"/>")).getOr { error("$it") }.variant(true)
        assertEquals(Argb(0xFF123456.toInt()), t.colors["keyBorder"])
        assertTrue(problems(withDark("<color name=\"newOne\" value=\"#123456\"/>")).any { it.contains("not in the light") })
        assertTrue(problems(withDark("<color name=\"key\" value=\"@nothing\"/>")).any { it == "@nothing is no colour in the dark colours" })
        assertTrue(problems(withDark("", variants = "variants=\"light\"")).any { it.contains("no dark variant") })
        assertTrue(problems(source.replace(root, "")).any { it.contains("variants= is required") })
        assertTrue(problems(source.replace(root, "dark=\"false\" variants=\"light\"")).any { it.contains("both") })
        assertTrue(problems(source.replace(root, "variants=\"dusk\"")).isNotEmpty())
    }

    @Test
    fun systemColours() {
        val e = assertNotNull(ColorExpr.parse("system(primary, #ff0000)"))
        assertEquals(Argb(0xFFFF0000.toInt()), e.resolve({ null }), "none given: the fallback")
        assertEquals(Argb(0xFF00FF00.toInt()), e.resolve({ null }, mapOf("primary" to Argb(0xFF00FF00.toInt()))::get))
        assertNotNull(ColorExpr.parse("system(accent1-0, @key)"))
        assertNotNull(ColorExpr.parse("system(neutral2-1000, darker(@key, 10%))"))
        assertNotNull(ColorExpr.parse("darker(system(on-surface, #000000), 10%)"))
        assertEquals(5 * 13 + 9, ColorExpr.SYSTEM_NAMES.size)
        val names = listOf("primary", "on-primary", "secondary-container", "surface-container", "surface-container-high",
            "surface-container-highest", "surface-bright", "on-surface", "on-surface-variant", "accent1-0", "accent2-10",
            "accent3-50", "neutral1-500", "neutral2-1000")
        assertTrue(ColorExpr.SYSTEM_NAMES.containsAll(names))
        assertTrue(listOf("accent1-25", "accent4-0", "neutral3-100", "secondary", "on-secondary").none { it in ColorExpr.SYSTEM_NAMES })
        assertNull(ColorExpr.parse("system(accent1-25, #000000)"), "no such tone")
        assertNull(ColorExpr.parse("system(primary)"), "a fallback is required")
        assertNull(ColorExpr.parse("system(primary, system(on-primary, #000000))"))
        assertNull(ColorExpr.parse("system(primary, darker(system(on-primary, #000000), 10%))"), "nested anywhere")
        // in a theme: the reader's given colour, else the fallback; the fallback's variable checked
        val s = source.replace("<color name=\"key\" value=\"#fbfbfc\"/>", "<color name=\"key\" value=\"system(surface-bright, #fbfbfc)\"/>")
        assertEquals(Argb(0xFFFBFBFC.toInt()), ThemeReader.theme(s).getOr { error("$it") }.colors["key"])
        val given = ThemeReader.theme(s, systemColors = SystemColors(mapOf("surface-bright" to Argb(0xFF0000FF.toInt())), emptyMap()))
            .getOr { error("$it") }
        assertEquals(Argb(0xFF0000FF.toInt()), given.colors["key"])
        assertTrue(problems(source.replace("value=\"#fbfbfc\"", "value=\"system(primary, @nothing)\"")).any { it == "@nothing is no colour" })
        assertTrue(problems(source.replace("value=\"#fbfbfc\"", "value=\"system(primary-ish, #000000)\"")).isNotEmpty())
    }

    @Test
    fun metadataAndMinApp() {
        val meta = "<theme xmlns=\"https://youmo-ime/theme/4\" name=\"MaterialLight\" $root version=\"4.0\">\n" +
            "  <meta author=\"Ann\" version=\"1.2\" license=\"CC-BY-4.0\" min-app=\"0.6.0\" preview=\"preview.png\"/>"
        val s = source.replace("<theme xmlns=\"https://youmo-ime/theme/4\" name=\"MaterialLight\" $root version=\"4.0\">", meta)
        val t = ThemeReader.theme(s).getOr { error("$it") }
        assertEquals(Meta("Ann", "1.2", "CC-BY-4.0", "0.6.0", "preview.png"), t.meta)
        assertTrue(ThemeReader.theme(s, appVersion = "0.6.0") is Parsed.Ok)
        assertTrue(ThemeReader.theme(s, appVersion = "0.6.1-beta2") is Parsed.Ok)
        assertTrue(ThemeReader.theme(s, appVersion = "0.5.2").problems().single().message.contains("needs the app 0.6.0"))
        assertTrue(ThemeReader.newer("0.10.0", "0.9.9"))
        assertTrue(!ThemeReader.newer("0.6.0", "0.6.0-beta1"))
        assertTrue(problems(s.replace("preview.png", "preview.jpg")).isNotEmpty())
        assertTrue(problems(s.replace("Ann", "A".repeat(65))).isNotEmpty())
        assertTrue(problems(s.replace("0.6.0", "six")).isNotEmpty())
        assertTrue(problems(s.replace("author=", "authr=")).any { it.contains("unknown attribute") })
        assertTrue(problems(s.replace("</colors>", "</colors>\n<meta/>")).any { it.contains("given twice") })
    }

    @Test
    fun reservedNamesWarnNotFail() {
        // each of a later stage's style properties, of its form: a warning, the style kept
        val reserved = listOf(
            "side-padding=\"2% 5%\"", "bottom-padding=\"1%\"",
            "underline=\"none\"", "cursor-color=\"system(primary, @keyText)\"",
            "height=\"38% 52%\"", "width=\"24dp\"", "side-padding=\"8dp 48dp\"", "bottom-padding=\"0 0\"", "gap=\"4dp\"",
            "label-style=\"dot\"", "underline=\"1dp @keyText\"", "cursor-color=\"@keyText\"", "blur=\"12dp\"",
            "ink-color=\"@keyText\"", "ink-width=\"3dp\"", "grid-color=\"@divider\"", "grid=\"false\"",
            "transition=\"150ms ease-out\"", "press-scale=\"95%\"", "press-sink=\"2dp\"", "appear=\"scale 150ms\"",
            "effect=\"particles(star.png, 12, 24dp, 600ms)\"", "sticker=\"cat.png\"",
        )
        assertEquals(Values.RESERVED.size, reserved.map { it.substringBefore('=') }.toSet().size, "every one tried")
        for (attr in reserved) {
            val w = warnings(withStyle("<style select=\"key\" $attr/>"))
            assertTrue(w.single().contains("${attr.substringBefore('=')}= is not supported"), "$attr: $w")
        }
        // with a property of today: that one works
        val t = ThemeReader.theme(withStyle("<style select=\"key\" fill=\"#123456\" press-sink=\"2dp\"/>")).getOr { error("$it") }
        assertTrue(t.styles.last().values.keys == setOf("fill"))
        // no later stage's value of a property of today is left: T4b's path(…), bubble and a bubble's size in %
        // read (ThemeTest's shapes), T7a's color="follow" the navigation bar's (WindowLooksTest)
        // of the wrong form, or misspelt: refused as ever
        assertTrue(problems(withStyle("<style select=\"key\" color=\"#000000\" align=\"middle\"/>")).single().contains("align"))
        assertTrue(problems(withStyle("<style select=\"key\" transition=\"fast\"/>")).isNotEmpty())
        assertTrue(problems(withStyle("<style select=\"key\" alignn=\"center\"/>")).any { it.contains("unknown attribute alignn=") })
        assertTrue(problems(withStyle("<style select=\"key\" shape=\"blob\"/>")).isNotEmpty())
        // colours in a reserved property: a colour, naming colours there
        for (attr in listOf("ink-color=\"rgb(0,0,0)\"", "cursor-color=\"nonsense(x)\"")) {
            assertTrue(problems(withStyle("<style select=\"key\" $attr/>")).single().contains("is not a value of"), attr)
        }
        assertEquals(listOf("<style>: @keyShadw is no colour"), problems(withStyle("<style select=\"key\" ink-color=\"@keyShadw\"/>")))
        // elements: a sound pack, whatever it holds; an animated picture
        val sounds = "<sounds><sound key=\"key\" src=\"tap.ogg\"/></sounds>"
        assertTrue(warnings(source.replace("</theme>", "$sounds\n</theme>")).single().contains("<sounds> is not supported"))
        assertTrue(problems(source.replace("</theme>", "<noises/>\n</theme>")).single().contains("<noises> is not allowed"))
    }

    // 060-render-p3 T4b: a key's shape and cap, the bubble's shape, size, tail and place, read; of the wrong
    // form or out of range refused; where they do nothing, a warning
    @Test
    fun theCapAndTheBubbleRead() {
        fun read(attr: String, select: String = "key") = ThemeReader.theme(withStyle("<style select=\"$select\" $attr/>")).ok().styles.last().values
        fun refused(attr: String, select: String = "key") =
            assertTrue(problems(withStyle("<style select=\"$select\" $attr/>")).any { it.contains(attr) }, attr)
        val path = assertIs<Value.OfPath>(read("shape=\"path(M0 0 H1 V1 H0 Z)\"")["shape"]).path
        assertEquals(5, path.segments.size, "M H V H Z")
        assertEquals(Value.Word("bubble"), read("shape=\"bubble\"", "preview")["shape"])
        assertEquals(Value.OfShadow(Shadow(0.0, 1.0, 2.0, ColorExpr.parse("#40000000"))), read("inner-shadow=\"0 1dp 2dp #40000000\"")["inner-shadow"])
        assertEquals(Value.OfShadow(Shadow.NONE), read("inner-shadow=\"none\"")["inner-shadow"])
        for (dp in listOf(0.0, 2.5, 8.0)) assertEquals(Value.Number(dp), read("depth=\"${SceneText.n(dp)}dp\"")["depth"])
        assertEquals(Value.OfColor(assertNotNull(ColorExpr.parse("@keyShadow"))), read("depth-color=\"@keyShadow\"")["depth-color"])
        assertEquals(Value.Flag(false), read("tail=\"false\"", "preview")["tail"])
        assertEquals(Value.Flag(true), read("tail=\"true\"", "preview")["tail"])
        for (dp in listOf(-24.0, 0.0, 24.0)) assertEquals(Value.Number(dp), read("offset=\"${SceneText.n(dp)}dp\"", "preview")["offset"])
        for (pct in listOf(100.0, 120.0, 160.0)) assertEquals(Value.Percent(pct), read("size=\"${SceneText.n(pct)}%\"", "preview")["size"])
        assertEquals(Value.Number(14.0), read("size=\"14sp\"")["size"], "a text's size as ever")
        listOf(
            "shape=\"path()\"", "shape=\"path(L1 1 L0 1 Z)\"", "shape=\"path(M0 0 L2 0 L1 1 Z)\"", "shape=\"blob\"",
            "inner-shadow=\"0 17dp 0 #000000\"", "inner-shadow=\"0 1dp\"", "depth=\"9dp\"", "depth=\"-1dp\"", "depth=\"2px\"",
            "depth-color=\"nonsense(x)\"", "tail=\"yes\"", "offset=\"25dp\"", "offset=\"-25dp\"", "size=\"99%\"", "size=\"161%\"", "size=\"12\"",
        ).forEach { refused(it, "preview") }
        assertEquals(listOf("<style>: @keyShadw is no colour"), problems(withStyle("<style select=\"key\" depth-color=\"@keyShadw\"/>")))
        // where they do nothing: a bubble's on a key, a cap on the bubble, a radius beside a path
        assertTrue(warnings(withStyle("<style select=\"preview\" shape=\"bubble\" tail=\"false\" size=\"120%\" offset=\"-4dp\"/>")).isEmpty())
        val onAKey = warnings(withStyle("<style select=\"key\" shape=\"bubble\" tail=\"false\" size=\"120%\" offset=\"-4dp\"/>"))
        assertEquals(3, onAKey.count { it.contains("only the preview") })
        assertEquals(1, onAKey.count { it.startsWith("size= in % is ignored") })
        assertTrue(warnings(withStyle("<style select=\"preview\" depth=\"2dp\"/>")).single().contains("only a key has a cap"))
        assertTrue(warnings(withStyle("<style select=\"key\" shape=\"path(M0 0 H1 V1 Z)\" radius=\"4dp\"/>")).single().contains("radius= does nothing"))
        // a tail beside a shape that is no bubble
        for (shape in listOf("rect", "path(M0 0 H1 V1 Z)")) {
            assertTrue(warnings(withStyle("<style select=\"preview\" shape=\"$shape\" tail=\"true\"/>")).single().contains("only a bubble has a tail"), shape)
        }
        // a path or an inner shadow on what has no face drawn (the keyboard's a plain fill, the bar's the app's)
        for (w in listOf("keyboard", "bar", "candidate", "handle", "panel")) {
            val faceless = warnings(withStyle("<style select=\"$w\" shape=\"path(M0 0 H1 V1 Z)\" inner-shadow=\"0 1dp 0 #40000000\"/>"))
            assertEquals(2, faceless.count { it.contains("on $w does nothing: only a key, a popup, the preview and a menu have a face drawn") }, "$w: $faceless")
        }
        for (w in listOf("key", "popup", "preview", "menu")) {
            assertTrue(warnings(withStyle("<style select=\"$w\" shape=\"path(M0 0 H1 V1 Z)\" inner-shadow=\"0 1dp 0 #40000000\"/>")).isEmpty(), w)
        }
        // a size in % elsewhere than the preview alone ignored: the key's sp size it would knock out kept
        val sized = ThemeReader.theme(withStyle("<style select=\"key\" size=\"14sp\"/><style select=\"preview, key.letter\" size=\"120%\"/>")).ok()
        assertNull(sized.styles.last().values["size"], "dropped")
        val sizedA = Cascade(sized, emptySet()).of(Selector.Target("key", "qwerty", "a", "letter", setOf("letter"), emptySet()))
        assertEquals(Value.Number(14.0), sizedA["size"], "the key's sp kept")
        // a later stage's widget beside the preview (warned of, selecting nothing) does not drop it
        val withReserved = withStyle("<style select=\"preview, clipboard\" size=\"120%\"/>")
        assertEquals(Value.Percent(120.0), ThemeReader.theme(withReserved).ok().styles.last().values["size"])
        assertTrue(warnings(withReserved).none { it.startsWith("size= in % is ignored") })
        // the cascade: unset, nothing; the band's colour a solid fill's darker, else a translucent black
        val t = ThemeReader.theme(withStyle("<style select=\"key\" depth=\"3dp\"/><style select=\"key.letter\" fill=\"linear(90deg, @key 0%, @altKey 100%)\"/>")).ok()
        val c = Cascade(t, emptySet())
        val letter = KeyLook(c.of(Selector.Target("key", "qwerty", "a", "letter", setOf("letter"), emptySet())), t)
        assertEquals(3.0, letter.depth)
        assertEquals(Argb(0x40000000), letter.depthColor, "a gradient: a translucent black")
        val plain = KeyLook(Cascade(ThemeReader.theme(source).ok(), emptySet()).of(Selector.Target("key", "qwerty", "a", "letter", setOf("letter"), emptySet())), t)
        assertEquals(0.0, plain.depth)
        assertNull(plain.path)
        assertNull(plain.innerShadow)
        assertEquals(1.0, plain.size)
        assertEquals(0.0, plain.offset)
        assertTrue(plain.tail)
        val keyFill = (plain.fill as Fill.Solid).color
        assertEquals(t.color(ColorExpr.Fn("darker", listOf(keyFill), 20.0)), plain.depthColor, "the fill's 20% darker")
    }

    // 060-render-p3 T4a: the text on a key, read; of the wrong form or out of its range refused
    @Test
    fun theTextOnAKeyReads() {
        val fonts = source.replace("</colors>", "</colors>\n<font name=\"kai\" src=\"kai.ttf\"/>")
        fun read(attr: String) = ThemeReader.theme(fonts.replace("</theme>", "<style select=\"key\" $attr/>\n</theme>")).ok().styles.last().values
        for (a in listOf("center", "top-left", "top-center")) assertEquals(Value.Word(a), read("align=\"$a\"")["align"])
        assertEquals(Value.Number(0.1), read("letter-spacing=\"0.1em\"")["letter-spacing"])
        assertEquals(Value.Number(-0.1), read("letter-spacing=\"-0.1em\"")["letter-spacing"])
        assertEquals(Value.Number(0.5), read("letter-spacing=\"0.5em\"")["letter-spacing"])
        assertEquals(Value.OfBorder(Border(1.5, ColorExpr.parse("#000000"))), read("stroke=\"1.5dp #000000\"")["stroke"])
        assertEquals(Value.OfBorder(Border(3.0, ColorExpr.parse("@keyText"))), read("stroke=\"3dp @keyText\"")["stroke"])
        assertEquals(Value.OfShadow(Shadow(0.0, 1.0, 2.0, ColorExpr.parse("#80000000"))), read("text-shadow=\"0 1dp 2dp #80000000\"")["text-shadow"])
        assertEquals(Value.OfShadow(Shadow.NONE), read("text-shadow=\"none\"")["text-shadow"])
        assertEquals(Value.Word("kai"), read("font-han=\"kai\"")["font-han"])
        assertEquals(Value.Word("system"), read("font-latin=\"system\"")["font-latin"])
        assertEquals(Value.OfShown(Shown.ImeName), read("space-content=\"ime-name\"")["space-content"])
        assertEquals(Value.OfShown(Shown.None), read("space-content=\"none\"")["space-content"])
        assertEquals(Value.OfShown(Shown.Text("空格")), read("space-content=\"text(空格)\"")["space-content"])
        assertEquals(Value.OfShown(Shown.Icon("space")), read("space-content=\"icon(space)\"")["space-content"])
        for (action in Values.RETURN_ACTIONS) {
            assertEquals(Value.OfShown(Shown.Text("发送")), read("return-icon-$action=\"text(发送)\"")["return-icon-$action"])
            assertEquals(Value.OfShown(Shown.Icon("send")), read("return-icon-$action=\"icon(send)\"")["return-icon-$action"])
        }
        // a key's text look is inherited by its parts, as CSS's; the hint takes no `hidden` from its key
        val t = ThemeReader.theme(withStyle("<style select=\"key\" stroke=\"1dp #000000\" hidden=\"true\"/>")).ok()
        val c = Cascade(t, emptySet())
        val q = Selector.Target("key", "qwerty", "q", null, setOf("letter"))
        assertEquals(Border(1.0, ColorExpr.parse("#000000")), TextLook(c.of(q.copy(part = "mark")), t).stroke)
        assertTrue(TextLook(c.of(q.copy(part = "label")), t).hidden)
        assertTrue(!TextLook(c.of(q.copy(part = "hint")), t).hidden)
        val own = ThemeReader.theme(withStyle("<style select=\"key hint\" hidden=\"true\"/>")).ok()
        assertTrue(TextLook(Cascade(own, emptySet()).of(q.copy(part = "hint")), own).hidden)
        // none set: today's look
        val none = TextLook(Cascade(Fixtures.materialLight, emptySet()).of(q.copy(part = "label")), Fixtures.materialLight)
        assertEquals(listOf<Any?>(Values.CENTER, 0.0, null, null, null, null, null, null), listOf(none.align, none.letterSpacing, none.fontHan, none.fontLatin, none.stroke, none.textShadow, none.spaceContent, none.returnIcon("send")))
        // refused
        for (attr in listOf(
            "align=\"middle\"", "align=\"top-right\"", "letter-spacing=\"0.6em\"", "letter-spacing=\"-0.2em\"", "letter-spacing=\"0.1\"",
            "letter-spacing=\"1px\"", "stroke=\"4dp #000000\"", "stroke=\"none\"", "stroke=\"1dp\"", "stroke=\"1dp nonsense\"",
            "text-shadow=\"0 1dp 2dp\"", "text-shadow=\"0 17dp 0 #000000\"", "text-shadow=\"0 1dp -2dp #000000\"",
            "space-content=\"text()\"", "space-content=\"text(${"x".repeat(33)})\"", "space-content=\"icon(Send)\"", "space-content=\"send\"",
            "return-icon-send=\"none\"", "return-icon-done=\"ime-name\"", "font-han=\"1kai\"",
        )) {
            assertTrue(problems(fonts.replace("</theme>", "<style select=\"key\" color=\"#000000\" $attr/>\n</theme>")).single().contains("is not a value of"), attr)
        }
        assertTrue(problems(withStyle("<style select=\"key\" return-icon-previous=\"icon(send)\"/>")).any { it.contains("unknown attribute return-icon-previous=") })
        assertTrue(problems(withStyle("<style select=\"key\" font-han=\"fancy\"/>")).single().contains("font-han=\"fancy\" is no <font>"))
        assertTrue(problems(withStyle("<style select=\"key\" font-latin=\"fancy\"/>")).single().contains("font-latin=\"fancy\" is no <font>"))
        // -0em as 0: one value
        assertEquals(Value.Number(0.0), read("letter-spacing=\"-0em\"")["letter-spacing"])
        // an icon the app has none of: refused, by its name
        for (name in Icons.ALL) assertEquals(Value.OfShown(Shown.Icon(name)), read("space-content=\"icon($name)\"")["space-content"])
        assertEquals(
            "<style>: return-icon-send=\"icon(rocket)\" is no icon of the app's",
            problems(withStyle("<style select=\"key\" return-icon-send=\"icon(rocket)\"/>")).single(),
        )
        // on a part they are not read from: a warning, the theme read
        val elsewhere = warnings(withStyle("<style select=\"key mark, */enter label\" align=\"top-left\" space-content=\"none\" return-icon-go=\"text(Go)\"/>"))
        assertEquals(
            listOf(
                "align= on a key's mark does nothing: it is read from its label",
                "space-content= on a key's mark does nothing: it is read from its label",
                "return-icon-go= on a key's mark does nothing: it is read from its icon",
                "return-icon-go= on a key's label does nothing: it is read from its icon",
            ),
            elsewhere,
        )
        assertTrue(warnings(withStyle("<style select=\"key label\" align=\"top-left\"/><style select=\"*/enter icon\" return-icon-go=\"text(Go)\"/>")).isEmpty())
    }

    @Test
    fun aThemeOfEveryLaterStageImports() {
        // reserved.xml: theme-4.xsd's example of T1 (xmllint-checked), T4a's, T4b's and T5's names (read now), T6a–A3's and a sound pack in it
        val t = ThemeReader.theme(testData("reserved.xml"))
        assertTrue(t is Parsed.Ok, "${t.problems()}")
        assertEquals(setOf(false, true), t.value.variants)
        assertEquals(Argb(0xFF303030.toInt()), t.value.variant(dark = true).colors["key"])
        assertEquals("0.6.0", t.value.meta?.minApp)
        // T5 made its scenes and the keyboard's size work: no warning of them
        val ignored = listOf(
            "83|<sounds>", "79|transition=", "79|clipboard", "79|:first",
            "80|press-scale=", "80|effect=", "80|sticker=", "81|appear=",
        ).map { "$it is not supported by this version, ignored" } +
            // its preview a path with a tail, which only a bubble has
            "81|tail= does nothing beside a shape that is no bubble: only a bubble has a tail"
        assertEquals(ignored, t.warnings().map { "${it.line}|${it.message}" })
        assertEquals(KeyboardSizes(height = Twin(38.0, 52.0), sidePadding = Twin(8.0, 48.0)), t.value.keyboardSizes)
    }

    @Test
    fun scenesPartsAndWidgetsOfLaterStages() {
        fun s(t: String) = assertNotNull(Selector.parse(t), t)
        assertEquals(setOf("im=wubi", "field=password"), s("@im=wubi @field=password key").scenes)
        // T5's: they select (typedScenesSelectAsTheEnvironmentIs)
        assertNull(s("@keyboard=nine key.letter").reserved)
        assertNull(Selector.parse("@field=phone key"))
        assertNull(Selector.parse("@im=Wubi key"))
        assertNull(Selector.parse("@colour=red key"))
        for (w in Selector.RESERVED_WIDGETS) assertEquals(w, s(w).reserved)
        // T7a's: they select (WindowLooksTest)
        for (w in Selector.WINDOW_WIDGETS) assertNull(s(w).reserved, w)
        assertEquals("candidate comment", s("candidate comment").reserved)
        assertEquals(":first", s("candidate:first").reserved)
        assertNull(Selector.parse("candidate mark"))
        assertNull(Selector.parse("toolbar label"))
        assertNull(Selector.parse("key:first"), ":first is a candidate's")
        assertNull(s("key:pressed").reserved)
        assertNull(s("@dark key").reserved)
        // such a style reads, warns, and selects nothing yet
        val src = withStyle("<style select=\"toolbar, clipboard\" fill=\"#123456\"/>")
        assertEquals(2, warnings(src).size)
        val t = ThemeReader.theme(src).getOr { error("$it") }
        val target = Selector.Target("key", "qwerty", "q", null, setOf("letter"))
        assertTrue(t.styles.last().selectors.none { it.matches(target, setOf("dark", "compact")) })
    }

    @Test
    fun systemColoursInStylesAndPerVariant() {
        val light = mapOf("primary" to Argb(0xFF0000AA.toInt()), "accent1-500" to Argb(0xFF00AA00.toInt()))
        val dark = mapOf("primary" to Argb(0xFF0000DD.toInt()), "accent1-500" to Argb(0xFF00AA00.toInt()))
        val sys = SystemColors(light, dark)
        val styled = withStyle(
            "<style select=\"key\" fill=\"system(primary, #111111)\" shadow=\"0 1dp 0 system(accent1-500, #222222)\"/>\n" +
                "<style select=\"popup\" fill=\"linear(90deg, system(primary, #333333) 0%, #000000 100%)\"/>",
        )
        val t = ThemeReader.theme(styled, systemColors = sys).getOr { error("$it") }
        val key = t.styles[t.styles.size - 2].values
        assertEquals(Argb(0xFF0000AA.toInt()), t.color(((key["fill"] as Value.OfFill).fill as Fill.Solid).color))
        assertEquals(Argb(0xFF00AA00.toInt()), t.color(assertNotNull((key["shadow"] as Value.OfShadow).shadow.color)))
        val stop = ((t.styles.last().values["fill"] as Value.OfFill).fill as Fill.Linear).stops[0].first
        assertEquals(Argb(0xFF0000AA.toInt()), t.color(stop))
        val none = ThemeReader.theme(styled).getOr { error("$it") }
        assertEquals(Argb(0xFF333333.toInt()), none.color(stop), "no system colours: the fallback")
        // the light <colors> take the light roles, the dark ones the dark; a style's follows the version drawn
        val both = source.replace(root, "variants=\"light dark\"")
            .replace("<color name=\"key\" value=\"#fbfbfc\"/>", "<color name=\"key\" value=\"system(primary, #fbfbfc)\"/>")
            .replace("</colors>", "</colors>\n<colors variant=\"dark\"><color name=\"bar\" value=\"system(primary, #000000)\"/></colors>")
            .replace("</theme>", "<style select=\"menu\" fill=\"system(primary, #444444)\"/>\n</theme>")
        val v = ThemeReader.theme(both, systemColors = sys).getOr { error("$it") }
        assertEquals(Argb(0xFF0000AA.toInt()), v.colors["key"])
        val d = v.variant(dark = true)
        assertEquals(Argb(0xFF0000DD.toInt()), d.colors["key"], "a light colour the dark set does not give: again, with the dark roles")
        assertEquals(Argb(0xFF0000DD.toInt()), d.colors["bar"])
        val menu = ((v.styles.last().values["fill"] as Value.OfFill).fill as Fill.Solid).color
        assertEquals(Argb(0xFF0000AA.toInt()), v.color(menu))
        assertEquals(Argb(0xFF0000DD.toInt()), d.color(menu))
        // a dark-only theme's <colors> are its dark ones
        val onlyDark = ThemeReader.theme(source.replace(root, "variants=\"dark\"")
            .replace("<color name=\"key\" value=\"#fbfbfc\"/>", "<color name=\"key\" value=\"system(primary, #fbfbfc)\"/>"), systemColors = sys)
        assertEquals(Argb(0xFF0000DD.toInt()), onlyDark.getOr { error("$it") }.colors["key"])
    }

    @Test
    fun darkCyclesAndProblemsOnce() {
        fun withDark(colors: String) = source.replace(root, "variants=\"light dark\"")
            .replace("</colors>", "</colors>\n<colors variant=\"dark\">$colors</colors>")
        // key is @keyText only in the dark, keyText @key everywhere: a cycle of the dark colours alone
        val cyc = source.replace("<color name=\"keyText\" value=\"#37474f\"/>", "<color name=\"keyText\" value=\"@key\"/>")
            .replace(root, "variants=\"light dark\"")
            .replace("</colors>", "</colors>\n<colors variant=\"dark\"><color name=\"key\" value=\"@keyText\"/></colors>")
        assertTrue(problems(cyc).isNotEmpty() && problems(cyc).all { it.endsWith("is defined by itself in the dark colours") }, "${problems(cyc)}")
        // a light colour's problem, resolved again for the dark: once
        val broken = withDark("<color name=\"bar\" value=\"#000000\"/>").replace("<color name=\"key\" value=\"#fbfbfc\"/>", "<color name=\"key\" value=\"@nothing\"/>")
        assertEquals(1, problems(broken).count { it.startsWith("@nothing is no colour") }, "${problems(broken)}")
        // two colours naming the same missing one: each its own problem
        val two = source.replace("<color name=\"key\" value=\"#fbfbfc\"/>", "<color name=\"key\" value=\"@nothing\"/>")
            .replace("<color name=\"keyText\" value=\"#37474f\"/>", "<color name=\"keyText\" value=\"@nothing\"/>")
        assertEquals(2, problems(two).count { it == "@nothing is no colour" })
    }

    @Test
    fun appVersionsCompared() {
        assertTrue(ThemeReader.newer("0.6.0", "0.5.2"))
        assertTrue(ThemeReader.newer("0.6.1", "v0.6.0-12-gabcdef"))
        assertTrue(!ThemeReader.newer("0.6.0", "0.6.0-12-gabcdef"))
        assertTrue(!ThemeReader.newer("0.6.0", "0.6"), "no patch: cannot tell")
        assertTrue(!ThemeReader.newer("0.6.0", "dev"), "garbage: cannot tell")
        assertTrue(!ThemeReader.newer("0.6.0", ""))
        assertTrue(!ThemeReader.newer("1.0.0", "0.6.0.1"))
    }

    @Test
    fun aDarkVersionWithoutDarkColoursTakesTheDarkRoles() {
        val sys = SystemColors(mapOf("primary" to Argb(0xFF0000AA.toInt())), mapOf("primary" to Argb(0xFF0000DD.toInt())))
        val s = source.replace(root, "variants=\"light dark\"")
            .replace("<color name=\"key\" value=\"#fbfbfc\"/>", "<color name=\"key\" value=\"system(primary, #fbfbfc)\"/>")
        val t = ThemeReader.theme(s, systemColors = sys).getOr { error("$it") }
        assertEquals(Argb(0xFF0000AA.toInt()), t.colors["key"])
        assertEquals(Argb(0xFF0000DD.toInt()), t.variant(dark = true).colors["key"])
        assertEquals(Argb(0xFF0000AA.toInt()), t.variant(dark = true).variant(dark = false).colors["key"])
        // a light theme has none
        assertNull(ThemeReader.theme(source).getOr { error("$it") }.darkColors)
    }

    @Test
    fun picturesAndPreviewsAreStrict() {
        val picture = "<picture src=\"bg.png\" animated=\"1\"><cut orientation=\"portrait\" file=\"p.png\"/></picture>"
        assertTrue(problems(source.replace("</colors>", "</colors>\n$picture")).single().contains("animated=\"1\""))
        // a no-break space first: a white space on JS's \s, not the JVM's; refused on both
        val nbsp = " bg.png"
        assertTrue(problems(source.replace("</colors>", "</colors>\n" + picture.replace("animated=\"1\" ", "").replace("bg.png", nbsp))).isNotEmpty())
        val meta = "<meta preview=\"${nbsp}\"/>".replace("bg.png", "p.png")
        assertTrue(problems(source.replace("  <colors>", "$meta\n  <colors>")).single().contains("preview="))
    }

    @Test
    fun problemsAreKeyedByColourAndTable() {
        // two colours on one line, both naming @nothing: two problems
        val oneLine = source.replace("<color name=\"key\" value=\"#fbfbfc\"/>", "<color name=\"key\" value=\"@nothing\"/><color name=\"x1\" value=\"@nothing\"/>")
        assertEquals(2, problems(oneLine).count { it == "@nothing is no colour" }, "${problems(oneLine)}")
        // the dark set overriding a colour with the light one's very problem: reported for each
        val both = oneLine.replace(root, "variants=\"light dark\"")
            .replace("</colors>", "</colors>\n<colors variant=\"dark\"><color name=\"key\" value=\"@nothing\"/></colors>")
        assertEquals(listOf("@nothing is no colour", "@nothing is no colour", "@nothing is no colour in the dark colours"), problems(both).filter { "@nothing" in it })
    }
}
