/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.children
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupOverridesCache
import splitties.views.existingOrNewId

/**
 * Pinyin on the 14 keys of two letters each (拼音双键), as iFlytek lays them out: QW ER TY UI OP,
 * AS DF GH JK L, then the separator (分词), ZX CV BN M and backspace; the full keyboard's bottom
 * row, with no 中/英 (English is the language key's round, as on the full keyboard).
 *
 * A key's tap sends its group's code ([NineKeyLetters.G14]'s, private use), which the engine reads as any of the
 * group's letters, as 九键's digits; on a key of two letters with where across it the finger came
 * down ([Keyboard.tapKey], [pressed]), so the letter on that side weighs more (讯飞's 你好 for
 * nihao); its candidates and the syllables on the code row come back
 * through the engine ([org.fcitx.fcitx5.android.core.EngineBridge.syllables]). A swipe types its
 * corner, iFlytek's: 1 to 0 on the top two rows (a keypad's digit, which picks that candidate while
 * typing), `~ ! @ ?` on the third. Its long press offers a row of its letters in capitals, the
 * corner and its letters (Q W 1 q w), the one chosen committed as it is after what is typed, and
 * above them what the full keyboard's long press of its letters offers besides. No glide, no Shift:
 * capitals are English's.
 *
 * [NineKeyLetters.G18]: 双拼's 18 keys, Q WE RT Y U IO P, A SD FG H JK L;, Z XC V BN M, laid out
 * and typed the same way, its corners 1 to 0 then marks (iFlytek has no 18 keys to follow). Their
 * taps send the code alone: where across the key it fell is the 14 keys' only so far.
 */
