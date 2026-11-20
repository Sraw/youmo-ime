/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.getSystemService

inline val EditText.str: String
    get() = editableText.toString()

/** For a field about to be hidden: else the keys go on typing into a field no longer shown. */
fun View.letGoOfFocusedField() {
    val focused = findFocus() ?: return
    focused.context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(focused.windowToken, 0)
    focused.clearFocus()
}
