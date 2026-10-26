/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * One key through the pipeline, fcitx's `KeyEvent` (`fcitx/event.h:360-386`).
 *
 * [key] is [origKey] normalized ([Key.normalize]); handlers match on [key], the hand-back to the
 * application carries [origKey] as it came in (`androidfrontend.cpp:385-392` passes the raw key).
 *
 * Two flags, as fcitx has them, and they mean different things:
 * - [filtered]: no later handler sees the key ([KeyPipeline] stops at the first one that filters);
 * - [accepted]: the key is used up and not handed back to the application.
 * A temp mode filters every key but accepts only those it uses (`tempmodemanager.cpp:63-72`); the
 * fullwidth key conversion accepts without filtering (`fullwidth.cpp:51`), so the key still reaches
 * ReservedLast.
 */
class KeyEvent(
    val origKey: Key,
    val release: Boolean,
    /** From the on-screen keyboard rather than a physical one. */
    val virtual: Boolean = false,
    /** The physical key's event time, to replay it; -1 when there is none. */
    val timestamp: Int = -1,
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

/** A key the engine sends on to the application (`InputContext::forwardKey`), e.g. a cursor move. */
class ForwardKeyEvent(val key: Key, val release: Boolean)

/** An invoke action on the code row / preedit (`InvokeActionEvent`): a tap at [cursor]. */
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
