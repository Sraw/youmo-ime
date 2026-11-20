/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import androidx.annotation.VisibleForTesting
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
import kotlin.math.ceil

object ThemeNames {

    /** UUID-named user themes (pre-0.6 in-app themes) show as 我的主题, numbered by UUID order so moving cards keeps names. */
    fun shown(ctx: Context, theme: Theme, among: List<Theme> = emptyList()): String = when {
        theme is Theme.Builtin -> BUILTIN[theme.file]?.let(ctx::getString) ?: theme.file
        theme is Theme.Versioned -> theme.file
        numbered(theme) -> number(theme, among).let { if (it <= 1) ctx.getString(R.string.theme_name_custom) else ctx.getString(R.string.theme_name_custom_n, it) }
        else -> theme.name
    }

    fun shown(ctx: Context, file: String): String = BUILTIN[file]?.let(ctx::getString) ?: file

    fun numbered(theme: Theme): Boolean = theme !is Theme.Builtin && theme !is Theme.Versioned && UUID.matches(theme.name)

    // 1 for the first UUID of those in [among], 1 too for one not among them
    private fun number(theme: Theme, among: List<Theme>): Int =
        among.filter(::numbered).map { it.name }.distinct().sorted().indexOf(theme.name) + 1

    /** BuiltinThemeFiles.FILES; a test holds every one to having one. */
    @VisibleForTesting
    internal val BUILTIN: Map<String, Int> = mapOf(
        BuiltinThemeFiles.MONET to R.string.theme_name_monet,
        BuiltinThemeFiles.MATERIAL to R.string.theme_name_material,
        BuiltinThemeFiles.PIXEL to R.string.theme_name_pixel,
        BuiltinThemeFiles.AMOLED_BLACK to R.string.theme_name_amoled_black,
    )

    private val UUID = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    /** At least 24 dp, more as the font is set larger. */
    fun rowHeight(ctx: Context): Int {
        val scale = ctx.resources.configuration.fontScale
        val density = ctx.resources.displayMetrics.density
        rows?.takeIf { it.scale == scale && it.density == density }?.let { return it.px }
        val px = maxOf(ctx.dp(MIN_ROW_DP), ceil(themeNameView(ctx).textSize * LINE).toInt() + ctx.dp(ROOM_DP))
        rows = Row(scale, density, px)
        return px
    }

    // every card asks it: cached by font scale and density
    private class Row(val scale: Float, val density: Float, val px: Int)

    @Volatile
    private var rows: Row? = null

    private const val LINE = 1.4f

    private const val MIN_ROW_DP = 24
    private const val ROOM_DP = 8
}

internal fun themeNameView(ctx: Context) = TextView(ctx).apply {
    textSize = 12f
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    gravity = Gravity.CENTER
    setTextColor(ctx.styledColor(android.R.attr.textColorPrimary))
    // read with the item (its root is the clickable one)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
}

internal fun themeCardWithName(ctx: Context, card: View, name: TextView) = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    clipChildren = false
    clipToPadding = false
    card.isDuplicateParentStateEnabled = true
    addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ThemeNames.rowHeight(ctx)))
}
