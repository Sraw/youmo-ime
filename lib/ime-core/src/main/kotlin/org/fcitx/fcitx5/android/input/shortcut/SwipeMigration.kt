/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers

/**
 * 0.5's swipe settings as 0.6's two slots a key (060-swipe-slots): 滑动 ([SwipeLayers]), the shortcuts
 * set ([KeyShortcuts]), 标点位置 and 快捷键图标位置. What a swipe did on every key is what it does
 * after; the long presses (`popup_overrides`) stay as they are, the long press's first reading them.
 */
object SwipeMigration {

    /** The settings that take the old ones' place: the slots, 上滑 and 下滑 on or off, where each draws. */
    data class Result(val slots: SwipeSlots, val up: Boolean, val down: Boolean, val upAt: KeyMarks.At, val downAt: KeyMarks.At)

    /** A new install's: the long press's first up, its shortcut down, drawn where 标点位置 and 快捷键图标位置 were by default. */
    val Fresh = Result(SwipeSlots.Default, up = true, down = true, KeyMarks.At.SymbolDefault, KeyMarks.At.ShortcutDefault)

    /**
     * [layers] as stored, or 0.5's default where an [upgraded] install never set it (null for neither, a
     * new install: [Fresh]); [shortcuts], [symbolAt] and [shortcutAt] as stored.
     */
    fun of(layers: SwipeLayers?, upgraded: Boolean, shortcuts: KeyShortcuts, symbolAt: KeyMarks.At, shortcutAt: KeyMarks.At): Result {
        val old = layers ?: if (upgraded) SwipeLayers.Default else null
        val placesAsNew = symbolAt == Fresh.upAt && shortcutAt == Fresh.downAt
        if (old == null && shortcuts.isEmpty && placesAsNew) return Fresh
        val l = old ?: SwipeLayers(SwipeLayers.Layer.Symbol, SwipeLayers.Layer.Shortcut)
        // the symbol up (or the shortcut down): the long press's first up; else down, 0.5's default and both off too
        val firstUp = l.up == SwipeLayers.Layer.Symbol || l.down == SwipeLayers.Layer.Shortcut
        val orientation = if (firstUp) SwipeOrientation.FirstUp else SwipeOrientation.FirstDown
        // each character key's shortcut the way its preset's shortcut is now
        val shortcutUp = !firstUp
        val slots = withShortcuts(SwipeSlots.Default.applied(orientation), shortcuts, shortcutUp)
        // the two places apart as 0.5 read them: the symbol yielding to the shortcut
        val spots = KeyMarks.settled(symbolAt, shortcutAt)
        return Result(
            slots,
            up = l.up != SwipeLayers.Layer.Off,
            down = l.down != SwipeLayers.Layer.Off,
            upAt = if (firstUp) spots.symbol else spots.shortcut,
            downAt = if (firstUp) spots.shortcut else spots.symbol,
        )
    }

    /** 0.5's [shortcuts] into [slots]: a character key's on [shortcutUp]'s way, a function key's (`fn/space.up`) its own way. */
    fun withShortcuts(slots: SwipeSlots, shortcuts: KeyShortcuts, shortcutUp: Boolean): SwipeSlots =
        shortcuts.names.fold(slots) { s, name ->
            val slot = SwipeSlot.of(shortcuts.resolve(name))
            if (KeyShortcuts.formOf(name) == KeyShortcuts.FUNCTION) s.with(name.substringBeforeLast('.'), name.endsWith(".up"), slot)
            else s.with(name, shortcutUp, slot)
        }
}
