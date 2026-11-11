/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.BuiltinThemeFiles
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp

/** A theme's name as the list shows it under its card (the owner, 0.6.0-beta2). */
object ThemeNames {

    /**
     * The built-in 3.0-era themes by their names in the docs (动态取色, Material, Pixel, AMOLED 黑); a 4.0
     * theme's (a built-in pack, an imported one) its file's, its versions one card; the user's own its name,
     * one named by a UUID (fcitx5-android's in-app themes, 0.5 and before) 我的主题, a UUID meaning nothing.
     */
    fun shown(ctx: Context, theme: Theme): String = when {
        theme is Theme.Builtin -> BUILTIN[theme.file]?.let(ctx::getString) ?: theme.file
        theme is Theme.Versioned -> theme.file
        UUID.matches(theme.name) -> ctx.getString(R.string.theme_name_custom)
        else -> theme.name
    }

    private val BUILTIN = mapOf(
        BuiltinThemeFiles.MONET to R.string.theme_name_monet,
        "material" to R.string.theme_name_material,
        "pixel" to R.string.theme_name_pixel,
        "amoled-black" to R.string.theme_name_amoled_black,
    )

    private val UUID = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    /** The name row's height: a line of 12 sp and room above it. */
    const val ROW_DP = 24
}

/** The one-line name under a card. */
internal fun themeNameView(ctx: Context) = TextView(ctx).apply {
    textSize = 12f
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    gravity = Gravity.CENTER
    setTextColor(ctx.styledColor(android.R.attr.textColorPrimary))
    // read with the item (its root is the clickable one)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
}

/** [card] (the list item's size less a name row) with [name] under it, the item. */
internal fun themeCardWithName(ctx: Context, card: View, name: TextView) = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    clipChildren = false
    clipToPadding = false
    card.isDuplicateParentStateEnabled = true
    addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(ThemeNames.ROW_DP)))
}
