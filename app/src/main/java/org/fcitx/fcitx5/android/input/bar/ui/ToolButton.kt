/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.ViewPropertyAnimator
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.BarIconDrawables
import org.fcitx.fcitx5.android.input.bar.BarIcons
import org.fcitx.fcitx5.android.input.bar.ToolbarButtonLooks
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIcons
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

    private var toolbar: ToolbarLook? = null

    // null: an image of the app's alone
    private var icons = PackIcons.EMPTY
    private var shown: BarIcons.Icon? = null

    // 0: a vector's own, 24 dp
    private var iconPx = 0

    /** One whose [icon] a theme may replace. */
    constructor(context: Context, icon: BarIcons.Icon, theme: Theme, look: ToolbarLook = SceneBar.of(context, theme).toolbar) : this(context) {
        dress(theme, look)
        setIcon(icon)
    }

    /** The app's whatever the theme (the title bar's back, the editor info's). */
    constructor(context: Context, @DrawableRes icon: Int, theme: Theme, look: ToolbarLook = SceneBar.of(context, theme).toolbar) : this(context) {
        dress(theme, look)
        setIcon(icon)
    }

    private fun dress(theme: Theme, look: ToolbarLook) {
        toolbar = look
        icons = PackIcons.of(theme)
        image.imageTintList = ColorStateList.valueOf(look.icon.value)
        background = background(look, on = false)
        add(image, lParams(wrapContent, wrapContent, gravityCenter))
    }

    // said by TalkBack as checked or not; null: no toggle
    private var on: Boolean? = null

    // false where the button's name already says its state
    private var checkable = true

    /**
     * Said as a checkable node (stateDescription is API 30's). [checkable] false where its name is the state's
     * or the tap's (简繁, 我的输入习惯), which a checked state would contradict.
     */
    fun setOn(on: Boolean, checkable: Boolean = true) {
        this.on = on
        this.checkable = checkable
        val look = toolbar ?: return
        setIconTint((if (on) look.onIcon else look.icon).value)
        if (look.fill != look.onFill) background = background(look, on)
    }

    /** Drawn once, in the state's colour. */
    fun setOn(on: Boolean, icon: BarIcons.Icon, checkable: Boolean = true) {
        shown = icon
        setOn(on, checkable)
        // the app's: the tint list already the state's
        if (!themed) drawIcon()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        on?.takeIf { checkable }?.let {
            info.isCheckable = true
            info.isChecked = it
        }
    }

    // no fill nor radius in this state: the ripple as ever, so a theme's `toolbar:selected fill` alone leaves
    // every button off as it was
    private fun background(look: ToolbarLook, on: Boolean): Drawable {
        val fill = if (on) look.onFill else look.fill
        if (look.radius == null && fill == null) return pressHighlight(look.pressed.value)
        fun shape(color: Argb): Drawable = look.radius?.let { SceneBar.shape(context, it, color) } ?: Circle(color.value)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), LayerDrawable(listOfNotNull(fill?.let(::shape), shape(look.pressed)).toTypedArray()))
            addState(intArrayOf(), fill?.let(::shape) ?: ColorDrawable(Color.TRANSPARENT))
        }
    }

    // a circle in the middle (an oval in a wide button is not)
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

    var iconAlpha: Float
        get() = image.alpha
        set(value) {
            image.alpha = value
        }

    /** Drawn greyed and still in its place, so nothing moves; its tap says why (KawaiiBarComponent). */
    var available = true
        set(value) {
            if (value == field) return
            field = value
            alpha = if (value) 1f else UNAVAILABLE_ALPHA
        }

    fun iconAnimate(): ViewPropertyAnimator = image.animate()

    /** A button made narrower keeps it. */
    fun fixIconSize(px: Int) {
        if (px == iconPx) return
        image.padding = 0
        image.layoutParams = image.layoutParams.apply {
            width = px
            height = px
        }
        iconPx = px
        if (themed) drawIcon()
    }

    @get:ColorInt
    val iconTint: Int get() = image.imageTintList?.defaultColor ?: 0

    fun setIconTint(@ColorInt color: Int) {
        image.imageTintList = ColorStateList.valueOf(color)
        // a tint list is nothing to a theme's icon: drawn again in this one
        if (themed) drawIcon()
    }

    fun setIcon(@DrawableRes icon: Int) {
        shown = null
        drawn = null
        showOwn(icon)
    }

    // read anew, not the shared one: a vector's raster only ever grows, and a copy (mutate) starts at the size its
    // source grew to, so drawn bigger elsewhere first, it was sharper than alone. lint takes getXml's id for an
    // xml resource's alone; a bitmap has no raster to grow and is read the usual way
    @SuppressLint("ResourceType")
    private fun showOwn(@DrawableRes icon: Int) {
        if (iconRes == icon && image.drawable != null) return
        val res = context.resources
        val file = TypedValue().also { res.getValue(icon, it, true) }.string
        image.setImageDrawable(
            if (file?.endsWith(".xml") == true) res.getXml(icon).use { Drawable.createFromXml(res, it, context.theme) }
            else ContextCompat.getDrawable(context, icon)?.mutate()
        )
        iconRes = icon
    }

    /** 0 for a theme's. The ImageView, given the drawable, has none. */
    @get:VisibleForTesting
    @DrawableRes
    internal var iconRes = 0
        private set

    /** Nothing till the theme's is read. */
    fun setIcon(icon: BarIcons.Icon) {
        shown = icon
        drawIcon()
    }

    /** Not where its file failed and the app's is drawn. */
    val themed: Boolean get() = shown?.let { BarIconDrawables.drawsOwn(icons, it) } == true

    /** Null where the theme's is not read yet. */
    @get:VisibleForTesting
    internal val iconDrawable: Drawable? get() = image.drawable

    // the same again is not rastered anew
    private var drawn: Triple<BarIcons.Icon, Int, Int>? = null

    private fun drawIcon() {
        val icon = shown ?: return
        if (!BarIconDrawables.themed(icons, icon)) {
            drawn = null
            showOwn(ToolbarButtonLooks.drawable(icon.fallback))
            return
        }
        val px = iconPx.takeIf { it > 0 } ?: dp(DEFAULT_ICON_DP)
        val now = Triple(icon, iconTint, px)
        if (now == drawn && image.drawable != null) return
        val d = BarIconDrawables.of(context, icons, icon, iconTint, px) { if (shown === icon) drawIcon() }
        drawn = now.takeIf { d != null }
        iconRes = 0
        image.setImageDrawable(d)
    }

    // the ripple's spread is animation too: off with 移除动画 as with 禁用动画
    private fun pressHighlight(@ColorInt color: Int): Drawable = if (!motionSettings().animates) {
        circlePressHighlightDrawable(color)
    } else {
        borderlessRippleDrawable(color, dp(20))
    }

    companion object {
        private const val DEFAULT_ICON_DP = 24

        /** Material's disabled content */
        const val UNAVAILABLE_ALPHA = 0.38f
    }
}
