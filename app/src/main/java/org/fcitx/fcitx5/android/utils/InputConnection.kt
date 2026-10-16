/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.os.Build
import android.view.inputmethod.InputConnection

fun InputConnection.monitorCursorAnchor(enable: Boolean = true): Boolean {
    if (!enable) {
        requestCursorUpdates(0)
        return false
    }
    // and at once: a field that reports nothing till its cursor moves would keep the last field's
    val mode = InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE
    var scheduled = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        scheduled = requestCursorUpdates(
            mode,
            InputConnection.CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS or InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER
        )
    }
    if (!scheduled) {
        scheduled = requestCursorUpdates(mode)
    }
    return scheduled
}
