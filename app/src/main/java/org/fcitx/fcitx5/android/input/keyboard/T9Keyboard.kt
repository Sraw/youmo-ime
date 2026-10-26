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
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
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
import org.fcitx.fcitx5.android.engine.host.Engines
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
import splitties.views.imageResource

/**
 * Pinyin on nine keys (九键), laid out as 搜狗 does: a column on the left offering what the first
 * digits not yet taken may be (the engine's [EngineBridge.syllables] of 九键), or punctuation while
 * nothing is typed; 1 is the separator (分词) while something is typed, else punctuation
 * (，。？！, with 连按 on pressed again soon the next in place of the last, as a digit key's letters; its long
 * press more); backspace, start over (重输) and 0 on the right; symbols, numbers, space, the
 * language switch and return below. Swiping a digit key, 1 too, types the digit itself (picks its
 * candidate while typing, as the number keyboard's digits do).
 *
 * A syllable is sent to the engine as a character of [Keyboard.SYLLABLES], saying which of
 * which list it is ([Keyboard.syllableKey]).
 *
 * A digit key pressed again soon is its next letter ([MultiTap], as [AppPrefs.Keyboard.t9MultiTap]
 * sets; off by default), sent as a character of [Keyboard.LETTERS] ([Keyboard.letterKey]), which
 * the engine puts in as that letter typed as itself and nothing else; its preview shows the letter.
 * Its long press offers a row of its letters in capitals, the digit and its letters (A B C 2 a b
 * c), the one chosen committed as it is after what is typed, and in rows above its symbols
 * ([NineKeySymbols]), typed as the full keyboard's long press types them (1's as they are).
 *
 * A glide across the digit keys ([GlideInput], the setting on) is read as full pinyin's, each
 * key's letters on it: the engine types the reading's letters as themselves.
 *
 * [english]: the nine keys of English ([EnglishName]), shown in its place after 九键拼音
 * ([EnglishLayoutPolicy]). Its digit keys spell words (预测, [Keyboard.nineKey]: 4663 good, home,
 * gone) or type a letter at a time (连按, as [AppPrefs.Keyboard.t9EnglishTyping] sets, and where
 * English suggests nothing); Shift ([NineKeyShift]) where 重输 is; English's marks in the column
 * and on 1, an apostrophe in the word while one is typed. It never glides.
 *
 * [shuangpin]: 九键双拼 ([ShuangpinName], design §3.4), 双拼 on [NineKeyLetters.NINE_SP]'s keys
 * (JKL; on 5, for 微软's and 紫光's ing): a digit key sends its group's code, which the engine
 * reads as any of its letters; 1 while typing the separator `'`; the column 双拼's syllables. No
 * 中/英 (the space takes its room), no 连按, no glide, the letters always the standard ones.
 */
