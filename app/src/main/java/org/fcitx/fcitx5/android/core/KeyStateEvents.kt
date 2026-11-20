/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.view.KeyEvent

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

/** A modifier's own bit on as it goes down and off as it comes up, as Android has it; states have no sides, so always the left key's. */
fun KeyStates.metaStateOf(keyCode: Int, up: Boolean): Int {
    val own = ownModifier(keyCode)?.first ?: return metaState
    return KeyStates(if (up) states - own else states + own).metaState
}

fun KeyStates.Companion.fromKeyEvent(event: KeyEvent): KeyStates {
    // some phones type a number/symbol with modifiers (Alt+Q for 1), which fcitx does not take as a selection key: drop
    // them where getNumber() == getUnicodeChar(); getNumber() may be 0, so displayLabel is checked too
    val unicode = event.unicodeChar
    // ' ' is the same unicodeChar whatever the modifier
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
    // fcitx reads a modifier's own bit as X11 does (off as it goes down), Android the other way round; else a lone
    // Shift's release never matched its press and no modifier-only hotkey fired
    ownModifier(event.keyCode)?.let { (state, otherSide) ->
        states = when {
            event.action == KeyEvent.ACTION_UP -> states + state
            event.metaState and otherSide != 0 -> states
            else -> states - state
        }
    }
    // Android's Meta is X11's Super, which fcitx's hotkeys name; Meta alone Key::normalize drops
    if ((states and KeyState.Meta.state) != 0u) states += KeyState.Super
    return KeyStates(states)
}

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
