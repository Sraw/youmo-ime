/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot

/**
 * What lifting the finger after a vertical swipe on a key does (060-swipe-slots): every key has an up
 * slot and a down slot ([SwipeSlot]), and 上滑 and 下滑 each on or off ([Ways]).
 *
 * A character key (a letter, a digit of the nine keys, a group, a zhuyin key): swiped a way that is
 * off, or to a slot with nothing (empty, its long press's first where its long press is off), it is a
 * tap: the letter is typed here, not left to the key's own tap, as a swipe that long has left the key,
 * and a touch that left its key is no tap (ST-15). With both ways off a swipe off the key is how a
 * press is taken back, as it always was.
 *
 * A function key (space, backspace, …): a way off, or a slot with nothing, leaves the key as it always was.
 *
 * Swiped past the threshold and back before the lift is cancelled, on every key.
 */
object KeySwipe {

    /**
     * A key's two slots; [char] for a character key, [hasFirst] whether its long press has a first
     * (what [SwipeSlot.First] types).
     */
    data class Key(val char: Boolean, val up: SwipeSlot, val down: SwipeSlot, val hasFirst: Boolean = true) {
        companion object {
            /**
             * A key no layout names (a panel's, 注音九键's ˉ): no slot of its own to set, its long press's
             * first the way [orientation] puts every key's preset, nothing the other way; 翻转 flips it
             * with them; no corner drawn (a mark is a named key's, 060-keys-unify S6).
             */
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

    /** 上滑 and 下滑 on or off. */
    data class Ways(val up: Boolean, val down: Boolean) {
        val allOff: Boolean get() = !up && !down

        companion object {
            val Both = Ways(up = true, down = true)
        }
    }

    sealed interface Outcome {
        /** The long press's first typed. */
        data object Symbol : Outcome

        /** [shortcut] run (a command, a text): never [KeyShortcut.None]. */
        data class Shortcut(val shortcut: KeyShortcut) : Outcome

        /** The key's own character, as a tap. */
        data object Tap : Outcome

        /** Nothing at all, the key's tap neither. */
        data object Cancel : Outcome

        /** Not the swipe's: the key's own handling (a tap, or nothing) stands. */
        data object None : Outcome
    }

    /** What the key's preview shows while the finger is down. */
    sealed interface Preview {
        data object Own : Preview
        data object Symbol : Preview
        data class Shortcut(val shortcut: KeyShortcut) : Preview
    }

    /**
     * The lift of a touch on [key] that swiped [totalY] steps (down positive), having [swiped] past
     * the threshold at some point; [consumed] when a long press took the touch already, [cancelled]
     * when the system took it; in a [password] field, where a command reading the field is not run.
     */
    @Suppress("LongParameterList")
    fun decide(
        key: Key,
        ways: Ways,
        totalY: Int,
        swiped: Boolean,
        consumed: Boolean,
        cancelled: Boolean,
        password: Boolean,
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
            is SwipeSlot.Text, is SwipeSlot.Command -> run(slot.shortcut, password) ?: nothing
        }
    }

    private fun run(shortcut: KeyShortcut, password: Boolean): Outcome? = when {
        shortcut == KeyShortcut.None -> null
        password && shortcut is KeyShortcut.Command && shortcut.command.readsField -> Outcome.Cancel
        else -> Outcome.Shortcut(shortcut)
    }

    /** What [key] previews having swiped [totalY] steps so far: what its lift would do. */
    fun preview(key: Key, ways: Ways, totalY: Int, password: Boolean): Preview =
        when (val outcome = decide(key, ways, totalY, swiped = true, consumed = false, cancelled = false, password)) {
            Outcome.Symbol -> Preview.Symbol
            is Outcome.Shortcut -> Preview.Shortcut(outcome.shortcut)
            else -> Preview.Own
        }
}
