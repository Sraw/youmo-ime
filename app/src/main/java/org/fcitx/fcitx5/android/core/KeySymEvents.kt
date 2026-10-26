/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.view.KeyCharacterMap
import android.view.KeyEvent

// KeySym itself is in ime-core; what needs Android's key events is here

val KeySym.keyCode get() = FcitxKeyMapping.symToKeyCode(sym) ?: KeyEvent.KEYCODE_UNKNOWN

// characters whose key fcitx has another sym for than the code point
private val NOT_AS_CHARACTER = setOf(
    // \t's sym is not its code point
    '\t'.code,
    // fcitx wants \r for return
    '\n'.code,
    // Android's private-use characters
    KeyCharacterMap.HEX_INPUT.code,
    KeyCharacterMap.PICKER_DIALOG_INPUT.code,
)

fun KeySym.Companion.fromKeyEvent(event: KeyEvent): KeySym? {
    val charCode = event.unicodeChar
    // the character first, so upper and lower case make different syms
    if (charCode != 0 && charCode !in NOT_AS_CHARACTER) return KeySym(charCode)
    return KeySym(FcitxKeyMapping.keyCodeToSym(event.keyCode) ?: return null)
}
