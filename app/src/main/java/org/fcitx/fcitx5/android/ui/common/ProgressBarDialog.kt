/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.common

import android.app.Activity
import android.content.Context
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import splitties.dimensions.dp
import splitties.resources.resolveThemeAttribute
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalMargin
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.styles.AndroidStyles
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.verticalMargin
import splitties.views.textAppearance

/**
 * A long job's dialog: what it is doing and how far ([set]), with a cancel that calls [cancel]
 * once (the job stops at its next step and the dialog goes when it has). A work of a minute
 * (a 1.9-million-word dictionary) is not a spinner to wait on blind.
 */
class ProgressBarDialog(private val context: Context, @StringRes title: Int, cancel: () -> Unit) {
    private val label: TextView
    private val bar: ProgressBar

    val dialog: AlertDialog

    init {
        val styles = AndroidStyles(context)
        label = context.textView { textAppearance = context.resolveThemeAttribute(android.R.attr.textAppearanceListItem) }
        bar = styles.progressBar.horizontal { isIndeterminate = false; max = 100 }
        dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(context.verticalLayout {
                add(label, lParams(matchParent) { horizontalMargin = dp(26); topMargin = dp(16) })
                add(bar, lParams(matchParent) { horizontalMargin = dp(26); verticalMargin = dp(12) })
            })
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // not dismissed by the button: it goes once the job has stopped and cleaned up
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { button ->
                button.isEnabled = false
                cancel()
            }
        }
    }

    /** [text] said over the bar, at [percent]. */
    fun set(text: String, percent: Int) {
        label.text = text
        bar.progress = percent
    }

    fun show() = dialog.show()

    fun dismiss() {
        // a destroyed activity has taken the dialog's window down: dismissing it then throws
        if ((context as? Activity)?.isDestroyed != true && dialog.isShowing) dialog.dismiss()
    }
}
