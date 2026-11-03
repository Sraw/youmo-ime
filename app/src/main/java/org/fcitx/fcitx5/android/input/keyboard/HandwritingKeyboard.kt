/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPadView
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.handwriting.InkPad
import org.fcitx.fcitx5.android.input.handwriting.ReadingToggle
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.core.add
import splitties.views.existingOrNewId

/**
 * Handwriting: a pad over most of the keyboard, written on a character at a time; backspace (taking
 * the pad's last stroke back while it has one), ，(its long press more marks), 。 and 注 (候选词注音 off or
 * on, [ReadingToggle]; its long press each style, handed to [setReading]) on the right;
 * symbols, numbers, space, the language switch and return below. What the pad reads is handed to
 * [deliver] with the piece it is of; the engine shows it as candidates. The pad wipes the piece it
 * is writing once the engine ends it (EngineState.handwritingEnded), and ends it itself when
 * hidden.
 */
@SuppressLint("ViewConstructor")
class HandwritingKeyboard(
    context: Context,
    theme: Theme,
    private val deliver: (InkReading, piece: Int) -> Unit,
    /** The pad's piece finished (the pen up long enough), told after its readings are delivered. */
    private val finished: (piece: Int) -> Unit = {},
    private val recognition: HandwritingRecognition = HandwritingRecognition.shared,
    /** 候选词注音 set from the 注 key, after what the pad wrote is in. */
    private val setReading: (ReadingStyle) -> Unit = {},
) : BaseKeyboard(context, theme, layout(context)) {

    companion object {
        const val Name = "Handwriting"

        // the pad's share of the width
        private const val PAD = 0.85f
        private const val SIDE = 1f - PAD

        private val MORE = arrayOf("？", "！", "：", "；", "、", "…", "“", "”")

        private fun spacer() = KeyDef(
            KeyDef.Appearance.Text("", 16f, percentWidth = PAD, border = KeyDef.Appearance.Border.Off, margin = false),
            emptySet(),
        )

        private fun mark(label: String, key: String, more: Array<String>? = null) = KeyDef(
            KeyDef.Appearance.Text(label, 20f, percentWidth = SIDE, variant = KeyDef.Appearance.Variant.Alternative),
            setOf(KeyDef.Behavior.Press(KeyAction.HostKeyAction(key))),
            more?.let { arrayOf(KeyDef.Popup.Keyboard.Explicit(it)) },
        )

        // 注: its long press's choices worded as the settings word them
        private fun readingKey(context: Context) = KeyDef(
            KeyDef.Appearance.Text("注", 20f, percentWidth = SIDE, variant = KeyDef.Appearance.Variant.Alternative, viewId = R.id.button_reading),
            setOf(KeyDef.Behavior.Press(KeyAction.ReadingAction())),
            arrayOf(
                KeyDef.Popup.Menu(
                    ReadingToggle.CHOICES.map { KeyDef.Popup.Menu.Item(context.getString(READING_NAMES.getValue(it)), 0, KeyAction.ReadingAction(it)) }.toTypedArray(),
                ),
            ),
        )

        private val READING_NAMES = mapOf(
            ReadingStyle.OFF to R.string.im_candidate_reading_off,
            ReadingStyle.PINYIN to R.string.im_candidate_reading_pinyin,
            ReadingStyle.ZHUYIN to R.string.im_candidate_reading_zhuyin,
        )

        // the pad's rows: those the keys on its right make
        private const val PAD_ROWS = 4

        fun layout(context: Context): List<List<KeyDef>> = listOf(
            listOf(spacer(), BackspaceKey(SIDE)),
            listOf(spacer(), mark("，", ",", MORE)),
            listOf(spacer(), mark("。", ".")),
            listOf(spacer(), readingKey(context)),
            listOf(
                LayoutSwitchKey("符", PickerWindow.Key.Symbol.name, SIDE),
                LayoutSwitchKey("123", NumberKeyboard.Name, 0.14f),
                SpaceKey(),
                LanguageKey(),
                ReturnKey(SIDE),
            ),
        )
    }

    private val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    private val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }
    private val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }
    private val readingKey: TextKeyView by lazy { findViewById(R.id.button_reading) }
    private val lastReading: String by AppPrefs.getInstance().internal.lastCandidateReading

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        lang.isVisible = v
    }

    private val padView = HandwritingPadView(
        context, theme,
        onRead = ::read,
        pause = { AppPrefs.getInstance().keyboard.handwritingPause.getValue().millis },
        onFinish = ::finish,
    ).apply {
        val prefs = ThemeManager.prefs
        val h = dp(prefs.keyHorizontalMargin.getValue())
        val v = dp(prefs.keyVerticalMargin.getValue())
        background = insetRadiusDrawable(h, v, dp(prefs.keyRadius.getValue().toFloat()), theme.keyBackgroundColor)
    }

    init {
        add(padView, lParams {
            topToTop = keyRows[0].existingOrNewId
            bottomToBottom = keyRows[PAD_ROWS - 1].existingOrNewId
            leftOfParent()
            width = 0
            height = 0
            matchConstraintPercentWidth = PAD
        })
        // under the pad, out of reach: TalkBack would stop on them
        keyRows.take(PAD_ROWS).forEach { row ->
            row.getChildAt(0).apply {
                isEnabled = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        lang.isVisible = showLangSwitchKey.getValue()
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    private var scope: CoroutineScope? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            s.launch { EngineState.handwritingEnded.collect(padView::ended) }
            s.launch { EngineState.candidateReading.collect(::showReading) }
        }
        recognition.warmUp()
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        // hidden, or the numbers shown: what was written is done, a key typed there commits it, so
        // finished as a pause would have (weighed again first); hidden, KeyboardWindow drops it
        if (padView.pad.finished.isNotEmpty()) finish(padView.pad.piece)
        padView.next()
        super.onDetachedFromWindow()
    }

    // the first piece of the field's input: those before were another input's
    private var since = padView.pad.piece

    /** Another input started (a field, or the keyboard shown again): the pad starts afresh, what it was reading dropped. */
    fun restart() {
        padView.next()
        since = padView.pad.piece
    }

    private fun read(read: InkPad.Read) {
        // delivered while the numbers or symbols are shown too: a key typed there commits it
        recognition.read(read) { reading ->
            if (read.piece >= since) deliver(reading, read.piece)
        }
    }

    // after the piece's readings, as the engine must have them first
    private fun finish(piece: Int) {
        recognition.afterReads { if (piece >= since) finished(piece) }
    }

    private val padHit = Rect()

    // the vivo workaround takes every touch for a key: the pad's are the pad's
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        padView.getHitRect(padHit)
        if (padHit.contains(ev.x.toInt(), ev.y.toInt())) return false
        return super.onInterceptTouchEvent(ev)
    }

    // public for its tests: a long press's pick (注's styles) comes here as a tap does
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    public override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        // pressed while a stroke is read: in after its reading, as written before it; dropped if
        // another input started meanwhile (restart), it was not typed for that one
        if (recognition.busy) {
            val at = since
            recognition.afterReads { if (isAttachedToWindow && at == since) act(action, source) }
        } else {
            act(action, source)
        }
    }

    // 注 as 候选词注音 is: the accent's colour on, TalkBack told which (the style last used kept by FcitxInputMethodService)
    private fun showReading(style: ReadingStyle) {
        val on = style != ReadingStyle.OFF
        readingKey.mainText.setTextColor(if (on) theme.accentKeyBackgroundColor else theme.altKeyTextColor)
        val name = context.getString(R.string.im_candidate_reading)
        readingKey.contentDescription = context.getString(if (on) R.string.hw_reading_key_on else R.string.hw_reading_key_off, name)
    }

    // the piece on the pad finished first, as a pause does: in the engine's sentence, not committed, then the setting
    private fun toggleReading(action: KeyAction.ReadingAction) {
        if (padView.pad.finished.isNotEmpty()) {
            finish(padView.pad.piece)
            padView.next()
        }
        val style = action.style ?: ReadingToggle.tapped(EngineState.candidateReading.value, lastReading)
        recognition.afterReads { if (isAttachedToWindow) setReading(style) }
    }

    private fun act(action: KeyAction, source: KeyActionListener.Source) {
        if (action is KeyAction.ReadingAction) return toggleReading(action)
        // backspace takes the pad's last stroke back while it has one; else it is the engine's. Decided
        // in its turn: behind a held key, the strokes it would take back are of the piece that key commits
        if (action is KeyAction.SymAction && action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace && padView.undo()) return
        // a key for the engine commits the piece: its strokes wiped now, not once the engine's end of it
        // comes back, else a backspace right behind (held with it) takes back a stroke of what is committed
        if ((action is KeyAction.SymAction || action is KeyAction.HostKeyAction) && padView.pad.finished.isNotEmpty()) padView.next()
        super.onAction(action, source)
    }

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        `return`.showReturnAction(appearance)
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        showInputMethodName(space, ime)
    }
}
