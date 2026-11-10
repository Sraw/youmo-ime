/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Phases
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.rules.KeyNames
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.scene.theme.Cascades
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// what SceneBuilder works out beyond the steps' own tests: defaults the app may leave out, the side a board docks to, never a throw
class SceneBuilderTest {
    private val ns = LayoutReader.NAMESPACE

    private fun board(family: String, rows: String) = LayoutReader.layout(
        "<keyboard xmlns=\"$ns\" id=\"t\" name=\"t\" family=\"$family\" columns=\"10\">\n$rows\n</keyboard>",
    ).ok()

    // the phases a profiler is told, in order, each ended; the scene the same as told nothing
    @Test
    fun itsPhasesTold() {
        val told = mutableListOf<String>()
        val phases = object : Phases {
            override fun begin(name: String) {
                told += name
            }

            override fun end() {
                told += "/"
            }
        }
        val scene = SceneBuilder(Fixtures.materialLight, Fixtures.portrait, FakeMeasurer, phases = phases).build(Fixtures.qwerty).ok()
        assertEquals(listOf("expand", "/", "place", "/", "cascade.of", "/", "faces", "/", "content", "/", "loose", "/", "paint", "/"), told)
        val plain = Fixtures.scene(Fixtures.portrait)
        assertEquals(plain.keys.map { it.id to it.faces }, scene.keys.map { it.id to it.faces })
        assertEquals(plain.base, scene.base)
    }

    // a theme's cascades kept from scene to scene (TextKeyboard's) make each scene as a fresh one does:
    // every snapshot's scene, in turn, through one theme's cascades, the same text as made alone
    @Test
    fun keptCascadesMakeTheSameScenes() {
        for (theme in listOf(Fixtures.materialLight, Forms.materialDark)) {
            val kept = Cascades(theme)
            for (shot in Shots.all) {
                val board = Forms.layout(shot.board)
                val fresh = SceneBuilder(theme, shot.env, FakeMeasurer).build(board, Forms.fragments).ok()
                val again = SceneBuilder(theme, shot.env, FakeMeasurer, cascades = kept).build(board, Forms.fragments).ok()
                assertEquals(SceneText.of(fresh), SceneText.of(again), "${theme.name} ${shot.name}")
            }
        }
        assertFailsWith<IllegalArgumentException> { SceneBuilder(Forms.materialDark, Fixtures.portrait, FakeMeasurer, cascades = Cascades(Fixtures.materialLight)) }
    }

