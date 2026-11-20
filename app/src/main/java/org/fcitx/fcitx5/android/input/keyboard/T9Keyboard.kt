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
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.set
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.TextBoardState
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.LetterCaseRule
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add

/**
 * Pinyin's digit keys send [Keyboard.pinyinNineKey] codes, since a plain digit would pick a candidate as a
 * hardware keyboard's does. A syllable goes to the engine as a character of [Keyboard.SYLLABLES] naming its
 * list; a 连按 letter as one of [Keyboard.LETTERS], typed as itself.
 */
@SuppressLint("ViewConstructor")
class T9Keyboard(
    context: Context,
    theme: Theme,
    private val letters: NineKeyLetters = NineKeyLetters.STANDARD,
    private val english: Boolean = false,
    private val shuangpin: Boolean = false,
    private val semicolon: Boolean,
    /** `id@stamp` ([customLayout]); null for the app's. */
    private val custom: String? = null,
) : BaseKeyboard(context, theme, themeBoard = KeyNames.NINE), AutoCapsKeys, TypingKeys {

    companion object {
        const val Name = "T9"

        /** A layout of its own, so that switching between it and [Name] shows the other. */
        const val EnglishName = "T9English"

        const val ShuangpinName = "T9Shuangpin"

        const val ShuangpinPlainName = "T9ShuangpinPlain"

        private const val SIDE_KEY = "side"

        internal const val ITEM_TEXT_DP = 16f

        const val LAYOUT = "nine"

        private val PUNCTUATION = listOf(",", ".", "?", "!", "…", "、", ":", "~", "@")

        internal const val ONE = ",.?!"

        internal fun oneLabel(marks: Map<String, String>) = ONE.map { marked(it, marks) }.joinToString("")

        private fun marked(c: Char, marks: Map<String, String>) = c.toString().let { marks[it] ?: it }

        /** Typed through English, which puts a word's space behind them. */
        private val ENGLISH_PUNCTUATION = listOf(",", ".", "?", "!", "'", "-", ":", ";", "@")

        private const val APOSTROPHE = "'"

        internal const val ENGLISH_TAP_MILLIS = 600L

        private const val SHOWN = 4

        private fun T9State.offersSyllables() = composing && syllables.isNotEmpty()

        internal fun labels(
            state: T9State,
            english: Boolean = false,
            address: AddressKeys = AddressKeys.NONE,
            marks: Map<String, String> = emptyMap(),
        ): List<String> = when {
            !english && state.offersSyllables() -> state.syllables
            address != AddressKeys.NONE -> AddressKeys.column(address)
            english -> ENGLISH_PUNCTUATION
            else -> PUNCTUATION.map { marks[it] ?: it }
        }

        internal fun pick(
            state: T9State,
            i: Int,
            english: Boolean = false,
            address: AddressKeys = AddressKeys.NONE,
            marks: Map<String, String> = emptyMap(),
        ): KeyAction =
            if (!english && state.offersSyllables()) {
                // the list's id: a pick shown before a key replaced the list is dropped
                KeyAction.SymAction(KeySym(UNICODE + Keyboard.syllableKey(i, state.id).code), KeyStates.Virtual)
            } else if (address != AddressKeys.NONE) {
                KeyAction.CommitAction(AddressKeys.column(address)[i])
            } else if (english) {
                KeyAction.HostKeyAction(ENGLISH_PUNCTUATION[i])
            } else {
                PUNCTUATION[i].let { KeyAction.CommitAction(marks[it] ?: it, punctuation = it.singleOrNull()?.code?.let(::isAsciiMark) == true) }
            }

        /**
         * 1 while [composing] is the separator, or with [semicolon] (微软's) `;`, which the engine reads as
         * ing after an initial and as the separator elsewhere.
         */
        internal fun resolveShuangpin(
            action: KeyAction,
            source: KeyActionListener.Source,
            tap: MultiTap,
            time: Long,
            composing: Boolean,
            semicolon: Boolean,
            marks: Map<String, String> = emptyMap(),
        ): KeyAction {
            val key = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull()
            if (source == KeyActionListener.Source.Popup || key == null || key !in '1'..'9') return resolve(action, source, tap, time, composing)
            if (key == '1') {
                if (!composing) return resolve(action, source, tap, time, composing = false, marks = marks)
                tap.reset()
                if (semicolon) return KeyAction.SymAction(KeySym(UNICODE + NineKeyLetters.NINE_SP_SEMICOLON.code), KeyStates.Virtual)
                // the engine's 双拼 separates with ', a digit being no key of it
                return KeyAction.HostKeyAction("'")
            }
            tap.reset()
            return codeOf(key, (action as KeyAction.HostKeyAction).touch)
        }

        /**
         * While [typing] every digit is its code, 1 the separator; 0 and a key with no letters end the input
         * (first candidate, then the digit). A digit sent as itself would pick its numbered candidate.
         */
        internal fun coded(action: KeyAction, typing: Boolean, letters: NineKeyLetters): KeyAction {
            val key = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull() ?: return action
            val code = key.isDigit() && typing || key in '2'..'9' && letters.letters(key).isNotEmpty()
            return if (code) KeyAction.SymAction(KeySym(UNICODE + Keyboard.pinyinNineKey(key)), KeyStates.Virtual, (action as KeyAction.HostKeyAction).touch) else action
        }

        internal fun codeOf(digit: Char, touch: Touch? = null) =
            KeyAction.SymAction(KeySym(UNICODE + NineKeyLetters.NINE_SP.codes[digit - '2'].code), KeyStates.Virtual, touch)

        internal fun lettersOn(d: Char, letters: NineKeyLetters, english: Boolean, shuangpin: Boolean) = when {
            shuangpin -> NineKeyLetters.NINE_SP.letters(NineKeyLetters.NINE_SP.codes[d - '2']).uppercase()
            english -> letters.letters(d).lowercase()
            else -> letters.letters(d).uppercase()
        }

        /** A long press's symbols go to the engine as the full keyboard's do (first candidate, then the mapped punctuation). */
        internal fun resolve(
            action: KeyAction,
            source: KeyActionListener.Source,
            tap: MultiTap,
            time: Long,
            composing: Boolean = false,
            letters: NineKeyLetters = NineKeyLetters.STANDARD,
            marks: Map<String, String> = emptyMap(),
        ): KeyAction {
            val key = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull()
            if (key == '1' && source != KeyActionListener.Source.Popup && !composing) return one(tap, time, marks)
            asIs(key, source)?.let {
                tap.reset()
                return chosen(it)
            }
            if (key == null || key !in '2'..'9') {
                tap.reset()
                return action
            }
            val letter = tap.press(key, letters.letters(key), time) ?: return action
            return letter(letter, again = true)
        }

        private fun asIs(key: Char?, source: KeyActionListener.Source): Char? =
            key?.takeIf { source == KeyActionListener.Source.Popup && (it in '0'..'9' || it.isAsciiLetter()) }

        private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

        // what is typed goes in first (the first candidate); never read as a key or a candidate pick
        private fun chosen(c: Char) = KeyAction.CommitAction(c.toString())

        private fun pinned(c: Char) = KeyAction.SymAction(KeySym(UNICODE + Keyboard.nineLetter(c)), KeyStates.Virtual)

        private fun letter(c: Char, again: Boolean) =
            KeyAction.SymAction(KeySym(UNICODE + Keyboard.letterKey(c, again)), KeyStates.Virtual)

        internal fun resolveEnglish(
            action: KeyAction,
            source: KeyActionListener.Source,
            tap: MultiTap,
            time: Long,
            composing: Boolean,
            spells: Boolean,
            upper: Boolean,
            runUpper: Boolean = upper,
            letters: NineKeyLetters = NineKeyLetters.STANDARD,
            marks: Map<String, String> = emptyMap(),
        ): KeyAction {
            val key = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull()
            if (key == '1' && source != KeyActionListener.Source.Popup) {
                if (!composing) return one(tap, time, marks)
                tap.reset()
                return KeyAction.HostKeyAction(APOSTROPHE)
            }
            asIs(key, source)?.let {
                tap.reset()
                // a letter goes into the spelled word pinned, the rest still guessed: so a word the data lacks is learned
                return if (spells && it.isAsciiLetter()) pinned(it) else chosen(it)
            }
            if (key == null || key !in '2'..'9') {
                tap.reset()
                return action
            }
            val keyLetters = letters.letters(key).lowercase()
            if (keyLetters.isEmpty()) {
                tap.reset()
                return action
            }
            if (spells) {
                tap.reset()
                // with where it fell, for English to read it as a neighbouring digit key
                return KeyAction.SymAction(KeySym(UNICODE + Keyboard.nineKey(key, upper)), KeyStates.Virtual, (action as KeyAction.HostKeyAction).touch)
            }
            val next = tap.press(key, keyLetters, time)
            return cycled(tap, next, keyLetters) { cased(it, if (next == null) upper else runUpper) }
        }

        private fun one(tap: MultiTap, time: Long, marks: Map<String, String>): KeyAction.CommitAction {
            val next = tap.press('1', ONE, time)
            if (next == null) tap.marks = marks
            val run = tap.marks
            return cycled(tap, next, ONE, punctuation = true) { marked(it, run) }
        }

        // [next] replaces what the run's last press committed ([MultiTap.typed]), not the letter before mapped anew
        private fun cycled(tap: MultiTap, next: Char?, run: String, punctuation: Boolean = false, text: (Char) -> String): KeyAction.CommitAction {
            val action = if (next == null) {
                KeyAction.CommitAction(text(run[0]), punctuation = punctuation)
            } else {
                KeyAction.CommitAction(text(next), replacing = tap.typed, punctuation = punctuation)
            }
            tap.typed = action.text
            return action
        }

        private fun cased(c: Char, upper: Boolean) = if (upper) c.uppercase() else c.toString()

        internal fun startsLetter(action: KeyAction): Boolean = when (action) {
            is KeyAction.SymAction -> action.sym.sym - UNICODE in Keyboard.NINE_DIGITS || action.sym.sym - UNICODE in Keyboard.NINE_LETTERS
            is KeyAction.HostKeyAction -> action.act.singleOrNull()?.isLetter() == true
            is KeyAction.CommitAction -> action.replacing.isEmpty() && action.text.singleOrNull()?.isLetter() == true
            else -> false
        }

        internal fun multiTapOf(setting: T9MultiTap) = MultiTap(setting.timeout)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        val changed = mapping != keysPunctuation
        super.onPunctuationUpdate(mapping)
        // labels only: show() would take the engine's last word for whether 1 is typing and cancel a press on the column
        if (changed) showLabels()
    }

    override var address: AddressKeys = AddressKeys.NONE
        set(value) {
            if (field == value) return
            field = value
            show(columnState)
        }

    override fun typingKeys(): List<Pair<Char, KeyRef>> =
        if (shuangpin) {
            ('2'..'9').map { NineKeyLetters.NINE_SP.codes[it - '2'] to KeyRef(it.toString()) }
        } else {
            ('2'..'9').filter { letters.letters(it).isNotEmpty() }.map { it to KeyRef(it.toString()) }
        }

    private val sceneLetters: List<Pair<Char, ScenePlace>> = ('2'..'9').flatMap { d ->
        scenePlace(d.toString()).let { at -> letters.letters(d).map { it.lowercaseChar() to at } }
    }

    // English's never glides (no full keyboard's keys), nor 双拼's (it reads no glided letters)
    override val glide: GlideInput? = if (english) {
        null
    } else {
        glideInput(
            letters = { sceneLetters }, down = GlideGesture.NINE_KEYS_DOWN, enabled = { !shuangpin },
            // the 3×3 with 1, not the side columns' Shift; a stroke starts there too (a 横 along the top row starts on 1)
            writesAlso = { it.id == "1" },
        ) { path, layout ->
            // a key pressed soon after a glide is a press of its own, not 连按's next letter
            multiTap.reset()
            onAction(KeyAction.GlideAction(Glide(path, layout)))
        }
    }

    private val letterCase by AppPrefs.getInstance().keyboard.letterCase

    private val multiTapPref = AppPrefs.getInstance().keyboard.t9MultiTap

    private fun tapOf(setting: T9MultiTap) =
        if (english) MultiTap(setting.timeout.takeIf { it > 0 } ?: ENGLISH_TAP_MILLIS) else multiTapOf(setting)

    private var multiTap = tapOf(multiTapPref.getValue())

    @Keep
    private val multiTapListener = ManagedPreference.OnChangeListener<T9MultiTap> { _, v ->
        multiTap = tapOf(v)
    }

    private val englishTyping = AppPrefs.getInstance().keyboard.t9EnglishTyping

    var noSuggestions = false

    private val shift = NineKeyShift()

    private var runUpper = false

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
        // its face is the scene's slot (nine.xml's `side`): the column draws only its items
        val keys = ThemeManager.shownLook(theme).keys
        val landscape = KeyboardArea.landscape(context)
        val h = dp(if (landscape) keys.gap.hLand else keys.gap.h)
        val v = dp(if (landscape) keys.gap.vLand else keys.gap.v)
        setPadding(h, v, h, v)
        addView(items)
    }

    private var itemHeight = 0

    private var columnState = T9State()

    private var typing = false

    private fun isTypingKey(action: KeyAction) = when (action) {
        is KeyAction.HostKeyAction -> action.act.singleOrNull()?.let { it in '2'..'9' && letters.letters(it).isNotEmpty() } == true
        is KeyAction.SymAction -> (action.sym.sym - UNICODE).let { c ->
            c in Keyboard.LETTERS || shuangpin && c in 0..Char.MAX_VALUE.code && NineKeyLetters.NINE_SP.isCode(c.toChar())
        }
        else -> false
    }

    override val ownsSyllables: Boolean get() = !english

    init {
        column.isInvisible = true
        add(column, lParams(0, 0))
        multiTapPref.registerOnChangeListener(multiTapListener)
        show(T9State())
    }

    private var scope: CoroutineScope? = null

    private val ownIm = if (shuangpin) InputMethodCatalog.SHUANGPIN else InputMethodCatalog.PINYIN

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            if (english) {
                s.launch { EngineState.englishTyping.collectLatest { show(T9State(composing = it)) } }
            } else {
                s.launch {
                    // another input method answering: nothing typed on the nine keys (a filter would leave a switch's
                    // syllables shown)
                    EngineState.syllables
                        .map { if (it.im == ownIm) T9State(it.composing, it.syllables, it.id) else T9State() }
                        .collectLatest(::show)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    private val columnHit = Rect()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        column.getHitRect(columnHit)
        if (columnHit.contains(ev.x.toInt(), ev.y.toInt())) return false
        return super.onInterceptTouchEvent(ev)
    }

    internal fun show(state: T9State) {
        // a press on the replaced list is cancelled: its pick would take what now sits there
        MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
            items.dispatchTouchEvent(it)
            it.recycle()
        }
        // and so is a click released but not run yet (View posts it on ACTION_UP)
        items.cancelPendingInputEvents()
        // what was typed gone (a candidate picked on the bar): the next press is a key of its own. Only then:
        // 1's punctuation resets the engine, and its next press must still be the next symbol
        val was = columnState.composing
        if (!state.composing && was) multiTap.reset()
        columnState = state
        typing = state.composing
        showLabels()
        column.scrollTo(0, 0)
        if (state.composing != was) canvas?.invalidateScene()
    }

    private fun showLabels() {
        val labels = labels(columnState, english, address, keysPunctuation)
        while (items.childCount < labels.size) items.addView(item(items.childCount))
        for (i in 0 until items.childCount) {
            val view = items.getChildAt(i) as TextView
            view.isVisible = i < labels.size
            if (view.isVisible) view.text = labels[i]
        }
    }

    // one line: a syllable longer than the column (zhuang) shrinks to fit, not wrapped onto a cut-off line
    private fun item(i: Int) = AutoScaleTextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, ITEM_TEXT_DP)
        scaleMode = AutoScaleTextView.Mode.Proportional
        isSingleLine = true
        gravity = Gravity.CENTER
        setTextColor(theme.altKeyTextColor)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, itemHeight.takeIf { it > 0 } ?: dp(40))
        background = pressHighlight()
        isClickable = true
        setOnClickListener {
            InputFeedbacks.hapticFeedback(this)
            InputFeedbacks.soundEffect(SoundKey.LETTER)
            onAction(pick(columnState, i, english, address, keysPunctuation))
        }
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        val key = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull()
        val time = pressed?.takeIf { it.digit == key }?.time ?: SystemClock.uptimeMillis()
        pressed = null
        if (english) return onEnglish(action, source, time)
        val marks = keysPunctuation
        val out = if (shuangpin) {
            resolveShuangpin(action, source, multiTap, time, typing, semicolon, marks)
        } else {
            resolve(action, source, multiTap, time, typing, letters, marks)
        }
        // 1 right after it is the separator, though the engine has not told of the typing yet
        if (isTypingKey(out)) typing = true
        super.onAction(if (shuangpin) out else coded(out, typing, letters), source)
    }

    private fun onEnglish(action: KeyAction, source: KeyActionListener.Source, time: Long) {
        if (action is KeyAction.CapsAction) {
            multiTap.reset()
            shift.pressed(action.lock)
            canvas?.invalidateScene()
            super.onAction(action, source)
            return
        }
        val spells = ImKeyboards.spellsWords(
            predictive = englishTyping.getValue() == T9EnglishTyping.Predictive,
            suggests = EngineState.englishSuggests,
            password = inPasswordField,
            address = address != AddressKeys.NONE,
            noSuggestions = noSuggestions,
        )
        val out = resolveEnglish(action, source, multiTap, time, typing, spells, shift.upper, runUpper, letters, keysPunctuation)
        if (startsLetter(out)) {
            runUpper = shift.typed()
            canvas?.invalidateScene()
            // 1 right after it is an apostrophe, though the engine has not told of the word yet
            if (out !is KeyAction.CommitAction) typing = true
        }
        super.onAction(out, source)
    }

    override val capsLocked: Boolean get() = shift.caps == AutoCaps.Caps.LOCK

    override fun applyAutoCaps(wants: Boolean) {
        if (english && shift.auto(wants)) canvas?.invalidateScene()
    }

    override fun onAttach() {
        super.onAttach()
        if (!english) return
        shift.reset()
        canvas?.invalidateScene()
    }

    /** Not by this keyboard's commit: 1 next is a symbol of its own, not one replacing a ， before the cursor. */
    fun cursorMoved() = multiTap.reset()

    private class Pressed(val digit: Char, val time: Long)

    private class Previewing(val key: KeyRef, val letters: String, val letter: String)

    private var previewing: Previewing? = null

    private fun previewCase(letter: Char): String = LetterCaseRule.shown(
        letter.toString(),
        if (english) runUpper else LetterCaseRule.upper(letterCase.case, Shift.None, shiftless = true),
    )

    private var pressed: Pressed? = null

    override fun onPopupAction(action: PopupAction) {
        val shown = when (action) {
            is PopupAction.PreviewAction -> {
                val digit = action.key.id.singleOrNull()?.takeIf { it in '1'..'9' }
                val now = SystemClock.uptimeMillis()
                pressed = digit?.let { Pressed(it, now) }
                val letter = digit?.takeUnless { shuangpin }?.let { multiTap.peek(it, letters.letters(it), now) }
                previewing = letter?.let { Previewing(action.key, action.content, previewCase(it)) }
                previewing?.let { action.copy(content = it.letter) }
            }
            is PopupAction.PreviewUpdateAction ->
                previewing?.takeIf { it.key == action.key && it.letters == action.content }?.let { action.copy(content = it.letter) }
            else -> null
        }
        super.onPopupAction(shown ?: action)
    }

    private fun pressHighlight() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(theme.keyPressHighlightColor))
    }

    override val keysIm: String get() = imeName

    override val strokesDrawn get() = glide != null

    // not a lazy: the scene may ask during BaseKeyboard's init, before this class's fields are assigned (a
    // lazy's delegate would be null too). The null branch is taken only then
    override val keysNineLetters: Map<Char, String> get() = nineLettersMade ?: nineLetters()

    private val nineLettersMade: Map<Char, String>? = nineLetters()

    private fun nineLetters() = ('2'..'9').associateWith { lettersOn(it, letters, english, shuangpin).lowercase() }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        super.onInputMethodUpdate(ime)
        showInputMethodName(ime)
    }

    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
        val state = TextBoardState(semicolonScheme = shuangpin && semicolon, langKey = showLangSwitchKey.getValue(), holdVoice = holdsToTalk)
        val base = state.input()
        val input = base.copy(ime = if (english) "english" else base.ime, composing = columnState.composing)
        val texts = Texts(
            alts = keyReadings(layout),
            imeName = shownImeName.orEmpty(),
            letterCase = LetterCaseRule.forIme(letterCase.case, english),
            nineLetters = ('2'..'9').associate { d -> d.toString() to lettersOn(d, letters, english, shuangpin).let { if (english && shift.upper) it.uppercase() else it } },
            a11yGrouped = context.getString(R.string.a11y_key_grouped),
            a11ySpace = context.getString(R.string.a11y_key_space_with_ime),
            punctuation = keysPunctuation,
        ).withReturn()
        val caps = when {
            !english -> Shift.None
            else -> when (shift.caps) {
                AutoCaps.Caps.NONE -> Shift.None
                AutoCaps.Caps.ONCE -> Shift.Once
                AutoCaps.Caps.LOCK -> Shift.Lock
            }
        }
        return SceneEnvs.of(this, look, theme.isDark, width, height, input = input, shift = caps, texts = texts, marks = keyMarks(layout))
    }

    private val columnSlot = Rect()

    // the column where the scene has its slot `side`, in the same pass as the canvas: never a frame elsewhere
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val c = canvas ?: return
        placing = true
        val placed = try {
            c.slot(SIDE_KEY, columnSlot)
        } finally {
            placing = false
        }
        column.isInvisible = !placed
        if (!placed) return
        columnSlot.offset(c.left, c.top)
        // set only when the slot moved: else the constraint pass (0×0) and this pass would fight, each
        // onSizeChanged posting another layout, never done
        val lp = column.layoutParams as ConstraintLayout.LayoutParams
        val moved = lp.leftMargin != columnSlot.left || lp.topMargin != columnSlot.top
        if (moved || lp.width != columnSlot.width() || lp.height != columnSlot.height()) {
            column.layoutParams = lParams(columnSlot.width(), columnSlot.height()) {
                topOfParent(columnSlot.top)
                leftOfParent(columnSlot.left)
            }
        }
        val exactly = { size: Int -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) }
        if (column.width != columnSlot.width() || column.height != columnSlot.height() || column.isLayoutRequested) {
            column.measure(exactly(columnSlot.width()), exactly(columnSlot.height()))
        }
        column.layout(columnSlot.left, columnSlot.top, columnSlot.right, columnSlot.bottom)
    }

    private var placing = false

    override fun onSceneBuilt(scene: Scene?) {
        if (placing) return
        // a scene remade for Shift or Return alone lays nothing out
        val slot = scene?.slots?.firstOrNull { it.id == SIDE_KEY }
        val c = canvas ?: return
        if (slot == null) {
            if (column.isVisible) requestLayout()
            return
        }
        builtSlot.set(slot.bounds)
        builtSlot.offset(c.left, c.top)
        if (!column.isVisible || builtSlot != columnSlot) requestLayout()
    }

    private val builtSlot = Rect()

    // last: the scene reads the state above
    init {
        drawKeysWithScene(custom?.let { customLayout(context, it) } ?: SceneLayouts.of(context, LAYOUT))
        column.bringToFront()
    }
}
