/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.input.theme.ThemeColors
import org.fcitx.fcitx5.android.scene.look.BoardBase
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.imageResource
import splitties.views.setPaddingDp

class ThemeThumbnailUi(override val ctx: Context) : Ui {

    enum class State { Normal, Selected, LightMode, DarkMode }

    val bkg = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    val bar = view(::View)

    val keys = view(::View)

    val checkMark = imageView {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val dynamicIcon = imageView {
        setPaddingDp(5, 5, 5, 5)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageResource = R.drawable.ic_baseline_auto_awesome_24
    }

    // a two-version built-in theme: shows which; a tap shows the other
    val versionChip = textView {
        gravity = Gravity.CENTER
        textSize = 12f
        minWidth = dp(32)
        minHeight = dp(32)
        setPaddingDp(8, 0, 8, 0)
        // read out by [version], which says what a tap does
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 48 dp at the least around the 32 dp chip, so the card looks the same. */
    val version = FrameLayout(ctx).apply {
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        addView(versionChip, FrameLayout.LayoutParams(wrapContent, wrapContent, Gravity.TOP or Gravity.END))
        visibility = View.GONE
    }

    /** [root] is it with the theme's name under it. */
    val card = constraintLayout {
        outlineProvider = ViewOutlineProvider.BOUNDS
        elevation = dp(2f)
        add(bkg, lParams(matchParent, matchParent))
        add(bar, lParams(matchParent, dp(BAR_DP)))
        add(keys, lParams(matchParent, matchParent))
        add(checkMark, lParams(dp(60), dp(60)) {
            centerInParent()
        })
        add(dynamicIcon, lParams(dp(32), dp(32)) {
            topOfParent()
            startOfParent()
        })
        add(version, lParams(wrapContent, wrapContent) {
            topOfParent()
            endOfParent()
        })
    }

    // one line, ellipsized; read out with the card
    val name = themeNameView(ctx)

    override val root = themeCardWithName(ctx, card, name)

    /** Named as one of [among] (ThemeNames.shown). */
    fun setTheme(theme: Theme, among: List<Theme> = emptyList()) {
        card.apply {
            foreground = rippleDrawable(theme.keyPressHighlightColor)
        }
        name.text = ThemeNames.shown(ctx, theme, among)
        val look = theme.look
        // the picture (decoded small off the main thread, blurred: ThemeAssets.thumbnail), else its colour
        bkg.imageDrawable = null
        bkg.setTag(R.id.theme_thumbnail_look, look)
        if (look.base == BoardBase.Picture) {
            val p = look.picture!!
            val dm = ctx.resources.displayMetrics
            ThemeAssets.thumbnail(look, ctx.dp(PICTURE_DP), dm.widthPixels, dm.density) { b ->
                // bound to another theme since
                if (b == null || bkg.getTag(R.id.theme_thumbnail_look) !== look) return@thumbnail
                bkg.setImageBitmap(b)
                bkg.colorFilter = if (p.brightness < 100) DarkenColorFilter(100 - p.brightness) else null
            }
        }
        bkg.backgroundColor = ThemeColors.board(look.palette, look.keys)
        bar.backgroundColor = ThemeColors.bar(look.palette, look.bar) ?: Color.TRANSPARENT
        keys.background = ThemeThumbnailKeys(look, ctx.resources.displayMetrics.density, ctx.dp(BAR_DP).toFloat())
        val foregroundTint = ColorStateList.valueOf(theme.altKeyTextColor)
        dynamicIcon.apply {
            visibility =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && theme is Theme.Builtin && theme.followsSystem) View.VISIBLE else View.GONE
            imageTintList = foregroundTint
        }
        checkMark.imageTintList = foregroundTint
        versionChip.setTextColor(theme.altKeyTextColor)
        versionChip.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(16f)
            setColor(theme.altKeyBackgroundColor)
        }
    }

    /** A one-version theme: the chip says which it lacks; picked while following the system, used in both modes (ThemeSlots). */
    fun setOnly(dark: Boolean) {
        version.isVisible = true
        version.setOnClickListener(null)
        version.isClickable = false
        version.alpha = ONLY_ALPHA
        versionChip.setText(if (dark) R.string.theme_no_light else R.string.theme_no_dark)
        version.contentDescription = ctx.getString(if (dark) R.string.theme_no_light else R.string.theme_no_dark)
    }

    fun setVersion(dark: Boolean?, switch: () -> Unit) {
        version.alpha = 1f
        version.isVisible = dark != null
        if (dark == null) {
            version.setOnClickListener(null)
            return
        }
        versionChip.setText(if (dark) R.string.theme_version_dark else R.string.theme_version_light)
        // the chip's 浅/深 alone tells a screen reader neither
        version.contentDescription = ctx.getString(if (dark) R.string.theme_version_dark_switch else R.string.theme_version_light_switch)
        version.setOnClickListener { switch() }
    }

    fun setChecked(checked: Boolean) {
        checkMark.isVisible = checked
        checkMark.imageResource = R.drawable.ic_baseline_check_24
    }

    fun setChecked(state: State) {
        checkMark.isVisible = state != State.Normal
        checkMark.imageResource = when (state) {
            State.Normal -> 0
            State.Selected -> R.drawable.ic_baseline_check_24
            State.LightMode -> R.drawable.ic_baseline_light_mode_24
            State.DarkMode -> R.drawable.ic_baseline_dark_mode_24
        }
    }

    private companion object {
        const val BAR_DP = 14
        const val ONLY_ALPHA = 0.7f

        // the item's 128 dp, cropped to its height from a little wider
        const val PICTURE_DP = 160
    }
}
