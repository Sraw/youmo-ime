/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.BarLook
import org.fcitx.fcitx5.android.scene.engine.BarSample
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A theme the shots draw with: [file] of the test data, [styles] added at its end and [fonts] after its
 * colours; written so (FacadeShotsTest) that the theme maker's library's tests make the same one.
 */
data class ThemeSource(val file: String, val styles: List<String> = emptyList(), val fonts: String = "") {
    val text: String
        get() = if (styles.isEmpty() && fonts.isEmpty()) {
            testData(file)
        } else {
            testData(file).replace("</colors>", "</colors>$fonts").replace("</theme>", styles.joinToString("\n", postfix = "\n</theme>"))
        }

    val theme: Theme get() = read.getOrPut(this) { ThemeReader.theme(text).ok() }

    companion object {
        val LIGHT = ThemeSource("material-light.xml")
        val DARK = ThemeSource("material-dark.xml")
        private val read = HashMap<ThemeSource, Theme>()
    }
}

/** A scene the snapshots hold: [board] in [env], drawn by [theme], written to snapshots/[name].txt. */
class Shot(val name: String, val board: String, val env: Env, val theme: ThemeSource = ThemeSource.LIGHT) {
    val scene: Scene by lazy { Forms.scene(board, env, theme.theme) }
}

/** A popup's scene the snapshots hold: [ask] in [env], drawn by [theme], written to snapshots/[name].txt. */
class PopupShot(val name: String, val env: Env, val ask: PopupAsk, val theme: ThemeSource = ThemeSource.LIGHT) {
    val scene: Scene by lazy { ask.scene(SceneBuilder(theme.theme, env, FakeMeasurer), env) }
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
            Shot("qwerty-dark", "qwerty", block.copy(dark = true), ThemeSource.DARK),
            Shot("qwerty-dark-flat", "qwerty", Forms.flat.copy(dark = true), ThemeSource.DARK),
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
            // a long one smaller by itself: the function keys' text as ever
            Shot(
                "qwerty-return-long", "qwerty", block.copy(texts = block.texts.copy(returnIcon = "send")),
                styled("<style select=\"*/enter\" return-icon-send=\"text(发送这条消息)\"/>"),
            ),
        )
    }

    /**
     * 060-render-p3 T4b, a key's shape and cap: material-light with one more each on the block qwerty
     * (a path with curves and a border, an inner shadow, a depth, and the three with a shadow together).
     */
    val shapes: List<Shot> by lazy {
        val block = Forms.env("qwerty", Forms.block)
        // a rectangle rounded by quadratic curves, a cubic bulging its top
        val path = "path(M0 .15 Q0 0 .15 0 C.4 .05 .6 .05 .85 0 Q1 0 1 .15 V.85 Q1 1 .85 1 H.15 Q0 1 0 .85 Z)"
        listOf(
            Shot("qwerty-path", "qwerty", block, styled("<style select=\"key\" shape=\"$path\" border=\"1dp #40000000\"/>")),
            Shot("qwerty-inner-shadow", "qwerty", block, styled("<style select=\"key\" inner-shadow=\"0 1dp 3dp #60000000\"/>")),
            Shot("qwerty-depth", "qwerty", block, styled("<style select=\"key\" depth=\"3dp\"/>")),
            Shot(
                "qwerty-cap", "qwerty", block,
                styled("<style select=\"key\" shape=\"$path\" depth=\"2dp\" depth-color=\"#ff9e9e9e\" inner-shadow=\"0 -1dp 2dp #50ffffff\"/>"),
            ),
        )
    }

    val all: List<Shot> get() = forms + states + looks + shapes

    /**
     * Each popup ([Forms.popups]) in the forms that change how one looks: block, flat, floating (its text
     * smaller); and on the block, T4b's: the bubble with its tail, larger and up; a path; the long press's
     * cell in focus with its radius and border.
     */
    val popups: List<PopupShot> = listOf("block" to Forms.block, "flat" to Forms.flat, "floating" to Forms.floating).flatMap { (form, env) ->
        Forms.popupAsks.map { (name, ask) -> PopupShot("popup-$name-$form", env, ask) }
    } + listOf(
        "preview-bubble" to "<style select=\"preview\" shape=\"bubble\"/>",
        "preview-bubble-large-up" to "<style select=\"preview\" shape=\"bubble\" size=\"130%\" offset=\"-6dp\"/>",
        "preview-path" to "<style select=\"preview\" shape=\"path(M0 0 H1 V.6 L.6 .6 L.5 .75 L.4 .6 L0 .6 Z)\" inner-shadow=\"0 2dp 2dp #30000000\"/>",
        "keys-selected-border" to "<style select=\"popup:selected\" radius=\"8dp\" border=\"1dp #ff1565c0\"/>",
    ).map { (name, style) ->
        val asks = Forms.popupAsks.toMap()
        PopupShot("popup-$name-block", Forms.block, asks.getValue(if (name.startsWith("keys")) "keys" else "preview-symbol"), styled(style))
    }

    /**
     * T6a's sample candidate bar ([BarSample]) on a phone (2.75 px a dp, 候选字号 20sp): material-light's, as
     * the app drew it before; a theme's pills on a coloured bar, the first in another colour, numbered,
     * the comment its own colour, spaced; dotted, no lines between, padded by the theme; in dark.
     */
    val bars: List<PopupShot> = listOf(
        "default" to emptyList(),
        "capsule" to listOf(
            "<style select=\"bar\" fill=\"#ffeceff1\"/>",
            "<style select=\"candidate\" fill=\"#ffffffff\" radius=\"50%\" inset=\"6dp 2dp\" gap=\"4dp\" label-style=\"number\"/>",
            "<style select=\"candidate:first\" fill=\"@accentKey\" color=\"#ffffffff\"/>",
            "<style select=\"candidate comment\" color=\"#ff1565c0\"/>",
            "<style select=\"candidates-divider\" fill=\"transparent\"/>",
        ),
        "dot-padded" to listOf(
            "<style select=\"candidate\" padding=\"0 16dp\" label-style=\"dot\" weight=\"700\"/>",
            "<style select=\"candidate label\" color=\"#ffe53935\"/>",
            "<style select=\"candidates-divider\" fill=\"#ff90a4ae\"/>",
        ),
        "dark" to listOf("<style select=\"@dark candidate\" fill=\"#ff263238\" color=\"#ffeceff1\"/>", "<style select=\"@dark bar\" fill=\"#ff000000\"/>"),
    ).map { (name, styles) ->
        PopupShot("bar-$name") {
            val scenes = if (name == "dark") setOf("dark") else emptySet()
            BarSample.scene(BarLook.of(Cascades(styled(*styles.toTypedArray())), scenes), BAR, FakeMeasurer)
        }
    }

    private val BAR = BarSample.Metrics(width = 1080.0, height = 110.0, density = 2.75, textSize = 55.0, paddingDp = 9.0, minWidthDp = 32.0)

    // material-light with [styles] more, and [fonts]
    private fun styled(vararg styles: String, fonts: String = "") = ThemeSource("material-light.xml", styles.toList(), fonts)

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
    fun theShapeOfAKey() = checkAll(Shots.shapes)

    @Test
    fun everyPopup() = check(Shots.popups.map { it.name to it.scene })

    @Test
    fun theSampleCandidateBar() = check(Shots.bars.map { it.name to it.scene })

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
        val names = Shots.all.map { it.name } + Shots.popups.map { it.name } + Shots.bars.map { it.name }
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
