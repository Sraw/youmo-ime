/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.voice.VoiceSession.Failure
import org.fcitx.fcitx5.android.input.voice.VoiceSession.State
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

/**
 * Voice input, in place of the keyboard, as Sogou's: a big microphone that listens as soon as the
 * panel opens, what is said typed at each pause. A tap on it pauses and resumes; deleting and a
 * new line beside it; the bar's arrow back to the keyboard. Recognized on the phone (VoiceEngine).
 */
class VoiceWindow : InputWindow.ExtendedInputWindow<VoiceWindow>(), InputBroadcastReceiver, VoiceListener.Events {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()

    private val session = VoiceSession()
    private var listener: VoiceListener? = null

    override val title: String by lazy { context.getString(R.string.voice_input) }

    private lateinit var status: TextView
    private lateinit var halo: View
    private lateinit var mic: ImageView

    override fun onCreateView(): View {
        val ctx = context
        status = TextView(ctx).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(theme.keyTextColor)
        }
        halo = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.accentKeyBackgroundColor)
            }
            alpha = HALO_ALPHA
        }
        mic = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_baseline_keyboard_voice_24)
            imageTintList = ColorStateList.valueOf(theme.accentKeyTextColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.accentKeyBackgroundColor)
            }
            setPadding(ctx.dp(22), ctx.dp(22), ctx.dp(22), ctx.dp(22))
            contentDescription = ctx.getString(R.string.voice_input)
            setOnClickListener { tapped() }
        }
        val micBox = FrameLayout(ctx).apply {
            addView(halo, FrameLayout.LayoutParams(ctx.dp(MIC), ctx.dp(MIC), Gravity.CENTER))
            addView(mic, FrameLayout.LayoutParams(ctx.dp(MIC), ctx.dp(MIC), Gravity.CENTER))
        }
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            addView(key(R.drawable.ic_baseline_backspace_24, R.string.backspace, FcitxKeyMapping.FcitxKey_BackSpace, repeat = true), side())
            addView(micBox, LinearLayout.LayoutParams(ctx.dp(MIC * 2), ctx.dp(MIC * 2)))
            addView(key(R.drawable.ic_baseline_keyboard_return_24, R.string.a11y_key_enter, FcitxKeyMapping.FcitxKey_Return, repeat = false), side())
        }
        val hint = TextView(ctx).apply {
            setText(R.string.voice_hint)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(theme.altKeyTextColor)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(12) })
        }
    }

    private fun side() = LinearLayout.LayoutParams(context.dp(KEY), context.dp(KEY)).apply {
        marginStart = context.dp(24)
        marginEnd = context.dp(24)
    }

    private fun key(icon: Int, description: Int, sym: Int, repeat: Boolean) = CustomGestureView(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = context.dp(8).toFloat()
            setColor(theme.keyBackgroundColor)
        }
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

    override fun onAttached() {
        if (!permitted()) session.failed(Failure.NoPermission)
        sync()
    }

    /** the field left for the keyboard in: what is recognized once it is left too is dropped */
    private var leftIn: EditorInfo? = null

    /** back to the keyboard, in the same field: what was said up to now is still typed */
    override fun onDetached() {
        // a new field gets a new EditorInfo: the one detached in, compared when the text comes
        leftIn = service.currentInputEditorInfo
        close(keepLast = true)
    }

    /**
     * The keyboard hidden, the field left, or the view thrown away (a rotation, a theme): the
     * microphone closes and nothing more is typed, until a tap. Whatever the state: the models
     * still loading, it must not open once they are in.
     */
    override fun onFinishInput() {
        session.pause()
        close(keepLast = false)
        show()
    }

    /** another field, or the same one restarted (a password field now, perhaps) */
    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) = onFinishInput()

    private fun close(keepLast: Boolean) {
        listener?.close(keepLast)
        listener = null
        session.unloaded()
    }

    /** The microphone and the models as [session] wants them: the one place either opens. */
    private fun sync() {
        if (service.inPasswordField) session.pause()
        if (!session.wantsModels) {
            listener?.stop()
        } else {
            val l = listener ?: VoiceListener(service.assets, this).also {
                listener = it
                it.load()
            }
            if (session.listening) l.start()
        }
        show()
    }

    private fun permitted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun tapped() {
        if (session.state == State.Failed) {
            if (session.failure == Failure.NoPermission && !permitted()) {
                VoicePermissionActivity.start(context)
                return
            }
            // granted meanwhile, in the settings; or the microphone, the models: another try
            session.reset()
        } else {
            session.tap()
        }
        sync()
    }

    override fun loaded() {
        session.loaded()
        sync()
    }

    override fun failed(why: Failure) {
        // the permission's failure stands: it is the one a tap mends
        if (session.failure != Failure.NoPermission) session.failed(why)
        close(keepLast = true)
        show()
    }

    override fun speechStarted() {
        session.speechStarted()
        show()
    }

    override fun speechEnded() {
        session.speechEnded()
        show()
    }

    override fun recognized(text: String) {
        val typed = session.recognized(text)
        // nothing said out loud goes into a password, nor into a field it was not said for
        val sameField = leftIn.let { it == null || it === service.currentInputEditorInfo }
        if (typed != null && sameField && !service.inPasswordField) {
            // as the emoji's: a pinyin being typed is committed first, not overwritten
            commonKeyActionListener.listener.onKeyAction(KeyAction.CommitAction(typed), KeyActionListener.Source.Keyboard)
        }
        show()
    }

    override fun level(level: Float) {
        val scale = if (session.listening) 1f + level * HALO_GROWTH else 1f
        halo.animate().scaleX(scale).scaleY(scale).setDuration(LEVEL_MS).start()
    }

    private fun show() {
        if (!::status.isInitialized) return
        status.setText(
            when (session.state) {
                State.Loading -> R.string.voice_loading
                State.Listening -> if (session.pending > 0) R.string.voice_recognizing else R.string.voice_listening
                State.Hearing -> R.string.voice_hearing
                State.Paused -> if (session.pending > 0) R.string.voice_recognizing else R.string.voice_paused
                State.Failed -> when (session.failure) {
                    Failure.NoPermission -> R.string.voice_no_permission
                    Failure.NoModel -> R.string.voice_no_model
                    else -> R.string.voice_no_microphone
                }
            }
        )
        mic.alpha = if (session.listening) 1f else IDLE_ALPHA
        if (!session.listening) halo.animate().scaleX(1f).scaleY(1f).setDuration(LEVEL_MS).start()
    }

    companion object {
        private const val MIC = 72
        private const val KEY = 56
        private const val HALO_ALPHA = 0.3f
        private const val HALO_GROWTH = 0.8f
        private const val IDLE_ALPHA = 0.5f
        private const val LEVEL_MS = 100L
    }
}
