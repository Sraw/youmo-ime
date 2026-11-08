/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.pack.PackStatus
import org.fcitx.fcitx5.android.utils.AppUtil
import splitties.dimensions.dp

/**
 * Over the whole keyboard while the text pack is not usable ([status]): the keyboard cannot type
 * without it, so it says so, and any touch on it opens the 资源包 page. Made with the keyboard
 * (FcitxInputMethodService), which is made anew once the pack is imported.
 */
@SuppressLint("ViewConstructor")
class PackGateView(context: Context, theme: Theme, status: PackStatus) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(theme.keyboardColor)
        val pad = dp(PADDING)
        setPadding(pad, pad, pad, pad)
        val message = when (status) {
            is PackStatus.NeedsUpdate -> context.getString(R.string.pack_text_needed_update, status.needed.minVersion)
            is PackStatus.NeedsNewerApp -> context.getString(R.string.pack_text_needed_app, status.manifest.version)
            else -> context.getString(R.string.pack_text_needed)
        }
        addView(TextView(context).apply {
            text = message
            textSize = TITLE_SP
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = context.getString(R.string.pack_gate_hint)
            textSize = HINT_SP
            setTextColor(theme.altKeyTextColor)
            gravity = Gravity.CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(HINT_GAP) })
        contentDescription = message
        isClickable = true
        setOnClickListener { AppUtil.launchMainToPacks(context) }
    }

    // every touch on it is its own: none reaches the keys under it
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        return true
    }

    private companion object {
        const val PADDING = 24
        const val HINT_GAP = 8
        const val TITLE_SP = 18f
        const val HINT_SP = 14f
    }
}
