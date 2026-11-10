/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// the one rule the app's import and the theme maker refuse a pack by: Shift, Backspace, Space, Return never blank
class BlankKeysTest {
    private val source = testData("material-light.xml")

    private fun theme(vararg style: String): Theme = ThemeReader.theme(source.replace("</theme>", style.joinToString("\n", postfix = "\n</theme>"))).ok()

    private val qwerty = BlankKeys.Board(Fixtures.qwerty)

    // the same keys under another board's id: what a theme's @keyboard= tells apart
    private val other = BlankKeys.Board(LayoutReader.layout(Fixtures.qwertySource.replaceFirst("id=\"qwerty\"", "id=\"other\"")).ok())

    private fun found(t: Theme) = BlankKeys.find(t, listOf(qwerty, other), limit = 100)

    @Test
    fun aThemeThatHidesNothingLeavesNothingBlank() {
        assertEquals(emptyList<BlankKeys.Blank>(), found(Fixtures.materialLight))
    }

    @Test
    fun aKeyHiddenWithNoImageIsFoundOnEveryBoard() {
        val blanks = found(theme("""<style select="*/enter" hidden="true"/>"""))
        assertEquals(setOf("qwerty/enter", "other/enter"), blanks.map { "${it.board}/${it.key}" }.toSet())
    }

    @Test
    fun aBoardsOwnSceneIsAskedOnThatBoardAloneAndAlwaysThere() {
        // hidden on qwerty alone, its image qwerty's: the app draws qwerty with @keyboard=qwerty on, never the other
        // board with it, so nothing is blank (the other board's Return was once tried under qwerty's scene)
        assertEquals(
            emptyList<BlankKeys.Blank>(),
            found(theme("""<style select="@keyboard=qwerty */enter" hidden="true"/>""", """<style select="qwerty/enter" fill="image(e.png, 1 1 1 1)"/>""")),
        )
        // the board's scene is on in every scene there: hidden on qwerty with no image is found, on qwerty only
        val blanks = found(theme("""<style select="@keyboard=qwerty */enter" hidden="true"/>"""))
        assertEquals(setOf("qwerty/enter"), blanks.map { "${it.board}/${it.key}" }.toSet())
    }

    @Test
    fun aStateTheStylesNameIsTried() {
        val blanks = found(theme("""<style select="*/shift:locked" hidden="true"/>"""))
        assertTrue(blanks.isNotEmpty() && blanks.all { it.key == "shift" && KeyState.Locked in it.states }, "$blanks")
    }

    @Test
    fun tooManyScenesTogetherAreRefusedNotHalfAsked() {
        // 20 input methods, 4 fields, 20 boards: 1600 sets of one of each, past what is tried
        val styles = (1..20).map { """<style select="@im=m$it */enter" hidden="true"/>""" } +
            listOf("password", "url", "number", "email").map { """<style select="@field=$it */enter" hidden="true"/>""" } +
            (1..20).map { """<style select="@keyboard=b$it */enter" hidden="true"/>""" }
        assertEquals(listOf(BlankKeys.TOO_MANY), found(theme(*styles.toTypedArray())))
    }
}
