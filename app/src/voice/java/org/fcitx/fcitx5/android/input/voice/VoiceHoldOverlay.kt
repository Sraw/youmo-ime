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
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.BaseInputView
import org.fcitx.fcitx5.android.input.InputView
import splitties.dimensions.dp

/**
 * Hold to talk on the space key ([VoiceHold]): over the keyboard while the finger is down, the
 * bars moving with the voice, what is heard so far, and where to slide to drop it; typed into
 * the field ([commit]) once the finger lifts and the last of it is recognized. The keyboard's
 * view stays under it, so the key keeps the touch: [BaseInputView.hold] follows the finger.
 */
class VoiceHoldOverlay(
    private val inputView: InputView,
    private val overlay: FrameLayout,
    private val commit: (String) -> Unit,
) : BaseInputView.Hold {
    private val service = inputView.service
    private val theme = inputView.theme
    private val ctx = inputView.context
    private val keyboard = inputView.keyboardView
    private val hold = VoiceHold(cancelDistance = keyboard.height * CANCEL_SHARE)

    // the field and the finger the long press was for
    private val field = service.currentInputEditorInfo
    private val pointer = inputView.longPressPointer
    private val downY = inputView.downY(pointer)

    private val main = Handler(Looper.getMainLooper())
    private var listener: VoiceListener? = null
    private var over = false

    private val talkColor = theme.keyboardColor
    private val cancelColor = CANCEL_RED
    private val background = GradientDrawable().apply { setColor(talkColor) }
    private var colorAnimator: ValueAnimator? = null
    private var currentColor = talkColor

    private val heard = TextView(ctx).apply {
        textSize = 18f
        gravity = Gravity.CENTER
        maxLines = 3
        // the end of a long one is what was just said
        ellipsize = TextUtils.TruncateAt.START
        setTextColor(theme.keyTextColor)
    }
    private val wave = VoiceWaveView(ctx).apply {
        color = theme.accentKeyBackgroundColor
        active = true
    }
    private val status = TextView(ctx).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        setText(R.string.voice_hold_listening)
    }
    private val hint = TextView(ctx).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(theme.altKeyTextColor)
        setText(R.string.voice_hold_hint)
    }
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = this@VoiceHoldOverlay.background
        val pad = ctx.dp(16)
        // the keyboard's view runs under the navigation bar: the hint above it
        val bar = ViewCompat.getRootWindowInsets(inputView)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        setPadding(pad, pad, pad, pad + bar)
        addView(heard, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(wave, LinearLayout.LayoutParams(ctx.dp(WAVE_WIDTH), ctx.dp(WAVE_HEIGHT)).apply { gravity = Gravity.CENTER })
        addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(12) })
        addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        // a touch on it is the held finger's, not the keys' under it
        isClickable = true
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            // the keyboard thrown away under it (hidden, rotated): nothing typed
            override fun onViewDetachedFromWindow(v: View) = drop()
        })
    }

    private val events = object : VoiceListener.Events {
        override fun loaded() = Unit
        override fun speechStarted() = Unit
        override fun speechEnded() = Unit

        override fun recognized(text: String) {
            hold.heard(text)
            heard.text = hold.text
        }

        override fun level(level: Float) = wave.level(level)

        override fun failed(why: VoiceSession.Failure) {
            status.setText(
                when (why) {
                    VoiceSession.Failure.NoModel -> R.string.voice_no_model
                    else -> R.string.voice_no_microphone
                }
            )
            end(after = MESSAGE_MS)
        }
    }

    /** The long press has fired: the microphone opens now, the models load meanwhile. */
    fun begin() {
        overlay.addView(card, FrameLayout.LayoutParams(keyboard.width, keyboard.height).apply {
            leftMargin = keyboard.left
            topMargin = keyboard.top
        })
        card.alpha = 0f
        card.scaleX = APPEAR_SCALE
        card.scaleY = APPEAR_SCALE
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(APPEAR_MS).start()
        inputView.hold = this
        listener = VoiceListener(service.assets, events) { VoiceEngine.userHotwords(inputView.fcitx) }.also {
            it.load()
            it.start()
        }
    }

    override fun touched(ev: MotionEvent) {
        // lifted, the rest is the recognizer's: a tap or a stray cancel drops nothing said
        if (over || hold.released) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val i = ev.findPointerIndex(pointer)
                if (i >= 0 && hold.moved(ev.getY(i) - downY)) zoneChanged()
            }
            MotionEvent.ACTION_UP -> lifted()
            MotionEvent.ACTION_POINTER_UP -> if (ev.getPointerId(ev.actionIndex) == pointer) lifted()
            MotionEvent.ACTION_CANCEL -> drop()
        }
    }

    /** another field, or the keyboard hidden: nothing typed, the microphone closed */
    override fun inputEnded() = drop()

    private fun zoneChanged() {
        val cancel = hold.zone == VoiceHold.Zone.Cancel
        card.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        status.setText(if (cancel) R.string.voice_hold_cancel else R.string.voice_hold_listening)
        val text = if (cancel) CANCEL_TEXT else theme.keyTextColor
        status.setTextColor(text)
        heard.setTextColor(text)
        hint.visibility = if (cancel) View.INVISIBLE else View.VISIBLE
        wave.color = if (cancel) CANCEL_TEXT else theme.accentKeyBackgroundColor
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentColor, if (cancel) cancelColor else talkColor).apply {
            duration = APPEAR_MS
            addUpdateListener {
                currentColor = it.animatedValue as Int
                background.setColor(currentColor)
            }
            start()
        }
    }

    private fun lifted() {
        if (!hold.release()) {
            drop()
            return
        }
        status.setText(R.string.voice_hold_recognizing)
        hint.visibility = View.INVISIBLE
        wave.active = false
        // the models not coming in, or a stretch not coming back: not the keyboard covered for good
        main.postDelayed(::giveUp, FINISH_MS)
        listener?.finish {
            main.removeCallbacksAndMessages(null)
            val text = hold.text
            // still the field it was said for: not text landing somewhere else
            if (text.isNotEmpty() && service.currentInputEditorInfo === field && !service.inPasswordField) {
                commit(text)
                end()
            } else {
                status.setText(R.string.voice_hold_nothing)
                end(after = MESSAGE_MS)
            }
        }
    }

    /** dropped: nothing typed, whatever was heard */
    private fun drop() = end()

    private fun giveUp() {
        status.setText(R.string.voice_hold_nothing)
        end(after = MESSAGE_MS)
    }

    private fun end(after: Long = 0L) {
        if (over) return
        over = true
        if (inputView.hold === this) inputView.hold = null
        listener?.close(keepLast = false)
        listener = null
        main.postDelayed({
            card.animate().alpha(0f).setDuration(APPEAR_MS).withEndAction { overlay.removeView(card) }.start()
        }, after)
    }

    companion object {
        // slid up a quarter of the keyboard's height: no slip of the finger, and still in reach
        private const val CANCEL_SHARE = 0.25f
        private const val CANCEL_RED = 0xFFC62828.toInt()
        private const val CANCEL_TEXT = 0xFFFFFFFF.toInt()
        private const val WAVE_WIDTH = 140
        private const val WAVE_HEIGHT = 56
        private const val APPEAR_SCALE = 0.96f
        private const val APPEAR_MS = 150L
        private const val MESSAGE_MS = 1200L
        private const val FINISH_MS = 15_000L
    }
}
