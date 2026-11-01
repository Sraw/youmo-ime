/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

/**
 * The buttons the bar above the keyboard may have besides its menu key. The menu key and [More]
 * are always there (the ⋯ panel is the way to the settings and to fcitx's own toggles), so not
 * the user's to set. [key] is what the preference stores: never renamed.
 */
enum class ToolbarButton(val key: String) {
    Undo("undo"),
    Redo("redo"),
    CursorMove("cursor_move"),
    Clipboard("clipboard"),

    /** The ⋯ panel (StatusAreaWindow): fixed, the toolbar's last, never in [ToolbarConfig.items] */
    More("more"),
    Emoji("emoji"),

    /** In a voice build only, and never in a password field: nothing said out loud goes into one */
    Voice("voice"),
    Hide("hide"),

    // the ⋯ panel's own, the same actions there and here
    /** chttrans's 简繁, where the input method has it */
    TradSimp("trad_simp"),
    Floating("floating"),

    /** Not where the keyboard floats or its letters split, as in the panel */
    OneHanded("one_handed"),
    Habits("habits"),
    ClearRecent("clear_recent"),
    Theme("theme"),
    InputMethodSettings("input_method_settings"),

    /** 键盘: the input method's keyboards to pick from (KeyboardPicker), where it has a choice */
    Keyboard("keyboard");

    companion object {
        fun ofKey(key: String) = entries.firstOrNull { it.key == key }

        /** The ones the user sets: all but the fixed [More] */
        val settable get() = entries.filter { it != More }
    }
}

/**
 * Which buttons the bar has and where, as the user set them in one list: the ones before
 * [DIVIDER] on the toolbar the menu key opens, the ones after it on the bar's right whenever
 * it is idle; each shown or not. Every settable [ToolbarButton] is in it once, so a button added
 * in a later version comes in hidden, before the divider ([parse]), the bar as the user left it.
 */
data class ToolbarConfig(val items: List<Item>) {

    sealed interface Item {
        data class Button(val button: ToolbarButton, val shown: Boolean) : Item

        /** Where the toolbar's buttons end and the always-there ones begin */
        data object Divider : Item
    }

    fun serialize(): String = items.joinToString(",") {
        when (it) {
            Item.Divider -> DIVIDER
            is Item.Button -> if (it.shown) it.button.key else HIDDEN + it.button.key
        }
    }

    /**
     * What the bar has in [situation], with room for [maxPinned] at the right: the ones after that
     * go onto the toolbar, in order, before the fixed ⋯ at its end. No divider: all on the toolbar.
     */
    fun layout(situation: Situation, maxPinned: Int = Int.MAX_VALUE): Layout {
        val divider = items.indexOf(Item.Divider).takeIf { it >= 0 } ?: items.size
        fun shown(range: List<Item>) = range.filterIsInstance<Item.Button>()
            .filter { it.shown && it.button != ToolbarButton.More && situation.offers(it.button) }
            .map { it.button }
        val pinned = shown(items.subList(minOf(divider + 1, items.size), items.size))
        val fits = pinned.take(maxPinned.coerceAtLeast(0))
        return Layout(shown(items.subList(0, divider)) + pinned.drop(fits.size) + ToolbarButton.More, fits)
    }

    /** What the keyboard is in, for the buttons that are of use only in some. */
    data class Situation(
        /** a voice build, not in a password field */
        val voice: Boolean,
        /** the input method has chttrans's 简繁 */
        val tradSimp: Boolean,
        /** not floating, the letters not split */
        val oneHanded: Boolean,
        /** the input method has keyboards to choose from */
        val keyboards: Boolean,
    ) {
        fun offers(button: ToolbarButton) = when (button) {
            ToolbarButton.Voice -> voice
            ToolbarButton.TradSimp -> tradSimp
            ToolbarButton.OneHanded -> oneHanded
            ToolbarButton.Keyboard -> keyboards
            else -> true
        }
    }

    /** [toolbar] in the middle when it is opened, [pinned] at the right, after the middle. */
    data class Layout(val toolbar: List<ToolbarButton>, val pinned: List<ToolbarButton>)

    companion object {
        const val DIVIDER = "|"
        private const val HIDDEN = "-"

        /** The bar as it always was: what v0.4 had, and the panel's others hidden on the toolbar. */
        val DEFAULT = ToolbarConfig(
            listOf(ToolbarButton.Undo, ToolbarButton.Redo, ToolbarButton.CursorMove, ToolbarButton.Clipboard)
                .map { Item.Button(it, true) } +
                listOf(
                    ToolbarButton.TradSimp, ToolbarButton.Floating, ToolbarButton.OneHanded, ToolbarButton.Habits,
                    ToolbarButton.ClearRecent, ToolbarButton.Theme, ToolbarButton.InputMethodSettings, ToolbarButton.Keyboard,
                ).map { Item.Button(it, false) } +
                Item.Divider +
                listOf(ToolbarButton.Emoji, ToolbarButton.Voice, ToolbarButton.Hide).map { Item.Button(it, true) }
        )

        /**
         * [raw] as [serialize] wrote it; empty (never set) is [DEFAULT]. A key not known (from a
         * later version) or twice is dropped, and so is ⋯ (fixed); a button missing is put in hidden
         * before the divider.
         */
        fun parse(raw: String): ToolbarConfig {
            if (raw.isEmpty()) return DEFAULT
            val items = mutableListOf<Item>()
            for (token in raw.split(',')) {
                val item = if (token == DIVIDER) Item.Divider else {
                    val hidden = token.startsWith(HIDDEN)
                    ToolbarButton.ofKey(token.removePrefix(HIDDEN))?.takeIf { it != ToolbarButton.More }?.let { Item.Button(it, !hidden) }
                }
                if (item != null && items.none { it.sameAs(item) }) items += item
            }
            if (Item.Divider !in items) items += Item.Divider
            val missing = ToolbarButton.settable.filter { b -> items.none { it is Item.Button && it.button == b } }
            items.addAll(items.indexOf(Item.Divider), missing.map { Item.Button(it, false) })
            return ToolbarConfig(items)
        }

        private fun Item.sameAs(other: Item) = when (this) {
            Item.Divider -> other == Item.Divider
            is Item.Button -> other is Item.Button && other.button == button
        }
    }
}
