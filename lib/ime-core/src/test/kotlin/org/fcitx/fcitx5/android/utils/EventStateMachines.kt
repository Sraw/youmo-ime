/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

/** A boolean state as last set; null if never. */
fun <B : EventStateMachine.BooleanStateKey> EventStateMachine<*, *, B>.getBooleanState(key: B): Boolean? =
    externalBooleanStates[key]

/** A test's starting point: no event, the listener told. */
fun <S : Any> EventStateMachine<S, *, *>.unsafeJump(state: S) {
    currentState = state
    onNewStateListener?.invoke(state)
}
