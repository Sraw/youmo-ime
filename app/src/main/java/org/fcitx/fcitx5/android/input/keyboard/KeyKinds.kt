/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.theme.KeyFaceGeometry
import org.fcitx.fcitx5.android.input.theme.KeyKind

/**
 * The kind a theme styles a key as ([KeyKind]): by the face colour it had before theme file 3.0, so
 * a theme of colours alone draws every key as before. The space bar is its own; the accent keys
 * (Return, 确定, 查词 on) are Return's; the alternative ones (⇧ ⌫ 123 …) the function keys'; the
 * rest, those on a letter's face (🌐, a pad's 符), the letters'.
 */
val KeyDef.Appearance.themeKind: KeyKind
    get() = when {
        viewId == R.id.button_space -> KeyKind.Space
        variant == Variant.Accent -> KeyKind.Enter
        variant == Variant.Alternative -> KeyKind.Function
        else -> KeyKind.Letter
    }

/** A key's own say over its face (KeyFaceGeometry.Border), as its definition has it. */
val Border.face: KeyFaceGeometry.Border
    get() = when (this) {
        Border.Default -> KeyFaceGeometry.Border.Default
        Border.On -> KeyFaceGeometry.Border.On
        Border.Off -> KeyFaceGeometry.Border.Off
        Border.Special -> KeyFaceGeometry.Border.Special
    }
