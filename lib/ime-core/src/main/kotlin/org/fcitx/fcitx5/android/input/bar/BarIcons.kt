/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

/**
 * The icons outside a keyboard's scene, by the names a theme's `<icon>` replaces; one table, so no view names its
 * own. A button doing a command's job has its name (`undo`, `hide-keyboard`), so a theme replaces both. Without the
 * name, [Icon.fallback] (which may differ from the key's). Names are written here, not made of [ToolbarButton.key]
 * (a preference value); a test holds them to the scene's `Icons.ALL`.
 */
object BarIcons {

    /** The app's drawable of a place (its ToolbarButtonLooks). */
    enum class Fallback {
        ExpandMore, ExpandLess, Undo, Redo, CursorMove, Clipboard, MoreHoriz, TagFaces, Voice, ArrowDropDown,
        ChttransTrad, ChttransSimp, OpenWith, PanTool, ViewPrivate, DeleteSweep, Palette, Language, Dialpad,
        Keyboard, QuickPhrase, Handwriting, Close, OpenInFull, PuncActive, PuncInactive, FullwidthActive,
        FullwidthInactive, PredictionActive, PredictionInactive, Settings, Backspace, ArrowPrev, ArrowNext, VerticalAlignBottom,
    }

    data class Icon(val name: String, val fallback: Fallback)

    /** The menu key, the toolbar shut. */
    val TOOLBAR_EXPAND = Icon("toolbar-expand", Fallback.ExpandMore)

    /** The menu key, the toolbar open. */
    val TOOLBAR_COLLAPSE = Icon("toolbar-collapse", Fallback.ExpandMore)

    val UNDO = Icon("undo", Fallback.Undo)
    val REDO = Icon("redo", Fallback.Redo)
    val CURSOR_MOVE = Icon("cursor-move", Fallback.CursorMove)

    /** The toolbar's, the idle bar's strip, the editing panel's button. */
    val CLIPBOARD = Icon("clipboard", Fallback.Clipboard)
    val MORE = Icon("more", Fallback.MoreHoriz)
    val EMOJI = Icon("emoji", Fallback.TagFaces)
    val VOICE = Icon("voice", Fallback.Voice)
    val HIDE_KEYBOARD = Icon("hide-keyboard", Fallback.ArrowDropDown)

    /** 简繁, traditional on. */
    val TRAD_SIMP = Icon("trad-simp", Fallback.ChttransTrad)

    /** 简繁, simplified. */
    val TRAD_SIMP_OFF = Icon("trad-simp-off", Fallback.ChttransSimp)
    val FLOATING = Icon("floating", Fallback.OpenWith)
    val ONE_HANDED = Icon("one-handed", Fallback.PanTool)
    val HABITS_OFF = Icon("habits-off", Fallback.ViewPrivate)
    val CLEAR_RECENT = Icon("clear-recent", Fallback.DeleteSweep)
    val THEME = Icon("theme", Fallback.Palette)
    val INPUT_METHOD_SETTINGS = Icon("input-method-settings", Fallback.Language)
    val CHOOSE_KEYBOARD = Icon("choose-keyboard", Fallback.Dialpad)
    val QUICK_INPUT = Icon("quick-input", Fallback.QuickPhrase)

    /** The toolbar's 手写 and the candidate bar's while it is on. */
    val HANDWRITING = Icon("handwriting", Fallback.Handwriting)
    val CANDIDATES_EXPAND = Icon("candidates-expand", Fallback.ExpandMore)
    val CANDIDATES_COLLAPSE = Icon("candidates-collapse", Fallback.ExpandLess)
    val PREDICTIONS_CLOSE = Icon("predictions-close", Fallback.Close)

    /** No diagonal: [FLOATING_RESIZE]'s is one, at the other end. */
    val FLOATING_DOCK = Icon("floating-dock", Fallback.VerticalAlignBottom)

    /** Dragged to resize: ⤢, mirrored (⤡) where the keyboard grows up and left. */
    val FLOATING_RESIZE = Icon("floating-resize", Fallback.OpenInFull)

    /** One-handed, the empty side's button: the whole width again. */
    val ONE_HANDED_EXIT = Icon("one-handed-exit", Fallback.OpenInFull)

    /** The floating candidate window's (a hardware keyboard's): the touch keyboard back. */
    val TOUCH_KEYBOARD = Icon("touch-keyboard", Fallback.Keyboard)

