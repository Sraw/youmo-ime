/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp

/**
 * The symbol panel's categories, named in words down its side as Sogou lists them: icons in a
 * row above said less, and left no room for more of them.
 */
class PickerSideUi(context: Context, private val theme: Theme) : ScrollView(context) {

    private val keyRipple by ThemeManager.prefs.keyRippleEffect

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private var items: List<TextView> = emptyList()

    private var selected = -1

    var onPick: ((Int) -> Unit)? = null

    init {
        isVerticalScrollBarEnabled = false
        setBackgroundColor(theme.barColor)
        addView(column)
    }

    fun setCategories(names: List<String>) {
        column.removeAllViews()
        selected = -1
        items = names.mapIndexed { i, name ->
            TextView(context).apply {
                text = name
                textSize = 15f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(theme.keyTextColor)
                if (!keyRipple) foreground = pressHighlightDrawable(theme.keyPressHighlightColor)
                setOnClickListener { onPick?.invoke(i) }
                column.addView(this, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(ITEM_HEIGHT)))
            }
        }
        items.forEach { style(it, false) }
    }

    fun setShown(category: Int, shown: Boolean) {
        items.getOrNull(category)?.isVisible = shown
    }

    fun activate(category: Int) {
        if (category == selected) return
        items.getOrNull(selected)?.let { style(it, false) }
        items.getOrNull(category)?.let {
            style(it, true)
            // scrolled into sight, once laid out
            post { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), false) }
        }
        selected = category
    }

    private fun style(item: TextView, active: Boolean) {
        item.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        item.setTextColor(theme.keyTextColor.alpha(if (active) 1f else INACTIVE))
        // the one shown the colour of the list beside it, as if a tab of it
        item.background = when {
            active -> ColorDrawable(theme.keyboardColor)
            keyRipple -> rippleDrawable(theme.keyPressHighlightColor)
            else -> null
        }
    }

    companion object {
        private const val ITEM_HEIGHT = 44
        private const val INACTIVE = 0.6f
    }
}