    // the app always says which pad; a board read without it is the full one, said so
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
        fun ids(env: Env) = Fixtures.scene(env, sided).keys.map { it.id }
        assertEquals(listOf("r", "x"), ids(Fixtures.landscape))
        assertEquals(listOf("l", "x"), ids(Fixtures.landscape.copy(dock = SplitKeyboard.Dock.LEFT)))
        assertEquals(listOf("x"), ids(Fixtures.portrait))
        assertEquals(listOf("x"), ids(Fixtures.portrait.copy(oneHanded = SplitKeyboard.Dock.LEFT)), "one-handed is not split")
    }

    // review L-12: whatever goes wrong building, a Failed, not a crash in the keyboard
    @Test
    fun buildingNeverThrows() {
        val broken = TextMeasurer { _, _, _, _ -> error("no fonts") }
        // each phase begun ended too, the build failing in one (a profiler's sections left open otherwise)
        var open = 0
        var begun = 0
        val phases = object : Phases {
            override fun begin(name: String) {
                open++
                begun++
            }

            override fun end() {
                open--
            }
        }
        val built = SceneBuilder(Fixtures.materialLight, Fixtures.portrait, broken, phases = phases).build(Fixtures.qwerty)
        assertIs<Parsed.Failed>(built)
        assertTrue(built.problems.single().message.contains("IllegalStateException"), "${built.problems}")
        assertTrue(begun > 0 && open == 0, "begun $begun, open $open")
    }

    // round 3: read as theme 3.0 read them, the space with the input method's name, a label spelt only on a group key
    @Test
    fun keysAreReadAsTheyWere() {
        fun keys(board: String, env: Env = Forms.env(board, Forms.block)) = Forms.scene(board, env).keys.associateBy { it.id }
        val rime = Forms.block.copy(texts = Forms.texts.copy(imeName = "Rime"))
        for (board in listOf("qwerty", "nine", "k14", "zhuyin", "handwriting")) {
            assertEquals("空格，${Forms.env(board, Forms.block).texts.imeName}", keys(board).getValue("space").a11y, board)
            val named = Forms.env(board, rime).let { it.copy(texts = it.texts.copy(imeName = "Rime")) }
            assertEquals("空格，Rime", keys(board, named).getValue("space").a11y, "$board: a Latin name is not spelt")
        }
        assertEquals("ABC，2", keys("nine").getValue("2").a11y)
        assertEquals("ABC", keys("number").getValue("back").a11y)
        assertEquals("a，@", keys("qwerty").getValue("a").a11y)
        // the app's strings as given: the default ones English's
        val english = Forms.block.copy(texts = Texts(imeName = "English"))
        assertEquals("Space, English", keys("qwerty", english).getValue("space").a11y)
        assertEquals("Q W, 1", keys("k14", english).getValue("r1c1").a11y)
        // what is no group key reads as written, letters or not
        val plain = board(
            "symbols",
            "<row><key id=\"fn\" label=\"Fn\" send=\"commit:x\"/><key id=\"tab\" label=\"Tab\" alt=\"⇥\" send=\"sym:Tab\"/>" +
                "<key id=\"g\" label=\"go\" letters=\"go\"/></row>",
        )
        val read = Fixtures.scene(Fixtures.portrait, plain).keys.associate { it.id to it.a11y }
        assertEquals(mapOf("fn" to "Fn", "tab" to "Tab, ⇥", "g" to "G O"), read)
    }

    // review L-5, M-4: the reading made where none is written, and what the app works a key by
    @Test
    fun aKeyCarriesItsReadingAndWhatItDoes() {
        val k14 = Forms.scene("k14", Forms.env("k14", Forms.block)).keys.associateBy { it.id }
        val qw = k14.getValue("r1c1")
        assertEquals("Q W，1", qw.a11y, "GroupedKeyboard's a11y_key_grouped, in Chinese")
        assertEquals("QW", qw.label)
        assertEquals("1", qw.alt)
        // its letters, the long press the app makes of them (no popup written: 060-render-v2 §15)
        assertEquals("qw", qw.letters?.group)
        assertTrue(qw.lean, "the 14 keys' two letters lean")
        assertNull(qw.popup)
        assertEquals("g14/qw", qw.shortcut)
        val zx = k14.getValue("r3c1")
        assertEquals("Z X，～", zx.a11y, "the corner as shown, Chinese punctuation's")
        assertEquals("～", zx.alt)
        assertEquals("{punct:~}", zx.altRaw)
        val semicolon = Forms.env("k18", Forms.block).let { it.copy(input = it.input.copy(ime = "shuangpin", schemeTypes = setOf("semicolon"))) }
        assertEquals("L ;，-", Forms.scene("k18", semicolon).keys.single { it.label == "L;" }.a11y)
        val space = Forms.scene("symbols", Forms.env("symbols", Forms.block)).keys.single { it.role == "space" }
        assertEquals("a11y_key_space", space.a11y, "the panel's, as written")
        assertEquals("space-long-press", space.actions["long-press"])
        assertEquals("space", space.sound)
        assertNull(space.label?.takeIf { it.isNotBlank() }, "no input method's name on the panel's space")
    }

    // what a screen reader's node gives as a key's id: the old views' names, a role each, every role the 26 keys' had one for
    @Test
    fun theOldViewsNamesByRole() {
        assertTrue(KeyNames.ROLES.containsAll(KeyNames.RESOURCES.keys))
        assertEquals(KeyNames.RESOURCES.size, KeyNames.RESOURCES.values.toSet().size, "a name a role")
        assertTrue(KeyNames.RESOURCES.values.all { it.matches(Regex("button_[a-z_]+")) })
        assertEquals("button_caps", KeyNames.RESOURCES["shift"])
        assertEquals("button_return", KeyNames.RESOURCES["enter"])
        assertNull(KeyNames.RESOURCES["symbols"], "符 and 123 had none")
    }
}
