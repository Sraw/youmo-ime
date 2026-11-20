/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.rules

/**
 * Where a key draws what its swipes reach, from 上滑图标位置 and 下滑图标位置; ime-core's and the app's settings read this
 * one copy. 翻转上下滑 swaps the two settings with the ways, so the letters' marks stay put.
 */
object KeyMarks {

    enum class At(val corner: Slot?) {
        TopRight(Slot.TopRight), BottomRight(Slot.BottomRight), TopLeft(Slot.TopLeft), BottomLeft(Slot.BottomLeft), Top(null), Bottom(null), None(null);

        val yielded: At
            get() = when (this) {
                TopRight -> BottomRight
                BottomRight -> TopRight
                TopLeft -> BottomLeft
                BottomLeft -> TopLeft
                Top, Bottom -> TopRight
                None -> None
            }

        val middle: Boolean get() = this == Top || this == Bottom

        companion object {
            val SymbolDefault = TopRight
            val ShortcutDefault = TopLeft
        }
    }

    data class Spots(val symbol: At, val shortcut: At)

    /** One place in both (an import, a backup) moves the symbol to [At.yielded], the shortcut kept where the user last saw it. */
    fun settled(symbol: At, shortcut: At): Spots =
        Spots(if (symbol == shortcut && symbol != At.None) symbol.yielded else symbol, shortcut)

    val LowOrder = listOf(At.TopRight, At.BottomRight, At.TopLeft, At.BottomLeft)

    /** The top corners first, so a free one at the top is not passed over for the bottom. */
    val TopOrder = listOf(At.TopRight, At.TopLeft, At.BottomRight, At.BottomLeft)

    fun lowOrder(at: At): List<At> = if (at == At.Top) TopOrder else LowOrder

    /** A 顶部 and a 底部 on low keys each take their own edge's right corner: by [LowOrder] the two would land the other way up. */
    fun onKeys(symbolAt: At, shortcutAt: At, landscape: Boolean, compact: Boolean): Spots {
        val s = settled(symbolAt, shortcutAt)
        if (!landscape && !compact) return s
        if (s.symbol.middle && s.shortcut.middle) return Spots(byEdge(s.symbol), byEdge(s.shortcut))
        val symbol = if (!s.symbol.middle) s.symbol else lowOrder(s.symbol).first { it != s.shortcut }
        val shortcut = if (!s.shortcut.middle) s.shortcut else lowOrder(s.shortcut).first { it != symbol }
        return Spots(symbol, shortcut)
    }

    private fun byEdge(at: At) = if (at == At.Top) At.TopRight else At.BottomRight

    fun shortcutSlot(at: At): Slot? = when (at) {
        At.Top -> Slot.Top
        At.Bottom -> Slot.Bottom
        else -> at.corner
    }

    fun symbolSlot(symbolAt: At, shortcutAt: At, landscape: Boolean, compact: Boolean): Slot? =
        when (val symbol = onKeys(symbolAt, shortcutAt, landscape, compact).symbol) {
            At.Top -> Slot.Top
            At.Bottom -> Slot.Below
            else -> symbol.corner
        }

    enum class Slot(val left: Boolean, val right: Boolean, val bottom: Boolean) {
        TopLeft(true, false, false), TopRight(false, true, false), BottomLeft(true, false, true), BottomRight(false, true, true),
        Top(false, false, false), Bottom(false, false, true), Below(false, false, true);

        val mirrored: Slot
            get() = when (this) {
                TopLeft -> BottomLeft
                BottomLeft -> TopLeft
                TopRight -> BottomRight
                BottomRight -> TopRight
                Top -> Bottom
                Bottom, Below -> Top
            }

        val centred: Boolean get() = !left && !right
    }

    data class Placed<T>(val at: Map<Slot, T> = emptyMap()) {
        operator fun get(slot: Slot): T? = at[slot]

        fun slotOf(t: T): Slot? = at.entries.firstOrNull { it.value == t }?.key

        companion object {
            fun <T> of(vararg pairs: Pair<Slot, T>): Placed<T> = Placed(mapOf(*pairs))
        }
    }

    fun <T : Any> place(symbol: T?, shortcut: T?, symbolAt: At, shortcutAt: At, landscape: Boolean, compact: Boolean = false): Placed<T> {
        val out = linkedMapOf<Slot, T>()
        val shortcutSlot = shortcutSlot(onKeys(symbolAt, shortcutAt, landscape, compact).shortcut)
        if (shortcut != null && shortcutSlot != null) out[shortcutSlot] = shortcut
        val symbolSlot = symbolSlot(symbolAt, shortcutAt, landscape, compact)
        if (symbol != null && symbolSlot != null) out[symbolSlot] = symbol
        return Placed(out)
    }

    /** Two in one place (an import the settings page never wrote) read as [settled]: the up one yields. */
    fun <T : Any> placeFunction(
        up: T?, down: T?, upOn: Boolean, downOn: Boolean, upAt: At, downAt: At, landscape: Boolean, compact: Boolean,
    ): Placed<T> {
        val out = linkedMapOf<Slot, T>()
        val u = up?.takeIf { upOn }
        val d = down?.takeIf { downOn }
        val (ua, da) = settled(upAt, downAt)
        // the other way's place stands for the symbol's (onKeys' arguments by role, not by way)
        val upSlot = shortcutSlot(onKeys(da, ua, landscape, compact).shortcut)
        val downSlot = shortcutSlot(onKeys(ua, da, landscape, compact).shortcut)
        if (u != null && upSlot != null) out[upSlot] = u
        if (d != null && downSlot != null) out[downSlot] = d
        return Placed(out)
    }
}
