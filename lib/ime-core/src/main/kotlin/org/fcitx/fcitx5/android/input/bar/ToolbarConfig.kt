/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

/** The bar's optional buttons; the menu key and [More] are always there. [key] is stored: never rename it. */
enum class ToolbarButton(val key: String) {
    Undo("undo"),
    Redo("redo"),
    CursorMove("cursor_move"),
    Clipboard("clipboard"),

    /** Fixed, the toolbar's last, never in [ToolbarConfig.items]. */
    More("more"),
    Emoji("emoji"),

    /** Never in a password field: nothing said out loud goes into one. */
    Voice("voice"),
    Hide("hide"),

    TradSimp("trad_simp"),
    Floating("floating"),

    /** Not where the keyboard floats; split, it goes one-handed over the split. */
    OneHanded("one_handed"),
    Habits("habits"),
    ClearRecent("clear_recent"),
    Theme("theme"),
    InputMethodSettings("input_method_settings"),

    Keyboard("keyboard"),

    QuickPhrase("quick_input"),

    /** Not in English nor a password field. */
    Handwriting("handwriting");

    companion object {
        fun ofKey(key: String) = entries.firstOrNull { it.key == key }

        val settable get() = entries.filter { it != More }
    }
}

/** One list: before [DIVIDER] the toolbar, after it the bar's right. A button new in a later version comes in hidden before the divider. */
data class ToolbarConfig(val items: List<Item>) {

    sealed interface Item {
        data class Button(val button: ToolbarButton, val shown: Boolean) : Item

        data object Divider : Item
    }

    fun serialize(): String = items.joinToString(",") {
        when (it) {
            Item.Divider -> DIVIDER
            is Item.Button -> if (it.shown) it.button.key else HIDDEN + it.button.key
        }
    }

    /** Every one after the divider shows however narrow the bar; an unavailable 手写 keeps its place so nothing moves on 中/英. */
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

    data class Situation(
        val voice: Boolean,
        val tradSimp: Boolean,
        val oneHanded: Boolean,
        val keyboards: Boolean,
        /** Not a password field nor English (HandwritingMode.offered); else greyed in its place. */
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

    /** 手写 shown first at the right, once, as 0.6.0's handwriting input method becomes it (KotlinHost, AppPrefs). */
    fun withHandwritingPinned(): ToolbarConfig {
        val rest = items.filterNot { it is Item.Button && it.button == ToolbarButton.Handwriting }
        // none but where made so (parse puts one in): 手写 alone at the right
        val divider = rest.indexOf(Item.Divider).takeIf { it >= 0 } ?: return ToolbarConfig(rest + Item.Divider + Item.Button(ToolbarButton.Handwriting, true))
        return ToolbarConfig(rest.subList(0, divider + 1) + Item.Button(ToolbarButton.Handwriting, true) + rest.subList(divider + 1, rest.size))
    }

    /** For a bar arranged before 手写 was offered ([handwritingNeverOffered]): after 表情, as [DEFAULT] has it. */
    fun withHandwritingOffered(): ToolbarConfig {
        val rest = items.filterNot { it is Item.Button && it.button == ToolbarButton.Handwriting }.toMutableList()
        if (Item.Divider !in rest) rest += Item.Divider
        val divider = rest.indexOf(Item.Divider)
        val emoji = rest.indexOfFirst { it is Item.Button && it.button == ToolbarButton.Emoji }
        rest.add(if (emoji > divider) emoji + 1 else divider + 1, Item.Button(ToolbarButton.Handwriting, true))
        return ToolbarConfig(rest)
    }

    /** [unavailable] drawn greyed in their place, a tap saying why. */
    data class Layout(
        val toolbar: List<ToolbarButton>,
        val pinned: List<ToolbarButton>,
        val unavailable: Set<ToolbarButton> = emptySet(),
    ) {
        /** Nothing of the toolbar's to show: the menu key is ⋯ itself. */
        val moreOnly: Boolean get() = toolbar.all { it == ToolbarButton.More }

        /**
         * Equal slots of about [preferredPx]: menu key first, [pinned] last, at least [MIDDLE_SLOTS] between, open or
         * shut alike so nothing moves. Toolbar buttons with no slot go to ⋯'s panel. [rowPx] 0 (not laid out): all on the toolbar.
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

    /** [overflow]: the toolbar's with no slot, in ⋯'s panel in this order. */
    data class Slots(val count: Int, val cellPx: Int, val toolbar: List<ToolbarButton>, val overflow: List<ToolbarButton>)

    companion object {
        const val DIVIDER = "|"

        /** Shut, room for a clipboard suggestion or a sum; open, one toolbar button and ⋯. */
        const val MIDDLE_SLOTS = 2
        private const val HIDDEN = "-"

        /** 快速输入 comes hidden, first on the toolbar, as any button added later; 手写 at the right. */
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

        /** Empty is [DEFAULT]; an unknown or repeated key, and ⋯, dropped; a missing button put in hidden before the divider. */
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
         * Whether a user-arranged [raw] predates 手写 on the list: 0.6.0's (none in it), or with [firstLook] the first
         * test build's (unticked before the divider). One the user moved or unticked since stays so.
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
