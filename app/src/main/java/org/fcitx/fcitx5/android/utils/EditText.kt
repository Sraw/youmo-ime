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

/**
 * The focused field under this view (or this one) let go of, the keyboard put away: a field about to be hidden (a card
 * folded, another way picked, the search left), or the keys go on typing into a field no longer shown (the connection
 * stays with it). Nothing where none under it has the focus.
 */
fun View.letGoOfFocusedField() {
    val focused = findFocus() ?: return
    focused.context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(focused.windowToken, 0)
    focused.clearFocus()
}
