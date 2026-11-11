/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Border
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.Radius
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * The key style settings over a 4.0 pack's own (0.6.0-beta2: 国风·天青's `inset="5dp 3dp"` and its Return's
 * `7dp 6dp` drew whatever was set): every built-in pack's every key kind, in each form, takes what is set and keeps
 * what is not as the pack draws it there, whatever order the pack wrote its styles in.
 */
class PackKeyStyleTest {

    private fun text(file: String): String = ZipFile(File("src/main/res/raw/$file")).use { zip ->
        zip.getInputStream(zip.getEntry("theme.xml")).bufferedReader().readText()
    }

    private fun read(text: String): Theme = (ThemeReader.theme(text) as Parsed.Ok).value

    private fun pack(file: String) = read(text(file))

    private fun look(theme: Theme, kind: String?, vararg scenes: String, key: Pair<String, String>? = null): KeyLook {
        val target = Selector.Target("key", key?.first, key?.second, classes = setOfNotNull(kind))
        return KeyLook(Cascades(theme).of(scenes.toSet()).of(target), theme)
    }

    private fun inset(theme: Theme, kind: String?, vararg scenes: String) = look(theme, kind, *scenes).inset

    private fun gaps(h: KeyStyleChoice.Spacing?, v: KeyStyleChoice.Spacing?) = KeyStyleChoice(null, h, v, null)

    private val kinds = listOf(null, "function", "enter", "space")
    private val forms = listOf(arrayOf(), arrayOf("landscape"), arrayOf("compact"), arrayOf("landscape", "compact"))
    private val tianqing = "builtin_theme_tianqing.theme"

    @Test
    fun followingThePackIsThePacksOwn() {
        val theme = pack(tianqing)
        assertSame(theme, PackKeyStyle.over(theme, KeyStyleChoice.FOLLOW))
        // 有边框 over a pack: its own faces, nothing added
        assertSame(theme, PackKeyStyle.over(theme, KeyStyleChoice(true, null, null, null)))
        assertEquals(Inset(5.0, 3.0, 5.0, 3.0), inset(theme, null))
        assertEquals(Inset(7.0, 6.0, 7.0, 6.0), inset(theme, "enter"))
    }

    @Test
    fun tianqingsKeysTakeBothSpacings() {
        val theme = PackKeyStyle.over(pack(tianqing), gaps(KeyStyleChoice.H_LOOSE, KeyStyleChoice.V_COMPACT))
        kinds.forEach { kind ->
            assertEquals("$kind", Inset(5.0, 5.0, 5.0, 5.0), inset(theme, kind))
            assertEquals("$kind landscape", Inset(3.0, 5.0, 3.0, 5.0), inset(theme, kind, "landscape"))
            assertEquals("$kind compact", Inset(Gap.compact(5).toDouble(), 5.0, Gap.compact(5).toDouble(), 5.0), inset(theme, kind, "compact"))
        }
    }

    @Test
    fun oneDirectionSetKeepsTheOtherThePacks() {
        val raw = pack(tianqing)
        val across = PackKeyStyle.over(raw, gaps(KeyStyleChoice.H_COMPACT, null))
        assertEquals(Inset(5.0, 2.0, 5.0, 2.0), inset(across, null))
        assertEquals(Inset(7.0, 2.0, 7.0, 2.0), inset(across, "enter"))
        val upDown = PackKeyStyle.over(raw, gaps(null, KeyStyleChoice.V_LOOSE))
        assertEquals(Inset(9.0, 3.0, 9.0, 3.0), inset(upDown, null))
        assertEquals(Inset(9.0, 6.0, 9.0, 6.0), inset(upDown, "enter"))
    }

    // the pack's `@compact key` written before its `key`: at 紧凑 it wins in the pack (a scene more), and the
    // direction not set is still its
    @Test
    fun aCompactInsetWrittenFirstStillGivesTheOtherDirection() {
        val t = text(tianqing)
        val key = t.lines().first { "select=\"key\" " in it }
        val raw = read(t.replace(key, "  <style select=\"@compact key\" inset=\"2dp 1dp\"/>\n$key"))
        assertEquals(Inset(2.0, 1.0, 2.0, 1.0), inset(raw, null, "compact"))
        val across = PackKeyStyle.over(raw, gaps(KeyStyleChoice.H_LOOSE, null))
        assertEquals(Inset(2.0, 5.0, 2.0, 5.0), inset(across, null, "compact"))
        assertEquals(Inset(5.0, 5.0, 5.0, 5.0), inset(across, null))
        val upDown = PackKeyStyle.over(raw, gaps(null, KeyStyleChoice.V_LOOSE))
        assertEquals(Inset(Gap.compact(9).toDouble(), 1.0, Gap.compact(9).toDouble(), 1.0), inset(upDown, null, "compact"))
    }

