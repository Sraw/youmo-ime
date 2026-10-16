/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.hardware

/**
 * The hardware keyboard's shortcuts for the input method, as a computer's Chinese input methods
 * have them and Sogou and Baidu on a phone: Shift pressed and let go by itself switches between
 * Chinese and English, as Ctrl+Space does (fcitx's); Ctrl and Shift together, let go, go on to the
 * next input method (Windows'), and again for each tap of one while the other is held. Told each
 * key as it goes down and up; says what to do, and whether the key is the shortcut's alone, kept
 * from the app and the engine.
 *
 * A modifier's tap counts only quickly done, within [tapMs], and with no other key between: Shift
 * held for a capital, or Ctrl+Shift+Z, is no tap. A key held down repeats its press, which neither
 * starts nor spoils one.
 */
class HardwareShortcuts(private val tapMs: Long = TAP_MS) {

    enum class Key { SHIFT, CTRL, SPACE, OTHER }

    enum class Action { TOGGLE, NEXT }

    /** What to do, if anything, and whether the key goes no further. */
    data class Decision(val action: Action?, val consumed: Boolean)

    // HELD: one of Ctrl and Shift still down after going on, the other pressed again goes on again
    private enum class Pending { NONE, SHIFT, CTRL, CTRL_SHIFT, HELD }

    private var pending = Pending.NONE
    private var since = 0L

    // Ctrl+Space was the shortcut: its Space's release goes no further either
    private var spaceTaken = false

    /**
     * [key] goes down, with [ctrl] and [shift] held (before it) and [others], Alt or Meta, held or not;
     * [repeat] for a held key's repeated press. [canToggle], asked of Ctrl+Space only: whether a toggle
     * has an input method to go to.
     */
    fun down(
        key: Key, ctrl: Boolean, shift: Boolean, others: Boolean, repeat: Boolean, timeMs: Long,
        canToggle: () -> Boolean = { true },
    ): Decision {
        if (repeat) return if (key == Key.SPACE && spaceTaken) TAKEN else NONE
        pending = when (key) {
            Key.SHIFT -> modifier(Pending.SHIFT, Pending.CTRL, otherHeld = ctrl, others)
            Key.CTRL -> modifier(Pending.CTRL, Pending.SHIFT, otherHeld = shift, others)
            Key.SPACE -> {
                // a fresh press: a taken Space whose release never came (focus moved) is let go
                spaceTaken = false
                if (ctrl && !shift && !others) {
                    pending = Pending.NONE
                    // nowhere to go: no shortcut, the key goes on to the app
                    if (!canToggle()) return NONE
                    spaceTaken = true
                    return Decision(Action.TOGGLE, consumed = true)
                }
                Pending.NONE
            }
            Key.OTHER -> Pending.NONE
        }
        since = timeMs
        return NONE
    }

    // [alone] pressed: a tap of its own, or with [other] held and pressed first (or gone on from) the two together
    private fun modifier(alone: Pending, other: Pending, otherHeld: Boolean, others: Boolean) = when {
        others -> Pending.NONE
        otherHeld -> if (pending == other || pending == Pending.HELD) Pending.CTRL_SHIFT else Pending.NONE
        else -> alone
    }

    /**
     * [key] went down where the shortcuts do not apply (switched off, a field of raw keys): no tap
     * goes on across it, and a taken Space whose release never came is let go, as [down] does.
     */
    fun passed(key: Key) {
        pending = Pending.NONE
        if (key == Key.SPACE) spaceTaken = false
    }

    /** [key] comes up. */
    fun up(key: Key, timeMs: Long): Decision {
        if (key == Key.SPACE && spaceTaken) {
            spaceTaken = false
            return TAKEN
        }
        val was = pending
        pending = Pending.NONE
        if (timeMs - since > tapMs) {
            // one slow press of Ctrl or Shift goes nowhere, but the other still held, the next tap goes on
            if (key in MODIFIERS && (was == Pending.CTRL_SHIFT || was == Pending.HELD)) {
                pending = Pending.HELD
            }
            return NONE
        }
        return when {
            key == Key.SHIFT && was == Pending.SHIFT -> Decision(Action.TOGGLE, consumed = false)
            key != Key.SPACE && key != Key.OTHER && was == Pending.CTRL_SHIFT -> {
                // the other still held: tapped again, this one goes on again, as on Windows
                pending = Pending.HELD
                Decision(Action.NEXT, consumed = false)
            }
            else -> NONE
        }
    }

    companion object {
        /** fcitx's own limit for a modifier-only shortcut (ModifierOnlyKeyTimeout). */
        const val TAP_MS = 250L

        private val MODIFIERS = setOf(Key.SHIFT, Key.CTRL)
        private val NONE = Decision(null, consumed = false)
        private val TAKEN = Decision(null, consumed = true)
    }
}
