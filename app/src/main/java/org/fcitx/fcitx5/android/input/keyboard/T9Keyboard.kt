/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.core.view.children
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.core.add
import splitties.views.existingOrNewId

/**
 * Pinyin on nine keys (九键), laid out as 搜狗 does: a column on the left offering what the first
 * digits not yet taken may be (the engine's [EngineBridge.t9] syllables), or punctuation while
 * nothing is typed; 1 is the separator (分词) while something is typed, else punctuation
 * (，。？！, pressed again soon the next in place of the last, as a digit key's letters; its long
 * press more); backspace, start over (重输) and 0 on the right; symbols, numbers, space, the
 * language switch and return below. Swiping a digit key, 1 too, types the digit itself.
 *
 * A syllable is sent to the engine as a character of [Keyboard.SYLLABLES], saying which of
 * which list it is ([Keyboard.syllableKey]).
 *
 * A digit key pressed again soon may be its next letter ([MultiTap], as [AppPrefs.Keyboard.t9MultiTap]
 * sets), sent as a character of [Keyboard.LETTERS] ([Keyboard.letterKey]), which the engine reads
 * as a press of its own, the run as keys or as that letter; its preview shows the letter. Its long press offers the digit and its letters, a letter chosen
 * typed as itself after what is typed.
 *
 * A glide across the digit keys ([GlideInput], the setting on) is read as full pinyin's, each
 * key's letters on it: the engine types the reading's letters as themselves.
 */
