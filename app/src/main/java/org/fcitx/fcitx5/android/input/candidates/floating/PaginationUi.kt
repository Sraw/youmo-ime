/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.floating

import android.content.Context
import android.widget.ImageView
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.bar.BarIconDrawables
import org.fcitx.fcitx5.android.input.bar.BarIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIcons
import org.fcitx.fcitx5.android.utils.styledFloat
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView

class PaginationUi(override val ctx: Context, val theme: Theme) : Ui {

    // a theme's `candidates-prev`, `candidates-next` too (061-toolbar-icons): drawn as the app's is, a square as high as the
    // arrow's room cropped to its width
    private fun createIcon(icon: BarIcons.Icon, @StringRes description: Int) = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
        isClickable = true
        contentDescription = ctx.getString(description)
        show(this, icon)
    }

    private fun show(v: ImageView, icon: BarIcons.Icon) {
        BarIconDrawables.show(v, PackIcons.of(theme), icon, theme.keyTextColor, v.dp(ARROW_HEIGHT_DP)) { show(v, icon) }
    }

    val prevIcon = createIcon(BarIcons.CANDIDATES_PREV, R.string.a11y_candidates_prev_page)
    val nextIcon = createIcon(BarIcons.CANDIDATES_NEXT, R.string.a11y_candidates_next_page)

    private val disabledAlpha = styledFloat(android.R.attr.disabledAlpha)

    override val root = constraintLayout {
        val w = dp(ARROW_WIDTH_DP)
        val h = dp(ARROW_HEIGHT_DP)
        add(nextIcon, lParams(w, h) {
            centerVertically()
            endOfParent()
        })
        add(prevIcon, lParams(w, h) {
            centerVertically()
            before(nextIcon)
        })
    }

    fun update(data: HostEvent.Paged) {
        prevIcon.alpha = if (data.hasPrev) 1f else disabledAlpha
        nextIcon.alpha = if (data.hasNext) 1f else disabledAlpha
    }

    private companion object {
        const val ARROW_WIDTH_DP = 10
        const val ARROW_HEIGHT_DP = 20
    }
}