@SuppressLint("ViewConstructor")
class GroupedKeyboard(
    context: Context,
    theme: Theme,
    private val letters: NineKeyLetters = NineKeyLetters.G14,
) : BaseKeyboard(context, theme, layout(letters, ::symbolsOf)) {

    companion object {
        const val Name14 = "Grouped14"

        /** 双拼's 18 keys ([NineKeyLetters.G18]). */
        const val Name18 = "Grouped18"

        // iFlytek's corners, a group each in order
        internal val CORNERS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "~", "!", "@", "?")

        // the 18 keys' as the 14's: the digits from the top row on (8 9 0 on the second, a candidate
        // each), marks after them; the 14 keys' four on the third row
        internal val CORNERS18 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "(", ")", "-", "~", "!", "@", "?", ":")

        private const val SIDE = 0.15f

        /** How [letters]' groups lie: the groups in each of the three rows, a row's key widths, and their corners. */
        private class Shape(val rows: List<Int>, val widths: List<Float>, val corners: List<String>)

        private fun shapeOf(letters: NineKeyLetters) = when (letters) {
            NineKeyLetters.G14 -> Shape(listOf(5, 5, 4), listOf(0.2f, 0.2f, 0.175f), CORNERS)
            // seven on top as wide as each other, six under them, five between the separator and backspace
            NineKeyLetters.G18 -> Shape(listOf(7, 6, 5), listOf(1f / 7, 1f / 6, 0.14f), CORNERS18)
            else -> error("no grouped keyboard of ${letters.tag()}")
        }

        // the separator's label: 九键's 1 while typing
        private const val SEPARATOR = "分词"

        /** A group's code as the engine reads it: a Unicode character, as 九键's syllables are sent. */
        internal fun code(c: Char) = KeyAction.SymAction(KeySym(UNICODE + c.code), KeyStates.Virtual)

        /** [action], a group's [code], as tapped at [at] across its key ([Keyboard.tapKey]) where it has two letters and a place. */
        internal fun tapped(action: KeyAction, at: Float, letters: NineKeyLetters): KeyAction {
            val sym = (action as? KeyAction.SymAction)?.sym?.sym ?: return action
            val c = (sym - UNICODE).takeIf { it in 0..Char.MAX_VALUE.code }?.toChar() ?: return action
            // the 14 keys' taps alone have places yet (Keyboard.TAPS): the 18 keys' send the code
            if (letters != NineKeyLetters.G14) return action
            if (at.isNaN() || !letters.isCode(c) || letters.letters(c).length < 2) return action
            return KeyAction.SymAction(KeySym(UNICODE + Keyboard.tapKey(c, at)), KeyStates.Virtual)
        }

        // a digit as a keypad's, as 九键's swipe sends it: typed, or its candidate picked while typing
        private fun corner(text: String): KeyAction = text.single().let { c ->
            if (c in '0'..'9') {
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + (c - '0')), KeyStates.Virtual)
            } else {
                KeyAction.FcitxKeyAction(text)
            }
        }

        /**
         * What the full keyboard's long press of [letter] offers besides letters and digits, as the
         * user changed it: the long press's rows above a group's own.
         */
        private fun symbolsOf(letter: String): List<String> =
            PopupOverridesCache.user.current.longPressOf(letter).orEmpty().toList()

        /**
         * [group]'s long press: Q W 1 q w, the [corner] under the finger; above, its letters'
         * symbols ([symbolsOf]) but the corner and any letter or digit.
         */
        internal fun longPressOf(group: String, corner: String, symbolsOf: (String) -> List<String>): KeyDef.Popup.Keyboard.Explicit {
            // ;'s capital is :, as Shift gives it: no second ; beside the lower one
            val upper = group.map { if (it == ';') ":" else it.uppercase() }
            val lower = group.map { it.toString() }
            val symbols = group.flatMap { symbolsOf(it.toString()) }
                .filter { s -> s != corner && s !in upper && s.none { it.isLetterOrDigit() } }
                .distinct()
            return KeyDef.Popup.Keyboard.Explicit(
                (upper + corner + lower + symbols).toTypedArray(),
                symbolsFrom = 1 + 2 * group.length,
                inRow = group.length,
            )
        }

        private fun groupKey(group: String, code: Char, corner: String, width: Float, symbolsOf: (String) -> List<String>): KeyDef {
            return KeyDef(
                KeyDef.Appearance.AltText(displayText = group.uppercase(), altText = corner, textSize = 20f, percentWidth = width),
                setOf(
                    KeyDef.Behavior.Press(code(code)),
                    KeyDef.Behavior.Swipe(corner(corner)),
                ),
                arrayOf(
                    KeyDef.Popup.AltPreview(group.uppercase(), corner),
                    longPressOf(group, corner, symbolsOf),
                ),
            )
        }

        /** The keys: [letters]' groups in their rows, each with its corner and long press ([symbolsOf] a letter's symbols). */
        internal fun layout(letters: NineKeyLetters, symbolsOf: (String) -> List<String>): List<List<KeyDef>> {
            val groups = letters.tag().split(',')
            val shape = shapeOf(letters)
            val first = shape.rows.runningFold(0, Int::plus)
            fun row(r: Int) = (first[r] until first[r + 1]).map {
                groupKey(groups[it], letters.codes[it], shape.corners[it], shape.widths[r], symbolsOf)
            }
            return listOf(
                row(0),
                row(1),
                listOf(
                    KeyDef(
                        KeyDef.Appearance.Text(
                            SEPARATOR, 16f, percentWidth = SIDE,
                            variant = KeyDef.Appearance.Variant.Alternative,
                            contentDescription = R.string.a11y_key_separator,
                        ),
                        // while typing the separator; else the full keyboard's ', typed by the app
                        setOf(KeyDef.Behavior.Press(KeyAction.FcitxKeyAction("'"))),
                    ),
                ) + row(2) + BackspaceKey(SIDE),
                listOf(
                    LayoutSwitchKey("符", PickerWindow.Key.Symbol.name, 0.12f, longPress = KeyAction.QuickPhraseAction),
                    LayoutSwitchKey("123", NumberKeyboard.Name, 0.12f, longPress = KeyAction.UnicodeAction),
                    SymbolKey(",", 0.1f, KeyDef.Appearance.Variant.Alternative, viewId = R.id.button_comma),
                    AddressKey("/", R.id.button_slash),
                    AddressKey("@", R.id.button_at),
                    // what is left of the row: at least 0.15 in every field (no 中/英: the space needs the width)
                    SpaceKey(),
                    SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative, viewId = R.id.button_period),
                    AddressKey(".", R.id.button_dot),
                    AddressKey(AddressKeys.DOMAINS.first(), R.id.button_domain, 0.14f, AddressKeys.DOMAINS),
                    LanguageKey(),
                    ReturnKey(),
                ),
            )
        }

        /**
         * What [action] from [source] types: a letter or digit of a long press committed as it is
         * (as 九键's): what is typed goes in first, and an ASCII letter sent as a key would be the
         * hardware keyboard's, full pinyin's. Anything else as it is.
         */
        internal fun resolve(action: KeyAction, source: KeyActionListener.Source): KeyAction {
            if (source != KeyActionListener.Source.Popup) return action
            val c = (action as? KeyAction.FcitxKeyAction)?.act?.singleOrNull() ?: return action
            return if (c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z') KeyAction.CommitAction(c.toString()) else action
        }
    }

    private val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    private val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }
    private val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }

    /** What the bottom row has either side of the space, as the field is: as the full keyboard's ([AddressKeys]). */
    override var address: AddressKeys = AddressKeys.NONE
        set(value) {
            field = value
            showAddressKeys()
        }

    private fun showAddressKeys() {
        findViewById<View>(R.id.button_comma).isVisible = address == AddressKeys.NONE
        findViewById<View>(R.id.button_period).isVisible = address == AddressKeys.NONE
        findViewById<View>(R.id.button_dot).isVisible = address != AddressKeys.NONE
        findViewById<View>(R.id.button_slash).isVisible = address == AddressKeys.URL
        findViewById<View>(R.id.button_at).isVisible = address == AddressKeys.EMAIL
        findViewById<View>(R.id.button_domain).isVisible = address != AddressKeys.NONE
    }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        showLanguageKey()
    }

    private fun showLanguageKey() {
        lang.isVisible = showLangSwitchKey.getValue()
    }

    init {
        showAddressKeys()
        showLanguageKey()
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    // said "Q W, 1": TalkBack reads the face "QW" as a word; the corner as drawn (～ in Chinese)
    private fun describeGroups() = groupViews.forEach { (view, group) ->
        view.contentDescription = context.getString(R.string.a11y_key_grouped, group.uppercase().toList().joinToString(" "), mapped(view.corner))
    }

    private val AltTextKeyView.corner get() = (def as KeyDef.Appearance.AltText).altText

    // the group keys with their letters
    private val groupViews: List<Pair<AltTextKeyView, String>> by lazy {
        val groups = letters.tag().split(',')
        keyRows.flatMap { it.children.toList() }.filterIsInstance<AltTextKeyView>().mapNotNull { view ->
            groups.firstOrNull { it.uppercase() == (view.def as KeyDef.Appearance.AltText).displayText }?.let { view to it }
        }
    }

    // a key's popup names it by its view's id
    private val groupOf: Map<Int, Pair<String, String>> by lazy {
        groupViews.associate { (view, group) -> view.existingOrNewId to (group to view.corner) }
    }

    private var punctuationMapping: Map<String, String> = emptyMap()
    private fun mapped(p: String) = punctuationMapping.getOrDefault(p, p)

    // the corners as the full keyboard's and the comma and full stop as typed: ， and 。 in Chinese
    // (the address keys commit as they are, and say so)
    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        groupViews.forEach { (view, _) -> view.altText.text = mapped(view.corner) }
        describeGroups()
        listOf(R.id.button_comma to ",", R.id.button_period to ".").forEach { (id, p) ->
            findViewById<TextKeyView>(id).mainText.text = mapped(p)
        }
    }

    // the long press made as it opens, from the user's 「按键字符」 as they are now, as the full keyboard's Preset is;
    // public for its test, which has no touch to open one
    public override fun onPopupAction(action: PopupAction) {
        val shown = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = previewOf(action.content, action.viewId, { it }, ::mapped))
            is PopupAction.PreviewUpdateAction -> action.copy(content = previewOf(action.content, action.viewId, { it }, ::mapped))
            is PopupAction.ShowKeyboardAction -> groupOf[action.viewId]?.let { (group, corner) ->
                action.copy(keyboard = longPressOf(group, corner, ::symbolsOf))
            } ?: action
            else -> action
        }
        super.onPopupAction(shown)
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        super.onAction(resolve(action, source), source)
    }

    // a tap on a key of two letters as its code with where it fell; a click no touch made (TalkBack),
    // a swipe's, L and M as the code alone
    override fun pressed(view: CustomGestureView, action: KeyAction): KeyAction = tapped(action, view.clickAt, letters)

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        `return`.showReturnAction(appearance)
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        showInputMethodName(space, ime)
    }

    // last: describeGroups reads [groupViews] and [punctuationMapping], which an init above them would find not yet made
    init {
        describeGroups()
    }
}
