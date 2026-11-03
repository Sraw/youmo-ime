/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.Rect
import org.fcitx.fcitx5.android.input.theme.Box
import org.fcitx.fcitx5.android.input.theme.Crop
import org.fcitx.fcitx5.android.input.theme.LookDefaults
import org.fcitx.fcitx5.android.input.theme.Picture

// the tests' custom themes as the app made them before 3.0 (no editing in the app now): a built-in
// theme's colours, with or without a picture under the keyboard

/** [this] built-in theme as a custom one named [name], no picture. */
fun Theme.Builtin.deriveCustomNoBackground(name: String) = Theme.Custom(name, isDark, look)

/** [this] built-in theme as a custom one named [name], [cropped] (cut from [src]) under the keyboard. */
fun Theme.Builtin.deriveCustomBackground(
    name: String,
    cropped: String,
    src: String,
    brightness: Int = 70,
    cropRect: Rect? = null,
    cropRotation: Int = 0,
) = Theme.Custom(
    name,
    isDark,
    LookDefaults.look(
        look.palette,
        Picture(src, Crop(cropped, cropRect?.let { Box(it.left, it.top, it.right, it.bottom) }, cropRotation), null, brightness, 0),
    ),
)

/** The picture's portrait cut as file 2.x had it ("backgroundImage"). */
data class BackgroundImage(
    val croppedFilePath: String,
    val srcFilePath: String,
    val brightness: Int,
    val cropRect: Rect?,
    val cropRotation: Int,
)

val Theme.Custom.backgroundImage: BackgroundImage?
    get() = look.picture?.let {
        BackgroundImage(
            it.portrait.file, it.src, it.brightness,
            it.portrait.cropRect?.let { b -> Rect(b.left, b.top, b.right, b.bottom) },
            it.portrait.rotation,
        )
    }
