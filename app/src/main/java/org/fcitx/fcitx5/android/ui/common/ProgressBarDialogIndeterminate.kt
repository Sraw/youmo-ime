/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.common

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.getGlobalSettings
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

@Suppress("FunctionName")
fun Context.ProgressBarDialogIndeterminate(@StringRes title: Int): AlertDialog.Builder {
    val androidStyles = AndroidStyles(this)
    return AlertDialog.Builder(this)
        .setTitle(title)
        .setView(verticalLayout {
            val shouldAnimate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ValueAnimator.areAnimatorsEnabled()
            } else {
                getGlobalSettings<Float>(Settings.Global.ANIMATOR_DURATION_SCALE) > 0f
            }
            add(if (shouldAnimate) {
                androidStyles.progressBar.horizontal {
                    isIndeterminate = true
                }
            } else {
                textView {
                    setText(R.string.please_wait)
                    textAppearance = resolveThemeAttribute(android.R.attr.textAppearanceListItem)
                }
            }, lParams {
                width = matchParent
                verticalMargin = dp(20)
                horizontalMargin = dp(26)
            })
        })
        .setCancelable(false)
}

fun LifecycleCoroutineScope.withLoadingDialog(
    context: Context,
    @StringRes title: Int = R.string.loading,
    threshold: Long = 200L,
    action: suspend () -> Unit
) {
    launch {
        withLoading<AlertDialog>(
            threshold,
            show = { context.ProgressBarDialogIndeterminate(title).show() },
            dismiss = {
                // a destroyed activity has taken the dialog's window down: dismissing it then throws
                if ((context as? Activity)?.isDestroyed != true) it.dismiss()
            },
            action = action
        )
    }
}

/**
 * Runs [action], showing what [show] makes once it has taken [threshold] ms, and [dismiss]es it
 * however [action] ends: thrown or cancelled too, as a non-cancelable dialog left up blocks the page.
 */
internal suspend fun <D : Any> withLoading(
    threshold: Long,
    show: () -> D,
    dismiss: (D) -> Unit,
    action: suspend () -> Unit
) = coroutineScope {
    var loadingDialog: D? = null
    val loadingJob = launch {
        delay(threshold)
        loadingDialog = show()
    }
    try {
        action()
    } finally {
        // not joined, as a cancelled coroutine cannot wait: on this one thread it cannot reach show() now
        loadingJob.cancel()
        loadingDialog?.let(dismiss)
    }
}