    val CANDIDATES_PREV = Icon("candidates-prev", Fallback.ArrowPrev)
    val CANDIDATES_NEXT = Icon("candidates-next", Fallback.ArrowNext)

    /** The status panel's 虚拟键盘, the settings' 按键 page: no button of the bar's. */
    val KEYBOARD_SETTINGS = Icon("keyboard-settings", Fallback.Keyboard)

    val APP_SETTINGS = Icon("app-settings", Fallback.Settings)

    val BACKSPACE = Icon("backspace", Fallback.Backspace)

    /** [TradSimp][ToolbarButton.TradSimp]'s simplified, as made before its state is known; [tradSimp] has both. */
    fun of(button: ToolbarButton): Icon = when (button) {
        ToolbarButton.Undo -> UNDO
        ToolbarButton.Redo -> REDO
        ToolbarButton.CursorMove -> CURSOR_MOVE
        ToolbarButton.Clipboard -> CLIPBOARD
        ToolbarButton.More -> MORE
        ToolbarButton.Emoji -> EMOJI
        ToolbarButton.Voice -> VOICE
        ToolbarButton.Hide -> HIDE_KEYBOARD
        ToolbarButton.TradSimp -> TRAD_SIMP_OFF
        ToolbarButton.Floating -> FLOATING
        ToolbarButton.OneHanded -> ONE_HANDED
        ToolbarButton.Habits -> HABITS_OFF
        ToolbarButton.ClearRecent -> CLEAR_RECENT
        ToolbarButton.Theme -> THEME
        ToolbarButton.InputMethodSettings -> INPUT_METHOD_SETTINGS
        ToolbarButton.Keyboard -> CHOOSE_KEYBOARD
        ToolbarButton.QuickPhrase -> QUICK_INPUT
        ToolbarButton.Handwriting -> HANDWRITING
    }

    fun tradSimp(traditional: Boolean): Icon = if (traditional) TRAD_SIMP else TRAD_SIMP_OFF

    fun menu(open: Boolean): Icon = if (open) TOOLBAR_COLLAPSE else TOOLBAR_EXPAND

    val menuStates: List<Icon> get() = listOf(TOOLBAR_EXPAND, TOOLBAR_COLLAPSE)

    /** By fcitx's icon name (chinese-addons' four, on and off); null for unikey's, absent here, or unknown. */
    fun engine(icon: String): Icon? = ENGINE[icon]

    private val ENGINE = mapOf(
        "fcitx-chttrans-active" to TRAD_SIMP,
        "fcitx-chttrans-inactive" to TRAD_SIMP_OFF,
        "fcitx-punc-active" to Icon("punctuation", Fallback.PuncActive),
        "fcitx-punc-inactive" to Icon("punctuation-off", Fallback.PuncInactive),
        "fcitx-fullwidth-active" to Icon("full-width", Fallback.FullwidthActive),
        "fcitx-fullwidth-inactive" to Icon("full-width-off", Fallback.FullwidthInactive),
        "fcitx-remind-active" to Icon("prediction", Fallback.PredictionActive),
        "fcitx-remind-inactive" to Icon("prediction-off", Fallback.PredictionInactive),
    )

    val ENGINE_ICONS: Set<String> get() = ENGINE.keys

    /** Read ahead when a theme is picked (the bar is made at once). */
    val BAR: List<Icon> = (
        listOf(TOOLBAR_EXPAND, TOOLBAR_COLLAPSE) + ToolbarButton.entries.map(::of) +
            listOf(TRAD_SIMP, CANDIDATES_EXPAND, CANDIDATES_COLLAPSE, PREDICTIONS_CLOSE, HANDWRITING, CLIPBOARD, FLOATING_DOCK, FLOATING_RESIZE) +
            listOf(ONE_HANDED_EXIT, TOUCH_KEYBOARD, CANDIDATES_PREV, CANDIDATES_NEXT)
        ).distinct()

    /** The status panel's and the panels' command buttons. */
    val PANELS: List<Icon> = (
        listOf(THEME, INPUT_METHOD_SETTINGS, KEYBOARD_SETTINGS, CHOOSE_KEYBOARD, ONE_HANDED, FLOATING, HABITS_OFF, CLEAR_RECENT) +
            ENGINE.values + listOf(APP_SETTINGS, CLIPBOARD, BACKSPACE)
        ).distinct()
}