@SuppressLint("ViewConstructor")
class T9Keyboard(
    context: Context,
    theme: Theme,
    /** What each digit key types, as the user set it: the engine reads the digits by the same. */
    private val letters: NineKeyLetters = NineKeyLetters.STANDARD,
    /** What each key's long press offers besides its digit and letters, as the user set it. */
    symbols: NineKeySymbols = NineKeySymbols.STANDARD,
    private val english: Boolean = false,
    private val shuangpin: Boolean = false,
) : BaseKeyboard(context, theme, layout(letters, symbols, english, shuangpin)), AutoCapsKeys {

    companion object {
        const val Name = "T9"

        /** The nine keys of English: a layout of its own, so that switching between it and [Name] shows the other. */
        const val EnglishName = "T9English"

        /** 九键双拼: a layout of its own, as [EnglishName]. */
        const val ShuangpinName = "T9Shuangpin"

        // the column's share of the width, and of the right one
        private const val SIDE = 0.16f
        private const val RIGHT = 0.18f

        // 中/英's share
        private const val LANGUAGE = 0.12f
        private const val DIGIT = (1f - SIDE - RIGHT) / 3

        /** Shown in the column while nothing is typed, committed as they are. */
        private val PUNCTUATION = listOf("，", "。", "？", "！", "…", "、", "：", "~", "@")

        /** What 1 types while nothing is typed: the first, pressed again soon the next in its place. */
        internal const val ONE = "，。？！"

        // 1's label while something is typed
        private const val SEPARATOR = "分词"

        /** The column's for English: typed through it, which puts a word's space behind them. */
        private val ENGLISH_PUNCTUATION = listOf(",", ".", "?", "!", "'", "-", ":", ";", "@")

        /** What 1 types for English while nothing is typed, as [ONE]. */
        internal const val ENGLISH_ONE = ".,?!'-"

        // 1's long press in English: the marks a word wants besides the column's and 1's own
        internal val ENGLISH_ONE_SYMBOLS = listOf(":", ";", "\"", "(", ")", "@", "&", "/", "*", "~")

        // 1's label for English, and while a word is typed, what it then types
        private const val ENGLISH_ONE_LABEL = ".,?!"
        private const val APOSTROPHE = "'"

        /** How soon English's 连按 takes a key pressed again as its next letter, 连按选字母 being off. */
        internal const val ENGLISH_TAP_MILLIS = 600L

        /** How many items of the column show at once. */
        private const val SHOWN = 4

        // nothing typed, or nothing of it left to take: the column offers punctuation
        private fun EngineBridge.T9State.offersSyllables() = composing && syllables.isNotEmpty()

        /**
         * The column's items for [state]: its syllables while typing, else punctuation; [english]'s
         * marks; in an address field ([address]) what addresses are made of ([AddressKeys.column]),
         * pinyin's syllables still while typing.
         */
        internal fun labels(state: EngineBridge.T9State, english: Boolean = false, address: AddressKeys = AddressKeys.NONE): List<String> = when {
            !english && state.offersSyllables() -> state.syllables
            address != AddressKeys.NONE -> AddressKeys.column(address)
            english -> ENGLISH_PUNCTUATION
            else -> PUNCTUATION
        }

        /**
         * What picking item [i] of [state]'s column types: for [english], the mark through English, the
         * word typed ended by it; an address's, committed as it is.
         */
        internal fun pick(state: EngineBridge.T9State, i: Int, english: Boolean = false, address: AddressKeys = AddressKeys.NONE): KeyAction =
            if (!english && state.offersSyllables()) {
                // with the list's id: shown before a key typed since replaced it, the pick is dropped
                KeyAction.SymAction(KeySym(UNICODE + Keyboard.syllableKey(i, state.id).code), KeyStates.Virtual)
            } else if (address != AddressKeys.NONE) {
                KeyAction.CommitAction(AddressKeys.column(address)[i])
            } else if (english) {
                KeyAction.FcitxKeyAction(ENGLISH_PUNCTUATION[i])
            } else {
                KeyAction.CommitAction(PUNCTUATION[i])
            }

        /**
         * What [action] from [source] types for 九键双拼: a digit key's press its group's code
         * ([NineKeyLetters.NINE_SP]), every press a key of its own; 1 while [composing] the
         * separator, else punctuation as [resolve] has it; a long press's letter or digit committed
         * as it is; anything else as it is.
         */
        internal fun resolveShuangpin(
            action: KeyAction,
            source: KeyActionListener.Source,
            tap: MultiTap,
            time: Long,
            composing: Boolean,
        ): KeyAction {
            val key = (action as? KeyAction.FcitxKeyAction)?.act?.singleOrNull()
            if (source == KeyActionListener.Source.Popup || key == null || key !in '1'..'9') return resolve(action, source, tap, time, composing)
            if (key == '1') {
                if (!composing) return resolve(action, source, tap, time, composing = false)
                tap.reset()
                // the engine's 双拼 separates with ', a digit being no key of it
                return KeyAction.FcitxKeyAction("'")
            }
            tap.reset()
            return codeOf(key)
        }

        /** The code of 九键双拼's digit key [digit]: its group's, sent as a Unicode character. */
        internal fun codeOf(digit: Char) =
            KeyAction.SymAction(KeySym(UNICODE + NineKeyLetters.NINE_SP.codes[digit - '2'].code), KeyStates.Virtual)

        /** A digit as a keypad's, as the number keyboard's: typed, or its candidate picked while typing (EngineEvent.KEYPAD). */
        private fun keypad(digit: Char) = KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + (digit - '0')), KeyStates.Virtual)

        private fun digitKey(digit: Char, letters: String, symbols: List<String>) = KeyDef(
            KeyDef.Appearance.AltText(displayText = letters, altText = digit.toString(), textSize = 20f, percentWidth = DIGIT),
            setOf(
                KeyDef.Behavior.Press(KeyAction.FcitxKeyAction(digit.toString())),
                KeyDef.Behavior.Swipe(keypad(digit)),
            ),
            arrayOf(
                KeyDef.Popup.AltPreview(letters, digit.toString()),
                // a row of its letters' capitals, the digit, its letters (A B C 2 a b c), the digit
                // under the finger; its symbols in rows above
                KeyDef.Popup.Keyboard.Explicit(
                    // ;'s capital is : (九键双拼's JKL;), as Shift gives it: no second ;
                    letters.map { if (it == ';') ":" else it.uppercase() }.toTypedArray() + digit.toString() +
                        letters.lowercase().map { it.toString() } + symbols.filter { it != ":" || ';' !in letters },
                    symbolsFrom = 1 + 2 * letters.length,
                    inRow = letters.length,
                ),
            ),
        )

        /**
         * What [action] from [source] types: a digit key's press [MultiTap] reads at [time]
         * through [letters]; a letter or digit of its long press committed as it is; 1, while nothing is [composing],
         * punctuation ([ONE]); anything else resets [tap] and types as it is: a symbol of 2 to 9's
         * long press goes to the engine as the full keyboard's do (the first candidate, then the
         * mark Chinese punctuation maps it to, quotes in turn, brackets paired), 1's come
         * committed as they are already.
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

        /** 中/英 as 搜狗 has it: the language typed in full, the other (with the slash) smaller and fainter. */
        fun languageLabel(english: Boolean): CharSequence = SpannableString("中/英").apply {
            val start = if (english) 0 else 1
            setSpan(RelativeSizeSpan(OTHER_LANGUAGE), start, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(Faint, start, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private const val OTHER_LANGUAGE = 0.7f

        // the language not typed: the key's text colour, fainter, whatever the theme
        private object Faint : CharacterStyle() {
            override fun updateDrawState(tp: TextPaint) {
                tp.alpha = tp.alpha * 3 / 5
            }
        }

        // a long press's letter or digit, committed as it is; null for any other key
        private fun asIs(key: Char?, source: KeyActionListener.Source): Char? =
            key?.takeIf { source == KeyActionListener.Source.Popup && (it in '0'..'9' || it.isAsciiLetter()) }

        private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

        // a letter or the digit a long press offers, committed as it is: what is typed goes in
        // first (the first candidate), as for its symbols; never read as a key, nor a candidate picked
        private fun chosen(c: Char) = KeyAction.CommitAction(c.toString())

        private fun pinned(c: Char) = KeyAction.SymAction(KeySym(UNICODE + Keyboard.nineLetter(c)), KeyStates.Virtual)

        private fun letter(c: Char, again: Boolean) =
            KeyAction.SymAction(KeySym(UNICODE + Keyboard.letterKey(c, again)), KeyStates.Virtual)

        /**
         * What [action] from [source] types for English: where it [spells] words, a digit key's
         * press is the key ([Keyboard.nineKey], a capital if [upper]); else a letter at a time,
         * committed, pressed again soon ([tap]) the next in place of the last, in the case
         * [runUpper] the run started in. A letter or digit of a long press is committed as it is (as
         * [resolve] has it); 1 while [composing] is an apostrophe in the word, else
         * English's marks ([ENGLISH_ONE]) as [ONE] is.
         */
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
        ): KeyAction {
            val key = (action as? KeyAction.FcitxKeyAction)?.act?.singleOrNull()
            if (key == '1' && source != KeyActionListener.Source.Popup) {
                if (composing) {
                    tap.reset()
                    return KeyAction.FcitxKeyAction(APOSTROPHE)
                }
                return cycled(tap.press(key, ENGLISH_ONE, time), ENGLISH_ONE) { it.toString() }
            }
            asIs(key, source)?.let {
                tap.reset()
                // where English spells the word, a letter goes into it, pinned, the rest still guessed:
                // a word the data lacks spelled so, and learned; the digit as it is
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
                return KeyAction.SymAction(KeySym(UNICODE + Keyboard.nineKey(key, upper)), KeyStates.Virtual)
            }
            val next = tap.press(key, keyLetters, time)
            return cycled(next, keyLetters) { cased(it, if (next == null) upper else runUpper) }
        }

        // the first of [run] committed for no [next], else [next] in place of the one before it
        private fun cycled(next: Char?, run: String, text: (Char) -> String): KeyAction.CommitAction {
            if (next == null) return KeyAction.CommitAction(text(run[0]))
            val last = run[(run.indexOf(next) + run.length - 1) % run.length]
            return KeyAction.CommitAction(text(next), replacing = text(last))
        }

        private fun cased(c: Char, upper: Boolean) = if (upper) c.uppercase() else c.toString()

        /** Whether [action] typed for English starts a letter, which Shift pressed once is used up by. */
        internal fun startsLetter(action: KeyAction): Boolean = when (action) {
            is KeyAction.SymAction -> action.sym.sym - UNICODE in Keyboard.NINE_DIGITS || action.sym.sym - UNICODE in Keyboard.NINE_LETTERS
            is KeyAction.FcitxKeyAction -> action.act.singleOrNull()?.isLetter() == true
            is KeyAction.CommitAction -> action.replacing.isEmpty() && action.text.singleOrNull()?.isLetter() == true
            else -> false
        }

        /** The run [setting] times: off, every press of a digit key or 1 is a key of its own. */
        internal fun multiTapOf(setting: T9MultiTap) = MultiTap(setting.timeout)

        // the column is drawn over these placeholders, so they are never touched
        private fun spacer() = KeyDef(
            KeyDef.Appearance.Text("", 16f, percentWidth = SIDE, border = KeyDef.Appearance.Border.Off, margin = false),
            emptySet(),
        )

        /**
         * The keys, each digit key with [letters]' letters on it; [english]'s in lower case, Shift for
         * 重输; [shuangpin]'s [NineKeyLetters.NINE_SP]'s, and no 中/英.
         */
        internal fun layout(
            letters: NineKeyLetters,
            symbols: NineKeySymbols = NineKeySymbols.STANDARD,
            english: Boolean = false,
            shuangpin: Boolean = false,
        ): List<List<KeyDef>> {
            fun on(d: Char) = when {
                shuangpin -> NineKeyLetters.NINE_SP.letters(NineKeyLetters.NINE_SP.codes[d - '2']).uppercase()
                english -> letters.letters(d).lowercase()
                else -> letters.letters(d).uppercase()
            }
            return layoutOf(::on, symbols, english, shuangpin)
        }

        private fun layoutOf(on: (Char) -> String, symbols: NineKeySymbols, english: Boolean, shuangpin: Boolean): List<List<KeyDef>> = listOf(
            listOf(
                spacer(),
                KeyDef(
                    KeyDef.Appearance.AltText(displayText = if (english) ENGLISH_ONE_LABEL else ONE, altText = "1", textSize = 16f, percentWidth = DIGIT),
                    setOf(
                        KeyDef.Behavior.Press(KeyAction.FcitxKeyAction("1")),
                        KeyDef.Behavior.Swipe(keypad('1')),
                    ),
                    // its symbols alone, none set apart: it has no letters; typed as they are, not
                    // through the engine, where . would be 。 English's its own, none of them full width
                    (if (english) ENGLISH_ONE_SYMBOLS else symbols.symbols('1')).takeIf { it.isNotEmpty() }?.let {
                        arrayOf(KeyDef.Popup.Keyboard.Explicit(it.toTypedArray(), asIs = true))
                    },
                ),
                digitKey('2', on('2'), symbols.symbols('2')),
                digitKey('3', on('3'), symbols.symbols('3')),
                BackspaceKey(RIGHT),
            ),
            listOf(
                spacer(),
                digitKey('4', on('4'), symbols.symbols('4')),
                digitKey('5', on('5'), symbols.symbols('5')),
                digitKey('6', on('6'), symbols.symbols('6')),
                if (english) {
                    CapsKey(RIGHT)
                } else {
                    KeyDef(
                        KeyDef.Appearance.Text("重输", 16f, percentWidth = RIGHT, variant = KeyDef.Appearance.Variant.Alternative),
                        setOf(KeyDef.Behavior.Press(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Escape)))),
                    )
                },
            ),
            listOf(
                spacer(),
                digitKey('7', on('7'), symbols.symbols('7')),
                digitKey('8', on('8'), symbols.symbols('8')),
                digitKey('9', on('9'), symbols.symbols('9')),
                KeyDef(
                    KeyDef.Appearance.Text("0", 20f, percentWidth = RIGHT, variant = KeyDef.Appearance.Variant.Alternative),
                    setOf(KeyDef.Behavior.Press(KeyAction.FcitxKeyAction("0"))),
                ),
            ),
            listOf(
                LayoutSwitchKey("符", PickerWindow.Key.Symbol.name, SIDE),
                LayoutSwitchKey("123", NumberKeyboard.Name, 0.14f),
                SpaceKey(),
            ) + listOfNotNull(
                NineKeyLanguageKey(LANGUAGE).takeUnless { shuangpin },
                LanguageKey(),
                ReturnKey(RIGHT),
            ),
        )
    }

    private val space: TextKeyView by lazy { findViewById(R.id.button_space) }

    /** The field's address: what the column offers in place of punctuation ([AddressKeys.column]). */
    override var address: AddressKeys = AddressKeys.NONE
        set(value) {
            if (field == value) return
            field = value
            show(columnState)
        }

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

    // glide typing (滑行输入) over the digit keys, read as full pinyin's letters; English's never
    // glides, its keys being no full keyboard's
    override val glide: GlideInput? = if (english || shuangpin) {
        null
    } else {
        glideInput(letters = { letterKeys }, down = GlideGesture.NINE_KEYS_DOWN) { path, layout ->
            // a glide ends a run of presses: a key pressed soon after it is a press of its own, not 连按's next letter
            multiTap.reset()
            onAction(KeyAction.GlideAction(Glide(path, layout)))
        }
    }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    private val multiTapPref = AppPrefs.getInstance().keyboard.t9MultiTap

    // English's 连按 is on always where it types a letter at a time: at 连按选字母's pace, or its own
    private fun tapOf(setting: T9MultiTap) =
        if (english) MultiTap(setting.timeout.takeIf { it > 0 } ?: ENGLISH_TAP_MILLIS) else multiTapOf(setting)

    private var multiTap = tapOf(multiTapPref.getValue())

    @Keep
    private val multiTapListener = ManagedPreference.OnChangeListener<T9MultiTap> { _, v ->
        multiTap = tapOf(v)
    }

    private val englishTyping = AppPrefs.getInstance().keyboard.t9EnglishTyping

    /** Set per field ([KeyboardWindow]): it asks for no suggestions, where English spells no word. */
    var noSuggestions = false

    private val shift = NineKeyShift()

    // the case of the letter 连按 is cycling through, as Shift was when its run started
    private var runUpper = false

    private val caps: ImageKeyView? by lazy { if (english) findViewById(R.id.button_caps) else null }

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        showLanguageKey()
    }

    // the language key as the user has it
    private fun showLanguageKey() {
        lang.isVisible = showLangSwitchKey.getValue()
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

    // a digit with letters (one with none the app types as it is), or a letter 连按 sends
    private fun isTypingKey(action: KeyAction) = when (action) {
        is KeyAction.FcitxKeyAction -> action.act.singleOrNull()?.let { it in '2'..'9' && letters.letters(it).isNotEmpty() } == true
        is KeyAction.SymAction -> (action.sym.sym - UNICODE).let { c ->
            c in Keyboard.LETTERS || shuangpin && c in 0..Char.MAX_VALUE.code && NineKeyLetters.NINE_SP.isCode(c.toChar())
        }
        else -> false
    }

    /** The column shows its syllables: 九键's, or 双拼's here; the code row's are not shown beside it. */
    override val ownsSyllables: Boolean get() = !english

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
        showLanguageKey()
        findViewById<TextKeyView>(R.id.button_nine_language)?.mainText?.text = languageLabel(english)
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        multiTapPref.registerOnChangeListener(multiTapListener)
        show(EngineBridge.T9State())
    }

    private var scope: CoroutineScope? = null

    // the input method whose syllables the column offers
    private val ownIm = if (shuangpin) Engines.SHUANGPIN else Engines.T9

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            if (english) {
                s.launch { EngineBridge.englishTyping.collectLatest { show(EngineBridge.T9State(composing = it)) } }
            } else {
                s.launch {
                    // another input method answering: nothing typed on the nine keys (not "no news", as a
                    // filter had it: a switch's last answer, another's, would leave the column's syllables shown)
                    EngineBridge.syllables
                        .map { if (it.im == ownIm) EngineBridge.T9State(it.composing, it.syllables, it.id) else EngineBridge.T9State() }
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
        if (state.composing != was) one.mainText.text = oneLabel(state.composing)
        val labels = labels(state, english, address)
        while (items.childCount < labels.size) items.addView(item(items.childCount))
        for (i in 0 until items.childCount) {
            val view = items.getChildAt(i) as TextView
            view.isVisible = i < labels.size
            if (view.isVisible) view.text = labels[i]
        }
        column.scrollTo(0, 0)
    }

    private fun oneLabel(composing: Boolean) = when {
        english -> if (composing) APOSTROPHE else ENGLISH_ONE_LABEL
        composing -> SEPARATOR
        else -> ONE
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
            onAction(pick(columnState, i, english, address))
        }
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        // a digit key's press as its preview counted it, when it went down
        val key = (action as? KeyAction.FcitxKeyAction)?.act?.singleOrNull()
        val time = pressed?.takeIf { it.digit == key }?.time ?: SystemClock.uptimeMillis()
        pressed = null
        if (english) return onEnglish(action, source, time)
        val out = if (shuangpin) resolveShuangpin(action, source, multiTap, time, typing) else resolve(action, source, multiTap, time, typing, letters)
        // a key the engine reads as typing: 1 right after it is the separator, though the engine
        // has not told of what is typed yet
        if (isTypingKey(out)) typing = true
        super.onAction(out, source)
    }

    private fun onEnglish(action: KeyAction, source: KeyActionListener.Source, time: Long) {
        if (action is KeyAction.CapsAction) {
            // a run of 连按 ends with it: the key pressed next is a letter of its own, in Shift's case
            multiTap.reset()
            shift.pressed(action.lock)
            showShift()
            super.onAction(action, source)
            return
        }
        val spells = EnglishLayoutPolicy.spellsWords(
            predictive = englishTyping.getValue() == T9EnglishTyping.Predictive,
            suggests = EngineBridge.englishSuggests,
            password = inPasswordField,
            address = address != AddressKeys.NONE,
            noSuggestions = noSuggestions,
        )
        val out = resolveEnglish(action, source, multiTap, time, typing, spells, shift.upper, runUpper, letters)
        if (startsLetter(out)) {
            runUpper = shift.typed()
            showShift()
            // a key English spells with: 1 right after it is an apostrophe, though the engine has not told of the word yet
            if (out !is KeyAction.CommitAction) typing = true
        }
        super.onAction(out, source)
    }

    /** Caps lock on: no sentence start changes Shift. */
    override val capsLocked: Boolean get() = shift.caps == AutoCaps.Caps.LOCK

    /** Shift on by itself where the editor [wants] a capital next, as [AutoCaps] decides; English's alone. */
    override fun applyAutoCaps(wants: Boolean) {
        if (english && shift.auto(wants)) showShift()
    }

    override fun onAttach() {
        super.onAttach()
        if (!english) return
        shift.reset()
        showShift()
    }

    // Shift's icon, said too (as the full keyboard's), and the letters on the keys in its case
    private fun showShift() {
        val key = caps ?: return
        key.img.imageResource = when (shift.caps) {
            AutoCaps.Caps.NONE -> R.drawable.ic_capslock_none
            AutoCaps.Caps.ONCE -> R.drawable.ic_capslock_once
            AutoCaps.Caps.LOCK -> R.drawable.ic_capslock_lock
        }
        ViewCompat.setStateDescription(
            key,
            when (shift.caps) {
                AutoCaps.Caps.NONE -> null
                AutoCaps.Caps.ONCE -> context.getString(R.string.a11y_state_shift_once)
                AutoCaps.Caps.LOCK -> context.getString(R.string.a11y_state_caps_lock)
            },
        )
        for (d in '2'..'9') {
            val on = letters.letters(d)
            digitKeys[d]?.mainText?.text = if (shift.upper) on.uppercase() else on.lowercase()
        }
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
                // 九键双拼 has no 连按: a key's preview is its letters
                val letter = digit?.takeUnless { shuangpin }?.let { multiTap.peek(it, letters.letters(it), now) }
                previewing = letter?.let { Previewing(action.viewId, action.content, if (!english || runUpper) it.uppercase() else it.lowercase()) }
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
