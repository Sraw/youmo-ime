/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import androidx.appcompat.app.AlertDialog
import androidx.core.text.inSpans
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.openUrl

/**
 * 新增主题's menu (060-render-p3 M5): import a theme file, or make one on the web theme maker in a
 * browser (the app has no network permission, so no WebView) and come back to import it.
 */
object ThemeAddMenu {

    fun show(ctx: Context, import: () -> Unit): AlertDialog {
        val web = SpannableStringBuilder(ctx.getString(R.string.theme_add_web)).append('\n')
            .inSpans(RelativeSizeSpan(SMALL)) { append(ctx.getString(R.string.theme_add_web_hint)) }
        val items = arrayOf<CharSequence>(ctx.getString(R.string.theme_add_import), web)
        return AlertDialog.Builder(ctx)
            .setTitle(R.string.new_theme)
            .setItems(items) { _, which ->
                if (which == 0) import() else ctx.openUrl(Const.themeMakerUrl)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private const val SMALL = 0.8f
}
