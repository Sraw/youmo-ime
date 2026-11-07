/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MenuCell
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader

/**
 * The phone the snapshots are of, and the keyboard's size on it in each form, as the app works them
 * out (rules D §1, §2): 1080 × 2340 px at 2.625 (420 dpi), 411 dp across upright, 891 on its side.
 *
 * The keyboard's height is the screen's times the height setting: 30% upright and 49% on its side
 * by default, compact the setting's lowest level (20%, 34%; KeyboardHeightLevels). The cap, 70% of
 * the screen less the candidate bar, the code row and the bottom padding (KeyboardHeightCap), does
 * not bind at any of these. Floating: 70% of the width, kept to 300–420 dp, so 300 dp; 85% of the
 * upright height; its text 0.85 (FloatingKeyboard). One-handed: 80% of the width, 328 dp
 * (SplitKeyboard.placement); split on its side, the letters across the whole width, the other boards
 * docked right at 42% (the default split_dock; Mode.AUTO splits from 600 dp).
 */
object Forms {
    const val DENSITY = 2.625
    private const val SCREEN_LONG = 2340.0
    private const val SCREEN_SHORT = 1080.0
    private const val UPRIGHT = 0.30
    private const val UPRIGHT_COMPACT = 0.20
    private const val SIDEWAYS = 0.49
    private const val SIDEWAYS_COMPACT = 0.34
    private const val FLOATING_MAX_DP = 420.0
    private const val FLOATING_WIDTH_DP = 300.0
    private const val FLOATING_HEIGHT = 0.85
    private const val FLOATING_TEXT = 0.85

    private val portraitHeight = (SCREEN_LONG * UPRIGHT).toInt().toDouble()

    /** The texts the app gives a pinyin keyboard in Chinese: Chinese punctuation, its name on the space, the screen reader's. */
    val texts = Texts(
        imeName = "拼音", comma = "，", period = "。", returnLabel = "换行", returnIcon = "enter",
        alts = Fixtures.qwertyAlts,
        punctuation = mapOf(
            "," to "，", "." to "。", "?" to "？", "!" to "！", "~" to "～", ":" to "：", "(" to "（", ")" to "）",
        ),
        // values-zh-rCN's a11y_key_grouped and a11y_key_space_with_ime
        a11yGrouped = "%1\$s，%2\$s",
        a11ySpace = "空格，%1\$s",
    )

    private val base = Env(SCREEN_SHORT, portraitHeight, DENSITY, input = Input(langKey = true), texts = texts)

    /** Block, the default 按键样式. */
    val block = base

    val flat = base.copy(flat = true)

    val compact = base.copy(height = (SCREEN_LONG * UPRIGHT_COMPACT).toInt().toDouble(), compact = true)

    /** On its side, not split (split_mode off). */
    val landscape = base.copy(width = SCREEN_LONG, height = (SCREEN_SHORT * SIDEWAYS).toInt().toDouble(), landscape = true)

    /** On its side, split (Mode.AUTO at 891 dp). */
    val split = landscape.copy(split = true)

    val floating = base.copy(
        width = FLOATING_WIDTH_DP * DENSITY,
        height = (portraitHeight * FLOATING_HEIGHT).toInt().toDouble(),
        floating = FLOATING_TEXT,
    )

    val oneHandedLeft = base.copy(oneHanded = SplitKeyboard.Dock.LEFT)

    val oneHandedRight = base.copy(oneHanded = SplitKeyboard.Dock.RIGHT)

    /** On its side and compact: the setting's lowest level there, 34%. */
    val landscapeCompact = landscape.copy(height = (SCREEN_SHORT * SIDEWAYS_COMPACT).toInt().toDouble(), compact = true)

    /** Floating on its side: 70% of 891 dp kept to 420; 85% of the docked height (the width's share, 47%, is under it). */
    val floatingLandscape = landscape.copy(
        width = FLOATING_MAX_DP * DENSITY,
        height = (landscape.height * FLOATING_HEIGHT).toInt().toDouble(),
        floating = FLOATING_TEXT,
    )

    /** One-handed on its side, not split: 80% of 891 dp is 712, kept to 420 (SplitKeyboard.DOCK_MAX_DP). */
    val oneHandedLandscape = landscape.copy(oneHanded = SplitKeyboard.Dock.LEFT)

    /** Every form, by the name its snapshot carries. */
    val all: Map<String, Env> = linkedMapOf(
        "block" to block, "flat" to flat, "compact" to compact, "landscape" to landscape, "split" to split,
        "floating" to floating, "one-handed-left" to oneHandedLeft, "one-handed-right" to oneHandedRight,
        "landscape-compact" to landscapeCompact, "floating-landscape" to floatingLandscape, "one-handed-landscape" to oneHandedLandscape,
    )

    /** Every board of rules C, by its id (KeyNames.BOARDS less emoji, which is no layout). */
    val boards = listOf("qwerty", "nine", "k14", "k18", "zhuyin", "zhuyin-nine", "number", "handwriting", "symbols")

    private val layouts = mutableMapOf<String, Layout>()

    fun layout(board: String): Layout = layouts.getOrPut(board) { LayoutReader.layout(testData("$board.xml")).ok() }

    /** The files the boards include, by id. */
    val fragments: Map<String, Fragment> by lazy {
        listOf("grouped-bottom").associateWith { LayoutReader.fragment(testData("$it.xml")).ok() }
    }

    val materialDark: Theme by lazy { ThemeReader.theme(testData("material-dark.xml")).ok() }

    /** [form] with what the app tells [board] by default: 注音 for the zhuyin boards, the full pad for number. */
    fun env(board: String, form: Env): Env = when (board) {
        "zhuyin", "zhuyin-nine" -> form.copy(input = form.input.copy(ime = "zhuyin"), texts = form.texts.copy(imeName = "注音"))
        "number" -> form.copy(input = form.input.copy(pad = "full"))
        else -> form
    }

    fun scene(board: String, env: Env, theme: Theme = Fixtures.materialLight): Scene =
        SceneBuilder(theme, env, FakeMeasurer).build(layout(board), fragments).ok()

    /**
     * The popups in [env], by name, as the app asks for them: a letter's bubble over a key 36 dp
     * across, a shortcut's words in one; a long press's seven keys in two rows round the second
     * column, the top row's last slot empty (PopupKeyboardUi's order); the edit menu's icons, its
     * middle in focus and its last disabled (a password field's); 手写's 注 menu of words.
     */
    fun popups(env: Env): List<Pair<String, () -> Scene>> {
        val b = SceneBuilder(Fixtures.materialLight, env, FakeMeasurer)
        val key = 36 * env.density
        val letters = listOf("a", "b", "c", "d", "e", "f", "g")
        val edit = listOf("select-all", "cut", "copy", "paste", "undo").map { MenuCell(it, it) }
        val reading = listOf(MenuCell("关", null), MenuCell("拼音", null), MenuCell("注音符号", null))
        return listOf(
            "preview-symbol" to { b.preview("g", key).ok() },
            "preview-words" to { b.preview("快速输入", key, b.previewWordsSize().first).ok() },
            "keys" to {
                b.popup(letters, listOf(listOf(2, 0, 1, 3), listOf(6, 4, 5, -1)), b.popupKeys(letters, 4, env.width), selected = 0).ok()
            },
            "menu-icons" to { b.menu(edit, edit.indices.toList(), selected = 2, disabled = setOf(4)).ok() },
            "menu-words" to { b.menu(reading, listOf(1, 0, 2), selected = 0).ok() },
        )
    }
}
