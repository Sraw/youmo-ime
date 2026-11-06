/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.BaseInputView
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.voice.VoiceTap.Act
import org.fcitx.fcitx5.android.input.voice.VoiceTap.Press
import splitties.dimensions.dp

/**
 * Tap to talk, the bar's microphone (060-voice-tap-to-talk.md): a mask over the keys, the bar left
 * above it, the bars moving with the voice and what it is doing; listening on with no finger held
 * till the microphone or the mask is tapped, × cancels. The settled part of what is said is typed
 * as it is said, the tail composing in the field ([VoiceSession], [VoiceTalk]); what each press does
 * is [VoiceTap]'s. No Enter of its own (the user's decision 2026-11-06: it would not follow the
 * theme): to send, stop it, then the keyboard's Enter. The mask is the keyboard view's own child,
 * so it moves and scales with it (floating, one-handed).
 */
class VoiceTapOverlay(
    private val inputView: InputView,
    commit: (String) -> Unit,
) : BaseInputView.Hold {
    private val theme = inputView.theme
    private val ctx = inputView.context
    private val service = inputView.service

    private val tap = VoiceTap()
    private val main = Handler(Looper.getMainLooper())
    private var gone = false

    // what the session tells: the mask's status, the bars, the quiet stop put off
    private val ui: VoiceSession.Ui = object : VoiceSession.Ui {
        override fun ready() {
            // the cue to start talking: felt as well as seen
            VoiceStyle.vibrate(card, cue = true)
            status.setText(R.string.voice_hold_listening)
        }

        override fun silent() = status.setText(R.string.voice_hold_silent)

        override fun heard(text: String) {
            heard.text = text
            quietFromNow()
        }

        override fun level(level: Float) = wave.level(level)

        override fun recognizing() {
            main.removeCallbacks(quiet)
            status.setText(R.string.voice_hold_recognizing)
            hint.visibility = View.INVISIBLE
            wave.active = false
        }

        override fun ended(message: Int?) = this@VoiceTapOverlay.ended(message)
    }

    private val session: VoiceSession = VoiceSession(service, inputView.fcitx, ui, asItGoes = true, commit)

    private val heard = TextView(ctx).apply {
        textSize = 18f
        gravity = Gravity.CENTER or Gravity.BOTTOM
        VoiceStyle.heard(this)
        setTextColor(theme.keyTextColor)
        // what is said shows in the field as it is said; here only where the field cannot show it
        visibility = if (session.composes) View.GONE else View.VISIBLE
    }
    private val wave = VoiceWaveView(ctx).apply {
        color = theme.accentKeyBackgroundColor
        active = true
    }
    private val pill = FrameLayout(ctx).apply {
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(PILL_HEIGHT / 2).toFloat()
            setColor(ColorUtils.setAlphaComponent(theme.accentKeyBackgroundColor, PILL_ALPHA))
        }
        addView(wave, FrameLayout.LayoutParams(ctx.dp(WAVE_WIDTH), ctx.dp(WAVE_HEIGHT), Gravity.CENTER))
    }
    private val status = TextView(ctx).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
        // 正在听 once the microphone is really on (ready), not as it is tapped
        setText(R.string.voice_hold_preparing)
    }
    private val hint = TextView(ctx).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(theme.altKeyTextColor)
        setText(R.string.voice_tap_hint)
    }

    private fun button(icon: Int, description: CharSequence, pressed: () -> Unit) = ImageView(ctx).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(theme.keyTextColor, BUTTON_FILL))
        }
        setOnClickListener { pressed() }
    }

    private val cancel = button(R.drawable.ic_baseline_close_24, ctx.getString(R.string.voice_tap_cancel)) { press(Press.CANCEL) }

    private val card: FrameLayout = FrameLayout(ctx).apply {
        setBackgroundColor(theme.keyboardColor)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = ctx.dp(12)
            setPadding(pad, pad, pad, pad)
            addView(heard, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(pill, LinearLayout.LayoutParams(ctx.dp(PILL_WIDTH), ctx.dp(PILL_HEIGHT)).apply { topMargin = ctx.dp(8) })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
            // the pill a little above the middle, not pushed to the bottom
            addView(View(ctx), LinearLayout.LayoutParams(-1, 0, 1f))
        }
        addView(column, FrameLayout.LayoutParams(-1, -1))
        val size = ctx.dp(BUTTON)
        val margin = ctx.dp(BUTTON_MARGIN)
        addView(cancel, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply { setMargins(margin, margin, margin, margin) })
        // anywhere on it stops; the keys under it get nothing
        setOnClickListener { press(Press.MASK) }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            // the keyboard's view thrown away under it (a theme changed, rotated): as the field left
            override fun onViewDetachedFromWindow(v: View) = press(Press.INPUT_ENDED)
        })
    }

    private val quiet = Runnable { press(Press.QUIET) }

    private fun quietFromNow() {
        main.removeCallbacks(quiet)
        if (tap.state == VoiceTap.State.LISTENING) main.postDelayed(quiet, VoiceTap.QUIET_MS)
    }

    /** Tapped: the microphone opens now, the models load meanwhile. */
    fun begin() {
        // under the bar (and the code row above it): the bar stays, its microphone stops it
        inputView.overKeys(card, underBar = true)
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(VoiceStyle.ANIMATE_MS).start()
        inputView.hold = this
        VoiceStyle.vibrate(card)
        session.begin()
        quietFromNow()
    }

    /** The bar's microphone tapped again. */
    fun micTapped() = press(Press.MIC)

    // a press off the mask stops it and then acts as usual (the bar's other buttons, the
    // candidates); the microphone's own is its tap's, the mask's its click's
    override fun touched(ev: MotionEvent) {
        val action = ev.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return
        val x = ev.getRawX(ev.actionIndex)
        val y = ev.getRawY(ev.actionIndex)
        // the microphone where it is now: the talk may have started from a key's shortcut, the
        // toolbar shown since
        if (card.contains(x, y) || inputView.voiceButton()?.contains(x, y) == true) return
        // the keyboard moved by its handle: not a press off the mask, the talk goes on
        if (inputView.handleShown()?.contains(x, y) == true) return
        press(Press.OUTSIDE)
    }

    override fun inputEnded(how: BaseInputView.Hold.InputEnd) = when (how) {
        // what is left typed straight in once recognized: the field and its connection stay
        BaseInputView.Hold.InputEnd.HIDDEN -> press(Press.HIDDEN)
        // the same field restarted (a WebView, the app's restartInput), or the keyboard shown again
        // over it: talk on; what composed is taken away or was the app's (EditingSession.provisionalRestarted)
        BaseInputView.Hold.InputEnd.STARTED -> if (!session.sameField) press(Press.INPUT_ENDED) else Unit
        // what was heard typed into the field it was said in, before its connection goes
        BaseInputView.Hold.InputEnd.LEFT -> press(Press.INPUT_ENDED)
    }

    // where it is seen, on the screen (as the raw x and y): moved and scaled with the keyboard
    // (floating), which its width and height alone do not tell
    private fun View.contains(x: Float, y: Float): Boolean {
        val seen = Rect()
        if (!isShown || !getGlobalVisibleRect(seen)) return false
        val window = IntArray(2).also { rootView.getLocationOnScreen(it) }
        seen.offset(window[0], window[1])
        return seen.contains(x.toInt(), y.toInt())
    }

    private fun press(press: Press) {
        when (tap.press(press)) {
            Act.NONE -> {}
            Act.STOP -> session.finish(SystemClock.uptimeMillis())
            Act.CUT -> session.cut()
            Act.HIDE -> session.hidden(SystemClock.uptimeMillis())
            Act.DROP -> session.drop()
            Act.TYPE_NOW -> session.typeNow()
        }
    }

    private fun ended(message: Int?) {
        tap.ended()
        main.removeCallbacks(quiet)
        if (inputView.hold === this) inputView.hold = null
        if (gone) return
        gone = true
        // still shown a moment (the message, the fade), the keys under it not yet back: a press
        // goes through to them rather than stopping a talk that is over
        card.isClickable = false
        cancel.isEnabled = false
        message?.let {
            status.setText(it)
            hint.visibility = View.INVISIBLE
        }
        main.postDelayed({
            card.animate().alpha(0f).setDuration(VoiceStyle.ANIMATE_MS).withEndAction { (card.parent as? ViewGroup)?.removeView(card) }.start()
        }, if (message != null) VoiceSession.MESSAGE_MS else 0L)
    }

    companion object {
        private const val PILL_ALPHA = 0x29
        private const val PILL_WIDTH = 168
        private const val PILL_HEIGHT = 52
        private const val WAVE_WIDTH = 104
        private const val WAVE_HEIGHT = 32
        private const val BUTTON = 44
        private const val BUTTON_MARGIN = 8
        private const val BUTTON_FILL = 0x14
    }
}
