/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A scene the snapshots hold: [board] in [env], drawn by [theme], written to snapshots/[name].txt. */
class Shot(val name: String, val board: String, val env: Env, val theme: Theme = Fixtures.materialLight) {
    val scene: Scene by lazy { Forms.scene(board, env, theme) }
}

/** A popup's scene the snapshots hold, written to snapshots/[name].txt. */
class PopupShot(val name: String, make: () -> Scene) {
    val scene: Scene by lazy(make)
}

/**
 * Every scene the snapshots hold: every board of rules C in material-light in every form a phone
 * shows it in ([Forms.all]), and the states that change a board's look (dark, a table looking up,
 * 双拼's `;`, caps lock, English nine keys shifted, composing, an address field, each number pad,
 * 注 on, the symbols locked).
 */
object Shots {
    val forms: List<Shot> = Forms.boards.flatMap { board ->
        Forms.all.map { (form, env) -> Shot("$board-$form", board, Forms.env(board, env)) }
    }

    val states: List<Shot> by lazy {
        val block = Forms.block
        fun on(board: String, change: (Env) -> Env) = change(Forms.env(board, block))
        listOf(
            Shot("qwerty-dark", "qwerty", block.copy(dark = true), Forms.materialDark),
            Shot("qwerty-dark-flat", "qwerty", Forms.flat.copy(dark = true), Forms.materialDark),
            Shot(
                "qwerty-table-looking-up", "qwerty",
                on("qwerty") { it.copy(input = it.input.copy(ime = "table", table = "wbx", composing = true, lookingUp = true)) },
            ),
            // 五笔's roots over the letters, more than three in two lines (TableKeyboard.label), a letter
            // with none itself (the app's); the letters in the corners
            Shot(
                "qwerty-table-roots", "qwerty",
                on("qwerty") {
                    it.copy(
                        input = it.input.copy(ime = "table", table = "wbx", roots = true),
                        texts = it.texts.copy(
                            alts = Fixtures.qwertyAlts.mapValues { (k, _) -> k },
                            other = Fixtures.qwertyAlts.keys.associate { k -> "root:$k" to k } +
                                mapOf("root:g" to "王一\n五戋", "root:f" to "土士二干\n十寸雨", "root:q" to "金", "root:y" to "言文方"),
                        ),
                    )
                },
            ),
            Shot(
                "qwerty-shuangpin-semicolon", "qwerty",
                on("qwerty") { it.copy(input = it.input.copy(ime = "shuangpin", schemeTypes = setOf("semicolon"))) },
            ),
            Shot("qwerty-caps-lock", "qwerty", on("qwerty") { it.copy(shift = Shift.Lock) }),
            Shot("nine-english-shifted", "nine", on("nine") { it.copy(input = it.input.copy(ime = "english"), shift = Shift.Once) }),
            Shot("nine-composing", "nine", on("nine") { it.copy(input = it.input.copy(composing = true)) }),
            Shot(
                "k18-shuangpin-semicolon", "k18",
                on("k18") { it.copy(input = it.input.copy(ime = "shuangpin", schemeTypes = setOf("semicolon"))) },
            ),
            Shot("k14-url", "k14", on("k14") { it.copy(input = it.input.copy(field = "url")) }),
            Shot("zhuyin-nine-composing", "zhuyin-nine", on("zhuyin-nine") { it.copy(input = it.input.copy(composing = true)) }),
            Shot("handwriting-reading-on", "handwriting", on("handwriting") { it.copy(states = mapOf("reading" to setOf(KeyState.On))) }),
            Shot("symbols-locked", "symbols", on("symbols") { it.copy(states = mapOf("lock" to setOf(KeyState.Locked))) }),
        ) + PADS.map { pad -> Shot("number-pad-$pad", "number", on("number") { it.copy(input = it.input.copy(pad = pad)) }) }
    }

