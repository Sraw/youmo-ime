/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.Keep
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupOverridesCache
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.SceneAction
import org.fcitx.fcitx5.android.input.scene.SceneBehavior
import org.fcitx.fcitx5.android.input.scene.SceneGroups
import org.fcitx.fcitx5.android.input.scene.TextBoardState
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.LetterCaseRule
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import timber.log.Timber

/**
 * Pinyin on the 14 keys of two letters each (拼音双键), as iFlytek lays them out: QW ER TY UI OP,
 * AS DF GH JK L, then the separator (分词), ZX CV BN M and backspace; the full keyboard's bottom
 * row, with no 中/英 (English is the language key's round, as on the full keyboard).
 *
 * A key's tap sends its group's code ([NineKeyLetters.G14]'s, private use), which the engine reads as any of the
 * group's letters, as 九键's digits; on a key of two letters with where across it the finger came
 * down ([Keyboard.tapKey], [pressed]), so the letter on that side weighs more (讯飞's 你好 for
 * nihao); its candidates and the syllables on the code row come back
 * through the engine ([EngineState.syllables]). A swipe types its
 * corner, iFlytek's: 1 to 0 on the top two rows (a keypad's digit, which picks that candidate while
 * typing), `~ ! @ ?` on the third. Its long press offers a row of its letters in capitals, the
 * corner and its letters (Q W 1 q w), the one chosen committed as it is after what is typed, and
 * above them what the full keyboard's long press of its letters offers besides. No glide, no Shift:
 * capitals are English's; but a stroke drawn over them while pinyin is typed (笔画筛选) filters
 * the candidates as on the full keyboard.
 *
 * [NineKeyLetters.G18]: 双拼's 18 keys, Q WE RT Y U IO P, A SD FG H JK L;, Z XC V BN M, laid out
 * and typed the same way, its corners 1 to 0 then marks (iFlytek has no 18 keys to follow). Their
 * taps send the code alone: where across the key it fell is the 14 keys' only so far.
 *
 * [wire]: what a group's code goes out as. Pinyin's 14 keys and 双拼's 18 send the group's code itself;
 * pinyin's 18 keys ([NamePinyin18]) and 双拼's 14 ([NameShuangpin14]) send codes of their own
 * ([Keyboard.pinyin18Key], [Keyboard.shuangpin14Key]), each read by its input method's own session
 * of that keyboard (I1), no tap places. [semicolon]: whether a group shows its `;` (双拼's, ing to 微软); pinyin's 18 keys have none, their L key L alone, its long press no `;` or `:`.
 *
 * The keys are its layout's (`k14`, `k18`, a theme's in their place), their groups checked as it is made
 * ([grouping], SceneGroups.taken; 060-render-p3 T9b): the built-in groups, typed as above; where
 * `ownGroups` (pinyin's keyboards), a layout's own groups (a theme's 17 keys), typed on a keyboard of their
 * own ([NineKeyLetters.custom]: codes of its own, sent as they are, a tap's place across and down its key,
 * [EngineState.ownKeys] telling the engine); any other grouping logged and gone by, the built-in layout drawn.
 */
