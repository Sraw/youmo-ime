/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MenuCell
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader

/**
 * The phone the snapshots are of (1080 × 2340 px at 2.625), and the keyboard's size on it in each form as the app works
 * them out: upright 30% of the screen (compact 20%), on its side four rows of 48 dp (compact 34).
 */
object Forms {
    const val DENSITY = 2.625
    private const val SCREEN_LONG = 2340.0
    private const val SCREEN_SHORT = 1080.0
    private const val UPRIGHT = 0.30
    private const val UPRIGHT_COMPACT = 0.20
    private const val SIDEWAYS_ROW_DP = 48.0
    private const val SIDEWAYS_COMPACT_ROW_DP = 34.0
    private const val ROWS = 4
    private const val FLOATING_SIDEWAYS = 0.49
    private const val FLOATING_MAX_DP = 420.0
    private const val FLOATING_WIDTH_DP = 300.0
    private const val FLOATING_HEIGHT = 0.85
    private const val FLOATING_TEXT = 0.85

    private val portraitHeight = (SCREEN_LONG * UPRIGHT).toInt().toDouble()

    val texts = Texts(
        imeName = "拼音", comma = "，", period = "。", returnLabel = "换行", returnIcon = "enter",
        alts = Fixtures.qwertyReadings,
        punctuation = mapOf(
            "," to "，", "." to "。", "?" to "？", "!" to "！", "~" to "～", ":" to "：", "(" to "（", ")" to "）",
        ),
        a11yGrouped = "%1\$s，%2\$s",
        a11ySpace = "空格，%1\$s",
    )

    private val base = Env(SCREEN_SHORT, portraitHeight, DENSITY, input = Input(langKey = true), texts = texts)

    val block = base

    val flat = base.copy(flat = true)

    val compact = base.copy(height = (SCREEN_LONG * UPRIGHT_COMPACT).toInt().toDouble(), compact = true)

    val landscape = base.copy(width = SCREEN_LONG, height = (ROWS * SIDEWAYS_ROW_DP * DENSITY).toInt().toDouble(), landscape = true)

    val split = landscape.copy(split = true)

    val floating = base.copy(
        width = FLOATING_WIDTH_DP * DENSITY,
        height = (portraitHeight * FLOATING_HEIGHT).toInt().toDouble(),
        floating = FLOATING_TEXT,
    )

    val oneHandedLeft = base.copy(oneHanded = SplitKeyboard.Dock.LEFT)

    val oneHandedRight = base.copy(oneHanded = SplitKeyboard.Dock.RIGHT)

    val landscapeCompact = landscape.copy(height = (ROWS * SIDEWAYS_COMPACT_ROW_DP * DENSITY).toInt().toDouble(), compact = true)

    /** Floating keeps 0.6.0's size: 85% of 0.6.0's docked height, 49% of the short side. */
    val floatingLandscape = landscape.copy(
        width = FLOATING_MAX_DP * DENSITY,
        height = ((SCREEN_SHORT * FLOATING_SIDEWAYS).toInt() * FLOATING_HEIGHT).toInt().toDouble(),
        floating = FLOATING_TEXT,
    )

    /** 80% of 891 dp is 712, kept to 420 (SplitKeyboard.DOCK_MAX_DP). */
    val oneHandedLandscape = landscape.copy(oneHanded = SplitKeyboard.Dock.LEFT)

    val all: Map<String, Env> = linkedMapOf(
        "block" to block, "flat" to flat, "compact" to compact, "landscape" to landscape, "split" to split,
        "floating" to floating, "one-handed-left" to oneHandedLeft, "one-handed-right" to oneHandedRight,
        "landscape-compact" to landscapeCompact, "floating-landscape" to floatingLandscape, "one-handed-landscape" to oneHandedLandscape,
    )

    val boards = listOf("qwerty", "nine", "k14", "k18", "zhuyin", "zhuyin-nine", "number", "symbols", "emoji", "emoticon", "emoji-search", "expanded-side", "number-row")

    private val layouts = mutableMapOf<String, Layout>()

