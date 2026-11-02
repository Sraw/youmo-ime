/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.NotLearned
import splitties.dimensions.dp

/**
 * The bar's 「不学」 mark (D5): 学 struck through, at the bar's left where the field learns nothing
 * or less than others; held, a small bubble says why ([NotLearned]). Gone where everything is learned.
 */
@SuppressLint("ViewConstructor")
class NotLearnedMark(ctx: Context, private val theme: Theme) : View(ctx) {

    private val glyph = ctx.getString(R.string.not_learned_glyph)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.altKeyTextColor
        textAlign = Paint.Align.CENTER
        textSize = dp(GLYPH_DP).toFloat()
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.altKeyTextColor
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }

    var reasons: List<NotLearned> = emptyList()
        set(value) {
            field = value
            visibility = if (value.isEmpty()) GONE else VISIBLE
            contentDescription = context.getString(R.string.not_learned_mark) + "\n" + said()
            if (value.isEmpty()) bubble?.dismiss()
        }

    private var bubble: PopupWindow? = null

    init {
        visibility = GONE
        setOnLongClickListener {
            showBubble()
            true
        }
    }

    /** What the bubble says: each reason on its own line. */
    fun said(): String = reasons.joinToString("\n") { context.getString(textOf(it)) }

    private fun showBubble() {
        bubble?.dismiss()
        val pad = dp(8)
        val view = TextView(context).apply {
            text = said()
            textSize = 13f
            setTextColor(theme.popupTextColor)
            setPadding(pad + pad / 2, pad, pad + pad / 2, pad)
            background = GradientDrawable().apply {
                setColor(theme.popupBackgroundColor)
                cornerRadius = dp(8).toFloat()
            }
        }
        // from the mark's left, over the bar: the keys under it stay as they are
        bubble = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true
            isTouchable = true
            elevation = dp(4).toFloat()
            view.setOnClickListener { dismiss() }
            view.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            val at = IntArray(2).also(::getLocationInWindow)
            showAtLocation(this@NotLearnedMark, Gravity.START or Gravity.TOP, at[0], at[1] - view.measuredHeight - dp(4))
        }
        removeCallbacks(dismissBubble)
        postDelayed(dismissBubble, BUBBLE_MS)
    }

    private val dismissBubble = Runnable { bubble?.dismiss() }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(dismissBubble)
        bubble?.dismiss()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText(glyph, cx, cy - (text.ascent() + text.descent()) / 2, text)
        val half = text.textSize * SLASH
        canvas.drawLine(cx - half, cy + half, cx + half, cy - half, stroke)
    }

    companion object {
        /** The mark's width beside the bar's buttons: narrower, so candidates and digits lose little. */
        const val WIDTH_DP = 28

        private const val GLYPH_DP = 15

        // how far the stroke reaches from the middle, of the glyph's size
        private const val SLASH = 0.6f

        private const val BUBBLE_MS = 4000L

        @StringRes
        fun textOf(reason: NotLearned): Int = when (reason) {
            NotLearned.PASSWORD -> R.string.not_learned_password
            NotLearned.APP_ASKS -> R.string.not_learned_app_asks
            NotLearned.OWN_APP -> R.string.not_learned_own_app
            NotLearned.HABITS_OFF -> R.string.not_learned_habits_off
            NotLearned.NO_PAIRS -> R.string.not_learned_no_pairs
            NotLearned.NO_PREDICT -> R.string.not_learned_no_predict
        }
    }
}
