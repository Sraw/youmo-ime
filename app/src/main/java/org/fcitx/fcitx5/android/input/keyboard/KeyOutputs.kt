/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.input.keys.KeyOutput

/**
 * The app's action for what an item types (060-keys-unify §1.3, M1): the long press's and a swipe's first
 * alike, so as-is is the item's, never the popup's (I8, I11). A [KeyOutput.Press] is sent as a key from the
 * popup (KeyActionListener.Source.Popup), so the nine keys' and 注音's own reading of a picked character
 * stays theirs; [placeholder] the keyboard's own `action:<name>`.
 */
fun KeyOutput.toKeyAction(placeholder: (String) -> KeyAction? = { null }): KeyAction? = when (this) {
    // more than one character is no key: committed (PopupKeyboardUi.keyAction)
    is KeyOutput.Press -> if (text.codePointCount(0, text.length) == 1) KeyAction.HostKeyAction(text) else KeyAction.CommitAction(text)
    is KeyOutput.Text -> KeyAction.CommitAction(text)
    is KeyOutput.Keypad -> KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + (digit - '0')), KeyStates.Virtual)
    is KeyOutput.Action -> SceneKeyDef.action(action, placeholder)
}
