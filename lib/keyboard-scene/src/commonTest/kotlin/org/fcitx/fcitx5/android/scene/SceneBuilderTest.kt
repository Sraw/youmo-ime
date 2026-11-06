/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// what SceneBuilder works out beyond the steps' own tests: defaults the app may leave out, the side a board docks to, never a throw
class SceneBuilderTest {
    private val ns = LayoutReader.NAMESPACE

    private fun board(family: String, rows: String) = LayoutReader.layout(
        "<keyboard xmlns=\"$ns\" id=\"t\" name=\"t\" family=\"$family\" columns=\"10\">\n$rows\n</keyboard>",
    ).ok()

    // review M-5: the app always says which pad; a board read without it is the full one, said so
    @Test
    fun aNumberPadWithNoPadIsTheFullOne() {
        val pads = board(
            "number",
            "<row><key id=\"=\" label=\"=\" send=\"commit:=\" when=\"pad=full\"/><key id=\"-\" label=\"-\" send=\"commit:-\" when=\"pad=signed\"/></row>",
        )
        val scene = Fixtures.scene(Fixtures.portrait, pads)
        assertEquals(listOf("="), scene.keys.map { it.id })
        assertTrue(scene.warnings.single().message.contains("no pad="), "${scene.warnings}")
        val signed = Fixtures.scene(Fixtures.portrait.copy(input = Fixtures.portrait.input.copy(pad = "signed")), pads)
        assertEquals(listOf("-"), signed.keys.map { it.id })
        assertTrue(signed.warnings.isEmpty())
    }

    // review L-7: split-side is the side a board docks to while the letters split, nothing otherwise
    @Test
    fun splitSideIsWhereTheBoardDocks() {
        val sided = board(
            "symbols",
            "<row><key id=\"l\" label=\"l\" send=\"commit:l\" when=\"split-side=left\"/>" +
                "<key id=\"r\" label=\"r\" send=\"commit:r\" when=\"split-side=right\"/><key id=\"x\" label=\"x\" send=\"commit:x\"/></row>",
        )
        fun ids(env: org.fcitx.fcitx5.android.scene.engine.Env) = Fixtures.scene(env, sided).keys.map { it.id }
        assertEquals(listOf("r", "x"), ids(Fixtures.landscape))
        assertEquals(listOf("l", "x"), ids(Fixtures.landscape.copy(dock = SplitKeyboard.Dock.LEFT)))
        assertEquals(listOf("x"), ids(Fixtures.portrait))
        assertEquals(listOf("x"), ids(Fixtures.portrait.copy(oneHanded = SplitKeyboard.Dock.LEFT)), "one-handed is not split")
    }

    // review L-12: whatever goes wrong building, a Failed, not a crash in the keyboard
    @Test
    fun buildingNeverThrows() {
        val broken = TextMeasurer { _, _, _, _ -> error("no fonts") }
        val built = SceneBuilder(Fixtures.materialLight, Fixtures.portrait, broken).build(Fixtures.qwerty)
        assertIs<Parsed.Failed>(built)
        assertTrue(built.problems.single().message.contains("IllegalStateException"), "${built.problems}")
    }

    // review L-5, M-4: the reading made where none is written, and what the app works a key by
    @Test
    fun aKeyCarriesItsReadingAndWhatItDoes() {
        val k14 = Forms.scene("k14", Forms.env("k14", Forms.block)).keys.associateBy { it.id }
        val qw = k14.getValue("r1c1")
        assertEquals("Q W, 1", qw.a11y)
        assertEquals("QW", qw.label)
        assertEquals("1", qw.alt)
        assertEquals("group", qw.popup?.from)
        assertEquals("g14/qw", qw.shortcut)
        val zx = k14.getValue("r3c1")
        assertEquals("Z X, ～", zx.a11y, "the corner as shown, Chinese punctuation's")
        assertEquals("～", zx.alt)
        assertEquals("{punct:~}", zx.altRaw)
        val semicolon = Forms.env("k18", Forms.block).let { it.copy(input = it.input.copy(ime = "shuangpin", schemeTypes = setOf("semicolon"))) }
        assertEquals("L ;, -", Forms.scene("k18", semicolon).keys.single { it.label == "L;" }.a11y)
        val space = Forms.scene("symbols", Forms.env("symbols", Forms.block)).keys.single { it.role == "space" }
        assertEquals("space-long-press", space.actions["long-press"])
        assertEquals("space", space.sound)
        assertNull(space.label?.takeIf { it.isNotBlank() }, "no input method's name on the panel's space")
    }
}
