/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.convert
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.convertWith
import org.fcitx.fcitx5.android.scene.skin.TrimeFixtures.compose
import org.fcitx.fcitx5.android.scene.skin.TrimeFixtures.files
import org.fcitx.fcitx5.android.scene.skin.TrimeFixtures.yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RimeComposeTest {
    private fun root(c: RimeCompose.Composed) = c.root as Map<*, *>

    private fun broken(main: String, vararg others: Pair<String, String>) {
        val e = assertFailsWith<SkinError> { compose(main, *others) }
        assertEquals(SkinError.Reason.BROKEN, e.reason)
    }

    @Test
    fun aPathOfTheFileItself() {
        val r = root(
            compose(
                """
                a: {x: 1, y: 2}
                b: {__include: /a, y: 3}
                c: {__include: a}
                d: {__include: /a/x}
                """,
            ),
        )
        assertEquals(yaml("{x: 1, y: 3}"), r["b"])
        assertEquals(yaml("{x: 1, y: 2}"), r["c"])
        assertEquals("1", r["d"])
    }

    @Test
    fun anotherFileWholeOrInPart() {
        val base = "name: Base\nk: v\ndeep: {x: {y: 1}}\n"
        assertEquals(yaml("{name: Main, k: v, deep: {x: {y: 1}}}"), compose("__include: base:/\nname: Main", "base" to base).root)
        assertEquals(yaml("{y: 1, z: 2}"), root(compose("p: {__include: 'base:/deep/x', z: 2}", "base" to base))["p"])
    }

    @Test
    fun whatIsNotInThePackIsSkippedAndTold() {
        val c = compose(
            """
            a: {__include: none:/x?, k: 1}
            b: {__include: none:/x}
            s: {__include: trime:/style, k: 2}
            o: {__include: /nothing/here}
            """,
            "trime" to "style: {k: 3}",
        )
        assertEquals(yaml("{k: 1}"), root(c)["a"])
        assertEquals(yaml("{k: 2}"), root(c)["s"])
        assertEquals(listOf("none:/x", "trime:/style", "/nothing/here"), c.skipped)
    }

    @Test
    fun ownKeysOverAnInclude() {
        val base = "l: [1, 2]\nm: {a: 1, n: {p: 1}}\ns: x\n"
        val rows = listOf(
            "l/+: [3]" to "{l: [1, 2, 3], m: {a: 1, n: {p: 1}}, s: x}",
            "m/+: {b: 2}" to "{l: [1, 2], m: {a: 1, n: {p: 1}, b: 2}, s: x}",
            "l: [9]" to "{l: [9], m: {a: 1, n: {p: 1}}, s: x}",
            "m: {n: {q: 2}}" to "{l: [1, 2], m: {a: 1, n: {p: 1, q: 2}}, s: x}",
            "m/=: {b: 2}" to "{l: [1, 2], m: {b: 2}, s: x}",
            "s/+: [y]" to "{l: [1, 2], m: {a: 1, n: {p: 1}}, s: x}",
        )
        assertRows(rows, { it.first }) { (own, want) -> assertEquals(yaml(want), compose("__include: base:/\n$own", "base" to base).root) }
    }

    @Test
    fun listIndexes() {
        val rows = listOf(
            "@last" to "[a, b, z]",
            "@next" to "[a, b, c, z]",
            "@before 0" to "[z, a, b, c]",
            "@after 1" to "[a, b, z, c]",
            "@after last" to "[a, b, c, z]",
            "@before last" to "[a, b, z, c]",
            "@1" to "[a, z, c]",
            "@9" to "[a, b, c]",
            "@x" to "[a, b, c]",
            "@after 2147483647" to "[a, b, c]",
        )
        assertRows(rows, { it.first }) { (at, want) ->
            assertEquals(yaml(want), root(compose("l: [a, b, c]\n__patch: {'l/$at': z}"))["l"])
        }
        assertEquals(yaml("[z]"), root(compose("l: []\n__patch: {'l/@last': z}"))["l"])
        val keys = root(compose("keys: [{c: q}, {c: w}]\n__patch: {'keys/@1/height': 5, 'keys/@0/': {c: e}}"))["keys"]
        assertEquals(yaml("[{c: e}, {c: w, height: 5}]"), keys)
        // an index into a map, a key into a list: left as they are
        assertEquals(yaml("{m: {a: 1}, l: [1]}"), compose("m: {a: 1}\nl: [1]\n__patch: {'m/@0': 2, 'l/k': 2}").root)
    }

    @Test
    fun patchesOneOrAListAReferenceOrAMap() {
        val r = root(
            compose(
                """
                conf:
                  more:
                    keys/+: [{c: x}]
                    height: 40
                kb:
                  keys: [{c: q}]
                  __patch: conf/more
                kb2:
                  keys: [{c: q}]
                  __patch: [conf/more, {'keys/@0/c': w}, missing/patch?, 7]
                h: {__include: conf/more/height}
                """,
            ),
        )
        assertEquals(yaml("{keys: [{c: q}, {c: x}], height: 40}"), r["kb"])
        assertEquals(yaml("{keys: [{c: w}, {c: x}], height: 40}"), r["kb2"])
        // a patch kept as written where it is: its keys are edits of what it patches
        assertEquals(yaml("{keys/+: [{c: x}], height: 40}"), (r["conf"] as Map<*, *>)["more"])
        assertEquals("40", r["h"])
    }

    @Test
    fun aPatchOnAPatchedTemplate() {
        // a template patched by an optional file, then patched onto a keyboard that includes another file's
        val r = root(
            compose(
                """
                __include: base:/
                plus:
                  height: 47
                  'keys/@0/': {click: num1, __include: conf/line}
                  'keys/@after 0/': {click: num2}
                  __patch: [extra:/plus?]
                conf:
                  line: {height: 39}
                preset_keyboards:
                  default:
                    __include: base:/preset_keyboards/default
                    __patch: plus
                """,
                "base" to "preset_keyboards:\n  default:\n    keys: [{width: 0}, {click: q}]\n    height: 50\n",
                "extra" to "plus: {height: 59}\n",
            ),
        )
        val default = (r["preset_keyboards"] as Map<*, *>)["default"]
        assertEquals(yaml("{keys: [{click: num1, height: 39}, {click: num2}, {click: q}], height: 59}"), default)
    }

    @Test
    fun appendAndMerge() {
        val base = "list: [1, 2]\nmap: {a: 1}\n"
        val r = root(
            compose(
                """
                __include: base:/
                list:
                  __append: [3]
                map:
                  __merge: {b: 2}
                x: {__include: base:/list, __append: [4]}
                y: {__append: [5]}
                """,
                "base" to base,
            ),
        )
        assertEquals(yaml("[1, 2, 3]"), r["list"])
        assertEquals(yaml("{a: 1, b: 2}"), r["map"])
        assertEquals(yaml("[1, 2, 4]"), r["x"])
        assertEquals(yaml("[5]"), r["y"])
    }

    @Test
    fun aCustomFilePatchesLast() {
        val c = compose("name: Mine\nstyle: {round_corner: 4}\n", "main.custom" to "patch: {name: Yours, style/round_corner: 9}")
        assertEquals(yaml("{name: Yours, style: {round_corner: 9}}"), c.root)
        assertEquals(yaml("{a: 1}"), compose("a: 1", "main.custom" to "nothing: here").root)
    }

    @Test
    fun theTreesReadAreNotChanged() {
        val tree = SkinYaml.parse("a: &a {k: 1}\nb: {__include: /a, j: 2}\nc: *a\nl: &l [1]\nd: {__include: /l, __append: [2]}\n")
        val before = tree.toString()
        val r = RimeCompose { null }.compose(tree, "main").root as Map<*, *>
        assertEquals(tree.toString(), before)
        assertEquals(yaml("{k: 1, j: 2}"), r["b"])
        assertEquals(yaml("[1, 2]"), r["d"])
    }

    @Test
    fun aReferenceToItselfIsBroken() {
        broken("a: {__include: /a}")
        broken("a: {b: {__include: /a}}")
        broken("__include: b:/", "b" to "__include: main:/")
    }

    @Test
    fun referencesHeldToSixteenDeep() {
        fun levels(n: Int) = (1..n).map { "f$it" to if (it < n) "__include: f${it + 1}:/" else "k: v" }.toTypedArray()
        assertEquals(yaml("{k: v}"), compose("__include: f1:/", *levels(RimeCompose.MAX_CHAIN)).root)
        broken("__include: f1:/", *levels(RimeCompose.MAX_CHAIN + 1))
    }

    @Test
    fun distinctReferencesHeldToTheirLimit() {
        val n = RimeCompose.MAX_REFERENCES + 10
        val values = (0 until n).joinToString(", ") { "x$it: $it" }
        // the same one many times is one
        val same = (0 until n).joinToString("\n") { "k$it: {__include: /v/x0}" }
        assertEquals("0", root(compose("v: {$values}\n$same"))["k${n - 1}"])
        broken("v: {$values}\n" + (0 until n).joinToString("\n") { "k$it: {__include: /v/x$it}" })
        // parts of one composed already count too
        broken("v: {$values}\nw: {__include: /v}\n" + (0 until n).joinToString("\n") { "k$it: {__include: /v/x$it}" })
    }

    @Test
    fun nodesHeldToTheirLimit() {
        // aliases expanded where a tree is put together
        val a = "a: &a [" + List(10) { "x" }.joinToString(", ") + "]\n"
        val b = "b: &b [" + List(20) { "*a" }.joinToString(", ") + "]\n"
        val c = "c: &c [" + List(20) { "*b" }.joinToString(", ") + "]\n"
        compose("$a$b$c" + "e: {__include: /a}\n")
        broken("$a$b$c" + "d: [" + List(50) { "*c" }.joinToString(", ") + "]\n" + "e: {__include: /a}\n")
        // a node used again counts again
        val big = "big: {" + (0 until 1000).joinToString(", ") { "k$it: $it" } + "}\n"
        compose(big + (0 until 50).joinToString("\n") { "u$it: {__include: /big}" })
        broken(big + (0 until 150).joinToString("\n") { "u$it: {__include: /big}" })
    }

    @Test
    fun aSharedListAppendedOverAndOverIsHeld() {
        val l = "big: {l: [" + (0 until 1000).joinToString(", ") + "]}\nu: {__include: /big}\nt: []\n__patch:\n"
        fun appended(times: Int) = l + List(times) { "  - {t/+: {__include: /big/l}}" }.joinToString("\n")
        assertEquals(3000, (root(compose(appended(3)))["t"] as List<*>).size)
        // each copy is longer than the last: uncounted, this one is billions of slots
        broken(appended(5000))
    }

    // --- TrimeSkin over it

    @Test
    fun aThemeOnlyOfIncludesConvertsAsItsWrittenOutSelf() {
        val built = convert(files("wave.trime.yaml" to TrimeFixtures.wave, "base.yaml" to TrimeFixtures.base))
        val written = convert(files("wave.trime.yaml" to TrimeFixtures.waveWrittenOut))
        assertEquals("Wave", built.name)
        assertEquals(written.themeXml, built.themeXml)
        assertEquals(written.notes, built.notes)
        assertFalse(SkinNote.INCLUDES in built.notes)
        val deeper = files("wave.trime.yaml" to TrimeFixtures.wave, "old/base.yaml" to "name: Old", "base.yaml" to TrimeFixtures.base)
        assertEquals(written.themeXml, convert(deeper).themeXml)
        // its base alone in the pack: the base is the theme
        assertEquals("Base", convert(files("base.yaml" to TrimeFixtures.base)).name)
    }

    @Test
    fun theReferencesSkippedAreCounted() {
        val yaml = TrimeFixtures.waveWrittenOut + "\nfallback_colors: {__include: trime:/fallback_colors}\nx: {__include: gone:/x}\ny: {__include: maybe:/y?}\n"
        val c = convert(files("wave.trime.yaml" to yaml))
        assertEquals(listOf("2"), c.notes[SkinNote.INCLUDES])
    }

    @Test
    fun theLargestThemeThatHasColoursIsTaken() {
        val big = "name: Big\n__include: gone:/\n" + "# padding\n".repeat(500)
        val c = convert(files("big.trime.yaml" to big, "wave.trime.yaml" to TrimeFixtures.wave, "base.yaml" to TrimeFixtures.base))
        assertEquals("Wave", c.name)
        val own = TrimeFixtures.wave + "\nauthor: {__include: /name}\n"
        val trime = convert(files("trime.yaml" to own, "base.yaml" to TrimeFixtures.base))
        assertEquals("Wave", trime.name)
        assertFalse(SkinNote.INCLUDES in trime.notes)
        // Trime's own settings copied beside a theme, larger and with schemes of their own
        val settings = TrimeFixtures.waveWrittenOut.replace("name: Wave", "name: Trime") + "# padding\n".repeat(500)
        assertEquals("Wave", convert(files("trime.yaml" to settings, "wave.trime.yaml" to TrimeFixtures.waveWrittenOut)).name)
        val e = assertFailsWith<SkinError> { convert(files("big.trime.yaml" to big)) }
        assertEquals(SkinError.Reason.NO_KEYBOARD, e.reason)
        val notAMap = assertFailsWith<SkinError> { convert(files("big.trime.yaml" to "- a\n- b\n" + "# padding\n".repeat(500), "wave.trime.yaml" to "name: w")) }
        assertEquals(SkinError.Reason.BROKEN, notAMap.reason)
    }

    @Test
    fun theYamlPutTogetherIsHeldInAll() {
        val part = "k: " + "x".repeat(900 * 1024) + "\n"
        val parts = (1..5).map { "p$it.yaml" to part }.toTypedArray()
        val main = TrimeFixtures.waveWrittenOut + "\n" + (1..5).joinToString("\n") { "q$it: {__include: p$it:/k}" }
        assertEquals("Wave", convert(files("wave.trime.yaml" to main, *parts.take(4).toTypedArray())).name)
        val e = assertFailsWith<SkinError> { convert(files("wave.trime.yaml" to main, *parts)) }
        assertEquals(SkinError.Reason.BROKEN, e.reason)
        val one = assertFailsWith<SkinError> {
            convert(files("wave.trime.yaml" to main, "p1.yaml" to "k: " + "x".repeat(SkinLimits.MAX_TEXT_BYTES) + "\n"))
        }
        assertEquals(SkinError.Reason.BROKEN, one.reason)
    }

    @Test
    fun eachThemeFileTriedHasLimitsOfItsOwn() {
        val wave = arrayOf("wave.trime.yaml" to TrimeFixtures.wave, "base.yaml" to TrimeFixtures.base)
        // a larger file without colours first, its includes all but the YAML one may put together
        val head = "name: Big\n" + (1..5).joinToString("\n") { "q$it: {__include: p$it:/k}" } + "\n" + "# padding\n".repeat(500)
        val part = (TrimeSkin.MAX_COMPOSED_BYTES - head.length - 100) / 5
        val parts = (1..5).map { "p$it.yaml" to "k: " + "x".repeat(part.toInt() - 4) + "\n" }.toTypedArray()
        assertEquals("Wave", convert(files("big.trime.yaml" to head, *parts, *wave)).name)
        // or one past the nodes' limit itself
        val a = "a: &a [" + List(10) { "x" }.joinToString(", ") + "]\n"
        val b = "b: &b [" + List(20) { "*a" }.joinToString(", ") + "]\n"
        val c = "c: &c [" + List(20) { "*b" }.joinToString(", ") + "]\n"
        val nodes = "name: Big\n$a$b$c" + "d: [" + List(50) { "*c" }.joinToString(", ") + "]\n" + "e: {__include: /a}\n" + "# padding\n".repeat(500)
        assertEquals("Wave", convert(files("big.trime.yaml" to nodes, *wave)).name)
        // the largest one broken and no other with colours: its own reason
        val e = assertFailsWith<SkinError> { convert(files("big.trime.yaml" to nodes, "small.trime.yaml" to "name: s")) }
        assertEquals(SkinError.Reason.BROKEN, e.reason)
        assertTrue("nodes" in e.detail, e.detail)
    }

    @Test
    fun aPatchsPathIsHeldToTheDepthOfAYamlRead() {
        fun path(n: Int) = List(n) { "a" }.joinToString("/")
        var v: Any? = compose("__patch: {'${path(SkinYaml.MAX_DEPTH)}': 1}").root
        repeat(SkinYaml.MAX_DEPTH) { v = (v as Map<*, *>)["a"] }
        assertEquals("1", v)
        broken("__patch: {'${path(SkinYaml.MAX_DEPTH + 1)}': 1}")
        // within the text's limit, half a million parts: no stack overflow
        broken("__patch: {'${"a/".repeat(500_000)}b': 1}")
    }

    @Test
    fun theFirstLetterKeyboardIsTheLayout() {
        val skin = TrimeSkin(SkinFiles(files("wave.trime.yaml" to TrimeFixtures.wave, "base.yaml" to TrimeFixtures.base), SkinFixtures.Platform()))
        val board = skin.look(Variant.LIGHT).layouts.single()
        assertEquals("qwerty", board.id)
        val keys = board.rows.flatten()
        val shift = keys.first { it.action == SkinAction.Role("shift") }
        assertEquals(listOf(0.0, 100.0, 15.0, 50.0), listOf(shift.x, shift.y, shift.w, shift.h))
        assertEquals(SkinAction.Role("backspace"), keys.first { it.x == 85.0 && it.y == 100.0 }.action)
        val c = convertWith(skin)
        assertEquals(setOf("qwerty"), c.layouts.keys)
        assertFalse(SkinNote.LAYOUT in c.notes)
        // the bottom row's switches the other way round from the app's: written in the skin's order
        val swapped = TrimeFixtures.base.replace("Keyboard_symbols, width: 12", "Keyboard_x, width: 12")
            .replace("Keyboard_number, width: 12", "Keyboard_symbols, width: 12").replace("Keyboard_x, width: 12", "Keyboard_number, width: 12")
        val other = TrimeSkin(SkinFiles(files("b.trime.yaml" to swapped), SkinFixtures.Platform()))
        assertEquals("qwerty", other.look(Variant.LIGHT).layouts.single().id)
        val o = convertWith(other)
        assertEquals(setOf("qwerty"), o.layouts.keys)
        assertFalse(SkinNote.LAYOUT in o.notes || SkinNote.LAYOUT_SOME in o.notes)
    }

    @Test
    fun aChineseLetterKeyboardBeforeAnEnglishOne() {
        val english = TrimeFixtures.base.substringAfter("  default:\n").replace("ascii_mode: 0", "ascii_mode: 1")
            .replace("Shift_L, width: 15", "Shift_L, width: 20").replace("BS, width: 15", "BS, width: 10")
        val yaml = TrimeFixtures.base.replace("keyboards: [.default, number]", "keyboards: [english, number, .default]") + "  english:\n" + english
        val keys = TrimeSkin(SkinFiles(files("b.trime.yaml" to yaml), SkinFixtures.Platform())).look(Variant.LIGHT).layouts.single().rows.flatten()
        assertEquals(15.0, keys.first { it.action == SkinAction.Role("shift") }.w)
        // only English: that one
        val only = yaml.replace("keyboards: [english, number, .default]", "keyboards: [english]")
        val shift = TrimeSkin(SkinFiles(files("b.trime.yaml" to only), SkinFixtures.Platform())).look(Variant.LIGHT).layouts.single().rows.flatten()
        assertEquals(20.0, shift.first { it.action == SkinAction.Role("shift") }.w)
    }

    @Test
    fun noLetterKeyboardNoLayout() {
        val yaml = TrimeFixtures.base.replace("keyboards: [.default, number]", "keyboards: [number]")
        val look = TrimeSkin(SkinFiles(files("b.trime.yaml" to yaml), SkinFixtures.Platform())).look(Variant.LIGHT)
        assertTrue(look.layouts.isEmpty())
        assertTrue(SkinNote.LAYOUT in look.notes)
        val noKeyboards = TrimeFixtures.base.substringBefore("preset_keyboards:")
        assertTrue(TrimeSkin(SkinFiles(files("b.trime.yaml" to noKeyboards), SkinFixtures.Platform())).look(Variant.LIGHT).layouts.isEmpty())
    }

    @Test
    fun keysTrimeNamesAreTheAppsRoles() {
        val rows = listOf(
            "{click: Q}" to SkinAction.Letter('q'),
            "{click: BackSpace}" to SkinAction.Role("backspace"),
            "{click: Mode_switch}" to SkinAction.Role("lang"),
            "{click: '，'}" to SkinAction.Role("comma"),
            "{click: Keyboard_emoji}" to SkinAction.Role("emoji"),
            "{click: Keyboard_more}" to SkinAction.Role("Keyboard_more"),
            "{click: sw}" to SkinAction.Role("symbols"),
            "{click: Menu}" to SkinAction.Role("Menu"),
            "{label: x}" to SkinAction.None,
        )
        assertRows(rows, { it.first }) { (key, want) ->
            val yaml = TrimeFixtures.base.replace("{click: Return, width: 15}", "$key, width: 15")
                .replace("BS: {label: del, send: BackSpace}", "BS: {label: del, send: BackSpace}\n  sw: {send: Eisu_toggle, select: symbols}")
            val keys = TrimeSkin(SkinFiles(files("b.trime.yaml" to yaml), SkinFixtures.Platform())).look(Variant.LIGHT).layouts.single().rows.flatten()
            assertEquals(want, keys.first { it.x == 85.0 && it.y == 150.0 }.action)
        }
    }
}
