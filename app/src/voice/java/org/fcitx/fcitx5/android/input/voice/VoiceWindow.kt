/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

/**
 * Voice input, in place of the keyboard, as WeChat's and Sogou's: a big microphone to hold while
 * talking, what was said typed when it is let go, dropped if the finger slides up first
 * ([VoiceHoldSession], as the space key's hold). Rings go out from it with the voice, and what
 * is heard shows above. Deleting and a new line beside it; the
 * bar's arrow back to the keyboard. Recognized on the phone (VoiceEngine).
 */
class VoiceWindow : InputWindow.ExtendedInputWindow<VoiceWindow>(), InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val fcitx by manager.fcitx()

    override val title: String by lazy { context.getString(R.string.voice_input) }

    private val main = Handler(Looper.getMainLooper())
    private var session: VoiceHoldSession? = null
    private var downY = 0f

    // this touch started the session: its moves, its lift and its cancel are the session's
    private var holding = false

    private lateinit var heard: TextView
    private lateinit var pulse: VoicePulseView
    private lateinit var mic: ImageView
    private lateinit var micBackground: GradientDrawable
    private lateinit var label: TextView

    override fun onCreateView(): View {
        val ctx = context
        heard = TextView(ctx).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            maxLines = 3
            // the end of a long one is what was just said
            ellipsize = TextUtils.TruncateAt.START
        }
        pulse = VoicePulseView(ctx).apply {
            color = theme.accentKeyBackgroundColor
            inner = ctx.dp(MIC / 2).toFloat()
        }
        micBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.accentKeyBackgroundColor)
        }
        mic = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_baseline_keyboard_voice_24)
            imageTintList = ColorStateList.valueOf(theme.accentKeyTextColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = micBackground
            val pad = ctx.dp(MIC_PADDING)
            setPadding(pad, pad, pad, pad)
            elevation = ctx.dp(3).toFloat()
            contentDescription = ctx.getString(R.string.voice_press_to_talk)
            setOnTouchListener(::touched)
        }
        val stage = FrameLayout(ctx).apply {
            clipChildren = false
            addView(pulse, FrameLayout.LayoutParams(-1, -1))
            addView(mic, FrameLayout.LayoutParams(ctx.dp(MIC), ctx.dp(MIC), Gravity.CENTER))
        }
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            clipChildren = false
            addView(key(R.drawable.ic_baseline_backspace_24, R.string.backspace, FcitxKeyMapping.FcitxKey_BackSpace, repeat = true), side())
            addView(stage, LinearLayout.LayoutParams(ctx.dp(STAGE), ctx.dp(STAGE)))
            addView(key(R.drawable.ic_baseline_keyboard_return_24, R.string.a11y_key_enter, FcitxKeyMapping.FcitxKey_Return, repeat = false), side())
        }
        label = TextView(ctx).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(theme.keyTextColor)
        }
        val hint = TextView(ctx).apply {
            setText(R.string.voice_hint)
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(theme.altKeyTextColor)
        }
        idle()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            val pad = ctx.dp(16)
            setPadding(pad, ctx.dp(8), pad, ctx.dp(10))
            addView(heard, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(row, LinearLayout.LayoutParams(-1, -2))
            addView(label, LinearLayout.LayoutParams(-1, -2))
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(6) })
        }
    }

    private fun side() = LinearLayout.LayoutParams(context.dp(KEY), context.dp(KEY)).apply {
        marginStart = context.dp(12)
        marginEnd = context.dp(12)
    }

    private fun key(icon: Int, description: Int, sym: Int, repeat: Boolean) = CustomGestureView(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.keyBackgroundColor)
        }
        elevation = context.dp(1).toFloat()
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(theme.keyTextColor)
            scaleType = ImageView.ScaleType.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        contentDescription = context.getString(description)
        val press = { commonKeyActionListener.listener.onKeyAction(KeyAction.SymAction(KeySym(sym)), KeyActionListener.Source.Keyboard) }
        setOnClickListener { press() }
        if (repeat) {
            repeatEnabled = true
            onRepeatListener = { press() }
        }
    }

    // the microphone's own touch: the whole of a hold, down to up, slides included
    @SuppressLint("ClickableViewAccessibility")
    private fun touched(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                holding = false
                press(v)
            }
            // a press while the last is recognized was not one: its cancel drops nothing
            MotionEvent.ACTION_MOVE -> if (holding) session?.moved(ev.rawY - downY)
            MotionEvent.ACTION_UP -> if (holding) session?.lift()
            MotionEvent.ACTION_CANCEL -> if (holding) session?.drop()
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) holding = false
        return true
    }

    private fun press(v: View) {
        // the last still being recognized: it is typed first, then another
        if (session?.over == false) return
        main.removeCallbacksAndMessages(null)
        when {
            !permitted() -> {
                VoicePermissionActivity.start(context)
                say(R.string.voice_no_permission)
            }
            service.inPasswordField -> say(R.string.voice_no_password)
            else -> {
                v.parent.requestDisallowInterceptTouchEvent(true)
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                session = VoiceHoldSession(service, fcitx, context.dp(CANCEL).toFloat(), ui) { text ->
                    // as the emoji's: a pinyin being typed is committed first, not overwritten
                    commonKeyActionListener.listener.onKeyAction(KeyAction.CommitAction(text), KeyActionListener.Source.Keyboard)
                }.also { it.begin() }
                holding = true
                talking()
            }
        }
    }

    private fun permitted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The field left, the keyboard hidden: what is being said is dropped. */
    private fun drop() {
        session?.drop()
        session = null
    }

    override fun onAttached() {
        main.removeCallbacksAndMessages(null)
        idle()
    }

    /**
     * Back to the keyboard: still talking, dropped; let go, what was said is still typed (the
     * same field, it checks), as it would have been a moment later.
     */
    override fun onDetached() {
        if (session?.released != true) drop()
    }

    override fun onFinishInput() = drop()

    /** another field, or the same one restarted (a password field now, perhaps) */
    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) = drop()

    // the ui the session tells, apart: the dependency manager reads a window's supertypes by
    // reflection, and R8 merges an interface away that only one class implements (a release
    // build crashed on it)
    private val ui = object : VoiceHoldSession.Ui {
        override fun heard(text: String) {
            heard.text = text
            heard.setTextColor(theme.keyTextColor)
        }

        override fun level(level: Float) {
            pulse.level(level)
        }

        override fun zone(cancel: Boolean) {
            mic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val color = if (cancel) CANCEL_RED else theme.accentKeyBackgroundColor
            micBackground.setColor(color)
            pulse.color = color
            label.setText(if (cancel) R.string.voice_release_to_cancel else R.string.voice_release_to_type)
            label.setTextColor(if (cancel) CANCEL_RED else theme.keyTextColor)
        }

        override fun recognizing() {
            settle()
            label.setText(R.string.voice_hold_recognizing)
        }

        override fun ended(message: Int?) {
            settle()
            label.setText(R.string.voice_press_to_talk)
            if (message == null) {
                idle()
            } else {
                say(message)
                main.postDelayed(::idle, VoiceHoldSession.MESSAGE_MS)
            }
        }
    }

    private fun talking() {
        say(R.string.voice_hold_listening)
        label.setText(R.string.voice_release_to_type)
        mic.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE).setDuration(ANIMATE_MS).start()
        pulse.active = true
    }

    // the microphone let go: back to its size and colour, the rings out
    private fun settle() {
        mic.animate().scaleX(1f).scaleY(1f).setDuration(ANIMATE_MS).start()
        pulse.active = false
        micBackground.setColor(theme.accentKeyBackgroundColor)
        pulse.color = theme.accentKeyBackgroundColor
        label.setTextColor(theme.keyTextColor)
    }

    private fun idle() {
        heard.setText(R.string.voice_panel_prompt)
        heard.setTextColor(theme.altKeyTextColor)
        label.setText(R.string.voice_press_to_talk)
    }

    private fun say(message: Int) {
        heard.setText(message)
        heard.setTextColor(theme.altKeyTextColor)
    }

    companion object {
        private const val MIC = 76
        private const val MIC_PADDING = 22
        private const val STAGE = 136
        private const val KEY = 52
        private const val CANCEL = 96
        private const val PRESSED_SCALE = 1.1f
        private const val ANIMATE_MS = 150L
        private const val CANCEL_RED = 0xFFC62828.toInt()
    }
}
