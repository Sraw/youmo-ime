/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.settings.cards.ChoiceCard
import splitties.dimensions.dp
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapInScrollView

/** [content] is at most [MAX_WIDTH_DP] wide and centred (stretched keys looked sparse); [wide] takes the whole width. */
abstract class KeyCapsPageFragment : Fragment() {

    protected lateinit var content: LinearLayout

    protected lateinit var wide: LinearLayout

    protected abstract fun rebuild()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val pad = ctx.dp(16)
        wide = ctx.verticalLayout {
            // a choice card's badge hangs over its corner into this padding
            setPadding(pad, ctx.dp(ChoiceCard.OVERHANG_DP), pad, 0)
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
        }
        content = object : LinearLayout(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val most = ctx.dp(MAX_WIDTH_DP)
                val width = if (MeasureSpec.getSize(widthMeasureSpec) > most) MeasureSpec.makeMeasureSpec(most, MeasureSpec.EXACTLY) else widthMeasureSpec
                super.onMeasure(width, heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, ctx.dp(8), pad, pad)
        }
        val page = ctx.verticalLayout {
            addView(wide, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        return page.wrapInScrollView {
            clipToPadding = false
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                view.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
                insets
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rebuild()
    }

    override fun onStop() {
        // before the first unlock the keyboard reads them from device-protected storage
        AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        super.onStop()
    }

    companion object {
        const val MAX_WIDTH_DP = 600
    }
}
