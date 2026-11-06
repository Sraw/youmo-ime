/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.theme.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 060-render-v2 §9: every board of rules C in material-light, in every form a phone shows it in
 * ([Forms.all]: block, flat, compact, on its side, split, floating, one-handed either side), plus
 * the states that change a board's look (dark, a table looking up, 双拼's `;`, English nine keys
 * shifted, an address field, each number pad, 注 on, the symbols locked). Each key in its normal
 * and pressed state, as [SceneText] writes them. The JVM writes them (-Psnapshots.record=true) and
 * both targets compare with what is committed, so the JVM and JS engines agree to the hundredth of
 * a pixel or one of them fails. A change to the engine shows here as a diff to review.
 */
class SnapshotTest {

    @Test
    fun everyBoardInEveryForm() = checkAll(
        Forms.boards.flatMap { board ->
            Forms.all.map { (form, env) -> Shot("$board-$form", board, Forms.env(board, env)) }
        },
    )

    @Test
    fun theStatesThatChangeABoard() {
        val block = Forms.block
        fun input(board: String, change: (Env) -> Env) = change(Forms.env(board, block))
        checkAll(
            listOf(
                Shot("qwerty-dark", "qwerty", block.copy(dark = true), Forms.materialDark),
                Shot("qwerty-dark-flat", "qwerty", Forms.flat.copy(dark = true), Forms.materialDark),
                Shot(
                    "qwerty-table-looking-up", "qwerty",
                    input("qwerty") {
                        it.copy(input = it.input.copy(ime = "table", table = "wbx", composing = true, lookingUp = true))
                    },
                ),
                Shot(
                    "qwerty-shuangpin-semicolon", "qwerty",
                    input("qwerty") { it.copy(input = it.input.copy(ime = "shuangpin", schemeTypes = setOf("semicolon"))) },
                ),
                Shot("qwerty-caps-lock", "qwerty", input("qwerty") { it.copy(shift = Shift.Lock) }),
                Shot(
                    "nine-english-shifted", "nine",
                    input("nine") { it.copy(input = it.input.copy(ime = "english"), shift = Shift.Once) },
                ),
                Shot("nine-composing", "nine", input("nine") { it.copy(input = it.input.copy(composing = true)) }),
                Shot(
                    "k18-shuangpin-semicolon", "k18",
                    input("k18") { it.copy(input = it.input.copy(ime = "shuangpin", schemeTypes = setOf("semicolon"))) },
                ),
                Shot("k14-url", "k14", input("k14") { it.copy(input = it.input.copy(field = "url")) }),
                Shot("zhuyin-nine-composing", "zhuyin-nine", input("zhuyin-nine") { it.copy(input = it.input.copy(composing = true)) }),
                Shot("handwriting-reading-on", "handwriting", input("handwriting") { it.copy(states = mapOf("reading" to setOf(KeyState.On))) }),
                Shot("symbols-locked", "symbols", input("symbols") { it.copy(states = mapOf("lock" to setOf(KeyState.Locked))) }),
            ) + PADS.map { pad ->
                Shot("number-pad-$pad", "number", input("number") { it.copy(input = it.input.copy(pad = pad)) })
            },
        )
    }

    @Test
    fun noBoardWarnsInAnyForm() {
        val warned = Forms.boards.flatMap { board ->
            Forms.all.mapNotNull { (form, env) ->
                Forms.scene(board, Forms.env(board, env)).warnings.takeIf { it.isNotEmpty() }?.let { "$board $form: $it" }
            }
        }
        assertTrue(warned.isEmpty(), warned.joinToString("\n"))
    }

    private class Shot(val name: String, val board: String, val env: Env, val theme: Theme = Fixtures.materialLight)

    // each differing file named, not just the first
    private fun checkAll(shots: List<Shot>) {
        val names = shots.map { it.name }
        assertEquals(names.toSet().size, names.size, "two shots of one name")
        val failed = shots.mapNotNull { shot ->
            val text = SceneText.of(Forms.scene(shot.board, shot.env, shot.theme))
            val path = "$TEST_DATA/snapshots/${shot.name}.txt"
            when {
                recording() -> null.also { writeText(path, text) }
                readText(path) == null -> "no $path: run the JVM tests with -Psnapshots.record=true"
                readText(path) != text -> "$path differs: review, then record anew"
                else -> null
            }
        }
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
    }

    private companion object {
        // NumberPad's, full being every form's
        val PADS = listOf("digits", "signed", "decimal", "signed-decimal", "phone", "datetime")
    }
}