@SuppressLint("ViewConstructor")
class T9Keyboard(
    context: Context,
    theme: Theme,
    /** What each digit key types, as the user set it: the engine reads the digits by the same. */
    private val letters: NineKeyLetters = NineKeyLetters.STANDARD,
) : BaseKeyboard(context, theme, layout(letters)) {

    companion object {
        const val Name = "T9"

        // the column's share of the width, and of the right one
        private const val SIDE = 0.16f
        private const val RIGHT = 0.18f
        private const val DIGIT = (1f - SIDE - RIGHT) / 3

        /** Shown in the column while nothing is typed, committed as they are. */
        private val PUNCTUATION = listOf("，", "。", "？", "！", "…", "、", "：", "~", "@")

        /** What 1 types while nothing is typed: the first, pressed again soon the next in its place. */
        internal const val ONE = "，。？！"

        /** 1's long press: the rest of 搜狗's column, committed as they are. */
        internal val ONE_MORE = listOf("…", "：", "；", "~", ".", "-", "@", "、", "“", "”")

        // 1's label while something is typed
        private const val SEPARATOR = "分词"

        /** How many items of the column show at once. */
        private const val SHOWN = 4

        // nothing typed, or nothing of it left to take: the column offers punctuation
        private fun EngineBridge.T9State.offersSyllables() = composing && syllables.isNotEmpty()

        /** The column's items for [state]: its syllables while typing, else punctuation. */
        internal fun labels(state: EngineBridge.T9State): List<String> =
            if (state.offersSyllables()) state.syllables else PUNCTUATION

        /** What picking item [i] of [state]'s column types. */
        internal fun pick(state: EngineBridge.T9State, i: Int): KeyAction =
            if (state.offersSyllables()) {
                // with the list's id: shown before a key typed since replaced it, the pick is dropped
                KeyAction.SymAction(KeySym(UNICODE + Keyboard.syllableKey(i, state.id).code), KeyStates.Virtual)
            } else {
                KeyAction.CommitAction(PUNCTUATION[i])
            }

        private fun digitKey(digit: Char, letters: String) = KeyDef(
            KeyDef.Appearance.AltText(displayText = letters, altText = digit.toString(), textSize = 20f, percentWidth = DIGIT),
            setOf(
                KeyDef.Behavior.Press(KeyAction.FcitxKeyAction(digit.toString())),
                KeyDef.Behavior.Swipe(KeyAction.CommitAction(digit.toString())),
            ),
            arrayOf(
                KeyDef.Popup.AltPreview(letters, digit.toString()),
                KeyDef.Popup.Keyboard.Explicit(arrayOf(digit.toString()) + letters.lowercase().map { it.toString() }),
            ),
        )

        /**
         * What [action] from [source] types: a digit key's press [MultiTap] reads at [time]
         * through [letters], a letter or digit of its long press; 1, while nothing is [composing],
         * punctuation ([ONE]), a symbol of its long press as it is; anything else resets [tap] and
         * types as it is.
         */
        internal fun resolve(
            action: KeyAction,
            source: KeyActionListener.Source,
            tap: MultiTap,
            time: Long,
            composing: Boolean = false,
            letters: NineKeyLetters = NineKeyLetters.STANDARD,
        ): KeyAction {
            val key = (action as? KeyAction.FcitxKeyAction)?.act?.singleOrNull()
            if (key == '1' && source != KeyActionListener.Source.Popup && !composing) {
                // as a digit key's letters: pressed again soon, the next in place of the last
                val next = tap.press(key, ONE, time) ?: return KeyAction.CommitAction(ONE.take(1))
                val last = ONE[(ONE.indexOf(next) + ONE.length - 1) % ONE.length]
                return KeyAction.CommitAction(next.toString(), replacing = last.toString())
            }
            if (source == KeyActionListener.Source.Popup && key != null && key.toString() in ONE_MORE) {
                tap.reset()
                // as it is: through the engine . would be 。
                return KeyAction.CommitAction(key.toString())
            }
            if (key == null || key !in '2'..'9' && key !in 'a'..'z') {
                tap.reset()
                return action
            }
            if (source == KeyActionListener.Source.Popup) {
                tap.reset()
                // the digit itself, as the swipe types it, not a key
                return if (key in '2'..'9') KeyAction.CommitAction(key.toString()) else letter(key, again = false)
            }
            if (key !in '2'..'9') return action
            val letter = tap.press(key, letters.letters(key), time) ?: return action
            return letter(letter, again = true)
        }

        private fun letter(c: Char, again: Boolean) =
            KeyAction.SymAction(KeySym(UNICODE + Keyboard.letterKey(c, again)), KeyStates.Virtual)

        /** The run [setting] times: off, every press of a digit key or 1 is a key of its own. */
        internal fun multiTapOf(setting: T9MultiTap) = MultiTap(setting.timeout)

        // the column is drawn over these placeholders, so they are never touched
        private fun spacer() = KeyDef(
            KeyDef.Appearance.Text("", 16f, percentWidth = SIDE, border = KeyDef.Appearance.Border.Off, margin = false),
            emptySet(),
        )

        /** The keys, each digit key with [letters]' letters on it. */
        internal fun layout(letters: NineKeyLetters): List<List<KeyDef>> = listOf(
            listOf(
                spacer(),
                KeyDef(
                    KeyDef.Appearance.AltText(displayText = ONE, altText = "1", textSize = 16f, percentWidth = DIGIT),
                    setOf(
                        KeyDef.Behavior.Press(KeyAction.FcitxKeyAction("1")),
                        KeyDef.Behavior.Swipe(KeyAction.CommitAction("1")),
                    ),
                    arrayOf(KeyDef.Popup.Keyboard.Explicit(ONE_MORE.toTypedArray())),
                ),
                digitKey('2', letters.letters('2').uppercase()),
                digitKey('3', letters.letters('3').uppercase()),
                BackspaceKey(RIGHT),
            ),
            listOf(
                spacer(),
                digitKey('4', letters.letters('4').uppercase()),
                digitKey('5', letters.letters('5').uppercase()),
                digitKey('6', letters.letters('6').uppercase()),
                KeyDef(
                    KeyDef.Appearance.Text("重输", 16f, percentWidth = RIGHT, variant = KeyDef.Appearance.Variant.Alternative),
                    setOf(KeyDef.Behavior.Press(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Escape)))),
                ),
            ),
            listOf(
                spacer(),
                digitKey('7', letters.letters('7').uppercase()),
                digitKey('8', letters.letters('8').uppercase()),
                digitKey('9', letters.letters('9').uppercase()),
                KeyDef(
                    KeyDef.Appearance.Text("0", 20f, percentWidth = RIGHT, variant = KeyDef.Appearance.Variant.Alternative),
                    setOf(KeyDef.Behavior.Press(KeyAction.FcitxKeyAction("0"))),
                ),
            ),
            listOf(
                LayoutSwitchKey("符", PickerWindow.Key.Symbol.name, SIDE),
                LayoutSwitchKey("123", NumberKeyboard.Name, 0.14f),
                SpaceKey(),
                LanguageKey(),
                ReturnKey(RIGHT),
            ),
        )
    }

    private val space: TextKeyView by lazy { findViewById(R.id.button_space) }

    // the keys 1 to 9 by their digits
    private val digitKeys: Map<Char, AltTextKeyView> = keyRows.flatMap { it.children.toList() }
        .filterIsInstance<AltTextKeyView>()
        .associateBy { (it.def as KeyDef.Appearance.AltText).altText.single() }

    // a key's preview names it by its view's id, not by its letters, which two keys may share
    private val digitOf: Map<Int, Char> = digitKeys.entries.associate { (digit, key) -> key.existingOrNewId to digit }

    // 1, its label as what is typed has it
    private val one: AltTextKeyView = digitKeys.getValue('1')
    private val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }
    private val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }

    // each digit key's letters on it, as the engine reads the digits
    private val letterKeys: List<Pair<Char, TextKeyView>> = ('2'..'9').flatMap { d ->
        letters.letters(d).map { it.lowercaseChar() to digitKeys.getValue(d) }
    }

    // glide typing (滑行输入) over the digit keys, read as full pinyin's letters
    override val glide = glideInput(letters = { letterKeys }, down = GlideGesture.NINE_KEYS_DOWN) { path, layout ->
        // a glide ends a run of presses: a key pressed soon after it is a press of its own, not 连按's next letter
        multiTap.reset()
        onAction(KeyAction.GlideAction(Glide(path, layout)))
    }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    private val multiTapPref = AppPrefs.getInstance().keyboard.t9MultiTap
    private var multiTap = multiTapOf(multiTapPref.getValue())

    @Keep
    private val multiTapListener = ManagedPreference.OnChangeListener<T9MultiTap> { _, v ->
        multiTap = multiTapOf(v)
    }

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        lang.isVisible = v
    }

    private val items = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val column = object : ScrollView(context) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            itemHeight = (h - paddingTop - paddingBottom) / SHOWN
            for (i in 0 until items.childCount) items.getChildAt(i).layoutParams.height = itemHeight
            post { items.requestLayout() }
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        val prefs = ThemeManager.prefs
        val h = dp(prefs.keyHorizontalMargin.getValue())
        val v = dp(prefs.keyVerticalMargin.getValue())
        background = insetRadiusDrawable(h, v, dp(prefs.keyRadius.getValue().toFloat()), theme.altKeyBackgroundColor)
        setPadding(h, v, h, v)
        addView(items)
    }

    private var itemHeight = 0

    /** What the column shows, read by its items when picked. */
    private var columnState = EngineBridge.T9State()

    // something typed, as the engine last told, or a key sent since that types
    private var typing = false

    // a digit with letters (one with none the app types as it is), or a letter a digit key's long press or 连按 sends
    private fun isTypingKey(action: KeyAction) = when (action) {
        is KeyAction.FcitxKeyAction -> action.act.singleOrNull()?.let { it in '2'..'9' && letters.letters(it).isNotEmpty() } == true
        is KeyAction.SymAction -> action.sym.sym - UNICODE in Keyboard.LETTERS
        else -> false
    }

    init {
        add(column, lParams {
            topToTop = keyRows[0].existingOrNewId
            bottomToBottom = keyRows[2].existingOrNewId
            leftOfParent()
            width = 0
            height = 0
            matchConstraintPercentWidth = SIDE
        })
        // under the column, out of reach: TalkBack would stop on them, a second finger under the vivo workaround hit them
        keyRows.take(3).forEach { row ->
            row.getChildAt(0).apply {
                isEnabled = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        lang.isVisible = showLangSwitchKey.getValue()
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        multiTapPref.registerOnChangeListener(multiTapListener)
        show(EngineBridge.T9State())
    }

    private var scope: CoroutineScope? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            s.launch { EngineBridge.t9.collectLatest(::show) }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    private val columnHit = Rect()

    // the vivo workaround takes every touch for a key: the column's are the column's
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        column.getHitRect(columnHit)
        if (columnHit.contains(ev.x.toInt(), ev.y.toInt())) return false
        return super.onInterceptTouchEvent(ev)
    }

    /** The column for [state]: its syllables while typing, else punctuation; its items kept, made only when more are needed. */
    internal fun show(state: EngineBridge.T9State) {
        // a press on the list replaced is cancelled, as removing its items did: its pick would take what now sits there
        MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
            items.dispatchTouchEvent(it)
            it.recycle()
        }
        // and so is a click released but not run yet (View posts it on ACTION_UP), as a removed item's was
        items.cancelPendingInputEvents()
        // what was typed gone (a candidate picked on the bar, which is not this keyboard's): the
        // next press is a key of its own. Only then: 1's punctuation resets the engine, which tells
        // of nothing typed again, and its next press must still be the next symbol
        val was = columnState.composing
        if (!state.composing && was) multiTap.reset()
        columnState = state
        typing = state.composing
        // only when it changes: setText does not skip the same text, and may lay the row out again
        if (state.composing != was) one.mainText.text = if (state.composing) SEPARATOR else ONE
        val labels = labels(state)
        while (items.childCount < labels.size) items.addView(item(items.childCount))
        for (i in 0 until items.childCount) {
            val view = items.getChildAt(i) as TextView
            view.isVisible = i < labels.size
            if (view.isVisible) view.text = labels[i]
        }
        column.scrollTo(0, 0)
    }

    private fun item(i: Int) = TextView(context).apply {
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(theme.altKeyTextColor)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, itemHeight.takeIf { it > 0 } ?: dp(40))
        background = pressHighlight()
        isClickable = true
        setOnClickListener {
            InputFeedbacks.hapticFeedback(this)
            InputFeedbacks.soundEffect(InputFeedbacks.SoundEffect.Standard)
            onAction(pick(columnState, i))
        }
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        // a digit key's press as its preview counted it, when it went down
        val key = (action as? KeyAction.FcitxKeyAction)?.act?.singleOrNull()
        val time = pressed?.takeIf { it.digit == key }?.time ?: SystemClock.uptimeMillis()
        pressed = null
        val out = resolve(action, source, multiTap, time, typing, letters)
        // a key the engine reads as typing: 1 right after it is the separator, though the engine
        // has not told of what is typed yet
        if (isTypingKey(out)) typing = true
        super.onAction(out, source)
    }

    /**
     * The cursor moved, not by this keyboard's own commit (a tap in the text, the app): 1 pressed
     * next is a symbol of its own, not one in place of a ， now before the cursor.
     */
    fun cursorMoved() = multiTap.reset()

    /** A digit key gone down at [time]: its press counts from then, as its preview did. */
    private class Pressed(val digit: Char, val time: Long)

    /** The [letter] the preview of the key of view [viewId] shows in place of its own [letters] (GHI), while it is held. */
    private class Previewing(val viewId: Int, val letters: String, val letter: String)

    private var previewing: Previewing? = null

    // the digit key gone down last
    private var pressed: Pressed? = null

    // a digit key's preview shows the letter pressing it again now types
    override fun onPopupAction(action: PopupAction) {
        val shown = when (action) {
            is PopupAction.PreviewAction -> {
                val digit = digitOf[action.viewId]
                val now = SystemClock.uptimeMillis()
                pressed = digit?.let { Pressed(it, now) }
                val letter = digit?.let { multiTap.peek(it, letters.letters(it), now) }
                previewing = letter?.let { Previewing(action.viewId, action.content, it.uppercase()) }
                previewing?.let { action.copy(content = it.letter) }
            }
            // back from the digit a swipe shows
            is PopupAction.PreviewUpdateAction ->
                previewing?.takeIf { it.viewId == action.viewId && it.letters == action.content }?.let { action.copy(content = it.letter) }
            else -> null
        }
        super.onPopupAction(shown ?: action)
    }

    private fun pressHighlight() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(theme.keyPressHighlightColor))
    }

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        `return`.showReturnAction(appearance)
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        showInputMethodName(space, ime)
    }
}
