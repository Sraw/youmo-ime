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

    /** Never in a password field: nothing said out loud goes into one */
    Voice("voice"),
    Hide("hide"),

    // the ⋯ panel's own, the same actions there and here
    /** chttrans's 简繁, where the input method has it */
    TradSimp("trad_simp"),
    Floating("floating"),

    /** Not where the keyboard floats, as in the panel; split, it goes one-handed over the split (061-wide-keyboard-style §3.3) */
    OneHanded("one_handed"),
    Habits("habits"),
    ClearRecent("clear_recent"),
    Theme("theme"),
    InputMethodSettings("input_method_settings"),

    /** 键盘: the input method's keyboards to pick from (KeyboardPicker), where it has a choice */
    Keyboard("keyboard"),

    /** 快速输入, as a long press on 符 opens it */
    QuickPhrase("quick_input"),

    /**
     * 手写: written over the letters of the input method in use, on or off (061-handwriting-toolbar); not in
     * English nor a password field
     */
    Handwriting("handwriting");

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
     * What the bar has in [situation]: the toolbar's, then the fixed ⋯ at its end; every one shown after the divider
     * at the right, however narrow the bar (the user's call, 2026-11-15: never one left out, [Layout.slots]). 手写 where
     * it is not written (English, a password field) keeps its place, [Layout.unavailable]: nothing moves as 中/英
     * switch (the user's call, 2026-11-15). No divider: all on the toolbar.
     */
    fun layout(situation: Situation): Layout {
        val divider = items.indexOf(Item.Divider).takeIf { it >= 0 } ?: items.size
        fun shown(range: List<Item>) = range.filterIsInstance<Item.Button>()
            .filter { it.shown && it.button != ToolbarButton.More && (it.button == ToolbarButton.Handwriting || situation.offers(it.button)) }
            .map { it.button }
        return Layout(
            shown(items.subList(0, divider)) + ToolbarButton.More,
            shown(items.subList(minOf(divider + 1, items.size), items.size)),
            if (situation.handwriting) emptySet() else setOf(ToolbarButton.Handwriting),
        )
    }

    /** What the keyboard is in, for the buttons that are of use only in some. */
    data class Situation(
        /** not in a password field (0.5.x: and a voice build) */
        val voice: Boolean,
        /** the input method has chttrans's 简繁 */
        val tradSimp: Boolean,
        /** not floating */
        val oneHanded: Boolean,
        /** the input method has keyboards to choose from */
        val keyboards: Boolean,
        /** handwriting may be written: not a password field, not English (HandwritingMode.offered); else greyed in its place */
        val handwriting: Boolean = true,
    ) {
        fun offers(button: ToolbarButton) = when (button) {
            ToolbarButton.Voice -> voice
            ToolbarButton.Handwriting -> handwriting
            ToolbarButton.TradSimp -> tradSimp
            ToolbarButton.OneHanded -> oneHanded
            ToolbarButton.Keyboard -> keyboards
            else -> true
        }
    }

    /**
     * This with 手写 shown first of the buttons at the right (after the divider), whatever the user had of it:
     * once, as 0.6.0's handwriting input method or 键盘手写 becomes it (KotlinHost, AppPrefs), so it is there to tap.
     */
    fun withHandwritingPinned(): ToolbarConfig {
        val rest = items.filterNot { it is Item.Button && it.button == ToolbarButton.Handwriting }
        // none but where made so (parse puts one in): the bar's right begins after the rest, 手写 alone there
        val divider = rest.indexOf(Item.Divider).takeIf { it >= 0 } ?: return ToolbarConfig(rest + Item.Divider + Item.Button(ToolbarButton.Handwriting, true))
        return ToolbarConfig(rest.subList(0, divider + 1) + Item.Button(ToolbarButton.Handwriting, true) + rest.subList(divider + 1, rest.size))
    }

    /**
     * This with 手写 shown at the bar's right after 表情 (where [DEFAULT] has it; first there without 表情): for a bar
     * arranged before 手写 was there to choose ([handwritingNeverOffered]).
     */
    fun withHandwritingOffered(): ToolbarConfig {
        val rest = items.filterNot { it is Item.Button && it.button == ToolbarButton.Handwriting }.toMutableList()
        if (Item.Divider !in rest) rest += Item.Divider
        val divider = rest.indexOf(Item.Divider)
        val emoji = rest.indexOfFirst { it is Item.Button && it.button == ToolbarButton.Emoji }
        rest.add(if (emoji > divider) emoji + 1 else divider + 1, Item.Button(ToolbarButton.Handwriting, true))
        return ToolbarConfig(rest)
    }

    /**
     * [toolbar] in the middle when it is opened, [pinned] at the right, after the middle; [unavailable] in their place
     * all the same, drawn greyed, a tap saying why.
     */
    data class Layout(
        val toolbar: List<ToolbarButton>,
        val pinned: List<ToolbarButton>,
        val unavailable: Set<ToolbarButton> = emptySet(),
    ) {
        /**
         * Nothing of the toolbar's to show here, none ticked or none of use ([layout]; a greyed 手写 holds its slot, so
         * counts): the menu key is ⋯ itself, a tap opening its panel, the toolbar never opened, 默认展开工具栏 of no
         * effect (the user's call, 2026-11-15).
         */
        val moreOnly: Boolean get() = toolbar.all { it == ToolbarButton.More }

        /**
         * The bar of [rowPx] split into slots of one width (the user's call, 2026-11-15): as many as fit at about
         * [preferredPx] (a bar button's own), the menu key in the first, [pinned] in the last, the toolbar open or shut
         * alike, so nothing moves nor changes width as it opens. Never fewer than the menu key, [pinned] and
         * [MIDDLE_SLOTS] between: a narrow bar (the floating keyboard's) has its slots narrower, every one at the right
         * kept. Open, the toolbar's in the slots between, ⋯ last; those with no slot left are in ⋯'s panel, none
         * scrolled. Not laid out yet ([rowPx] 0): [preferredPx] wide, all on the toolbar.
         */
        fun slots(rowPx: Int, preferredPx: Int): Slots {
            if (rowPx <= 0 || preferredPx <= 0) return Slots(0, preferredPx, toolbar, emptyList())
            val count = maxOf((rowPx + preferredPx / 2) / preferredPx, 1 + MIDDLE_SLOTS + pinned.size)
            val more = toolbar.filter { it == ToolbarButton.More }
            val others = toolbar - more.toSet()
            val room = count - 1 - pinned.size - more.size
            return if (others.size <= room) Slots(count, rowPx / count, toolbar, emptyList())
            else Slots(count, rowPx / count, others.take(room) + more, others.drop(room))
        }
    }

    /**
     * The bar split into [count] slots [cellPx] wide (what is left over, less than a slot, after the toolbar's);
     * [toolbar] the open toolbar's, ⋯ last; [overflow] the toolbar's with no slot, in ⋯'s panel in this order.
     */
    data class Slots(val count: Int, val cellPx: Int, val toolbar: List<ToolbarButton>, val overflow: List<ToolbarButton>)

    companion object {
        const val DIVIDER = "|"

        /**
         * The least slots between the menu key and the right's: shut, a clipboard suggestion's or a sum's room; open,
         * one of the toolbar's and ⋯.
         */
        const val MIDDLE_SLOTS = 2
        private const val HIDDEN = "-"

        /**
         * The bar as it always was: what v0.4 had, and the panel's others hidden on the toolbar. 快速输入
         * among them, first: one to add, not on the bar till the user ticks it (the user's call,
         * 2026-11-02; 符's long press and the gestures' command open it as ever). A bar the user
         * arranged gets it hidden too, as any button added later ([parse]). 手写 at the right among the
         * always-there ones (the user's call, 061-handwriting-toolbar Q2).
         */
        val DEFAULT = ToolbarConfig(
            listOf(ToolbarButton.Undo, ToolbarButton.Redo, ToolbarButton.CursorMove, ToolbarButton.Clipboard)
                .map { Item.Button(it, true) } +
                listOf(
                    ToolbarButton.QuickPhrase, ToolbarButton.TradSimp, ToolbarButton.Floating, ToolbarButton.OneHanded, ToolbarButton.Habits,
                    ToolbarButton.ClearRecent, ToolbarButton.Theme, ToolbarButton.InputMethodSettings, ToolbarButton.Keyboard,
                ).map { Item.Button(it, false) } +
                Item.Divider +
                listOf(ToolbarButton.Emoji, ToolbarButton.Handwriting, ToolbarButton.Voice, ToolbarButton.Hide).map { Item.Button(it, true) }
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

        /**
         * Whether [raw], a bar the user arranged, is one from before 手写 was on the list to choose: 0.6.0's, no 手写 in
         * it (parse adds it hidden, as any button added later, but this one the user's call has on the bar, 2026-11-15);
         * or, [firstLook] (the first start of a version with this), 0.6.1's first test build's, where the settings page
         * wrote it unticked before the divider, as parse put it in. One the user took off since it was there to choose
         * (unticked at the right, or moved) stays so. Empty: never arranged, [DEFAULT] has it.
         */
        fun handwritingNeverOffered(raw: String, firstLook: Boolean): Boolean {
            if (raw.isEmpty()) return false
            val tokens = raw.split(',')
            val at = tokens.indexOfFirst { it.removePrefix(HIDDEN) == ToolbarButton.Handwriting.key }
            if (at < 0) return true
            val divider = tokens.indexOf(DIVIDER).takeIf { it >= 0 } ?: tokens.size
            return firstLook && at < divider && tokens[at].startsWith(HIDDEN)
        }

        private fun Item.sameAs(other: Item) = when (this) {
            Item.Divider -> other == Item.Divider
            is Item.Button -> other is Item.Button && other.button == button
        }
    }
}
