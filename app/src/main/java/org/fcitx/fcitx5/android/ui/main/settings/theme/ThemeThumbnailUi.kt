/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.input.theme.BoardBase
import org.fcitx.fcitx5.android.input.theme.ThemeColors
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.imageDrawable
import splitties.views.imageResource
import splitties.views.setPaddingDp

class ThemeThumbnailUi(override val ctx: Context) : Ui {

    enum class State { Normal, Selected, LightMode, DarkMode }

    val bkg = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    val bar = view(::View)

    // the theme's keys in little (ThemeThumbnailKeys)
    val keys = view(::View)

    val checkMark = imageView {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val dynamicIcon = imageView {
        setPaddingDp(5, 5, 5, 5)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageResource = R.drawable.ic_baseline_auto_awesome_24
    }

    override val root = constraintLayout {
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
    }

    fun setTheme(theme: Theme) {
        root.apply {
            foreground = rippleDrawable(theme.keyPressHighlightColor)
        }
        val look = theme.look
        // the picture under the keyboard if it is there (decoded small off the main thread, blurred as on
        // the keyboard: ThemeAssets.thumbnail), its colour else; the bar's colour if it has one
        bkg.imageDrawable = null
        bkg.tag = look
        if (look.base == BoardBase.Picture) {
            val p = look.picture!!
            val dm = ctx.resources.displayMetrics
            ThemeAssets.thumbnail(look, ctx.dp(PICTURE_DP), dm.widthPixels, dm.density) { b ->
                // bound to another theme since
                if (b == null || bkg.tag !== look) return@thumbnail
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && theme is Theme.Monet) View.VISIBLE else View.GONE
            imageTintList = foregroundTint
        }
        checkMark.imageTintList = foregroundTint
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

        // the picture decoded this wide: the item's 128 dp, cropped to its height from a little wider
        const val PICTURE_DP = 160
    }
}