    /**
     * 060-render-p3 T4a, the text on a key: material-light with one look more each, on the block qwerty
     * (its letters with their symbols, the space and ⌫ with their swipes, Return with its icon).
     */
    val looks: List<Shot> by lazy {
        val block = Forms.env("qwerty", Forms.block)
        fun styled(vararg styles: String, fonts: String = "") = ThemeReader.theme(
            testData("material-light.xml").replace("</colors>", "</colors>$fonts").replace("</theme>", styles.joinToString("\n", postfix = "\n</theme>")),
        ).ok()
        listOf(
            Shot("qwerty-align-top-left", "qwerty", block, styled("<style select=\"key\" align=\"top-left\"/>")),
            Shot("qwerty-align-top-center", "qwerty", block, styled("<style select=\"key\" align=\"top-center\"/>")),
            Shot("qwerty-letter-spacing", "qwerty", block, styled("<style select=\"key\" letter-spacing=\"0.2em\"/>")),
            Shot(
                "qwerty-stroke-shadow", "qwerty", block,
                styled("<style select=\"key\" stroke=\"1dp #000000\" text-shadow=\"0 1dp 2dp #80000000\"/>", "<style select=\"key mark\" stroke=\"0 #000000\"/>"),
            ),
            Shot(
                "qwerty-fonts", "qwerty", block.copy(texts = block.texts.copy(imeName = "拼音 Pinyin")),
                styled("<style select=\"key\" font-han=\"kai\" font-latin=\"mono\"/>", fonts = "\n<font name=\"kai\" src=\"kai.ttf\"/>\n<font name=\"mono\" src=\"mono.ttf\"/>"),
            ),
            Shot("qwerty-space-content", "qwerty", block, styled("<style select=\"*/space\" space-content=\"text(空格)\"/>")),
            Shot(
                "qwerty-return-icon", "qwerty", block.copy(texts = block.texts.copy(returnIcon = "send")),
                styled("<style select=\"*/enter\" return-icon-send=\"text(发送)\"/>"),
            ),
            Shot("qwerty-hint-mark-hidden", "qwerty", block, styled("<style select=\"key hint\" hidden=\"true\"/>", "<style select=\"key mark\" hidden=\"true\"/>")),
        )
    }

    val all: List<Shot> get() = forms + states + looks

    /** Each popup ([Forms.popups]) in the forms that change how one looks: block, flat, floating (its text smaller). */
    val popups: List<PopupShot> = listOf("block" to Forms.block, "flat" to Forms.flat, "floating" to Forms.floating).flatMap { (form, env) ->
        Forms.popups(env).map { (name, make) -> PopupShot("popup-$name-$form", make) }
    }

    // NumberPad's, full being every form's
    private val PADS = listOf("digits", "signed", "decimal", "signed-decimal", "phone", "datetime")
}

/**
 * 060-render-v2 §9: [Shots], each key in its normal and pressed state, as [SceneText] writes them.
 * The JVM writes them (-Psnapshots.record=true) and both targets compare with what is committed, so
 * the JVM and JS engines agree to the hundredth of a pixel or one of them fails. A change to the
 * engine shows here as a diff to review.
 */
class SnapshotTest {

    @Test
    fun everyBoardInEveryForm() = checkAll(Shots.forms)

    @Test
    fun theStatesThatChangeABoard() = checkAll(Shots.states)

    @Test
    fun theTextOnAKey() = checkAll(Shots.looks)

    @Test
    fun everyPopup() = check(Shots.popups.map { it.name to it.scene })

    @Test
    fun noBoardWarnsInAnyForm() {
        val warned = Shots.forms.mapNotNull { s -> s.scene.warnings.takeIf { it.isNotEmpty() }?.let { "${s.name}: $it" } }
        assertTrue(warned.isEmpty(), warned.joinToString("\n"))
    }

    // L-10: a snapshot no shot writes any more is stale, not kept
    @Test
    fun everySnapshotIsAShot() {
        // recording, the shots may not all be written yet (the tests run in any order)
        if (recording()) return
        val names = Shots.all.map { it.name } + Shots.popups.map { it.name }
        assertEquals(names.toSet().size, names.size, "two shots of one name")
        val files = listNames("$TEST_DATA/snapshots")
        assertEquals(names.map { "$it.txt" }.sorted(), files)
    }

    // each differing file named, not just the first
    private fun checkAll(shots: List<Shot>) = check(shots.map { it.name to it.scene })

    private fun check(shots: List<Pair<String, Scene>>) {
        val failed = shots.mapNotNull { (name, scene) ->
            val text = SceneText.of(scene)
            val path = "$TEST_DATA/snapshots/$name.txt"
            val committed = readText(path)
            when {
                recording() -> null.also { writeText(path, text) }
                committed == null -> "no $path: run the JVM tests with -Psnapshots.record=true"
                committed != text -> "$path differs: review, then record anew"
                else -> null
            }
        }
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
    }
}
