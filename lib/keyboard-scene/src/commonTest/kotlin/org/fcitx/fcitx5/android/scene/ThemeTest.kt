/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes
import org.fcitx.fcitx5.android.scene.theme.Meta
import org.fcitx.fcitx5.android.scene.theme.Radius
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Twin
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
            "align=\"top-left\"", "letter-spacing=\"0.1em\"", "stroke=\"1dp #000000\"", "text-shadow=\"0 1dp 2dp #80000000\"",
            "text-shadow=\"none\"", "inner-shadow=\"none\"", "side-padding=\"2% 5%\"", "bottom-padding=\"1%\"",
            "underline=\"none\"", "cursor-color=\"system(primary, @keyText)\"",
            "font-han=\"system\"", "font-latin=\"system\"", "space-content=\"ime-name\"", "return-icon-send=\"icon(send)\"",
            "inner-shadow=\"0 1dp 2dp @keyShadow\"", "depth=\"2dp\"", "depth-color=\"@keyShadow\"", "tail=\"true\"", "offset=\"-4dp\"",
            "height=\"38% 52%\"", "width=\"24dp\"", "side-padding=\"8dp 48dp\"", "bottom-padding=\"0 0\"", "gap=\"4dp\"",
            "label-style=\"dot\"", "underline=\"1dp @keyText\"", "cursor-color=\"@keyText\"", "blur=\"12dp\"",
            "ink-color=\"@keyText\"", "ink-width=\"3dp\"", "grid-color=\"@divider\"", "grid=\"false\"",
            "transition=\"150ms ease-out\"", "press-scale=\"95%\"", "press-sink=\"2dp\"", "appear=\"scale 150ms\"",
            "effect=\"particles(star.png, 12, 24dp, 600ms)\"", "sticker=\"cat.png\"",
        )
        assertEquals(Values.RESERVED.size, reserved.map { it.substringBefore('=') }.toSet().size + 5, "every one tried but the other return icons")
        for (attr in reserved) {
            val w = warnings(withStyle("<style select=\"key\" $attr/>"))
            assertTrue(w.single().contains("${attr.substringBefore('=')}= is not supported"), "$attr: $w")
        }
        // with a property of today: that one works
        val t = ThemeReader.theme(withStyle("<style select=\"key\" fill=\"#123456\" depth=\"2dp\"/>")).getOr { error("$it") }
        assertTrue(t.styles.last().values.keys == setOf("fill"))
        // a later stage's value of a property of today
        for (attr in listOf("shape=\"path(M0 0 L1 0 L1 1 Z)\"", "shape=\"bubble\"", "size=\"120%\"", "color=\"follow\"")) {
            assertTrue(warnings(withStyle("<style select=\"preview\" $attr/>")).single().contains("is not supported"), attr)
        }
        // of the wrong form, or misspelt: refused as ever
        assertTrue(problems(withStyle("<style select=\"key\" align=\"middle\"/>")).single().contains("align"))
        assertTrue(problems(withStyle("<style select=\"key\" transition=\"fast\"/>")).isNotEmpty())
        assertTrue(problems(withStyle("<style select=\"key\" alignn=\"center\"/>")).any { it.contains("unknown attribute alignn=") })
        assertTrue(problems(withStyle("<style select=\"key\" shape=\"blob\"/>")).isNotEmpty())
        // colours in a reserved property: a colour, naming colours there
        for (attr in listOf("ink-color=\"rgb(0,0,0)\"", "cursor-color=\"nonsense(x)\"", "stroke=\"1dp nonsense\"", "text-shadow=\"0 1dp 2dp\"")) {
            assertTrue(problems(withStyle("<style select=\"key\" $attr/>")).single().contains("is not a value of"), attr)
        }
        assertEquals(listOf("<style>: @keyShadw is no colour"), problems(withStyle("<style select=\"key\" depth-color=\"@keyShadw\"/>")))
        // a reserved value of the wrong form, an empty path
        assertTrue(problems(withStyle("<style select=\"preview\" size=\"12\"/>")).any { it.contains("size=\"12\" is not") })
        assertTrue(problems(withStyle("<style select=\"preview\" shape=\"path( )\"/>")).any { it.contains("shape=\"path( )\" is not") })
        // elements: a sound pack, whatever it holds; an animated picture
        val sounds = "<sounds><sound key=\"key\" src=\"tap.ogg\"/></sounds>"
        assertTrue(warnings(source.replace("</theme>", "$sounds\n</theme>")).single().contains("<sounds> is not supported"))
        assertTrue(problems(source.replace("</theme>", "<noises/>\n</theme>")).single().contains("<noises> is not allowed"))
    }

    @Test
    fun aThemeOfEveryLaterStageImports() {
        // reserved.xml: theme-4.xsd's example of T1 (xmllint-checked), names of T4a–A3 and a sound pack in it
        val t = ThemeReader.theme(testData("reserved.xml"))
        assertTrue(t is Parsed.Ok, "${t.problems()}")
        assertEquals(setOf(false, true), t.value.variants)
        assertEquals(Argb(0xFF303030.toInt()), t.value.variant(dark = true).colors["key"])
        assertEquals("0.6.0", t.value.meta?.minApp)
        // T5 made its scenes and the keyboard's size work: no warning of them
        val ignored = listOf(
            "83|<sounds>", "79|transition=", "79|clipboard", "79|:first", "80|align=", "80|stroke=", "80|depth=",
            "80|press-scale=", "80|effect=", "80|sticker=", "81|shape=\"path(M0 0 L1 0 L1 1 Z)\"", "81|size=\"120%\"", "81|tail=",
            "81|appear=",
        ).map { "$it is not supported by this version, ignored" }
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

    // 060-render-p3 T5: what is typed as scenes, the keyboard's size

    @Test
    fun typedScenesSelectAsTheEnvironmentIs() {
        val t = ThemeReader.theme(
            withStyle(
                "<style select=\"@im=wubi key\" fill=\"#000001\"/>\n" +
                    "<style select=\"@field=password key\" fill=\"#000002\"/>\n" +
                    "<style select=\"@keyboard=nine key\" fill=\"#000003\"/>",
            ),
        ).getOr { error("$it") }
        val q = Selector.Target("key", "qwerty", "q", null, setOf("letter"))
        fun fill(env: Env) = (KeyLook(Cascade(t, env.scenes).of(q), t).fill as Fill.Solid).color
        val plain = Fixtures.portrait
        val before = fill(plain)
        val wubi = plain.copy(input = Input(ime = "table", table = "wubi"))
        assertEquals(ColorExpr.parse("#000001"), fill(wubi))
        // a table by its kind is no table by its name
        assertEquals(before, fill(plain.copy(input = Input(ime = "table", table = "cangjie"))))
        assertEquals(ColorExpr.parse("#000002"), fill(plain.copy(input = Input(field = "password"))))
        assertEquals(before, fill(plain.copy(input = Input(field = "text"))))
        assertEquals(ColorExpr.parse("#000003"), fill(plain.copy(board = "nine")))
        assertEquals(before, fill(plain.copy(board = "qwerty")))
        // as specific as a class: the later of two that both hold, the password's
        assertEquals(ColorExpr.parse("#000002"), fill(wubi.copy(input = wubi.input.copy(field = "password"))))
        // no warning: they work now
        assertEquals(emptyList(), warnings(withStyle("<style select=\"@im=wubi @field=url @keyboard=k18 key\" fill=\"#000001\"/>")))
    }

    // what the input method, the field and the board make of a theme's cascades: none more where it names
    // none of them, and for one that does, at most 8 a board in one screen (060-render-p3 T5), each of the
    // input methods and fields there is met
    @Test
    fun cascadesAreKeptByTheScenesAThemeNames() {
        val ims = listOf(Input(ime = "pinyin"), Input(ime = "shuangpin"), Input(ime = "english"), Input(ime = "table", table = "wubi"),
            Input(ime = "table", table = "cangjie"), Input(ime = "handwriting"), Input(ime = null))
        val fields = listOf("text", "password", "url", "email", "number", null)
        fun made(theme: Theme, board: String): Int {
            val c = Cascades(theme)
            for (im in ims) for (field in fields) for (dark in listOf(false, true)) {
                val env = Fixtures.portrait.copy(input = im.copy(field = field), board = board, dark = dark)
                c.of(env.scenes)
                c.of(env.scenes - "flat")
            }
            return c.size
        }
        // material-light names no scene of these: one, whatever is typed
        assertEquals(1, made(Fixtures.materialLight, "qwerty"))
        val named = ThemeReader.theme(
            withStyle(
                "<style select=\"@im=wubi key\" fill=\"#000001\"/>\n<style select=\"@field=password key, @field=url key\" fill=\"#000002\"/>\n" +
                    "<style select=\"@keyboard=nine key\" fill=\"#000003\"/>",
            ),
        ).getOr { error("$it") }
        for (board in listOf("qwerty", "nine")) {
            // wubi or not, a password, a URL or neither; light or dark the same, the theme naming no @dark
            val n = made(named, board)
            assertEquals(2 * 3, n, board)
            assertTrue(n <= 8, "$board: $n")
        }
    }

    @Test
    fun theKeyboardsSizeIsReadAndChecked() {
        fun sizes(style: String) = ThemeReader.theme(withStyle(style)).getOr { error("$it") }.keyboardSizes
        assertEquals(KeyboardSizes(), Fixtures.materialLight.keyboardSizes)
        assertEquals(
            KeyboardSizes(Twin(40.0, 40.0), Twin(0.0, 24.5), Twin(4.0, 0.0)),
            sizes("<style select=\"keyboard\" height=\"40%\" side-padding=\"0 24.5dp\" bottom-padding=\"4dp 0\"/>"),
        )
        assertEquals(emptyList(), warnings(withStyle("<style select=\"keyboard\" height=\"10% 90%\"/>")))
        // the later style's, a property at a time
        val two = "<style select=\"keyboard\" height=\"30% 40%\" side-padding=\"8dp\"/>\n<style select=\"keyboard\" height=\"35% 45%\"/>"
        assertEquals(KeyboardSizes(Twin(35.0, 45.0), Twin(8.0, 8.0)), sizes(two))
        // out of the settings' ranges, of another unit, three values: refused
        for (bad in listOf("height=\"95%\"", "height=\"9%\"", "height=\"300dp\"", "side-padding=\"301dp\"", "side-padding=\"5%\"",
            "bottom-padding=\"101dp\"", "bottom-padding=\"-1dp\"", "height=\"30% 40% 50%\"", "height=\"\"")) {
            assertTrue(problems(withStyle("<style select=\"keyboard\" $bad/>")).first().contains(bad.substringBefore('=')), bad)
        }
        // under a scene: of its form, ignored with a warning; one size a theme
        val scened = withStyle("<style select=\"@landscape keyboard\" height=\"40%\"/>")
        assertTrue(warnings(scened).single().contains("height= of a keyboard under a scene is ignored"))
        assertEquals(KeyboardSizes(), sizes("<style select=\"@landscape keyboard\" height=\"40%\"/>"))
        assertTrue(problems(withStyle("<style select=\"@landscape keyboard\" height=\"95%\"/>")).isNotEmpty())
        // another widget's: a later stage's (the bar's height, T6b), as before
        assertTrue(warnings(withStyle("<style select=\"bar\" height=\"40dp\"/>")).single().contains("height= is not supported"))
        // with today's properties, the keyboard's fill still works
        val both = ThemeReader.theme(withStyle("<style select=\"keyboard\" fill=\"#123456\" height=\"40%\"/>")).getOr { error("$it") }
        assertEquals(setOf("fill", "height"), both.styles.last().values.keys)
    }
}
