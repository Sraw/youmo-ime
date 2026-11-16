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

    /** The theme's toolbar button (060-render-p3 T6b): its fill and shape, its icon's colour, pressed and on. */
    private var toolbar: ToolbarLook? = null

    // the theme's icons ([setIcon] of a name) and the one shown by its name, null: an image of the app's alone
    private var icons = PackIcons.EMPTY
    private var shown: BarIcons.Icon? = null

    // the icon's side ([fixIconSize]), 0: a vector's own, 24 dp
    private var iconPx = 0

    /** A button of the bar's or a panel's whose [icon] a theme may replace (061-toolbar-icons). */
    constructor(context: Context, icon: BarIcons.Icon, theme: Theme, look: ToolbarLook = SceneBar.of(context, theme).toolbar) : this(context) {
        dress(theme, look)
        setIcon(icon)
    }

    /** A button whose icon is the app's whatever the theme (the title bar's back, the editor info's). */
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

    // a toggle's state, said by TalkBack as checked or not; null: no toggle
    private var on: Boolean? = null

    /**
     * A toggle's button drawn on ([ToolbarLook.onIcon], [ToolbarLook.onFill]) or off, as the theme has it, and
     * said so: a checkable node, checked while on (061-handwriting-toolbar §3.8; stateDescription is API 30's).
     */
    fun setOn(on: Boolean) {
        this.on = on
        val look = toolbar ?: return
        setIconTint((if (on) look.onIcon else look.icon).value)
        if (look.fill != look.onFill) background = background(look, on)
    }

    /** [setOn] with the icon of that state ([icon]): drawn once, in the state's colour. */
    fun setOn(on: Boolean, icon: BarIcons.Icon) {
        shown = icon
        setOn(on)
        // the app's: set by its resource, the tint list already the state's
        if (!themed) drawIcon()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        on?.let {
            info.isCheckable = true
            info.isChecked = it
        }
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

    var iconAlpha: Float
        get() = image.alpha
        set(value) {
            image.alpha = value
        }

    /**
     * False: of no use here (手写 in English), drawn greyed, the theme's icon or the app's alike, and still in its place
     * so that nothing moves (the user's call, 2026-11-15); its tap says why (KawaiiBarComponent).
     */
    var available = true
        set(value) {
            if (value == field) return
            field = value
            alpha = if (value) 1f else UNAVAILABLE_ALPHA
        }

    fun iconAnimate(): ViewPropertyAnimator = image.animate()

    /** The icon [px] square whatever the button's size: a button made narrower keeps it as it was. */
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

    /** The icon's colour: lit (a toggle on) or not */
    @get:ColorInt
    val iconTint: Int get() = image.imageTintList?.defaultColor ?: 0

    fun setIconTint(@ColorInt color: Int) {
        image.imageTintList = ColorStateList.valueOf(color)
        // a theme's icon is drawn in its colour (a tint list is nothing to it): drawn again in this one
        if (themed) drawIcon()
    }

    fun setIcon(@DrawableRes icon: Int) {
        shown = null
        drawn = null
        showOwn(icon)
    }

    // the image read anew, not the resource's shared one: a vector's raster only ever grows, and a copy (mutate) starts
    // at the size its source grew to. Drawn at its own size elsewhere first (another bar, a panel), the shared one made
    // this button's smaller icon sharper than drawn alone, its pixels hanging on what was drawn before
    // (a toolbar image is a vector's XML, read as XML: lint takes getXml's id for an xml resource's alone. One that
    // isn't (a bitmap) has no raster to grow, and is read the usual way, a copy of its own.) The same icon shown again
    // (each arrangement, status and input method update sets it) keeps the image it has.
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

    /** The app's image shown, by its resource; 0 for a theme's. The ImageView, given the drawable, has none. */
    @get:VisibleForTesting
    @DrawableRes
    internal var iconRes = 0
        private set

    /** [icon]: the theme's of its name where it has one (nothing till it is read), else the app's ([BarIconDrawables]). */
    fun setIcon(icon: BarIcons.Icon) {
        shown = icon
        drawIcon()
    }

    /** Whether the icon shown is the theme's by its name (or will be, once read). */
    val themed: Boolean get() = shown?.let { BarIconDrawables.themed(icons, it) } == true

    /** The icon as drawn now; null where the theme's is not read yet. */
    @get:VisibleForTesting
    internal val iconDrawable: Drawable? get() = image.drawable

    // what the theme's icon drawn now was drawn of (name, colour, side): the same again is not rastered anew
    private var drawn: Triple<BarIcons.Icon, Int, Int>? = null

    private fun drawIcon() {
        val icon = shown ?: return
        if (!BarIconDrawables.themed(icons, icon)) {
            // the app's, in the tint list as ever
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
        // the app's vectors' side
        private const val DEFAULT_ICON_DP = 24

        /** Material's disabled content: a button of no use here (⋯'s panel's cell for it too) */
        const val UNAVAILABLE_ALPHA = 0.38f
    }
}
