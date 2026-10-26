/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.view.KeyEvent

// KeyStates itself is in ime-core; what needs Android's key events is here

val KeyStates.metaState: Int get() {
    var metaState = 0
    if (alt) metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    if (meta) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
    if (numLock) metaState = metaState or KeyEvent.META_NUM_LOCK_ON
    if (capsLock) metaState = metaState or KeyEvent.META_CAPS_LOCK_ON
    return metaState
}

/**
 * [metaState] for an event of [keyCode] made up from these states, fcitx's ([fromKeyEvent]): a
 * modifier key's own bit back on as it goes down and off as it comes up ([up]), as Android has it,
 * so that an editor's sticky meta state follows. The states have no sides: the bit goes off with
 * either key of a pair while the other may still be held, and is always the left key's.
 */
fun KeyStates.metaStateOf(keyCode: Int, up: Boolean): Int {
    val own = ownModifier(keyCode)?.first ?: return metaState
    return KeyStates(if (up) states - own else states + own).metaState
}

fun KeyStates.Companion.fromKeyEvent(event: KeyEvent): KeyStates {
    // drop modifier state when using combination keys to input number/symbol on some phones
    // because fcitx doesn't recognize selection key with modifiers (eg. Alt+Q for 1)
    // in which case event.getNumber().toInt() == event.getUnicodeChar()
    // ... but some keys can have event.getNumber() return 0, need to check displayLabel as well
    val unicode = event.unicodeChar
    // skip ' ', because it would produce same unicodeChar regardless of the modifier
    if (unicode != 0 && unicode != ' '.code) {
        val char = unicode.toChar()
        if (char == event.number || event.displayLabel.uppercaseChar() != char.uppercaseChar()) {
            return KeyStates.Empty
        }
    }
    var states = KeyState.NoState.state
    event.apply {
        if (isAltPressed) states += KeyState.Alt
        if (isCtrlPressed) states += KeyState.Ctrl
        if (isShiftPressed) states += KeyState.Shift
        if (isCapsLockOn) states += KeyState.CapsLock
        if (isNumLockOn) states += KeyState.NumLock
        if (isMetaPressed) states += KeyState.Meta
    }
    // fcitx reads a key's state as X11 has it, as it was before the key: a modifier key's
    // own bit off as it goes down, on as it comes up. Android has it the other way round,
    // and a lone Shift's release never matched its press: no modifier-only hotkey could fire
    // (the other side's key of the pair held down keeps the bit on, as in X11)
    ownModifier(event.keyCode)?.let { (state, otherSide) ->
        states = when {
            event.action == KeyEvent.ACTION_UP -> states + state
            event.metaState and otherSide != 0 -> states
            else -> states - state
        }
    }
    // Android's Meta is X11's Super (Mod4), what fcitx's hotkeys name (Super+` and Super+; for quick
    // input); Meta alone fcitx's Key::normalize drops, so Meta+` was a plain `
    if ((states and KeyState.Meta.state) != 0u) states += KeyState.Super
    return KeyStates(states)
}

/** A modifier key's own state, and the meta flag of the other side's key of its pair. */
private fun ownModifier(keyCode: Int): Pair<KeyState, Int>? = when (keyCode) {
    KeyEvent.KEYCODE_SHIFT_LEFT -> KeyState.Shift to KeyEvent.META_SHIFT_RIGHT_ON
    KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyState.Shift to KeyEvent.META_SHIFT_LEFT_ON
    KeyEvent.KEYCODE_CTRL_LEFT -> KeyState.Ctrl to KeyEvent.META_CTRL_RIGHT_ON
    KeyEvent.KEYCODE_CTRL_RIGHT -> KeyState.Ctrl to KeyEvent.META_CTRL_LEFT_ON
    KeyEvent.KEYCODE_ALT_LEFT -> KeyState.Alt to KeyEvent.META_ALT_RIGHT_ON
    KeyEvent.KEYCODE_ALT_RIGHT -> KeyState.Alt to KeyEvent.META_ALT_LEFT_ON
    KeyEvent.KEYCODE_META_LEFT -> KeyState.Meta to KeyEvent.META_META_RIGHT_ON
    KeyEvent.KEYCODE_META_RIGHT -> KeyState.Meta to KeyEvent.META_META_LEFT_ON
    else -> null
}
