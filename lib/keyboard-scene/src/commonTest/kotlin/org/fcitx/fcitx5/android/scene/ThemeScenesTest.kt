/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Twin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 060-render-p3 T5 of theme-4.xsd: what is typed as a theme's scenes, the cascades they make, the keyboard's size
class ThemeScenesTest {
    private val source = testData("material-light.xml")

    private fun problems(s: String) = ThemeReader.theme(s).problems().map { it.message }

    private fun withStyle(style: String) = source.replace("</theme>", "$style\n</theme>")

    private fun warnings(s: String): List<String> {
        val t = ThemeReader.theme(s)
        assertTrue(t is Parsed.Ok, "${t.problems()}")
        return t.warnings().map { it.message }
    }

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
        assertEquals(emptyList(), warnings(withStyle("<style select=\"@im=pinyin key, @im=table key\" fill=\"#000001\"/>")))
        // an input method the app has none of: read, warned of, once; it selects nothing
        val wubbi = withStyle("<style select=\"@im=wubbi key, @im=wubbi @dark key\" fill=\"#000001\"/>")
        assertEquals(listOf("@im=wubbi is no input method of this app: it selects nothing"), warnings(wubbi))
    }

    // what the input method, the field and the board make of a theme's cascades: none more where it names
    // none of them, and for one that does, one for each set of those it names met together (060-render-p3 T5),
    // each of the input methods and fields there is met
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
            assertEquals(2 * 3, made(named, board), board)
        }
    }

    // a theme naming many scenes: the last Cascades.KEPT used kept, the least used let go first
    @Test
    fun cascadesKeptAreTheLastUsed() {
        val many = ThemeReader.theme(
            withStyle(
                "<style select=\"@im=wubi key, @field=password key, @field=url key, @keyboard=nine key\" fill=\"#000001\"/>\n" +
                    "<style select=\"@dark key, @landscape key, @compact key\" fill=\"#000002\"/>",
            ),
        ).getOr { error("$it") }
        val names = listOf("im=wubi", "field=password", "keyboard=nine", "dark", "landscape", "compact")
        // 2^6 sets of them, then the URL's
        val sets = (0 until 64).map { bits -> names.filterIndexedTo(HashSet()) { i, _ -> bits shr i and 1 == 1 } }
        val c = Cascades(many)
        val made = sets.associateWith { c.of(it) }
        // the last 32 kept, 32 to 63, the oldest first
        assertEquals(Cascades.KEPT, c.size)
        assertTrue(made.getValue(sets[63]) === c.of(sets[63]))
        // 33 used again: the newest now
        assertTrue(made.getValue(sets[33]) === c.of(sets[33]))
        // 0 let go, made again; 32 let go for it, the least used
        assertTrue(made.getValue(sets[0]) !== c.of(sets[0]))
        assertTrue(made.getValue(sets[32]) !== c.of(sets[32]))
        // 34 let go for that, 33 still kept
        assertTrue(made.getValue(sets[33]) === c.of(sets[33]))
        assertEquals(Cascades.KEPT, c.size)
        // a scene the theme names not: the same as without it
        assertTrue(c.of(sets[1] + "split") === c.of(sets[1]))
        assertTrue(c.of(setOf("field=url")) === c.of(setOf("field=url", "split")))
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
        // in a state: the same, said so
        assertTrue(warnings(withStyle("<style select=\"keyboard:pressed\" height=\"40%\"/>")).single().contains("height= of a keyboard in a state is ignored"))
        // in a list with another widget: said so, and the other's a later stage's (the bar's height, T6b)
        val listed = warnings(withStyle("<style select=\"keyboard, bar\" height=\"40%\"/>"))
        assertEquals(2, listed.size, "$listed")
        assertTrue(listed[0].contains("height= of a keyboard in a list of other widgets is ignored"), "$listed")
        assertTrue(listed[1].contains("height= is not supported"), "$listed")
        assertEquals(KeyboardSizes(), sizes("<style select=\"keyboard, bar\" height=\"40%\"/>"))
        assertEquals(KeyboardSizes(), sizes("<style select=\"@landscape keyboard\" height=\"40%\"/>"))
        assertTrue(problems(withStyle("<style select=\"@landscape keyboard\" height=\"95%\"/>")).isNotEmpty())
        // another widget's: a later stage's (the bar's height, T6b), as before
        assertTrue(warnings(withStyle("<style select=\"bar\" height=\"40dp\"/>")).single().contains("height= is not supported"))
        // with today's properties, the keyboard's fill still works
        val both = ThemeReader.theme(withStyle("<style select=\"keyboard\" fill=\"#123456\" height=\"40%\"/>")).getOr { error("$it") }
        assertEquals(setOf("fill", "height"), both.styles.last().values.keys)
    }
}
