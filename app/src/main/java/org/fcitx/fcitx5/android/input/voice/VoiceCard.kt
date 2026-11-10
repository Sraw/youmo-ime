/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import splitties.dimensions.dp

/**
 * The parts of the card the two talks show alike (VoiceHoldOverlay, held; VoiceTapOverlay,
 * tapped): the bars on their pill, and the card's going. Made in one place so they stay alike.
 */
internal object VoiceCard {

    /** the bars, in [color] (the theme's voice look's microphone colour) */
    fun wave(ctx: Context, color: Int) = VoiceWaveView(ctx).apply {
        this.color = color
        active = true
    }

    /** [wave] on a pill of its own, as a bubble the voice is in */
    fun pill(ctx: Context, wave: View, color: Int) = FrameLayout(ctx).apply {
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(PILL_HEIGHT / 2).toFloat()
            setColor(color)
        }
        addView(wave, FrameLayout.LayoutParams(ctx.dp(WAVE_WIDTH), ctx.dp(WAVE_HEIGHT), Gravity.CENTER))
    }

    /** the pill's place in the card's column */
    fun pillParams(ctx: Context) = LinearLayout.LayoutParams(ctx.dp(PILL_WIDTH), ctx.dp(PILL_HEIGHT)).apply { topMargin = ctx.dp(PILL_GAP) }

    /** [card] faded out and taken away, after [message] has shown a moment if there is one */
    fun fadeOut(card: View, main: Handler, message: Int?) {
        main.postDelayed({
            card.animate().alpha(0f).setDuration(VoiceStyle.ANIMATE_MS).withEndAction { (card.parent as? ViewGroup)?.removeView(card) }.start()
        }, if (message != null) VoiceSession.MESSAGE_MS else 0L)
    }

    private const val PILL_WIDTH = 168
    private const val PILL_HEIGHT = 52
    private const val PILL_GAP = 8
    private const val WAVE_WIDTH = 104
    private const val WAVE_HEIGHT = 32
}
