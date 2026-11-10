/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.ViewPropertyAnimator
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.scene.engine.ToolbarLook
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import org.fcitx.fcitx5.android.utils.circlePressHighlightDrawable
import org.fcitx.fcitx5.android.utils.motionSettings
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageResource
import splitties.views.padding

class ToolButton(context: Context) : CustomGestureView(context) {

    private val image = imageView {
        isClickable = false
        isFocusable = false
        padding = dp(10)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    var iconRotation: Float
        get() = image.rotation
        set(value) {
            image.rotation = value
        }

    /** The theme's toolbar button (060-render-p3 T6b): its fill and shape, its icon's colour, pressed and on. */
    private var toolbar: ToolbarLook? = null

    constructor(context: Context, @DrawableRes icon: Int, theme: Theme, look: ToolbarLook = SceneBar.of(context, theme).toolbar) : this(context) {
        toolbar = look
        image.imageTintList = ColorStateList.valueOf(look.icon.value)
        setIcon(icon)
        background = background(look, on = false)
        add(image, lParams(wrapContent, wrapContent, gravityCenter))
    }

    /** A toggle's button drawn on ([ToolbarLook.onIcon], [ToolbarLook.onFill]) or off, as the theme has it. */
    fun setOn(on: Boolean) {
        val look = toolbar ?: return
        setIconTint((if (on) look.onIcon else look.icon).value)
        if (look.fill != look.onFill) background = background(look, on)
    }

    // no fill nor radius in this state: the press highlight's circle (a ripple) as ever, so a theme's
    // `toolbar:selected fill` alone leaves every button off as it was; else the fill of its shape, the pressed colour over it
    private fun background(look: ToolbarLook, on: Boolean): Drawable {
        val fill = if (on) look.onFill else look.fill
        if (look.radius == null && fill == null) return pressHighlight(look.pressed.value)
        fun shape(color: Argb): Drawable = look.radius?.let { SceneBar.shape(context, it, color) } ?: Circle(color.value)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), LayerDrawable(listOfNotNull(fill?.let(::shape), shape(look.pressed)).toTypedArray()))
            addState(intArrayOf(), fill?.let(::shape) ?: ColorDrawable(Color.TRANSPARENT))
        }
    }

    // no radius: a circle, as wide as the button's shorter side and in its middle (an oval in a wide button is not)
    @VisibleForTesting
    internal class Circle(@ColorInt color: Int) : GradientDrawable() {
        init {
            shape = OVAL
            setColor(color)
        }

        override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
            val side = minOf(right - left, bottom - top)
            val x = left + (right - left - side) / 2
            val y = top + (bottom - top - side) / 2
            super.setBounds(x, y, x + side, y + side)
        }
    }

    fun iconAnimate(): ViewPropertyAnimator = image.animate()

    /** The icon [px] square whatever the button's size: a button made narrower keeps it as it was. */
    fun fixIconSize(px: Int) {
        image.padding = 0
        image.layoutParams = image.layoutParams.apply {
            width = px
            height = px
        }
    }

    /** The icon's colour: lit (a toggle on) or not */
    @get:ColorInt
    val iconTint: Int get() = image.imageTintList?.defaultColor ?: 0

    fun setIconTint(@ColorInt color: Int) {
        image.imageTintList = ColorStateList.valueOf(color)
    }

    fun setIcon(@DrawableRes icon: Int) {
        image.imageResource = icon
    }

    // the ripple's spread is animation too: off with 移除动画 as with 禁用动画
    private fun pressHighlight(@ColorInt color: Int): Drawable = if (!motionSettings().animates) {
        circlePressHighlightDrawable(color)
    } else {
        borderlessRippleDrawable(color, dp(20))
    }
}
