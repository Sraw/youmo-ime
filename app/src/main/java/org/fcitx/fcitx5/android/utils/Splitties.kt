/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

@file:OptIn(InternalSplittiesApi::class)

package org.fcitx.fcitx5.android.utils

import android.content.Context
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import splitties.experimental.InternalSplittiesApi
import splitties.resources.withResolvedThemeAttribute
import splitties.views.dsl.core.Ui

fun Context.styledFloat(@AttrRes attrRes: Int) = withResolvedThemeAttribute(attrRes) {
    when (type) {
        TypedValue.TYPE_FLOAT -> float
        else -> throw IllegalArgumentException("float attribute expected")
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun View.styledFloat(@AttrRes attrRes: Int) = context.styledFloat(attrRes)

@Suppress("NOTHING_TO_INLINE")
inline fun Ui.styledFloat(@AttrRes attrRes: Int) = ctx.styledFloat(attrRes)

inline val ConstraintLayout.LayoutParams.unset
    get() = ConstraintLayout.LayoutParams.UNSET

/**
 * Replaces splitties' `styledColor`, which rejects anything but a plain color. Material 3 themes
 * point attributes such as `colorControlNormal` at color state lists; this takes their default.
 */
@ColorInt
fun Context.styledColor(@AttrRes attrRes: Int): Int =
    MaterialColors.getColor(this, attrRes, "styledColor")

@Suppress("NOTHING_TO_INLINE")
inline fun View.styledColor(@AttrRes attrRes: Int) = context.styledColor(attrRes)

@Suppress("NOTHING_TO_INLINE")
inline fun Ui.styledColor(@AttrRes attrRes: Int) = ctx.styledColor(attrRes)

@Suppress("NOTHING_TO_INLINE")
inline fun Fragment.styledColor(@AttrRes attrRes: Int) = requireContext().styledColor(attrRes)

@ColorInt
fun Context.styledColorOrDefault(@AttrRes attrRes: Int, @ColorInt defaultValue: Int) =
    runCatching { styledColor(attrRes) }.getOrDefault(defaultValue)

@Suppress("NOTHING_TO_INLINE")
inline fun View.styledColorOrDefault(@AttrRes attrRes: Int, @ColorInt defaultValue: Int) =
    context.styledColorOrDefault(attrRes, defaultValue)

@Suppress("NOTHING_TO_INLINE")
inline fun Ui.styledColorOrDefault(@AttrRes attrRes: Int, @ColorInt defaultValue: Int) =
    ctx.styledColorOrDefault(attrRes, defaultValue)