    // the pack's `@landscape key` written after `key.enter`: on its side it wins Return in the pack (later, as weighty)
    @Test
    fun aLandscapeInsetWrittenAfterReturnsStillGivesTheOtherDirection() {
        val t = text(tianqing)
        val enter = t.lines().first { "select=\"key.enter\"" in it }
        val raw = read(t.replace(enter, "$enter\n  <style select=\"@landscape key\" inset=\"4dp 2dp\"/>"))
        assertEquals(Inset(4.0, 2.0, 4.0, 2.0), inset(raw, "enter", "landscape"))
        val across = PackKeyStyle.over(raw, gaps(KeyStyleChoice.H_LOOSE, null))
        assertEquals(Inset(4.0, 5.0, 4.0, 5.0), inset(across, "enter", "landscape"))
        assertEquals(Inset(7.0, 5.0, 7.0, 5.0), inset(across, "enter"))
        val upDown = PackKeyStyle.over(raw, gaps(null, KeyStyleChoice.V_LOOSE))
        assertEquals(Inset(6.0, 2.0, 6.0, 2.0), inset(upDown, "enter", "landscape"))
    }

    // 机械键帽's 4dp 3dp 6dp 3dp: its 2 dp more below (its depth) kept about the level set
    @Test
    fun aPacksUnevenTopAndBottomKeepTheirDifference() {
        val theme = PackKeyStyle.over(pack("builtin_theme_keycap.theme"), gaps(null, KeyStyleChoice.V_LOOSE))
        assertEquals(Inset(8.0, 3.0, 10.0, 3.0), inset(theme, null))
        assertEquals(Inset(5.0, 3.0, 7.0, 3.0), inset(theme, null, "landscape"))
        // none above the cell's edge: a level of 1 dp less half the 2 dp difference is 0 on top
        assertEquals(Inset(0.0, 3.0, 2.0, 3.0), inset(PackKeyStyle.over(pack("builtin_theme_keycap.theme"), gaps(null, KeyStyleChoice.V_COMPACT)), null, "landscape", "compact"))
    }

    @Test
    fun everyBuiltInPackTakesTheSpacingsInEveryForm() {
        val files = File("src/main/res/raw").listFiles { f -> f.name.startsWith("builtin_theme_") && f.name.endsWith(".theme") }!!
        assertEquals(12, files.size)
        val cases = files.flatMap { f -> kinds.flatMap { k -> forms.map { Triple(f, k, it) } } }
        val raw = files.associateWith { pack(it.name) }
        val drawn = raw.mapValues { (_, t) -> PackKeyStyle.over(t, gaps(KeyStyleChoice.H_STANDARD, KeyStyleChoice.V_LOOSE)) }
        cases.forEach { (f, kind, form) ->
            val v = (if ("landscape" in form) 6 else 9).let { if ("compact" in form) Gap.compact(it) else it }.toDouble()
            val own = inset(raw.getValue(f), kind, *form)
            val got = inset(drawn.getValue(f), kind, *form)
            val half = (own.bottom - own.top) / 2
            assertTrue("${f.name} $kind ${form.toList()}: $got", got.left == 3.0 && got.right == 3.0 && (got.top + got.bottom) / 2 == v.coerceAtLeast(half) && got.bottom - got.top == 2 * half || got.top == 0.0)
        }
    }

    @Test
    fun theRadiusSetIsEveryKeysInEveryForm() {
        val raw = pack(tianqing)
        val theme = PackKeyStyle.over(raw, KeyStyleChoice(null, null, null, KeyStyleChoice.RADIUS_ROUND))
        val round = Radius(List(4) { 10.0 })
        kinds.forEach { kind -> forms.forEach { form -> assertEquals("$kind ${form.toList()}", round, look(theme, kind, *form).radius) } }
        // the gaps untouched
        assertEquals(inset(raw, "enter"), inset(theme, "enter"))
    }

    @Test
    fun withoutBordersAPacksKeysAreFlatTheSpaceABarReturnACircle() {
        val dopamine = pack("builtin_theme_dopamine.theme")
        val theme = PackKeyStyle.over(dopamine, KeyStyleChoice(false, null, null, null))
        val transparent = Fill.Solid(ColorExpr.Literal(Argb.TRANSPARENT))
        // a key coloured by its name too (多巴胺's q)
        listOf(look(theme, null, "flat"), look(theme, "function", "flat", "landscape"), look(theme, null, "flat", key = "qwerty" to "q")).forEach {
            assertEquals(transparent, it.fill)
            assertEquals(Border.NONE, it.border)
            assertEquals(0.0, it.depth, 0.0)
        }
        assertEquals("bar", look(theme, "space", "flat").shape)
        assertEquals(Fill.Solid(ColorExpr.Ref("spaceBar")), look(theme, "space", "flat").fill)
        assertEquals("circle", look(theme, "enter", "flat", "compact").shape)
        // not flat (the setting's 有边框 or following): the pack's own
        assertEquals(look(dopamine, null).fill, look(theme, null).fill)
    }
}
