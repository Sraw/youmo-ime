/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.inputmethodservice.InputMethodService
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard

/**
 * Whether the keyboard is 紧凑 as 键盘高度 is set now, or the theme's height followed ([KeyboardSizes.compact]:
 * [KeyboardHeightLevels], the level the settings list shows): its keys' text smaller ([keyTextScale]), their marks in the top right only ([KeyMarks]).
 * Read as the keys are made; the service makes them anew when this changes.
 */
internal fun keyboardCompact(): Boolean = KeyboardArea.sizes().compact

/**
 * The keys' text: floating ([FloatingKeyboard]), as much smaller as the keys are shorter, or a letter
 * and the digit under it ran into each other; 紧凑 ([keyboardCompact]) smaller too, the smaller of
 * the two where both are ([KeyTextScale]).
 */
internal fun keyTextScale(context: Context): Float {
    // a keyboard drawn elsewhere (the theme's preview in the settings) is not the floating one, but
    // is 紧凑 as the real one is, so the preview shows what the level does
    val floating = if (ThemeManager.prefs.floatingKeyboard.getValue() && context.inInputMethod()) {
        val screen = context.resources.configuration.screenWidthDp
        FloatingKeyboard.textScale(FloatingKeyboard.widthDp(screen), screen)
    } else {
        null
    }
    return KeyTextScale.of(keyboardCompact(), floating)
}

internal fun Context.inInputMethod(): Boolean {
    var at: Context? = this
    while (at is ContextWrapper) {
        if (at is InputMethodService) return true
        at = at.baseContext
    }
    return false
}