@SuppressLint("ViewConstructor")
class GroupedKeyboard private constructor(
    context: Context,
    theme: Theme,
    // the built-in keyboard this one is (G14, G18): its layout's id, its theme board, its keys' names
    builtIn: NineKeyLetters,
    // named apart from the properties below, which an initializer would otherwise not see (review L4: the
    // 18 keys' wire reached a layout's own codes through sceneTable)
    builtInWire: (Char) -> Int,
    builtInSemicolon: Boolean,
    private val grouping: Grouping,
) : BaseKeyboard(
    context, theme, layout(builtIn, builtInWire, builtInSemicolon, ::symbolsOf),
    themeBoard = shapeOf(builtIn).board,
    shortcuts = true,
) {

    constructor(
        context: Context,
        theme: Theme,
        letters: NineKeyLetters = NineKeyLetters.G14,
        wire: (Char) -> Int = Char::code,
        semicolon: Boolean = true,
        /** Whether a layout's own groups are typed as such (pinyin's 14 and 18 keys); else they are gone by, as 双拼's. */
        ownGroups: Boolean = false,
    ) : this(context, theme, letters, wire, semicolon, grouping(context, letters, ownGroups))

    // what the engine reads this keyboard's codes and record by: the built-in groups', or the layout's own
    private val letters: NineKeyLetters = grouping.letters

    // a layout's own groups' codes go as they are (NineKeyLetters.CUSTOM_BLOCK); the built-in ones' as [wire] has them
    private val wire: (Char) -> Int = if (grouping.own != null) Char::code else builtInWire

    // a layout's own groups shown as written, a `;` among them too
    private val semicolon: Boolean = builtInSemicolon || grouping.own != null

    // the groups in the engine's order: a layout's own key's code is its group's place among them
    private val groups: List<String> = letters.tag().split(',')

    // a layout's own groups' keys, in the same order: the place of a key whose letters another key has too (review L3)
    private val ownIds: List<String> = grouping.layout?.takeIf { grouping.own != null }?.let { SceneGroups.ids(it.layout, it.fragments) }.orEmpty()

    companion object {
        const val Name14 = "Grouped14"

        /** 双拼's 18 keys ([NineKeyLetters.G18]) under a scheme that types `;` (微软's ing): L; shows it. */
        const val Name18 = "Grouped18"

        /** 双拼's 18 keys under any other scheme: L alone, as pinyin's, its code the same. */
        const val Name18Plain = "Grouped18Plain"

        /** Pinyin's 18 keys: [NineKeyLetters.G18]'s groups, sent as [Keyboard.pinyin18Key]. */
        const val NamePinyin18 = "GroupedPinyin18"

        /** 双拼's 14 keys: [NineKeyLetters.G14]'s groups, sent as [Keyboard.shuangpin14Key]. */
        const val NameShuangpin14 = "GroupedShuangpin14"

        /**
         * How a grouped keyboard types and draws its layout ([grouping]): [letters] what the engine reads its
         * codes by, [own] a layout's own groups as written (null for the built-in ones), [layout] what is drawn.
         */
        internal class Grouping(val letters: NineKeyLetters, val own: List<String>?, val layout: SceneLayout?)

        /**
         * The layout of the keyboard [builtIn] (G14, G18) as its source has it, its groups held to [builtIn]'s
         * (SceneGroups.taken, 060-render-p3 T9b): [builtIn]'s; a layout's own where [own] lets them be; any other
         * logged with why, and the layout as the app has it drawn instead ([SceneLayouts.builtIn]), the block gone by.
         */
        internal fun grouping(context: Context, builtIn: NineKeyLetters, own: Boolean, log: Boolean = true): Grouping {
            val id = layoutOf(builtIn)
            val shown = SceneLayouts.of(context, id) ?: return Grouping(builtIn, null, null)
            return when (val taken = SceneGroups.taken(shown.layout, builtIn, own, shown.fragments)) {
                SceneGroups.Taken.BuiltIn -> Grouping(builtIn, null, shown)
                is SceneGroups.Taken.Own -> Grouping(taken.letters, taken.written, shown)
                is SceneGroups.Taken.Refused -> {
                    // a settings page asks on each refresh ([KeyboardLabels]): the keyboard made logs it
                    if (log) Timber.e("scene layout %s: %s", id, taken.why)
                    Grouping(builtIn, null, SceneLayouts.builtIn(context, id))
                }
            }
        }

        // iFlytek's corners, a group each in order
        internal val CORNERS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "~", "!", "@", "?")

        // the 18 keys' as the 14's: the digits from the top row on (8 9 0 on the second, a candidate
        // each), marks after them; the 14 keys' four on the third row
        internal val CORNERS18 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "(", ")", "-", "~", "!", "@", "?", ":")

        private const val SIDE = 0.15f

        /**
         * How [letters]' groups lie: the groups in each of the three rows, a row's key widths, and their corners;
         * the keyboard's name in a theme ([board]) and its keys' shortcuts' ([preset]).
         */
        private class Shape(val rows: List<Int>, val widths: List<Float>, val corners: List<String>, val board: String, val preset: String)

        private fun shapeOf(letters: NineKeyLetters) = when (letters) {
            NineKeyLetters.G14 -> Shape(listOf(5, 5, 4), listOf(0.2f, 0.2f, 0.175f), CORNERS, KeyNames.K14, KeyShortcutPreset.GROUPED14)
            // seven on top as wide as each other, six under them, five between the separator and backspace
            NineKeyLetters.G18 -> Shape(listOf(7, 6, 5), listOf(1f / 7, 1f / 6, 0.14f), CORNERS18, KeyNames.K18, KeyShortcutPreset.GROUPED18)
            else -> error("no grouped keyboard of ${letters.tag()}")
        }

        // the separator's label: 九键's 1 while typing
        private const val SEPARATOR = "分词"

        /** A group's code as the engine reads it: a Unicode character, as 九键's syllables are sent. */
        internal fun code(c: Char, wire: (Char) -> Int = Char::code) = KeyAction.SymAction(KeySym(UNICODE + wire(c)), KeyStates.Virtual)

        /**
         * [action], a group's [code], as tapped at [at] across its key ([Keyboard.tapKey]) where its
         * layout has the group lean ([leaning]: its key's lean=, 060-render-v2 §15) and the engine
         * places a tap of its code (the 14 keys' and a layout's own groups': the 18 keys' send the code).
         * A layout's own group's code goes with where [down] the key it fell too (060-render-p3 T9b), its
         * cell the scene's; a click no touch made (NaN), its code alone.
         */
        internal fun tapped(action: KeyAction, at: Float, letters: NineKeyLetters, leaning: Set<String>, down: Float = Float.NaN): KeyAction {
            val sym = (action as? KeyAction.SymAction)?.sym?.sym ?: return action
            val c = (sym - UNICODE).takeIf { it in 0..Char.MAX_VALUE.code }?.toChar() ?: return action
            val leans = letters.isCode(c) && letters.letters(c) in leaning && Keyboard.placesTaps(c)
            if (at.isNaN() || !leans) return action
            val own = c.code in NineKeyLetters.CUSTOM_BLOCK
            if (own && down.isNaN()) return action
            val sent = if (own) Keyboard.tapKey(c, at, down) else Keyboard.tapKey(c, at)
            return KeyAction.SymAction(KeySym(UNICODE + sent), KeyStates.Virtual)
        }

        // a digit as a keypad's, as 九键's swipe sends it: typed, or its candidate picked while typing
        private fun corner(text: String): KeyAction = text.single().let { c ->
            if (c in '0'..'9') {
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + (c - '0')), KeyStates.Virtual)
            } else {
                KeyAction.HostKeyAction(text)
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
            // a key of no corner (a layout's own that swipes nothing): Q W q w, its first lower case letter under the finger
            val corners = if (corner.isEmpty()) emptyList() else listOf(corner)
            return KeyDef.Popup.Keyboard.Explicit(
                (upper + corners + lower + symbols).toTypedArray(),
                symbolsFrom = corners.size + 2 * group.length,
                inRow = group.length,
            )
        }

        // named by its place ([name], r1c1…): the same key whatever letters a scheme puts on it
        @Suppress("LongParameterList")
        private fun groupKey(form: String, group: String, code: Char, corner: String, width: Float, symbolsOf: (String) -> List<String>, wire: (Char) -> Int, name: String): KeyDef {
            return KeyDef(
                KeyDef.Appearance.AltText(displayText = group.uppercase(), altText = corner, textSize = 20f, percentWidth = width),
                setOf(
                    KeyDef.Behavior.Press(code(code, wire)),
                    KeyDef.Behavior.Swipe(corner(corner)),
                ),
                arrayOf(
                    KeyDef.Popup.AltPreview(group.uppercase(), corner),
                    longPressOf(group, corner, symbolsOf),
                ),
                name = name,
                // its letters, a `;` of 18's L; left off or not
                shortcut = KeyShortcuts.charKey(form, group.lowercase().replace(";", "")),
            )
        }

        /** The layouts their keys are drawn from (lib/keyboard-scene's k14.xml, k18.xml). */
        internal fun layoutOf(letters: NineKeyLetters): String = shapeOf(letters).board

        /** [letters]' groups as their keys show them: without `;` unless [semicolon]. */
        private fun groupsOf(letters: NineKeyLetters, semicolon: Boolean) =
            letters.tag().split(',').map { if (semicolon) it else it.replace(";", "") }

        /**
         * The keys: [letters]' groups in their rows, each with its corner and long press ([symbolsOf] a
         * letter's symbols), sent as [wire] has them; a group's `;` left off but with [semicolon].
         */
        internal fun layout(letters: NineKeyLetters, wire: (Char) -> Int = Char::code, semicolon: Boolean = true, symbolsOf: (String) -> List<String>): List<List<KeyDef>> {
            val groups = groupsOf(letters, semicolon)
            val shape = shapeOf(letters)
            val first = shape.rows.runningFold(0, Int::plus)
            fun row(r: Int) = (first[r] until first[r + 1]).map {
                groupKey(
                    shape.preset,
                    groups[it], letters.codes[it], shape.corners[it], shape.widths[r], symbolsOf, wire, "r${r + 1}c${it - first[r] + 1}",
                )
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
                        setOf(KeyDef.Behavior.Press(KeyAction.HostKeyAction("'"))),
                        name = "apostrophe",
                        shortcut = "separator",
                    ),
                ) + row(2) + BackspaceKey(SIDE),
                bottomRow(),
            )
        }

        /** The bottom row, the zhuyin keyboards' too: 符 123 , (or the address keys) space . 中/英 return. */
        internal fun bottomRow(): List<KeyDef> = listOf(
            LayoutSwitchKey("符", PickerWindow.Key.Symbol.name, 0.12f, longPress = KeyAction.QuickPhraseAction),
            LayoutSwitchKey("123", NumberKeyboard.Name, 0.12f, longPress = KeyAction.UnicodeAction),
            SymbolKey(",", 0.1f, KeyDef.Appearance.Variant.Alternative, viewId = R.id.button_comma, shortcut = "comma"),
            AddressKey("/", R.id.button_slash),
            AddressKey("@", R.id.button_at),
            // what is left of the row: at least 0.15 in every field (no 中/英: the space needs the width)
            SpaceKey(),
            SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative, viewId = R.id.button_period, shortcut = "period"),
            AddressKey(".", R.id.button_dot),
            AddressKey(AddressKeys.DOMAINS.first(), R.id.button_domain, 0.14f, AddressKeys.DOMAINS),
            LanguageKey(),
            ReturnKey(),
        )

        /**
         * What [action] from [source] types: a letter or digit of a long press committed as it is
         * (as 九键's): what is typed goes in first, and an ASCII letter sent as a key would be the
         * hardware keyboard's, full pinyin's. Anything else as it is.
         */
        internal fun resolve(action: KeyAction, source: KeyActionListener.Source): KeyAction {
            if (source != KeyActionListener.Source.Popup) return action
            val c = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull() ?: return action
            return if (c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z') KeyAction.CommitAction(c.toString()) else action
        }
    }

    /** What the bottom row has either side of the space, as the field is: as the full keyboard's ([AddressKeys]). */
    override var address: AddressKeys = AddressKeys.NONE
        set(value) {
            field = value
            canvas?.invalidateScene()
        }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        canvas?.invalidateScene()
    }

    init {
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    // the groups' keys where the scene draws them: each key's letters= (060-render-v2 §15), its first letter's
    private fun groupPlaces(): List<Pair<Char, KeyPlace>> =
        canvas?.shownKeys.orEmpty().mapNotNull { k -> k.letters?.let { it.group[0] to scenePlace(k.id, k.copy == 1) } }

    // no glide: a stroke drawn alone (笔画筛选), over the group keys, each its first letter's
    override val glide = glideInput(
        letters = { groupPlaces() },
        enabled = { false },
        filters = { InputMethodCatalog.filtersByStrokes(imeName) },
        writes = { InputMethodCatalog.writesOnKeys(imeName) },
    ) { _, _ -> }

    // the code of the key [id] of [group]'s letters: the built-in keyboard's of its first letter (as always), a
    // layout's own its key's place among the engine's groups (a letter may be on more than one key of its own,
    // the same letters too); a key not found by its id (one of a when=, its id another's too), its group's first place
    private fun codeOf(group: String, id: String): Char? = if (grouping.own == null) {
        letters.key(group[0])
    } else {
        val place = ownIds.indexOf(id).takeIf { it >= 0 && groups.getOrNull(it) == group } ?: groups.indexOf(group)
        place.takeIf { it >= 0 }?.let(letters.codes::get)
    }

    // the group and corner of the key [ref] whose long press is its letters' (no popup written, or from="group"):
    // by its node's letters= and swipe; null for one whose layout wrote its own (060-render-p3 T9b)
    private fun groupOf(ref: KeyRef): Pair<String, String>? =
        canvas?.keyAt(ref.id, if (ref.copy) 1 else 0)?.takeIf { n -> n.popup.let { it == null || it.from == "group" } }?.let(::groupOfNode)

    private fun groupOfNode(node: KeyNode): Pair<String, String>? = node.letters?.let { shown(it.group) to cornerOf(node) }

    // a group's letters as its key shows them: pinyin's 18 keys' L without the `;` the engine reads it by
    private fun shown(group: String) = if (semicolon) group else group.replace(";", "")

    // the corner a group key swipes (its keypad digit, its mark as typed: ~, not the ～ drawn)
    private fun cornerOf(node: KeyNode): String =
        node.actions["swipe-up"]?.let(SceneAction::of)?.let(SceneBehavior::typed).orEmpty()

    // a group key's (letters=) code and long press (060-render-v2 §15): the code of the engine's group its
    // first letter is in, sent as [wire] has it (a tap's place added by [pressed]); its letters around its
    // corner, made again as it opens ([onPopupAction])
    override val sceneTable = object : SceneKeyDef.Table {
        override fun group(letters: String, node: KeyNode): KeyAction? = codeOf(letters, node.id)?.let { code(it, wire) }

        override fun groupPopup(letters: String, node: KeyNode): KeyDef.Popup.Keyboard =
            longPressOf(shown(letters), cornerOf(node), ::symbolsOf)
    }

    private var punctuationMapping: Map<String, String> = emptyMap()
    private fun mapped(p: String) = punctuationMapping.getOrDefault(p, p)

    // the corners as the full keyboard's and the comma and full stop as typed: ， and 。 in Chinese
    // (the address keys commit as they are, and say so)
    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        canvas?.invalidateScene()
    }

    // the long press made as it opens, from the user's 「按键字符」 as they are now, as the full keyboard's Preset is;
    // public for its test, which has no touch to open one
    public override fun onPopupAction(action: PopupAction) {
        val shown = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = previewOf(cased(action.content), action.key, { it }, ::mapped))
            // a swipe's shortcut named (words) shows as the user named it: the case rule is the letters'
            is PopupAction.PreviewUpdateAction -> action.copy(content = previewOf(if (action.words) action.content else cased(action.content), action.key, { it }, ::mapped))
            is PopupAction.ShowKeyboardAction -> groupOf(action.key)?.let { (group, corner) ->
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
    override fun pressed(clickAt: Float, clickDown: Float, action: KeyAction): KeyAction = tapped(action, clickAt, letters, leaning, clickDown)

    // the groups whose taps lean (按落点加权): the layout's lean=, whichever draws the keys, its groups the
    // keyboard's (060-render-v2 §15); a layout of other groups, or none read: as built in (SceneGroups.leaningOf)
    private val leaning: Set<String> by lazy {
        val layout = sceneBoard?.layout ?: grouping.layout
        SceneGroups.leaningOf(letters, layout?.layout, layout?.fragments.orEmpty())
    }

    // Return as last shown: its icon on the scene's key too
    private var returnAppearance = ReturnKeyAppearance.Enter

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        returnAppearance = appearance
        canvas?.invalidateScene()
    }

    // the input method shown, for whether a stroke is drawn (笔画筛选)
    private var imeName = ""

    // 拼音键字母大小写 (made anew when it changes): the groups' faces and bubbles in capitals but where it says otherwise
    private val letterCase by AppPrefs.getInstance().keyboard.letterCase
    private val upper: Boolean get() = LetterCaseRule.upper(letterCase.case, Shift.None, shiftless = true)

    // a group's letters in a bubble as the face has them; a corner as it is
    private fun cased(content: String) =
        if (content.isNotEmpty() && content.all { it.isLetter() || it == ';' }) LetterCaseRule.shown(content, upper) else content

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        imeName = ime.uniqueName
        showInputMethodName(ime)
        canvas?.invalidateScene()
    }

    // what the keys show now as the scene engine's environment (k14.xml's, k18.xml's and grouped-bottom.xml's
    // when= and bindings): the 18 keys' L; under a scheme that types `;`, an address's keys, the language
    // key, the space's microphone; the corners, the comma and the full stop as punctuation maps them
    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
        val state = TextBoardState(
            semicolonScheme = groupsOf(letters, semicolon).any { ';' in it },
            address = address,
            langKey = showLangSwitchKey.getValue(),
            holdVoice = holdsToTalk,
        )
        val texts = Texts(
            imeName = shownImeName.orEmpty(),
            letterCase = letterCase.case,
            comma = mapped(","),
            period = mapped("."),
            returnLabel = returnAppearance.label(context).toString(),
            returnIcon = SceneIcons.returnName(returnAppearance.icon),
            confirm = context.getString(R.string.key_confirm),
            punctuation = punctuationMapping,
            // the formats as they are: the engine fills their places (getString with no arguments formats nothing)
            a11yGrouped = context.getString(R.string.a11y_key_grouped),
            a11ySpace = context.getString(R.string.a11y_key_space_with_ime),
        )
        return SceneEnvs.of(this, look, theme.isDark, width, height, input = state.input(), texts = texts, marks = SceneBoard.marks(layout, charShortcutMarks))
    }

    // a layout's own groups told the engine as this keyboard is shown, before any of its keys is tapped
    // (KeyboardWindow attaches a keyboard as it makes it): a keyboard kept while another's own groups were
    // shown tells its own again (EngineState.ownKeys). Not as it is made: one made and never shown (a
    // preview) would change the groups the keyboard shown is read by. A built-in grouping leaves it as it is, on
    // purpose (sticky): only a layout of its own sends the codes those groups read, so the stale ones read nothing
    override fun onAttach() {
        super.onAttach()
        grouping.own?.let { EngineState.ownKeys = it }
    }

    // last: the scene reads the state above (the keys drawn by the scene engine, each as its layout says)
    init {
        drawKeysWithScene(grouping.layout)
    }
}
