/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
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
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.TextBoardState
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.engine.Texts
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add

/**
 * Pinyin on nine keys (九键), laid out as 搜狗 does: a column on the left offering what the first
 * digits not yet taken may be (the engine's [EngineState.syllables] of 九键), or punctuation while
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
 * Pinyin's ([Name], its keyboard set to the nine keys): a digit key with letters sends its code
 * ([Keyboard.pinyinNineKey]), which pinyin's nine keys' session reads, and so does 1 while typing
 * (the separator); 0 and a digit key with no letters are digits, typed or picking.
 *
 * [english]: the nine keys of English ([EnglishName]), its keyboard set so. Its digit keys spell words (预测, [Keyboard.nineKey]: 4663 good, home,
 * gone) or type a letter at a time (连按, as [AppPrefs.Keyboard.t9EnglishTyping] sets, and where
 * English suggests nothing); Shift ([NineKeyShift]) where 重输 is; English's marks in the column
 * and on 1, an apostrophe in the word while one is typed. It never glides.
 *
 * [shuangpin]: 九键双拼 ([ShuangpinName], design §3.4), 双拼 on [NineKeyLetters.NINE_SP]'s keys
 * (JKL; on 5, for 微软's ing): a digit key sends its group's code, which the engine
 * reads as any of its letters; 1 while typing the separator `'`; the column 双拼's syllables. No
 * 连按, no glide, the letters always the standard ones. None has 中/英 (I1): the language key goes
 * round the input methods, each on its own keyboard.
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
    /** 九键双拼's 5 shows `;` (JKL;): only under a scheme that types it (微软's ing). */
    private val semicolon: Boolean = true,
) : BaseKeyboard(
    context, theme, layout(letters, symbols, english, shuangpin, semicolon), themeBoard = KeyNames.NINE, shortcuts = true,
), AutoCapsKeys {

    companion object {
        const val Name = "T9"

        /** The nine keys of English: a layout of its own, so that switching between it and [Name] shows the other. */
        const val EnglishName = "T9English"

        /** 九键双拼: a layout of its own, as [EnglishName]. */
        const val ShuangpinName = "T9Shuangpin"

        /** 九键双拼 under a scheme that types no `;`: 5 is JKL alone, its long press no `;` or `:` of it. */
        const val ShuangpinPlainName = "T9ShuangpinPlain"

        // the column's share of the width, and of the right one
        private const val SIDE = 0.16f

        // the column's name in a theme (KeyNames.NINE)
        private const val SIDE_KEY = "side"

        /** A column item's text size, in dp, before a long syllable is drawn smaller to fit. */
        internal const val ITEM_TEXT_DP = 16f

        /** The nine keys' layout for the scene engine (060-render-p2 S5a), every variant's. */
        const val LAYOUT = "nine"
        private const val RIGHT = 0.18f

        private const val DIGIT = (1f - SIDE - RIGHT) / 3

        /** Shown in the column while nothing is typed, committed as they are. */
        private val PUNCTUATION = listOf("，", "。", "？", "！", "…", "、", "：", "~", "@")

        /** What 1 types while nothing is typed: the first, pressed again soon the next in its place. */
        internal const val ONE = "，。？！"

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
        private fun EngineState.T9State.offersSyllables() = composing && syllables.isNotEmpty()

        /**
         * The column's items for [state]: its syllables while typing, else punctuation; [english]'s
         * marks; in an address field ([address]) what addresses are made of ([AddressKeys.column]),
         * pinyin's syllables still while typing.
         */
        internal fun labels(state: EngineState.T9State, english: Boolean = false, address: AddressKeys = AddressKeys.NONE): List<String> = when {
            !english && state.offersSyllables() -> state.syllables
            address != AddressKeys.NONE -> AddressKeys.column(address)
            english -> ENGLISH_PUNCTUATION
            else -> PUNCTUATION
        }

        /**
         * What picking item [i] of [state]'s column types: for [english], the mark through English, the
         * word typed ended by it; an address's, committed as it is.
         */
        internal fun pick(state: EngineState.T9State, i: Int, english: Boolean = false, address: AddressKeys = AddressKeys.NONE): KeyAction =
            if (!english && state.offersSyllables()) {
                // with the list's id: shown before a key typed since replaced it, the pick is dropped
                KeyAction.SymAction(KeySym(UNICODE + Keyboard.syllableKey(i, state.id).code), KeyStates.Virtual)
            } else if (address != AddressKeys.NONE) {
                KeyAction.CommitAction(AddressKeys.column(address)[i])
            } else if (english) {
                KeyAction.HostKeyAction(ENGLISH_PUNCTUATION[i])
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
            val key = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull()
            if (source == KeyActionListener.Source.Popup || key == null || key !in '1'..'9') return resolve(action, source, tap, time, composing)
            if (key == '1') {
                if (!composing) return resolve(action, source, tap, time, composing = false)
                tap.reset()
                // the engine's 双拼 separates with ', a digit being no key of it
                return KeyAction.HostKeyAction("'")
            }
            tap.reset()
            return codeOf(key)
        }

        /**
         * What pinyin's nine keys send for [action] as [resolve] gave it: a digit key with [letters]
         * its code ([Keyboard.pinyinNineKey]), which pinyin reads on the nine keys alone; while
         * [typing] every digit its code, 1 the separator, 0 and a key with no letters ending the input
         * as 九键拼音's did (the first candidate, then the digit). Sent as the digit itself, pinyin would
         * read it as a hardware keyboard's: the candidate it numbers picked. Any other as it is.
         */
        internal fun coded(action: KeyAction, typing: Boolean, letters: NineKeyLetters): KeyAction {
            val key = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull() ?: return action
            val code = key.isDigit() && typing || key in '2'..'9' && letters.letters(key).isNotEmpty()
            return if (code) KeyAction.SymAction(KeySym(UNICODE + Keyboard.pinyinNineKey(key)), KeyStates.Virtual) else action
        }

        /** The code of 九键双拼's digit key [digit]: its group's, sent as a Unicode character. */
        internal fun codeOf(digit: Char) =
            KeyAction.SymAction(KeySym(UNICODE + NineKeyLetters.NINE_SP.codes[digit - '2'].code), KeyStates.Virtual)

        /** A digit as a keypad's, as the number keyboard's: typed, or its candidate picked while typing (EngineEvent.KEYPAD). */
        private fun keypad(digit: Char) = KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + (digit - '0')), KeyStates.Virtual)

        /**
         * Digit key [digit]'s long press (the views' and nine.xml's `popup from="nine"`): a row of its
         * [letters]' capitals, the digit, its letters (A B C 2 a b c), the digit under the finger; its
         * [symbols] in rows above.
         */
        internal fun ninePopup(digit: Char, letters: String, symbols: List<String>) = KeyDef.Popup.Keyboard.Explicit(
            // ;'s capital is : (九键双拼's JKL;), as Shift gives it: no second ;
            letters.map { if (it == ';') ":" else it.uppercase() }.toTypedArray() + digit.toString() +
                letters.lowercase().map { it.toString() } + symbols.filter { it != ":" || ';' !in letters },
            symbolsFrom = 1 + 2 * letters.length,
            inRow = letters.length,
        )

        /**
         * 1's long press: its symbols alone, none set apart: it has no letters; typed as they are, not
         * through the engine, where . would be 。 English's its own, none of them full width. Null with none.
         */
        internal fun onePopup(english: Boolean, symbols: NineKeySymbols) =
            (if (english) ENGLISH_ONE_SYMBOLS else symbols.symbols('1')).takeIf { it.isNotEmpty() }?.let {
                KeyDef.Popup.Keyboard.Explicit(it.toTypedArray(), asIs = true)
            }

        /** The letters on digit key [d] (2 to 9), as [layout] puts them. */
        internal fun lettersOn(d: Char, letters: NineKeyLetters, english: Boolean, shuangpin: Boolean, semicolon: Boolean) = when {
            shuangpin -> NineKeyLetters.NINE_SP.letters(NineKeyLetters.NINE_SP.codes[d - '2']).uppercase().let { if (semicolon) it else it.replace(";", "") }
            english -> letters.letters(d).lowercase()
            else -> letters.letters(d).uppercase()
        }

        private fun digitKey(digit: Char, letters: String, symbols: List<String>) = KeyDef(
            KeyDef.Appearance.AltText(displayText = letters, altText = digit.toString(), textSize = 20f, percentWidth = DIGIT),
            setOf(
                KeyDef.Behavior.Press(KeyAction.HostKeyAction(digit.toString())),
                KeyDef.Behavior.Swipe(keypad(digit)),
            ),
            arrayOf(
                KeyDef.Popup.AltPreview(letters, digit.toString()),
                ninePopup(digit, letters, symbols),
            ),
            name = digit.toString(),
            shortcut = KeyShortcuts.charKey(KeyShortcutPreset.NINE, digit.toString()),
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
            val key = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull()
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
            val key = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull()
            if (key == '1' && source != KeyActionListener.Source.Popup) {
                if (composing) {
                    tap.reset()
                    return KeyAction.HostKeyAction(APOSTROPHE)
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
            is KeyAction.HostKeyAction -> action.act.singleOrNull()?.isLetter() == true
            is KeyAction.CommitAction -> action.replacing.isEmpty() && action.text.singleOrNull()?.isLetter() == true
            else -> false
        }

        /** The run [setting] times: off, every press of a digit key or 1 is a key of its own. */
        internal fun multiTapOf(setting: T9MultiTap) = MultiTap(setting.timeout)

        // the column is drawn over these placeholders, so they are never touched; a theme names the
        // column by the first ([name]), its look one for the whole column
        private fun spacer(name: String? = null) = KeyDef(
            KeyDef.Appearance.Text("", 16f, percentWidth = SIDE, border = KeyDef.Appearance.Border.Off, margin = false),
            emptySet(),
            name = name,
        )

        /**
         * The keys, each digit key with [letters]' letters on it; [english]'s in lower case, Shift for
         * 重输; [shuangpin]'s [NineKeyLetters.NINE_SP]'s (5's `;` left off but with [semicolon]), and no 中/英.
         */
        internal fun layout(
            letters: NineKeyLetters,
            symbols: NineKeySymbols = NineKeySymbols.STANDARD,
            english: Boolean = false,
            shuangpin: Boolean = false,
            semicolon: Boolean = true,
        ): List<List<KeyDef>> {
            fun on(d: Char) = lettersOn(d, letters, english, shuangpin, semicolon)
            return layoutOf(::on, symbols, english)
        }

        private fun layoutOf(on: (Char) -> String, symbols: NineKeySymbols, english: Boolean): List<List<KeyDef>> = listOf(
            listOf(
                spacer(SIDE_KEY),
                KeyDef(
                    KeyDef.Appearance.AltText(displayText = if (english) ENGLISH_ONE_LABEL else ONE, altText = "1", textSize = 16f, percentWidth = DIGIT),
                    setOf(
                        KeyDef.Behavior.Press(KeyAction.HostKeyAction("1")),
                        KeyDef.Behavior.Swipe(keypad('1')),
                    ),
                    onePopup(english, symbols)?.let { arrayOf(it) },
                    name = "1",
                    // a character key, as the digits are: its swipe types 1 as ever
                    shortcut = KeyShortcuts.charKey(KeyShortcutPreset.NINE, "1"),
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
                        name = "reset",
                        shortcut = "retype",
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
                    setOf(KeyDef.Behavior.Press(KeyAction.HostKeyAction("0"))),
                    name = "0",
                    shortcut = "zero",
                ),
            ),
            listOf(
                LayoutSwitchKey("符", PickerWindow.Key.Symbol.name, SIDE, longPress = KeyAction.QuickPhraseAction),
                LayoutSwitchKey("123", NumberKeyboard.Name, 0.14f),
                SpaceKey(),
                LanguageKey(),
                ReturnKey(RIGHT),
            ),
        )
    }

    /** The field's address: what the column offers in place of punctuation ([AddressKeys.column]). */
    override var address: AddressKeys = AddressKeys.NONE
        set(value) {
            if (field == value) return
            field = value
            show(columnState)
        }

    // each letter where the scene draws its digit's key (nine.xml's key N)
    private val sceneLetters: List<Pair<Char, KeyPlace>> = ('2'..'9').flatMap { d ->
        scenePlace(d.toString()).let { at -> letters.letters(d).map { it.lowercaseChar() to at } }
    }

    // a digit key's long press (nine.xml's `popup from="nine"`), as the views' (ninePopup, onePopup)
    private val ninePopups: Map<String, KeyDef.Popup.Keyboard> by lazy {
        ('2'..'9').associate { d -> "$d" to ninePopup(d, lettersOn(d, letters, english, shuangpin, semicolon), symbols.symbols(d)) } +
            listOfNotNull(onePopup(english, symbols)?.let { "1" to it })
    }

    override val sceneTable = object : SceneKeyDef.Table {
        override fun popup(from: String, node: KeyNode): KeyDef.Popup.Keyboard? = if (from == "nine") ninePopups[node.id] else null
    }

    // glide typing (滑行输入) over the digit keys, read as full pinyin's letters, and a stroke drawn
    // over them (笔画筛选) in pinyin's and 双拼's; English's never glides, its keys being no full
    // keyboard's, nor 双拼's, which reads no letters glided
    override val glide: GlideInput? = if (english) {
        null
    } else {
        // the keys where the scene draws them (their centres, GlideGesture's)
        glideInput(letters = { sceneLetters }, down = GlideGesture.NINE_KEYS_DOWN, enabled = { !shuangpin }, filters = { InputMethodCatalog.filtersByStrokes(imeName) }) { path, layout ->
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

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        showLanguageKey()
    }

    // the language key as the user has it
    private fun showLanguageKey() {
        canvas?.invalidateScene()
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
        // its face is the scene's slot's (nine.xml's `side`): the column draws only its items, which take the press
        val keys = ThemeManager.shownLook(theme).keys
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val h = dp(if (landscape) keys.gap.hLand else keys.gap.h)
        val v = dp(if (landscape) keys.gap.vLand else keys.gap.v)
        setPadding(h, v, h, v)
        addView(items)
    }

    private var itemHeight = 0

    /** What the column shows, read by its items when picked. */
    private var columnState = EngineState.T9State()

    // something typed, as the engine last told, or a key sent since that types
    private var typing = false

    // a digit with letters (one with none the app types as it is), or a letter 连按 sends
    private fun isTypingKey(action: KeyAction) = when (action) {
        is KeyAction.HostKeyAction -> action.act.singleOrNull()?.let { it in '2'..'9' && letters.letters(it).isNotEmpty() } == true
        is KeyAction.SymAction -> (action.sym.sym - UNICODE).let { c ->
            c in Keyboard.LETTERS || shuangpin && c in 0..Char.MAX_VALUE.code && NineKeyLetters.NINE_SP.isCode(c.toChar())
        }
        else -> false
    }

    /** The column shows its syllables: 九键's, or 双拼's here; the code row's are not shown beside it. */
    override val ownsSyllables: Boolean get() = !english

    init {
        // placed in onLayout, not shown before
        column.isInvisible = true
        add(column, lParams(0, 0))
        showLanguageKey()
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        multiTapPref.registerOnChangeListener(multiTapListener)
        show(EngineState.T9State())
    }

    private var scope: CoroutineScope? = null

    // the input method whose syllables the column offers
    private val ownIm = if (shuangpin) InputMethodCatalog.SHUANGPIN else InputMethodCatalog.PINYIN

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            if (english) {
                s.launch { EngineState.englishTyping.collectLatest { show(EngineState.T9State(composing = it)) } }
            } else {
                s.launch {
                    // another input method answering: nothing typed on the nine keys (not "no news", as a
                    // filter had it: a switch's last answer, another's, would leave the column's syllables shown)
                    EngineState.syllables
                        .map { if (it.im == ownIm) EngineState.T9State(it.composing, it.syllables, it.id) else EngineState.T9State() }
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

    // the keyboard takes every touch for a key (BaseKeyboard.onInterceptTouchEvent): the column's are the column's
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        column.getHitRect(columnHit)
        if (columnHit.contains(ev.x.toInt(), ev.y.toInt())) return false
        return super.onInterceptTouchEvent(ev)
    }

    /** The column for [state]: its syllables while typing, else punctuation; its items kept, made only when more are needed. */
    internal fun show(state: EngineState.T9State) {
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
        val labels = labels(state, english, address)
        while (items.childCount < labels.size) items.addView(item(items.childCount))
        for (i in 0 until items.childCount) {
            val view = items.getChildAt(i) as TextView
            view.isVisible = i < labels.size
            if (view.isVisible) view.text = labels[i]
        }
        column.scrollTo(0, 0)
        if (state.composing != was) canvas?.invalidateScene()
    }

    // one line, in dp as the keys' text is: a syllable longer than the column (shuang, zhuang on a
    // narrow one) is drawn smaller to fit, not wrapped onto a second line the cell cuts off (A7)
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
            InputFeedbacks.soundEffect(InputFeedbacks.SoundEffect.Standard)
            onAction(pick(columnState, i, english, address))
        }
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        // a digit key's press as its preview counted it, when it went down
        val key = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull()
        val time = pressed?.takeIf { it.digit == key }?.time ?: SystemClock.uptimeMillis()
        pressed = null
        if (english) return onEnglish(action, source, time)
        val out = if (shuangpin) resolveShuangpin(action, source, multiTap, time, typing) else resolve(action, source, multiTap, time, typing, letters)
        // a key the engine reads as typing: 1 right after it is the separator, though the engine
        // has not told of what is typed yet
        if (isTypingKey(out)) typing = true
        super.onAction(if (shuangpin) out else coded(out, typing, letters), source)
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
        val spells = ImKeyboards.spellsWords(
            predictive = englishTyping.getValue() == T9EnglishTyping.Predictive,
            suggests = EngineState.englishSuggests,
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
        canvas?.invalidateScene()
    }

    /**
     * The cursor moved, not by this keyboard's own commit (a tap in the text, the app): 1 pressed
     * next is a symbol of its own, not one in place of a ， now before the cursor.
     */
    fun cursorMoved() = multiTap.reset()

    /** A digit key gone down at [time]: its press counts from then, as its preview did. */
    private class Pressed(val digit: Char, val time: Long)

    /** The [letter] the preview of the key [key] shows in place of its own [letters] (GHI), while it is held. */
    private class Previewing(val key: KeyRef, val letters: String, val letter: String)

    private var previewing: Previewing? = null

    // the digit key gone down last
    private var pressed: Pressed? = null

    // a digit key's preview shows the letter pressing it again now types
    override fun onPopupAction(action: PopupAction) {
        val shown = when (action) {
            is PopupAction.PreviewAction -> {
                // the scene's key by its id there, the digit (nine.xml)
                val digit = action.key.id.singleOrNull()?.takeIf { it in '1'..'9' }
                val now = SystemClock.uptimeMillis()
                pressed = digit?.let { Pressed(it, now) }
                // 九键双拼 has no 连按: a key's preview is its letters
                val letter = digit?.takeUnless { shuangpin }?.let { multiTap.peek(it, letters.letters(it), now) }
                previewing = letter?.let { Previewing(action.key, action.content, if (!english || runUpper) it.uppercase() else it.lowercase()) }
                previewing?.let { action.copy(content = it.letter) }
            }
            // back from the digit a swipe shows
            is PopupAction.PreviewUpdateAction ->
                previewing?.takeIf { it.key == action.key && it.letters == action.content }?.let { action.copy(content = it.letter) }
            else -> null
        }
        super.onPopupAction(shown ?: action)
    }

    private fun pressHighlight() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(theme.keyPressHighlightColor))
    }

    // Return as last shown: its icon on the scene's key too
    private var returnAppearance = ReturnKeyAppearance.Enter

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        returnAppearance = appearance
        canvas?.invalidateScene()
    }

    // the input method shown, for whether a stroke is drawn (笔画筛选)
    private var imeName = ""

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        imeName = ime.uniqueName
        showInputMethodName(ime)
        canvas?.invalidateScene()
    }

    // what the keys show now (nine.xml's when= and bindings): English's or not, 1 as what the engine
    // says is typed, each digit's letters (the user's, 双拼's, Shift's
    // case), Shift, the language key, the space's input method, Return
    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
        val state = TextBoardState(langKey = showLangSwitchKey.getValue(), holdVoice = holdsToTalk)
        val input = state.input().copy(ime = if (english) "english" else state.input().ime, composing = columnState.composing)
        val texts = Texts(
            imeName = shownImeName.orEmpty(),
            returnLabel = returnAppearance.label(context).toString(),
            returnIcon = SceneIcons.returnName(returnAppearance.icon),
            nineLetters = ('2'..'9').associate { d -> d.toString() to lettersOn(d, letters, english, shuangpin, semicolon).let { if (english && shift.upper) it.uppercase() else it } },
            a11yGrouped = context.getString(R.string.a11y_key_grouped),
            a11ySpace = context.getString(R.string.a11y_key_space_with_ime),
        )
        val caps = when {
            !english -> Shift.None
            else -> when (shift.caps) {
                AutoCaps.Caps.NONE -> Shift.None
                AutoCaps.Caps.ONCE -> Shift.Once
                AutoCaps.Caps.LOCK -> Shift.Lock
            }
        }
        return SceneEnvs.of(this, look, theme.isDark, width, height, input = input, shift = caps, texts = texts, marks = SceneBoard.marks(layout, charShortcutMarks))
    }

    private val columnSlot = Rect()

    // the column where the scene has its slot `side` (nine.xml), over the canvas, in the same pass as the canvas (as
    // HandwritingKeyboard's pad): never a frame elsewhere; no scene (060-render-p2 U8), the column not shown either
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val c = canvas ?: return
        val placed = c.slot(SIDE_KEY, columnSlot)
        column.isInvisible = !placed
        if (!placed) return
        columnSlot.offset(c.left, c.top)
        val exactly = { size: Int -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) }
        if (column.width != columnSlot.width() || column.height != columnSlot.height() || column.isLayoutRequested) {
            column.measure(exactly(columnSlot.width()), exactly(columnSlot.height()))
        }
        column.layout(columnSlot.left, columnSlot.top, columnSlot.right, columnSlot.bottom)
    }

    // last: the scene reads the state above (the keys drawn by the scene engine, each wired as nine.xml's
    // node says; the column a native view in its slot)
    init {
        drawKeysWithScene(LAYOUT)
        column.bringToFront()
    }
}
