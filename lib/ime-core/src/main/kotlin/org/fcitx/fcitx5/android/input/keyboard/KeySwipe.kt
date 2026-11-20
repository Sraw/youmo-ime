/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.phrase.TextVariables
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import java.util.Calendar

/**
 * The lift after a vertical swipe on a key: each key has an up and a down slot, 上滑 and 下滑 each on or off. A
 * character key swiped a way that is off or to an empty slot types itself here (a touch that left its key is no
 * tap); both ways off, a swipe off is how a press is taken back. A function key is left as it was. Swiped and back is cancelled.
 */
object KeySwipe {

    /** [hasFirst]: its long press has a first ([SwipeSlot.First]'s). */
    data class Key(val char: Boolean, val up: SwipeSlot, val down: SwipeSlot, val hasFirst: Boolean = true) {
        companion object {
            /** A key no layout names (a panel's, 注音九键's ˉ): its first the way [orientation] puts presets, flipped with them; no corner. */
            fun unnamed(orientation: SwipeOrientation, hasFirst: Boolean): Key {
                val firstUp = orientation == SwipeOrientation.FirstUp
                return Key(
                    char = true,
                    up = if (firstUp) SwipeSlot.First else SwipeSlot.Empty,
                    down = if (firstUp) SwipeSlot.Empty else SwipeSlot.First,
                    hasFirst = hasFirst,
                )
            }
        }
    }

    data class Ways(val up: Boolean, val down: Boolean) {
        val allOff: Boolean get() = !up && !down

        companion object {
            val Both = Ways(up = true, down = true)
        }
    }

    sealed interface Outcome {
        data object Symbol : Outcome

        /** Never [KeyShortcut.None]. */
        data class Shortcut(val shortcut: KeyShortcut) : Outcome

        data object Tap : Outcome

        /** The key's tap neither. */
        data object Cancel : Outcome

        /** The key's own handling (a tap, or nothing) stands. */
        data object None : Outcome
    }

    sealed interface Preview {
        data object Own : Preview
        data object Symbol : Preview
        data class Shortcut(val shortcut: KeyShortcut) : Preview
    }

    /** In a [password] field a command reading the field is not run; a variables text that fills nothing is an empty slot. */
    @Suppress("LongParameterList")
    fun decide(
        key: Key,
        ways: Ways,
        totalY: Int,
        swiped: Boolean,
        consumed: Boolean,
        cancelled: Boolean,
        password: Boolean,
        now: () -> Calendar,
    ): Outcome {
        // both off is 0.5.0's 禁用: no swipe on any key, a function key's tap kept as a character key's is
        if (consumed || cancelled || ways.allOff) return Outcome.None
        if (!swiped && totalY == 0) return Outcome.None
        if (totalY == 0) return Outcome.Cancel
        val up = totalY < 0
        // a slot with nothing: a character key's tap, a function key as it was
        val nothing = if (key.char) Outcome.Tap else Outcome.None
        if (!(if (up) ways.up else ways.down)) return nothing
        return when (val slot = if (up) key.up else key.down) {
            SwipeSlot.First -> if (key.hasFirst) Outcome.Symbol else nothing
            SwipeSlot.Empty -> nothing
            is SwipeSlot.Text -> if (slot.variables && TextVariables.expand(slot.text, now()).isEmpty()) nothing else run(slot.shortcut, password) ?: nothing
            is SwipeSlot.Command -> run(slot.shortcut, password) ?: nothing
        }
    }

    private fun run(shortcut: KeyShortcut, password: Boolean): Outcome? = when {
        shortcut == KeyShortcut.None -> null
        password && shortcut is KeyShortcut.Command && shortcut.command.readsField -> Outcome.Cancel
        else -> Outcome.Shortcut(shortcut)
    }

    /** What its lift would do. */
    fun preview(key: Key, ways: Ways, totalY: Int, password: Boolean, now: () -> Calendar): Preview =
        when (val outcome = decide(key, ways, totalY, swiped = true, consumed = false, cancelled = false, password, now)) {
            Outcome.Symbol -> Preview.Symbol
            is Outcome.Shortcut -> Preview.Shortcut(outcome.shortcut)
            else -> Preview.Own
        }
}
