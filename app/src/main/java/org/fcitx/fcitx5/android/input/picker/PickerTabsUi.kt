/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.canvas.ScenePanels
import org.fcitx.fcitx5.android.scene.look.PressEffect
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.gravityCenter
import splitties.views.imageDrawable

/** The emoji's categories as tabs in the bar, their colours the theme's (`picker categories`, the one shown `picker:selected categories`; 060-render-p3 T7b). */
class PickerTabsUi(override val ctx: Context, val theme: Theme) : Ui {

    private val keyRipple = ThemeManager.shownLook(theme).keys.press == PressEffect.Ripple

    private val look = ScenePanels.picker(ctx, theme, side = false)

    inner class TabUi : Ui {
        override val ctx = this@PickerTabsUi.ctx

        var position: Int = -1

        val label = textView {
            textSize = 16f // sp
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(look.categories.color.value)
        }

        val icon = imageView()

        // its background not shown: the ripple, if any
        private val plain: Drawable? = if (keyRipple) rippleDrawable(theme.keyPressHighlightColor) else null

        override val root = view(::CustomGestureView) {
            add(label, lParams {
                gravity = gravityCenter
            })
            add(icon, lParams {
                gravity = gravityCenter
            })
            if (keyRipple) {
                background = plain
            } else {
                foreground = pressHighlightDrawable(theme.keyPressHighlightColor)
            }
            setOnClickListener {
                onTabClick(this@TabUi)
            }
        }

        fun setLabel(str: String) {
            label.text = str
            icon.isVisible = false
            label.isVisible = true
        }

        fun setIcon(@DrawableRes src: Int) {
            icon.imageDrawable = ctx.drawable(src)!!.apply {
                setTint(look.categories.color.value)
            }
            label.isVisible = false
            icon.isVisible = true
        }

        fun setActive(active: Boolean) {
            val color = (if (active) look.selected else look.categories).color.value
            label.setTextColor(color)
            icon.imageDrawable?.setTint(color)
            // the one shown on a fill of its own where the theme gives it one (none by default: the bar's)
            look.selected.fill?.let { root.background = if (active) ColorDrawable(it.value) else plain }
        }
    }

    private var tabs: Array<TabUi> = arrayOf()
    private var selected = -1

    private var onTabClick: (TabUi.(Int) -> Unit)? = null

    override val root = constraintLayout {
        look.categories.fill?.let { setBackgroundColor(it.value) }
    }

    fun setTabs(categories: List<PickerData.Category>) {
        tabs.forEach { root.removeView(it.root) }
        selected = -1
        tabs = Array(categories.size) {
            val category = categories[it]
            TabUi().apply {
                position = it
                if (category.icon != 0) setIcon(category.icon)
                else setLabel(category.label)
                setActive(false)
            }
        }
        tabs.forEachIndexed { i, tabUi ->
            root.add(tabUi.root, root.lParams {
                centerVertically()
                if (i == 0) startOfParent() else after(tabs[i - 1].root)
                if (i == tabs.size - 1) endOfParent() else before(tabs[i + 1].root)
            })
        }
    }

    fun activateTab(index: Int) {
        if (index == selected) return
        if (selected >= 0) {
            tabs[selected].setActive(false)
        }
        tabs[index].setActive(true)
        selected = index
    }

    private fun onTabClick(tabUi: TabUi) {
        onTabClick?.invoke(tabUi, tabUi.position)
    }

    fun setOnTabClickListener(listener: (TabUi.(Int) -> Unit)? = null) {
        onTabClick = listener
    }
}
