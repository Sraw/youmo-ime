/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.BaseInputView
import org.fcitx.fcitx5.android.input.InputView
import splitties.dimensions.dp

/**
 * Hold to talk on the space key ([VoiceHoldSession]): over the keyboard while the finger is down,
 * the bars moving with the voice, what is heard so far, and where to slide to drop it; typed into
 * the field ([commit]) once the finger lifts. All of it in the upper part, the lower left empty:
 * the finger is on the space, at the bottom, and covers what is under it. The keyboard's view
 * stays under the overlay, so the key keeps the touch: [BaseInputView.hold] follows the finger.
 */
class VoiceHoldOverlay(
    private val inputView: InputView,
    private val overlay: FrameLayout,
    // the finger the long press was for
    private val pointer: Int,
    commit: (String) -> Unit,
) : BaseInputView.Hold, VoiceHoldSession.Ui {
    private val theme = inputView.theme
    private val ctx = inputView.context
    private val keyboard = inputView.keyboardView

    private val downY = inputView.downY(pointer)

    private val session = VoiceHoldSession(inputView.service, inputView.fcitx, VoiceHold(keyboard.height * CANCEL_SHARE), this, commit)
    private val main = Handler(Looper.getMainLooper())
    private var gone = false

    private val talkColor = theme.keyboardColor
    private val background = GradientDrawable().apply { setColor(talkColor) }
    private var colorAnimator: ValueAnimator? = null
    private var currentColor = talkColor

    private val heard = TextView(ctx).apply {
        textSize = 18f
        gravity = Gravity.CENTER or Gravity.BOTTOM
        VoiceStyle.heard(this)
        setTextColor(theme.keyTextColor)
    }
    private val wave = VoiceWaveView(ctx).apply {
        color = theme.accentKeyBackgroundColor
        active = true
    }

    // the bars on a pill of their own, as a bubble the voice is in
    private val pillColor = ColorUtils.setAlphaComponent(theme.accentKeyBackgroundColor, PILL_ALPHA)
    private val pill = FrameLayout(ctx).apply {
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(PILL_HEIGHT / 2).toFloat()
            setColor(pillColor)
        }
        addView(wave, FrameLayout.LayoutParams(ctx.dp(WAVE_WIDTH), ctx.dp(WAVE_HEIGHT), Gravity.CENTER))
    }
    // what the status line says (listeningText): ready before silent
    // the microphone's first sample not zero come: what 正在听 waits for
    private var ready = false
    // 2 s of zeroes before the first sound
    private var silent = false
    // in the zone where lifting drops it: 松开手指，取消输入 stays as the microphone comes on
    private var cancelling = false
    // the 正在听 cue felt this hold (the card is made anew for each)
    private var cued = false

    private val status = TextView(ctx).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        // 正在听 once the microphone is really on (ready), not as the finger goes down
        setText(R.string.voice_hold_preparing)
    }

    private val hint = TextView(ctx).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(theme.altKeyTextColor)
        setText(R.string.voice_hold_hint)
    }
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = this@VoiceHoldOverlay.background
        val pad = ctx.dp(12)
        setPadding(pad, pad, pad, 0)
        addView(heard, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(pill, LinearLayout.LayoutParams(ctx.dp(PILL_WIDTH), ctx.dp(PILL_HEIGHT)).apply { topMargin = ctx.dp(8) })
        addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
        // under the finger: nothing there, it would not be seen. The keyboard's view runs under
        // the navigation bar too: from where the finger went down to the bottom, and its tip above
        val under = (keyboard.bottom - downY + ctx.dp(FINGER)).toInt().coerceIn(0, keyboard.height / 2)
        addView(View(ctx), LinearLayout.LayoutParams(-1, under))
        // a touch on it is the held finger's, not the keys' under it
        isClickable = true
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            // the keyboard thrown away under it (hidden, rotated): nothing typed
            override fun onViewDetachedFromWindow(v: View) = session.drop()
        })
    }

    /** The long press has fired: the microphone opens now, the models load meanwhile. */
    fun begin() {
        overlay.addView(card, FrameLayout.LayoutParams(keyboard.width, keyboard.height).apply {
            leftMargin = keyboard.left
            topMargin = keyboard.top
        })
        card.alpha = 0f
        pill.scaleX = APPEAR_SCALE
        pill.scaleY = APPEAR_SCALE
        card.animate().alpha(1f).setDuration(VoiceStyle.ANIMATE_MS).start()
        pill.animate().scaleX(1f).scaleY(1f).setDuration(VoiceStyle.ANIMATE_MS * 2).start()
        inputView.hold = this
        session.begin()
    }

    override fun touched(ev: MotionEvent) {
        if (session.over) return
        // lifted, the rest is the recognizer's: a tap or a stray cancel drops nothing said. A new
        // press ends the tail still being recorded, anywhere on the keyboard's view
        if (session.released) {
            val action = ev.actionMasked
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) session.pressedAgain()
            return
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val i = ev.findPointerIndex(pointer)
                if (i >= 0) session.moved(0f, ev.getY(i) - downY)
            }
            MotionEvent.ACTION_UP -> session.lift(ev.eventTime)
            MotionEvent.ACTION_POINTER_UP -> if (ev.getPointerId(ev.actionIndex) == pointer) session.lift(ev.eventTime)
            MotionEvent.ACTION_CANCEL -> session.drop()
        }
    }

    /** another field, or the keyboard hidden: nothing typed, the microphone closed */
    override fun inputEnded() = session.drop()

    override fun ready() {
        ready = true
        if (cancelling) return
        cued = true
        // the cue to start talking: felt as well as seen; not under 松开手指，取消输入, which it would contradict
        VoiceStyle.vibrate(card, cue = true)
        status.setText(R.string.voice_hold_listening)
    }

    override fun silent() {
        silent = true
        if (!cancelling) status.setText(R.string.voice_hold_silent)
    }

    private fun listeningText() = when {
        ready -> R.string.voice_hold_listening
        silent -> R.string.voice_hold_silent
        else -> R.string.voice_hold_preparing
    }

    override fun heard(text: String) {
        heard.text = text
    }

    override fun level(level: Float) = wave.level(level)

    override fun zone(cancel: Boolean) {
        cancelling = cancel
        // back from the cancel zone with the microphone come on there: its cue now, once
        val cue = !cancel && ready && !cued
        if (cue) cued = true
        VoiceStyle.vibrate(card, cue = cue)
        status.setText(if (cancel) R.string.voice_hold_cancel else listeningText())
        val text = if (cancel) CANCEL_TEXT else theme.keyTextColor
        status.setTextColor(text)
        heard.setTextColor(text)
        hint.visibility = if (cancel) View.INVISIBLE else View.VISIBLE
        wave.color = if (cancel) CANCEL_TEXT else theme.accentKeyBackgroundColor
        (pill.background as GradientDrawable).setColor(if (cancel) CANCEL_PILL else pillColor)
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentColor, if (cancel) VoiceStyle.CANCEL_RED else talkColor).apply {
            duration = VoiceStyle.ANIMATE_MS
            addUpdateListener {
                currentColor = it.animatedValue as Int
                background.setColor(currentColor)
            }
            start()
        }
    }

    override fun recognizing() {
        status.setText(R.string.voice_hold_recognizing)
        hint.visibility = View.INVISIBLE
        wave.active = false
    }

    override fun ended(message: Int?) {
        if (inputView.hold === this) inputView.hold = null
        if (gone) return
        gone = true
        // still shown a moment (the message, the fade), but no longer the held finger's: clickable,
        // it took the next press on the space under it and the long press never fired (VO-16)
        card.isClickable = false
        message?.let { status.setText(it) }
        main.postDelayed({
            card.animate().alpha(0f).setDuration(VoiceStyle.ANIMATE_MS).withEndAction { overlay.removeView(card) }.start()
        }, if (message != null) VoiceHoldSession.MESSAGE_MS else 0L)
    }

    companion object {
        // slid up a quarter of the keyboard's height: no slip of the finger, and still in reach
        private const val CANCEL_SHARE = 0.25f

        // how far above the point it touches a finger still covers, in dp
        private const val FINGER = 32
        private const val CANCEL_PILL = 0x33FFFFFF
        private const val CANCEL_TEXT = 0xFFFFFFFF.toInt()
        private const val PILL_ALPHA = 0x29
        private const val PILL_WIDTH = 168
        private const val PILL_HEIGHT = 52
        private const val WAVE_WIDTH = 104
        private const val WAVE_HEIGHT = 32
        private const val APPEAR_SCALE = 0.85f
    }
}
