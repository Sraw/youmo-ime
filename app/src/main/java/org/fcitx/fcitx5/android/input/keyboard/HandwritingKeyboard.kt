/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keys.KeyState as KeysState
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPadView
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.handwriting.InkKeys
import org.fcitx.fcitx5.android.input.handwriting.ReadingToggle
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.set
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add

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
) : BaseKeyboard(context, theme, layout(context), themeBoard = KeyNames.HANDWRITING, shortcuts = true) {

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
            name = if (key == ",") "comma" else "period",
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
            name = "reading",
        )

        private val READING_ON = mapOf("reading" to setOf(KeyState.On))

        private val READING_NAMES = mapOf(
            ReadingStyle.OFF to R.string.im_candidate_reading_off,
            ReadingStyle.PINYIN to R.string.im_candidate_reading_pinyin,
            ReadingStyle.ZHUYIN to R.string.im_candidate_reading_zhuyin,
        )

        // the pad's rows: those the keys on its right make
        private const val PAD_ROWS = 4

        /** The layout its keys are drawn from (lib/keyboard-scene's handwriting.xml), the pad its slot [PAD_SLOT]. */
        internal const val LAYOUT = KeyNames.HANDWRITING
        internal const val PAD_SLOT = "pad"

        // 注's long press as handwriting.xml names its items: the settings' words
        private val MENU_LABELS = mapOf(
            "reading_off" to R.string.im_candidate_reading_off,
            "reading_pinyin" to R.string.im_candidate_reading_pinyin,
            "reading_zhuyin" to R.string.im_candidate_reading_zhuyin,
        )

        fun layout(context: Context): List<List<KeyDef>> = listOf(
            listOf(spacer(), BackspaceKey(SIDE)),
            listOf(spacer(), mark("，", ",", MORE)),
            listOf(spacer(), mark("。", ".")),
            listOf(spacer(), readingKey(context)),
            listOf(
                LayoutSwitchKey("符", PickerWindow.Key.Symbol.name, SIDE, longPress = KeyAction.QuickPhraseAction),
                LayoutSwitchKey("123", NumberKeyboard.Name, 0.14f),
                SpaceKey(),
                LanguageKey(),
                ReturnKey(SIDE),
            ),
        )
    }

    private val lastReading: String by AppPrefs.getInstance().internal.lastCandidateReading

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        canvas?.invalidateScene()
    }

    private val padView: HandwritingPadView = HandwritingPadView(
        context, theme,
        onRead = { keys.read(it) },
        pause = { AppPrefs.getInstance().keyboard.handwritingPause.getValue().millis },
        onFinish = { keys.finish(it) },
    )

    // the pad's readings and the keys beside it, in order
    private val keys: InkKeys = InkKeys(recognition, padView, deliver, finished)

    private val padSlot = Rect()

    // in onLayout, which reads the scene (made there if it must be): placed already, nothing to ask
    private var placing = false

    // the scene made anew as it is drawn (Return, a key shown, 注; or none, failed): the pad placed by it
    // again, shown or hidden with the keys, not left as the last layout had it
    override fun onSceneBuilt(scene: Scene?) {
        if (placing) return
        // asked only when the slot moved, appeared or went: a scene made again for Shift or Return alone lays
        // nothing out
        val slot = scene?.slots?.firstOrNull { it.id == PAD_SLOT }
        val c = canvas ?: return
        if (slot == null) {
            if (padView.isVisible) requestLayout()
            return
        }
        builtSlot.set(slot.bounds)
        builtSlot.offset(c.left, c.top)
        if (!padView.isVisible || builtSlot != padSlot) requestLayout()
    }

    private val builtSlot = Rect()

    // the pad where the scene has its slot
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val c = canvas ?: return
        placing = true
        val placed = try {
            c.slot(PAD_SLOT, padSlot)
        } finally {
            placing = false
        }
        // no scene (none even of the built-in theme, 060-render-p2 U8): nothing of the keyboard shown, the pad neither
        padView.isInvisible = !placed
        if (!placed) return
        padSlot.offset(c.left, c.top)
        padView.layout(padSlot.left, padSlot.top, padSlot.right, padSlot.bottom)
        // its strokes as the theme has them (the slot's ink; its grid the canvas draws under the pad)
        c.slotInk(PAD_SLOT)?.let { padView.ink(it.color.value, it.width.toFloat()) }
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
        if (padView.pad.finished.isNotEmpty()) keys.finish(padView.pad.piece)
        padView.next()
        super.onDetachedFromWindow()
    }

    /** Another input started (a field, or the keyboard shown again): the pad starts afresh, what it was reading dropped. */
    fun restart() {
        keys.restart()
    }

    private val padHit = Rect()

    // where each finger down went: the pad's ids, the keys' ids, in order
    private val padFingers = mutableListOf<Int>()
    private val keyFingers = mutableListOf<Int>()
    // each side's gesture began at its own first finger's down
    private var padDown = 0L
    private var keyDown = 0L

    /**
     * Each finger to what it came down on: on the pad the pad's, else the keys' (the scene's routing):
     * a stroke written while ⌫ is held, ⌫ tapped while a stroke is being written.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        dropLostFingers(ev)
        var taken = false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> taken = fingerDown(ev)
            MotionEvent.ACTION_MOVE -> {
                if (padFingers.isNotEmpty()) taken = send(ev, padFingers, true, MotionEvent.ACTION_MOVE, -1)
                if (keyFingers.isNotEmpty()) taken = send(ev, keyFingers, false, MotionEvent.ACTION_MOVE, -1) || taken
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                val onPad = id in padFingers
                val from = if (onPad) padFingers else keyFingers
                if (id in from) {
                    taken = send(ev, from, onPad, if (from.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, id)
                    from -= id
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (padFingers.isNotEmpty()) taken = send(ev, padFingers, true, MotionEvent.ACTION_CANCEL, -1)
                if (keyFingers.isNotEmpty()) taken = send(ev, keyFingers, false, MotionEvent.ACTION_CANCEL, -1) || taken
                padFingers.clear()
                keyFingers.clear()
            }
        }
        return taken
    }

    // a finger down: to the pad or the keys, by where it lands; whether the side took it
    private fun fingerDown(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // a side still holding a finger is a stream cut short whose id this one reuses (always 0),
            // which dropLostFingers cannot see: cancelled before the new gesture, as no UP will come
            if (padFingers.isNotEmpty()) cancelEmpty(true, ev)
            if (keyFingers.isNotEmpty()) cancelEmpty(false, ev)
            padFingers.clear()
            keyFingers.clear()
        }
        val i = ev.actionIndex
        val id = ev.getPointerId(i)
        padView.getHitRect(padHit)
        val onPad = padView.isVisible && padHit.contains(ev.getX(i).toInt(), ev.getY(i).toInt())
        val to = if (onPad) padFingers else keyFingers
        if (to.isEmpty()) sideDown(onPad, ev.eventTime)
        to += id
        val taken = send(ev, to, onPad, if (to.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, id)
        // the gesture kept while the pad is shown, its first finger on no key: a finger after it on the
        // pad comes here only if the stream does
        return taken || (ev.actionMasked == MotionEvent.ACTION_DOWN && padView.isVisible)
    }

    private fun sideDown(onPad: Boolean, at: Long) {
        if (onPad) padDown = at else keyDown = at
    }

    private fun dropLostFingers(ev: MotionEvent) {
        // a finger the event no longer has (a stream cut short): its side's gesture cancelled, with the
        // fingers it had (those still here), and the side starts over
        for ((side, toPad) in listOf(padFingers to true, keyFingers to false)) {
            if (side.all { ev.findPointerIndex(it) >= 0 }) continue
            side.retainAll { ev.findPointerIndex(it) >= 0 }
            if (side.isNotEmpty()) send(ev, side, toPad, MotionEvent.ACTION_CANCEL, -1)
            else cancelEmpty(toPad, ev)
            side.clear()
        }
    }

    // a side whose fingers have all gone from the stream: a bare CANCEL at where [ev] is
    private fun cancelEmpty(toPad: Boolean, ev: MotionEvent) {
        val e = MotionEvent.obtain(if (toPad) padDown else keyDown, ev.eventTime, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        if (toPad) padView.dispatchTouchEvent(e) else super.dispatchTouchEvent(e)
        e.recycle()
    }

    // [ev] with only the fingers [ids] (in their order), its batched samples too (a stroke's points between
    // frames), [action] on finger [id]'s index among them: to the pad in its own pixels, or to the keys (the
    // keyboard's own dispatch, the scene's routing). Whether the side took it
    private fun send(ev: MotionEvent, ids: List<Int>, toPad: Boolean, action: Int, id: Int): Boolean {
        val at = ids.map(ev::findPointerIndex)
        val props = Array(ids.size) { MotionEvent.PointerProperties().also { p -> ev.getPointerProperties(at[it], p) } }
        val dx = if (toPad) -padView.left.toFloat() else 0f
        val dy = if (toPad) -padView.top.toFloat() else 0f
        // the coordinates of each finger at sample [h] (its history's, or the current one at historySize)
        fun coords(h: Int) = Array(ids.size) { n ->
            MotionEvent.PointerCoords().also { c ->
                if (h < ev.historySize) ev.getHistoricalPointerCoords(at[n], h, c) else ev.getPointerCoords(at[n], c)
                c.x += dx
                c.y += dy
            }
        }
        val time = { h: Int -> if (h < ev.historySize) ev.getHistoricalEventTime(h) else ev.eventTime }
        val e = MotionEvent.obtain(
            if (toPad) padDown else keyDown, time(0), action or (ids.indexOf(id).coerceAtLeast(0) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            ids.size, props, coords(0), ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision, ev.deviceId, ev.edgeFlags,
            ev.source, ev.flags,
        )
        for (h in 1..ev.historySize) e.addBatch(time(h), coords(h), ev.metaState)
        val taken = if (toPad) padView.dispatchTouchEvent(e) else super.dispatchTouchEvent(e)
        e.recycle()
        return taken
    }

    // public for its tests: a long press's pick (注's styles) comes here as a tap does. super's is
    // called, by act: later, once a stroke being read is in, or never (the pad's own keys, an undo,
    // input restarted meanwhile), which lint cannot see
    @SuppressLint("MissingSuperCall")
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    public override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        // pressed while a stroke is read: in after its reading, as written before it; dropped if
        // another input started meanwhile (restart), it was not typed for that one
        keys.onAction({ isAttachedToWindow }) { act(action, source) }
    }

    // 注 as 候选词注音 is: the accent's colour on, TalkBack told which (the style last used kept by FcitxInputMethodService)
    private fun showReading(style: ReadingStyle) {
        val on = style != ReadingStyle.OFF
        readingOn = on
        canvas?.invalidateScene()
    }

    // the piece on the pad finished first, as a pause does: in the engine's sentence, not committed, then the setting
    private fun toggleReading(action: KeyAction.ReadingAction) {
        if (padView.pad.finished.isNotEmpty()) {
            keys.finish(padView.pad.piece)
            padView.next()
        }
        val style = action.style ?: ReadingToggle.tapped(EngineState.candidateReading.value, lastReading)
        recognition.afterReads { if (isAttachedToWindow) setReading(style) }
    }

    private fun act(action: KeyAction, source: KeyActionListener.Source) {
        if (action is KeyAction.ReadingAction) return toggleReading(action)
        // backspace takes the pad's last stroke back while it has one; else it is the engine's (InkKeys.took)
        if (keys.took(action)) return
        super.onAction(action, source)
    }

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        returnAppearance = appearance
        canvas?.invalidateScene()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        showInputMethodName(ime)
        canvas?.invalidateScene()
    }

    // ，and 。 as punctuation maps them: the views showed them full width whatever was typed; the scene
    // shows what they type ({punct:,}: 显示和输出一致, 060-render-p2 §5.3 S5b)
    private var punctuationMapping: Map<String, String> = emptyMap()

    override fun keyState(): KeysState = super.keyState().copy(punctuation = punctuationMapping)

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        canvas?.invalidateScene()
    }

    // what the scene's keys show as last told
    private var returnAppearance = ReturnKeyAppearance.Enter
    private var readingOn = false

    // what the keys show now as the scene engine's environment (handwriting.xml's when= and bindings): the
    // language key, the space's name and microphone, Return's label and icon, ，。 as typed, 注 on or off
    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
        val input = Input(langKey = showLangSwitchKey.getValue(), holdVoice = holdsToTalk)
        val texts = Texts(
            imeName = shownImeName.orEmpty(),
            returnLabel = returnAppearance.label(context).toString(),
            returnIcon = SceneIcons.returnName(returnAppearance.icon),
            punctuation = punctuationMapping,
            a11ySpace = context.getString(R.string.a11y_key_space_with_ime),
        )
        val env = SceneEnvs.of(this, look, theme.isDark, width, height, input, texts = texts, marks = keyMarks(layout))
        return if (readingOn) env.copy(states = READING_ON) else env
    }

    // 注 said as its view said it (SceneA11y's _on and _off)
    override fun sceneString(name: String): String? = when (name) {
        "a11y_key_reading_on" -> context.getString(R.string.hw_reading_key_on, context.getString(R.string.im_candidate_reading))
        "a11y_key_reading_off" -> context.getString(R.string.hw_reading_key_off, context.getString(R.string.im_candidate_reading))
        else -> null
    }

    // ，。 named (060-swipe-slots): swiped to an empty slot, typed as a tap
    override val punctuationSwipesTap: Boolean get() = true

    // a getter: asked in init (drawKeysWithScene), before this class's own properties are made
    override val sceneTable: SceneKeyDef.Table get() = object : SceneKeyDef.Table {
        override fun menuLabel(label: String): String = MENU_LABELS[label]?.let(context::getString) ?: label
    }

    // last, after every field its scene reads (sceneEnv): the keyboard's state
    init {
        add(padView, lParams(0, 0))
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        // the keys drawn by the scene engine; the pad's face the slot's, handwriting.xml's face="keep"; the pad
        // over the canvas, in its slot (onLayout)
        drawKeysWithScene(LAYOUT)
        padView.bringToFront()
        // the canvas at another size (one-handed, split, another height): the pad to the slot of the scene at
        // that size; laid out again at the same bounds, nothing to place again
        canvas?.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            val moved = l != oldL || t != oldT
            if (moved || r != oldR || b != oldB) post { requestLayout() }
        }
    }
}