    fun layout(board: String): Layout = layouts.getOrPut(board) { LayoutReader.layout(testData("$board.xml")).ok() }

    val fragments: Map<String, Fragment> by lazy {
        listOf("grouped-bottom").associateWith { LayoutReader.fragment(testData("$it.xml")).ok() }
    }

    val materialDark: Theme by lazy { ThemeReader.theme(testData("material-dark.xml")).ok() }

    /** [form] with what the app tells [board] by default, its keys' marks as built in (DefaultMarks). */
    fun env(board: String, form: Env): Env {
        val told = when (board) {
            "zhuyin", "zhuyin-nine" -> form.copy(input = form.input.copy(ime = "zhuyin"), texts = form.texts.copy(imeName = "注音"))
            "number" -> form.copy(input = form.input.copy(pad = "full"))
            else -> form
        }
        // the layout's own variant: no board here is 双拼's
        val firstUp = told.marks.firstUp
        val marks = DefaultMarks.shortcuts(layout(board), fragments, punctuation = told.texts.punctuation, firstUp = firstUp)
        val way = if (firstUp) ":up" else ":down"
        val readings = marks.entries.mapNotNull { (k, v) -> (v as? MarkContent.Symbol)?.takeIf { k.endsWith(way) }?.let { k.removeSuffix(way) to it.text } }.toMap()
        return told.copy(marks = told.marks.copy(shortcuts = marks), texts = told.texts.copy(alts = readings))
    }

    fun scene(board: String, env: Env, theme: Theme = Fixtures.materialLight): Scene =
        SceneBuilder(theme, env, FakeMeasurer).build(layout(board), fragments).ok()

    /** The popups in [env], by name, as the app asks for them (PopupKeyboardUi's order). */
    fun popups(env: Env, theme: Theme = Fixtures.materialLight): List<Pair<String, () -> Scene>> {
        val b = SceneBuilder(theme, env, FakeMeasurer)
        return popupAsks.map { (name, ask) -> name to { ask.scene(b, env) } }
    }

    /** As the theme maker's library is asked for them too (FacadeShotsTest). */
    val popupAsks: List<Pair<String, PopupAsk>> by lazy {
        val letters = listOf("a", "b", "c", "d", "e", "f", "g")
        val edit = listOf("select-all", "cut", "copy", "paste", "undo").map { MenuCell(it, it) }
        val reading = listOf(MenuCell("关", null), MenuCell("拼音", null), MenuCell("注音符号", null))
        listOf(
            "preview-symbol" to PopupAsk.Preview("g", KEY_DP, words = false),
            "preview-words" to PopupAsk.Preview("快速输入", KEY_DP, words = true),
            "keys" to PopupAsk.Keys(letters, listOf(listOf(2, 0, 1, 3), listOf(6, 4, 5, -1)), columns = 4, selected = 0),
            "menu-icons" to PopupAsk.Menu(edit, edit.indices.toList(), selected = 2, disabled = setOf(4)),
            "menu-words" to PopupAsk.Menu(reading, listOf(1, 0, 2), selected = 0, disabled = emptySet()),
        )
    }

    private const val KEY_DP = 36.0
}

sealed interface PopupAsk {
    fun scene(b: SceneBuilder, env: Env): Scene

    data class Preview(val text: String, val keyDp: Double, val words: Boolean) : PopupAsk {
        override fun scene(b: SceneBuilder, env: Env): Scene =
            b.preview(text, keyDp * env.density, if (words) b.previewWordsSize().ok().first else null).ok()
    }

    data class Keys(val labels: List<String>, val rows: List<List<Int>>, val columns: Int, val selected: Int) : PopupAsk {
        override fun scene(b: SceneBuilder, env: Env): Scene = b.popup(labels, rows, b.popupKeys(labels, columns, env.width).ok(), selected).ok()
    }

    data class Menu(val cells: List<MenuCell>, val order: List<Int>, val selected: Int, val disabled: Set<Int>) : PopupAsk {
        override fun scene(b: SceneBuilder, env: Env): Scene = b.menu(cells, order, selected, disabled).ok()
    }
}
