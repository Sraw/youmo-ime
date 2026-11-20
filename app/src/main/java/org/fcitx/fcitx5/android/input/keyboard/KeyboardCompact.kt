/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.inputmethodservice.InputMethodService
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.scene.rules.KeyTextScale

/** Read as the keys are made; the service makes them anew when this changes. */
internal fun keyboardCompact(): Boolean = KeyboardArea.sizes().compact

/** Floating: as much smaller as the keys are shorter, or a letter and the digit under it ran into each other. */
internal fun keyTextScale(context: Context): Float {
    // a keyboard drawn elsewhere (the theme's preview) is not the floating one, but is 紧凑 as the real one is
    val floating = if (context.inInputMethod() && KeyboardStyles.floating(context)) KeyboardArea.floatingSize(KeyboardArea.screen(context)) else null
    return KeyTextScale.of(keyboardCompact(), floating?.textScale?.toDouble()).toFloat() * (floating?.resize ?: 1f)
}

internal fun Context.inInputMethod(): Boolean {
    var at: Context? = this
    while (at is ContextWrapper) {
        if (at is InputMethodService) return true
        at = at.baseContext
    }
    return false
}
