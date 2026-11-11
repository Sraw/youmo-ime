/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.KeyLook
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
 * The spacing settings over a 4.0 pack's own insets (0.6.0-beta2: 国风·天青's `inset="5dp 3dp"` and its
 * Return's `7dp 6dp` drew whatever was set): every built-in pack's every key kind, in each form, takes the
 * set direction from the setting and keeps the other as the pack has it.
 */
class PackKeyGapTest {

    private fun pack(file: String): Theme {
        ZipFile(File("src/main/res/raw/$file")).use { zip ->
            val text = zip.getInputStream(zip.getEntry("theme.xml")).bufferedReader().readText()
            return (ThemeReader.theme(text) as Parsed.Ok).value
        }
    }

    private fun inset(theme: Theme, kind: String?, vararg scenes: String): Inset =
        KeyLook(Cascades(theme).of(scenes.toSet()).of(Selector.Target("key", classes = setOfNotNull(kind))), theme).inset

    private val kinds = listOf(null, "function", "enter", "space")
    private val forms = listOf(arrayOf(), arrayOf("landscape"), arrayOf("compact"), arrayOf("landscape", "compact"))

    @Test
    fun followingThePackIsThePacksOwn() {
        val theme = pack("builtin_theme_tianqing.theme")
        assertSame(theme, PackKeyGap.over(theme, null, null))
        assertEquals(Inset(5.0, 3.0, 5.0, 3.0), inset(theme, null))
        assertEquals(Inset(7.0, 6.0, 7.0, 6.0), inset(theme, "enter"))
    }

    @Test
    fun tianqingsKeysTakeBothSettings() {
        val theme = PackKeyGap.over(pack("builtin_theme_tianqing.theme"), KeyStyleChoice.H_LOOSE, KeyStyleChoice.V_COMPACT)
        kinds.forEach { kind ->
            assertEquals("$kind", Inset(5.0, 5.0, 5.0, 5.0), inset(theme, kind))
            assertEquals("$kind landscape", Inset(3.0, 5.0, 3.0, 5.0), inset(theme, kind, "landscape"))
            assertEquals("$kind compact", Inset(Gap.compact(5).toDouble(), 5.0, Gap.compact(5).toDouble(), 5.0), inset(theme, kind, "compact"))
        }
    }

    @Test
    fun oneDirectionSetKeepsTheOtherThePacks() {
        val raw = pack("builtin_theme_tianqing.theme")
        val across = PackKeyGap.over(raw, KeyStyleChoice.H_COMPACT, null)
        assertEquals(Inset(5.0, 2.0, 5.0, 2.0), inset(across, null))
        assertEquals(Inset(7.0, 2.0, 7.0, 2.0), inset(across, "enter"))
        val upDown = PackKeyGap.over(raw, null, KeyStyleChoice.V_LOOSE)
        assertEquals(Inset(9.0, 3.0, 9.0, 3.0), inset(upDown, null))
        assertEquals(Inset(9.0, 6.0, 9.0, 6.0), inset(upDown, "enter"))
    }

    @Test
    fun everyBuiltInPackTakesTheSettingsInEveryForm() {
        val files = File("src/main/res/raw").listFiles { f -> f.name.startsWith("builtin_theme_") && f.name.endsWith(".theme") }!!
        assertEquals(12, files.size)
        val cases = files.flatMap { f -> kinds.flatMap { k -> forms.map { Triple(f, k, it) } } }
        val drawn = files.associateWith { PackKeyGap.over(pack(it.name), KeyStyleChoice.H_STANDARD, KeyStyleChoice.V_LOOSE) }
        cases.forEach { (f, kind, form) ->
            val v = (if ("landscape" in form) 6 else 9).let { if ("compact" in form) Gap.compact(it) else it }.toDouble()
            val got = inset(drawn.getValue(f), kind, *form)
            assertTrue("${f.name} $kind ${form.toList()}: $got", got == Inset(v, 3.0, v, 3.0))
        }
    }
}
