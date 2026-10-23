/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import androidx.annotation.ColorInt

fun View.setVerticalScrollbarThumbColor(@ColorInt color: Int) {
    val drawable = ShapeDrawable(RectShape()).apply {
        this.paint.color = color
    }
    verticalScrollbarThumbDrawable = drawable
}
