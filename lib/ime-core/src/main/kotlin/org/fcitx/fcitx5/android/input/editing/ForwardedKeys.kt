/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/**
 * What to do with a key fcitx hands back -- a hardware key it did not consume, or one an input
 * method forwarded -- when there is no original Android `KeyEvent` to replay.
 */
object ForwardedKeys {

    sealed interface Action {
        /** Send a synthetic key event with this Android key code. */
        data class SendKey(val keyCode: Int, val up: Boolean) : Action

        /** No Android key code exists for it: type the character instead, once, on key down. */
        data class CommitChar(val text: String) : Action

        /** Nothing sensible to do (a key up with no key code, or no character). */
        data object Ignore : Action
    }

    /** `KeyEvent.KEYCODE_UNKNOWN`. */
    const val KEYCODE_UNKNOWN = 0

    /**
     * `KeyCharacterMap.PICKER_DIALOG_INPUT`: the character that makes the default
     * `QwertyKeyListener` pop up a Gingerbread-era character picker. A key carrying it is
     * replayed without its character, so the key still reaches the editor but no dialog opens.
     */
    const val PICKER_DIALOG_INPUT = 0xEF01

    fun decide(keyCode: Int, up: Boolean, unicode: Int): Action = when {
        keyCode != KEYCODE_UNKNOWN -> Action.SendKey(keyCode, up)
        !up && unicode > 0 -> Action.CommitChar(String(Character.toChars(unicode)))
        else -> Action.Ignore
    }

    fun opensCharacterPicker(unicodeChar: Int) = unicodeChar == PICKER_DIALOG_INPUT
}

/**
 * Tracks the meta state of a hardware keyboard with sticky modifiers, so that releasing a
 * modifier clears exactly the meta bits that release dropped -- and not those of a modifier
 * still held or still latched.
 * See `InputConnection.clearMetaKeyStates`.
 */
class StickyMetaState {

    private var last = 0

    fun onModifierDown(metaState: Int) {
        last = metaState
    }

    /** @return the meta bits to clear in the editor */
    fun onModifierUp(metaState: Int): Int {
        val released = last xor metaState
        last = metaState
        return released
    }
}
