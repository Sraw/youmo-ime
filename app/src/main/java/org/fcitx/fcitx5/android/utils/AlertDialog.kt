/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.content.DialogInterface
import android.widget.Button
import androidx.appcompat.app.AlertDialog

val AlertDialog.positiveButton: Button
    get() = getButton(DialogInterface.BUTTON_POSITIVE)

/**
 * Change positive button listener **AFTER** [AlertDialog.show] has been called.
 *
 * In the listener: `true` to dismiss the dialog; `false` to keep the dialog open.
 */
fun AlertDialog.onPositiveButtonClick(l: AlertDialog.() -> Boolean?): AlertDialog {
    positiveButton.setOnClickListener {
        if (l.invoke(this) == true) dismiss()
    }
    return this
}
