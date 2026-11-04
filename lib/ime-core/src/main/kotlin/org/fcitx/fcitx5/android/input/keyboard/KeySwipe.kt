/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers.Layer
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut

/**
 * What lifting the finger after a vertical swipe on a key does, by the 滑动 setting ([SwipeLayers]).
 *
 * A character key (a letter, a digit of the nine keys, a group) has two layers: its corner symbol
 * and its shortcut. Swiped the way that is off, or to a layer it has nothing on (its long press
 * turned off, no shortcut set), it is a tap: the letter is typed here, not left to the key's own
 * tap, as a swipe that long has left the key, and a touch that left its key is no tap (ST-15).
 * With both ways off a swipe off the key is how a press is taken back, as it always was.
 *
 * A function key (space, backspace, …) has no symbol: a way that is on reaches its shortcut that
 * way; a way off, or no shortcut, leaves the key as it always was.
 *
 * Swiped past the threshold and back before the lift is cancelled, on every key.
 */
object KeySwipe {

    sealed interface Key {
        /** A character key: whether it has a symbol to swipe to, and its shortcut. */
        data class Char(val hasSymbol: Boolean, val shortcut: KeyShortcut) : Key

        /** A function key: its shortcut up and down. */
        data class Function(val up: KeyShortcut, val down: KeyShortcut) : Key
    }

    sealed interface Outcome {
        /** The corner symbol typed. */
        data object Symbol : Outcome

        /** [shortcut] run: never [KeyShortcut.None]. */
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
        layers: SwipeLayers,
        totalY: Int,
        swiped: Boolean,
        consumed: Boolean,
        cancelled: Boolean,
        password: Boolean,
    ): Outcome {
        // 关 is 0.5.0's 禁用: no swipe on any key, a function key's tap kept as a character key's is
        if (consumed || cancelled || layers.allOff) return Outcome.None
        if (!swiped && totalY == 0) return Outcome.None
        return when (key) {
            is Key.Char -> when {
                totalY == 0 -> Outcome.Cancel
                else -> when (layers.layerOf(totalY)) {
                    Layer.Off -> Outcome.Tap
                    Layer.Symbol -> if (key.hasSymbol) Outcome.Symbol else Outcome.Tap
                    Layer.Shortcut -> run(key.shortcut, password) ?: Outcome.Tap
                }
            }
            is Key.Function -> when {
                totalY == 0 -> Outcome.Cancel
                layers.layerOf(totalY) == Layer.Off -> Outcome.None
                else -> run(if (totalY < 0) key.up else key.down, password) ?: Outcome.None
            }
        }
    }

    private fun run(shortcut: KeyShortcut, password: Boolean): Outcome? = when {
        shortcut == KeyShortcut.None -> null
        password && shortcut is KeyShortcut.Command && shortcut.command.readsField -> Outcome.Cancel
        else -> Outcome.Shortcut(shortcut)
    }

    /** What [key] previews having swiped [totalY] steps so far: what its lift would do. */
    fun preview(key: Key, layers: SwipeLayers, totalY: Int, password: Boolean): Preview =
        when (val outcome = decide(key, layers, totalY, swiped = true, consumed = false, cancelled = false, password)) {
            Outcome.Symbol -> Preview.Symbol
            is Outcome.Shortcut -> Preview.Shortcut(outcome.shortcut)
            else -> Preview.Own
        }
}
