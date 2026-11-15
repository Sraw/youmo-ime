/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

/**
 * The icons a scene names that the app draws (its `SceneIcons`, held to this list by a test there): what
 * a theme's `icon(name)` (`space-content`, `return-icon-<action>`) may name, and the names a theme's `<icon>`
 * replaces the app's of, on the keys and the bar alike (the bar's: ime-core's BarIcons). A name not among them is a
 * problem when the theme is read: the app would draw nothing for it.
 */
object Icons {
    /** Return's, by the action the field asks for (the app's ReturnKeyAppearance). */
    val RETURN = listOf("enter", "go", "search", "send", "next", "done", "previous")

    /** A shortcut's command's (TextBoardScene.icon of each KeyCommand). */
    val COMMANDS = listOf(
        "select-all", "content-copy", "content-cut", "content-paste", "undo", "redo", "delete-sweep", "restore",
        "field-start", "field-end", "cursor-move", "clipboard", "quick-input", "emoji", "voice", "trad-simp",
        "choose-keyboard", "hide-keyboard", "swipe-select", "stroke-h", "stroke-s", "stroke-p", "stroke-n", "stroke-z",
    )

    /** A key's own (a layout's `icon=`, the keys the app draws an icon on). */
    val KEYS = listOf(
        "backspace", "language", "space", "shift", "shift-once", "shift-locked", "number-pad", "lock", "lock-open",
        "emoji", "voice", "arrow-upward", "arrow-downward",
    )

    /**
     * The bar's own, a name for each thing done that no command does (061-toolbar-icons §2.2; the bar's buttons
     * doing a command's job go by the command's name): the menu key's two states, the toolbar's buttons, the
     * candidate bar's and the floating keyboard's strip's. Keys may name them too.
     */
    val TOOLBAR = listOf(
        "toolbar-expand", "toolbar-collapse", "trad-simp-off", "more", "floating", "one-handed", "habits-off", "clear-recent",
        "theme", "input-method-settings", "handwriting", "candidates-expand", "candidates-collapse", "predictions-close",
        "floating-dock", "one-handed-exit", "touch-keyboard", "candidates-prev", "candidates-next",
    )

    /** The status panel's own (§2.8): its cells the bar has no button of, the engine's toggles on and off, its gear. */
    val PANEL = listOf(
        "keyboard-settings", "punctuation", "punctuation-off", "full-width", "full-width-off", "prediction", "prediction-off",
        "app-settings",
    )

    /** A swipe's text with no room on its key (MarkContent.Text.SIGN): a longer line over a shorter. */
    const val SHORT_TEXT = "short-text"

    /** [SHORT_TEXT]'s ink in its 24-unit square (the app's ic_baseline_short_text_24: M4,9h16v2H4z M4,13h10v2H4z). */
    val SHORT_TEXT_INK = Ink(4.0, 9.0, 20.0, 15.0)

    /** Where an icon's paths are in its [UNITS]-unit square. */
    data class Ink(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        /** The ink in [box], the icon's square as drawn. */
        fun of(box: Box): Box {
            val sx = box.width / UNITS
            val sy = box.height / UNITS
            return Box(box.left + left * sx, box.top + top * sy, box.left + right * sx, box.top + bottom * sy)
        }
    }

    private const val UNITS = 24.0

    val ALL: Set<String> = (RETURN + KEYS + SHORT_TEXT + COMMANDS + TOOLBAR + PANEL).toSet()
}
