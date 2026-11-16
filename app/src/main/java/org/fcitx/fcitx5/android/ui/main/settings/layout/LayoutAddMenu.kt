/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.layout

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import androidx.appcompat.app.AlertDialog
import androidx.core.text.inSpans
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.openUrl

/**
 * 外观 → 布局's **+** (061-custom-layout §4.3): import a `.layout` file, or make one on the web layout maker in a browser
 * (the theme maker's `layout/` page; the app has no network permission), as 新增主题's menu (ThemeAddMenu).
 */
object LayoutAddMenu {

    /** The layout maker's page: the theme maker's `layout/`. */
    val makerUrl: String get() = Const.themeMakerUrl + "layout/"

    fun show(ctx: Context, import: () -> Unit): AlertDialog {
        val web = SpannableStringBuilder(ctx.getString(R.string.layout_add_web)).append('\n')
            .inSpans(RelativeSizeSpan(SMALL)) { append(ctx.getString(R.string.theme_add_web_hint)) }
        val items = arrayOf<CharSequence>(ctx.getString(R.string.layout_add_import), web)
        return AlertDialog.Builder(ctx)
            .setTitle(R.string.layout_add)
            .setItems(items) { _, which ->
                if (which == 0) import() else ctx.openUrl(makerUrl)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private const val SMALL = 0.8f
}
