/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.BaseInputView
import org.fcitx.fcitx5.android.input.InputView
import splitties.dimensions.dp

/**
 * All of it in the upper part, the lower left empty: the finger is on the space and covers what is
 * under it. The keyboard's view stays under the overlay, so the key keeps the touch.
 */
class VoiceHoldOverlay(
    private val inputView: InputView,
    private val pointer: Int,
    commit: (String) -> Unit,
) : BaseInputView.Hold, VoiceHoldSession.Ui {
    private val theme = inputView.theme
    private val ctx = inputView.context
    private val keyboard = inputView.keyboardView

    private val downY = inputView.downY(pointer)

    // the finger's moves are the input view's pixels: the share of the keyboard as it is seen (scaled, floating)
    private val session = VoiceHoldSession(inputView.service, inputView.fcitx, VoiceHold(keyboard.height * keyboard.scaleY * CANCEL_SHARE), this, commit)
    private val main = Handler(Looper.getMainLooper())
    private var gone = false

    private val look = VoiceStyle.panel(ctx, theme)
    private val talkColor = look.fill.value
    private val glass = VoiceStyle.glass(ctx, look)
    private var colorAnimator: ValueAnimator? = null
    private var currentColor = talkColor

    private val heard = VoiceCard.heard(ctx, theme)
    private val wave = VoiceCard.wave(ctx, look.micColor.value)

    private val pillColor = look.micFill.value
    private val pill = VoiceCard.pill(ctx, wave, pillColor)
    // the microphone's first sample not zero come: what 正在听 waits for
    private var ready = false
    // 2 s of zeroes before the first sound
    private var silent = false
    // 松开手指，取消输入 stays as the microphone comes on
    private var cancelling = false
    // the card is made anew for each hold
    private var cued = false

    private val status = VoiceCard.status(ctx, theme)

    private val hint = VoiceCard.hint(ctx, theme, R.string.voice_hold_hint)
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = glass
        val pad = ctx.dp(12)
        setPadding(pad, pad, pad, 0)
        addView(heard, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(pill, VoiceCard.pillParams(ctx))
        addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
        // the keyboard's view runs under the navigation bar too: from where the finger went down to the bottom
        val under = (keyboard.height - downInKeyboard() + ctx.dp(FINGER)).toInt().coerceIn(0, keyboard.height / 2)
        addView(View(ctx), LinearLayout.LayoutParams(-1, under))
        // a touch on it is the held finger's, not the keys' under it
        isClickable = true
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            // the keyboard thrown away under it (hidden, rotated): what was heard typed, as a tap's
            override fun onViewDetachedFromWindow(v: View) = session.fieldLeft()
        })
    }

    // the keyboard may be moved and scaled in the input view (floating)
    private fun downInKeyboard(): Float {
        val at = floatArrayOf(0f, downY - keyboard.top)
        Matrix().also { keyboard.matrix.invert(it) }.mapPoints(at)
        return at[1]
    }

    fun begin() {
        // it moves and scales with it (floating, one-handed)
        inputView.overKeys(card, underBar = false)
        glass.under(card, keyboard as ViewGroup)
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
        // lifted, the rest is the recognizer's: a tap or a stray cancel drops nothing said
        if (session.released) {
            val action = ev.actionMasked
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) session.pressedAgain()
            return
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val i = ev.findPointerIndex(pointer)
                if (i >= 0) session.moved(ev.getY(i) - downY)
            }
            MotionEvent.ACTION_UP -> session.lift(ev.eventTime)
            MotionEvent.ACTION_POINTER_UP -> if (ev.getPointerId(ev.actionIndex) == pointer) session.lift(ev.eventTime)
            MotionEvent.ACTION_CANCEL -> session.drop()
        }
    }

    /** The same field's input restarted (a WebView, restartInput) while the finger is down: the hold goes on. */
    override fun inputEnded(how: BaseInputView.Hold.InputEnd) {
        if (how == BaseInputView.Hold.InputEnd.STARTED && session.sameField) return
        if (how == BaseInputView.Hold.InputEnd.HIDDEN && session.released) {
            session.hidden(SystemClock.uptimeMillis())
        } else {
            session.fieldLeft()
        }
    }

    override fun ready() {
        ready = true
        if (cancelling) return
        cued = true
        // not under 松开手指，取消输入, which it would contradict
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
        wave.color = if (cancel) CANCEL_TEXT else look.micColor.value
        (pill.background as GradientDrawable).setColor(if (cancel) CANCEL_PILL else pillColor)
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentColor, if (cancel) VoiceStyle.CANCEL_RED else talkColor).apply {
            duration = VoiceStyle.ANIMATE_MS
            addUpdateListener {
                currentColor = it.animatedValue as Int
                glass.color = currentColor
            }
            start()
        }
    }

    override fun recognizing() = VoiceCard.recognizing(status, hint, wave)

    override fun ended(message: Int?) {
        if (inputView.hold === this) inputView.hold = null
        if (gone) return
        gone = true
        // clickable, it took the next press on the space under it and the long press never fired
        VoiceCard.ended(card, status, hint, main, message)
    }

    companion object {
        // no slip of the finger, and still in reach
        private const val CANCEL_SHARE = 0.25f

        // how far above the point it touches a finger still covers, in dp
        private const val FINGER = 32
        private const val CANCEL_PILL = 0x33FFFFFF
        private const val CANCEL_TEXT = 0xFFFFFFFF.toInt()
        private const val APPEAR_SCALE = 0.85f
    }
}
