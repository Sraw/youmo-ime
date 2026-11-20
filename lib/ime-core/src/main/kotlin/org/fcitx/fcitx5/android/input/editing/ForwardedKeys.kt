/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/** A key fcitx hands back with no original Android `KeyEvent` to replay. */
object ForwardedKeys {

    sealed interface Action {
        data class SendKey(val keyCode: Int, val up: Boolean) : Action

        /** No key code for it: type the character once, on key down. */
        data class CommitChar(val text: String) : Action

        /** A key up with no key code, or no character. */
        data object Ignore : Action
    }

    const val KEYCODE_UNKNOWN = 0

    /** `KeyCharacterMap.PICKER_DIALOG_INPUT` pops the default listener's character picker: replayed without its character. */
    const val PICKER_DIALOG_INPUT = 0xEF01

    fun decide(keyCode: Int, up: Boolean, unicode: Int): Action = when {
        keyCode != KEYCODE_UNKNOWN -> Action.SendKey(keyCode, up)
        !up && unicode > 0 -> Action.CommitChar(String(Character.toChars(unicode)))
        else -> Action.Ignore
    }

    fun opensCharacterPicker(unicodeChar: Int) = unicodeChar == PICKER_DIALOG_INPUT
}

/** Releasing a sticky modifier clears exactly its meta bits, not those still held or latched (`clearMetaKeyStates`). */
class StickyMetaState {

    private var last = 0

    fun onModifierDown(metaState: Int) {
        last = metaState
    }

    /** The meta bits to clear in the editor. */
    fun onModifierUp(metaState: Int): Int {
        val released = last and metaState.inv()
        last = metaState
        return released
    }
}
