/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.BaseInputView
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.voice.VoiceTap.Act
import org.fcitx.fcitx5.android.input.voice.VoiceTap.Press
import splitties.dimensions.dp

/**
 * Listening on with no finger held till the microphone or the mask is tapped. No Enter of its own (it
 * would not follow the theme): to send, stop it, then the keyboard's Enter. The mask is the keyboard
 * view's own child, so it moves and scales with it.
 */
class VoiceTapOverlay(
    private val inputView: InputView,
    commit: (String) -> Unit,
) : BaseInputView.Hold {
    private val theme = inputView.theme
    private val ctx = inputView.context
    private val look = VoiceStyle.panel(ctx, theme)
    private val service = inputView.service

    private val tap = VoiceTap()
    private val main = Handler(Looper.getMainLooper())
    private var gone = false

    private val ui: VoiceSession.Ui = object : VoiceSession.Ui {
        override fun ready() {
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
            VoiceCard.recognizing(status, hint, wave)
        }

        override fun ended(message: Int?) = this@VoiceTapOverlay.ended(message)

        override fun interrupted() = press(Press.INTERRUPTED)
    }

    private val session: VoiceSession = VoiceSession(service, inputView.fcitx, ui, asItGoes = true, commit)

    private val heard = VoiceCard.heard(ctx, theme).apply {
        // INVISIBLE, not GONE: its share of the height kept, the pill stays in the middle. Here only where the field cannot show it
        visibility = if (session.composes) View.INVISIBLE else View.VISIBLE
    }
    private val wave = VoiceCard.wave(ctx, look.micColor.value)
    private val pill = VoiceCard.pill(ctx, wave, look.micFill.value)
    private val status = VoiceCard.status(ctx, theme)
    private val hint = VoiceCard.hint(ctx, theme, R.string.voice_tap_hint)

    private val glass = VoiceStyle.glass(ctx, look)

    private val card: FrameLayout = FrameLayout(ctx).apply {
        background = glass
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = ctx.dp(12)
            setPadding(pad, pad, pad, pad)
            addView(heard, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(pill, VoiceCard.pillParams(ctx))
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
            // a little above the middle
            addView(View(ctx), LinearLayout.LayoutParams(-1, 0, 1f))
        }
        addView(column, FrameLayout.LayoutParams(-1, -1))
        // the keys under it get nothing
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

    fun begin() {
        // the bar stays, its microphone stops it
        inputView.overKeys(card, underBar = true)
        glass.under(card, inputView.keyboardView as ViewGroup)
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(VoiceStyle.ANIMATE_MS).start()
        inputView.hold = this
        VoiceStyle.vibrate(card)
        session.begin()
        quietFromNow()
    }

    fun micTapped() = press(Press.MIC)

    // a press off the mask stops it and then acts as usual; the microphone's own is its tap's
    override fun touched(ev: MotionEvent) {
        val action = ev.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return
        val x = ev.getRawX(ev.actionIndex)
        val y = ev.getRawY(ev.actionIndex)
        // where it is now: the talk may have started from a key's shortcut, the toolbar shown since
        if (card.contains(x, y) || inputView.voiceButton()?.contains(x, y) == true) return
        // the keyboard moved by its handle: the talk goes on
        if (inputView.handleShown()?.contains(x, y) == true) return
        press(Press.OUTSIDE)
    }

    override fun inputEnded(how: BaseInputView.Hold.InputEnd) = when (how) {
        // the field and its connection stay
        BaseInputView.Hold.InputEnd.HIDDEN -> press(Press.HIDDEN)
        // the same field restarted (a WebView, restartInput) or the keyboard shown again over it: talk on
        BaseInputView.Hold.InputEnd.STARTED -> if (!session.sameField) press(Press.INPUT_ENDED) else Unit
        // before its connection goes
        BaseInputView.Hold.InputEnd.LEFT -> press(Press.INPUT_ENDED)
    }

    // on the screen: moved and scaled with the keyboard (floating), which its width and height alone do not tell
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
            Act.STOP_NOW -> session.stopNow(SystemClock.uptimeMillis())
            Act.CUT -> session.cut()
            Act.HIDE -> session.hidden(SystemClock.uptimeMillis())
            Act.TYPE_NOW -> session.typeNow()
        }
    }

    private fun ended(message: Int?) {
        tap.ended()
        main.removeCallbacks(quiet)
        if (inputView.hold === this) inputView.hold = null
        if (gone) return
        gone = true
        // a press goes through to the keys under it rather than stopping a talk that is over
        VoiceCard.ended(card, status, hint, main, message)
    }

}
