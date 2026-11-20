/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.session.Action

/**
 * fcitx's `KeyEvent`: handlers match on [key] ([origKey] normalized); the hand-back carries [origKey]. [filtered]: no
 * later handler sees it; [accepted]: not handed back. A temp mode filters every key but accepts only those it uses;
 * fullwidth accepts without filtering, so the key still reaches ReservedLast.
 */
class KeyEvent(
    val origKey: Key,
    val release: Boolean,
    val virtual: Boolean = false,
    /** -1 when there is none. */
    val timestamp: Int = -1,
    /** The handwriting pad's reading or a glide, carried in the key so it cannot come apart from it. */
    val payload: Action? = null,
) {
    val key: Key = origKey.normalize()

    var filtered: Boolean = false
        private set
    var accepted: Boolean = false
        private set

    fun filter() {
        filtered = true
    }

    fun accept() {
        accepted = true
    }

    fun filterAndAccept() {
        filter()
        accept()
    }
}

/** `InputContext::forwardKey`, e.g. a cursor move. */
class ForwardKeyEvent(val key: Key, val release: Boolean)

/** A tap at [cursor] on the code row / preedit. */
class InvokeActionEvent(val action: Action, val cursor: Int) {
    enum class Action { LeftClick, RightClick }

    var filtered: Boolean = false
        private set
    var accepted: Boolean = false
        private set

    fun filter() {
        filtered = true
    }

    fun accept() {
        accepted = true
    }
}
