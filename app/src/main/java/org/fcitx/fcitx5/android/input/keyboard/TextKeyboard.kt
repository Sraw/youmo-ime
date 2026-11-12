/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.input.keys.KeyResolver
import org.fcitx.fcitx5.android.input.keys.KeyState as KeysState
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.SceneLetters
import org.fcitx.fcitx5.android.input.scene.TextBoardState
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.LetterCase
import org.fcitx.fcitx5.android.scene.engine.LetterCaseRule
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.look.ThemeLook

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout, KeyNames.QWERTY), AutoCapsKeys {

    // the halves (the scene splits the rows, SplitKeyboard): 双拼's ing is L's long press there (LS-29),
    // a table's code key has a key of its own
    private val isSplit = splits(context)

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"

        // the layout its keys are drawn from (lib/keyboard-scene's qwerty.xml)
        private const val LAYOUT = "qwerty"

        /** Whether the letters are split here: as the setting is, the screen as wide as it is now; floating, never. */
        fun splits(context: Context): Boolean = KeyboardArea.splits(KeyboardArea.screen(context))

        /** The keyboard's width, dp: the screen's less its side padding (InputView's), or floating its own. */
        fun widthDp(context: Context): Int = KeyboardArea.widthDp(KeyboardArea.screen(context))

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                AlphabetKey("Q", "1"),
                AlphabetKey("W", "2"),
                AlphabetKey("E", "3"),
                AlphabetKey("R", "4"),
                AlphabetKey("T", "5"),
                AlphabetKey("Y", "6"),
                AlphabetKey("U", "7"),
                AlphabetKey("I", "8"),
                AlphabetKey("O", "9"),
                AlphabetKey("P", "0")
            ),
            listOf(
                AlphabetKey("A", "@"),
                AlphabetKey("S", "*"),
                AlphabetKey("D", "+"),
                AlphabetKey("F", "-"),
                AlphabetKey("G", "="),
                AlphabetKey("H", "/"),
                AlphabetKey("J", "#"),
                AlphabetKey("K", "("),
                AlphabetKey("L", ")"),
                // where a physical keyboard has it: 微软 双拼 types ing on it, hidden else (semicolonKey);
                // a table's code key the face has nowhere else, as the engine says (TableKeyboard.extraKey)
                SymbolKey(";", 0.1f, viewId = R.id.button_semicolon),
                SymbolKey("*", 0.1f, viewId = R.id.button_star),
            ),
            listOf(
                // a table's 拼 key in Shift's place, one of the two shown as its lookup is (drawn by the scene)
                CapsKey(),
                LookUpKey(on = false),
                LookUpKey(on = true),
                AlphabetKey("Z", "'"),
                AlphabetKey("X", ":"),
                AlphabetKey("C", "\""),
                AlphabetKey("V", "?"),
                AlphabetKey("B", "!"),
                AlphabetKey("N", "~"),
                AlphabetKey("M", "\\"),
                BackspaceKey()
            ),
            listOf(
                // Sogou's row: symbols and numbers each a key of their own, the comma and the
                // full stop either side of the space, the language switch after them; held, 符
                // and 123 open quick phrase and Unicode input, which have no key in this row
                LayoutSwitchKey("符", PickerWindow.Key.Symbol.name, 0.12f, longPress = KeyAction.QuickPhraseAction),
                LayoutSwitchKey("123", NumberKeyboard.Name, 0.12f, longPress = KeyAction.UnicodeAction),
                SymbolKey(",", 0.1f, KeyDef.Appearance.Variant.Alternative, viewId = R.id.button_comma, shortcut = "comma"),
                // an address field's in place of the comma and the full stop (AddressKeys), hidden else
                AddressKey("/", R.id.button_slash),
                AddressKey("@", R.id.button_at),
                SpaceKey(),
                SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative, viewId = R.id.button_period, shortcut = "period"),
                // as it is: through the engine . would be 。
                AddressKey(".", R.id.button_dot),
                AddressKey(AddressKeys.DOMAINS.first(), R.id.button_domain, 0.14f, AddressKeys.DOMAINS),
                LanguageKey(),
                ReturnKey(),
                // a table's while a code is typed, in Return's place (drawn by the scene)
                ConfirmKey(),
            )
        )

        // what a table's code key may have of its own beside the letters, the first wanted first
        private val EXTRA_SLOTS = listOf(';', '*')

    }

    /** What the bottom row has either side of the space, as the field is: see [AddressKeys]. */
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

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    // 拼音键字母大小写 as it holds for the input method now: English's follows Shift (made anew when it changes)
    private val letterCaseChoice by AppPrefs.getInstance().keyboard.letterCase
    private val letterCase: LetterCase get() = LetterCaseRule.forIme(letterCaseChoice.case, english = imeName == InputMethodNames.KEYBOARD)


    // the input method last reported, for the `;` key again when the 双拼 scheme changed meanwhile
    private var imeName = ""

    override val keysIm: String get() = imeName

    // a table's roots in a letter's long press where the keys show them all (rootsOf), its letter in its corner where they show any
    override fun keyState(): KeysState = super.keyState().copy(
        caps = capsState != CapsState.None,
        roots = table?.takeIf { it.labels == KeyLabels.ROOTS }?.let { t -> rootsBy(t) },
        letterCorners = table?.labelled == true,
    )

    // one function a table, so the state, and so the marks made of it, compare equal while the table is the same
    private var rootsMade: Pair<TableKeyboard, (Char) -> String?>? = null

    private fun rootsBy(t: TableKeyboard): (Char) -> String? = rootsMade?.takeIf { it.first == t }?.second
        ?: { c: Char -> t.rootsOf(c)?.joinToString(" ") }.also { rootsMade = t to it }

    // what the engine last said of a table input method's keyboard (EngineState.tableKeyboard), null
    // for another, or none said yet of the one shown: its letters' roots, its 拼 key, its code keys
    private var table: TableKeyboard? = null

    // a table's every root on the letters leaves no corner free of them: the shortcuts unmarked, swiped all the same
    override val charShortcutMarks: Boolean
        get() = table?.let { it.labels == KeyLabels.ROOTS && it.roots != null } != true

    // the table's code key the letters had no room for, as last shown (showSemicolonKey): the drawn keys' too
    private var extraKey: Char? = null

    // Return as it was last shown: while something is typed, a table shows 确定 in its place
    private var returnAppearance = ReturnKeyAppearance.Enter

    // the letter key whose long press shows its roots (TableKeyboard.rootsOf) in its bubble
    private var rootsShownOn: KeyRef? = null

    private var scope: CoroutineScope? = null

    // what the keys show now, as the scene engine's environment: the screen's and the settings' as every
    // keyboard's (SceneEnvs), this one's state, Shift, texts and marks its own
    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
        // first: the layout the corners' resolver reads (alts)
        val marks = keyMarks(layout)
        val state = TextBoardState(
            semicolonScheme = semicolonKey(imeName, EngineState.shuangpinScheme),
            table = imeName in InputMethodCatalog.TABLES,
            tableExtra = extraKey,
            lookingUp = table?.lookingUp == true,
            composing = returnAppearance.composing,
            address = address,
            langKey = showLangSwitchKey.getValue(),
            holdVoice = holdsToTalk,
            roots = table?.labelled == true,
        )
        val texts = Texts(
            imeName = shownImeName.orEmpty(),
            comma = transformPunctuation(","),
            period = transformPunctuation("."),
            returnLabel = returnAppearance.label(context).toString(),
            returnIcon = SceneIcons.returnName(returnAppearance.icon),
            confirm = context.getString(R.string.key_confirm),
            keepUppercase = keepLettersUppercase,
            letterCase = letterCase,
            punctuation = punctuationMapping,
            // the corners as said, and a table's roots
            alts = keyReadings(layout),
            // a letter with no root left empty: the engine shows it as {case:x} would (Expand's root)
            other = SceneLetters.roots(table),
            // the formats as they are: the engine fills their places (getString with no arguments formats nothing)
            a11yGrouped = context.getString(R.string.a11y_key_grouped),
            a11ySpace = context.getString(R.string.a11y_key_space_with_ime),
        )
        return SceneEnvs.of(
            this, look, theme.isDark, width, height,
            input = state.input(),
            split = isSplit,
            shift = shift(),
            texts = texts,
            marks = marks,
        )
    }

    /** The scenes the engine made for this keyboard: a test's count of what was not kept. */
    @VisibleForTesting
    val scenesMade: Int get() = sceneBoard?.scenesMade ?: 0

    /** The scenes kept let go, the next asked made anew as things are: a measure of what making one costs (KeyboardPerfBaselineTest). */
    @VisibleForTesting
    fun forgetScene() {
        SceneBoard.forgetScenes()
        canvas?.invalidateScene()
    }

    // a letter as its key shows it with no table's roots over it: Shift's case, kept upper case, or 拼音键字母大小写's
    private fun letterShown(letter: String) = LetterCaseRule.shown(letter, LetterCaseRule.upper(letterCase, shift(), keepUppercase = keepLettersUppercase))

    private fun shift(): Shift = when (capsState) {
        CapsState.None -> Shift.None
        CapsState.Once -> Shift.Once
        CapsState.Lock -> Shift.Lock
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            s.launch { EngineState.tableKeyboard.collect(::showTable) }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    // [state] of the table shown, or of another input method's (none of this one's then)
    private fun showTable(state: TableKeyboard?) {
        // 全部字根 shown as 键名 at 紧凑, read as the keys are made (made anew into and out of it)
        val shown = state?.takeIf { it.im == imeName }?.atHeight(keyboardCompact())
        if (shown == table) return
        // another table's roots as well: 五笔's 键名 to 仓颉's, both NAMES, told before the switch shows
        val relabels = shown?.labels != table?.labels || shown?.roots !== table?.roots
        table = shown
        // the keys' labels: the table's roots or none; another table's roots relabel the swipe marks too
        canvas?.invalidateScene()
        if (relabels) showSwipeMarks()
        showSemicolonKey()
    }

    private var capsState: CapsState = CapsState.None

    // the input method reads a glide (InputMethodCatalog.glides)
    private var glides = false

    private fun showSemicolonKey() {
        // the table's code key the face has nowhere else: not on a corner its swipe types, unless the corners show roots
        val extra = table?.extraKey(EXTRA_SLOTS, BOTTOM_ROW_KEYS + if (table?.labelled == true) emptySet() else cornerKeys())
        extraKey = extra
        canvas?.invalidateScene()
    }

    // a letter's first changed may free the table's code key's place, or take it
    override fun onKeyListsChanged() {
        super.onKeyListsChanged()
        showSemicolonKey()
    }

    // what the letters' swipes type, drawn in their corners: each letter's first as the resolver has it now
    // (kept while the resolver and the state are the same: 26 keys resolved once, 060-s6-review L9)
    private fun cornerKeys(): Set<Char> {
        val resolver = keyResolver() ?: return emptySet()
        val state = keyState()
        corners?.takeIf { it.resolver === resolver && it.state == state }?.let { return it.keys }
        val keys = ('a'..'z').mapNotNullTo(HashSet()) { c -> resolver.resolve(KeyId("full/$c"), state).first?.text?.singleOrNull() }
        corners = Corners(resolver, state, keys)
        return keys
    }

    private class Corners(val resolver: KeyResolver, val state: KeysState, val keys: Set<Char>)

    private var corners: Corners? = null

    // glide typing (滑行输入) where the input method reads one; a stroke (笔画筛选) in pinyin's and 双拼's
    // the letters where the scene places them: each letter node of the scene by
    // its (id, copy), a split row's second G its copy 1
    private fun sceneLetters(): List<Pair<Char, KeyPlace>> = canvas?.shownKeys.orEmpty()
        .filter { it.id.length == 1 && it.id[0] in 'a'..'z' }
        .map { it.id[0] to scenePlace(it.id, it.copy == 1) }

    override val glide = glideInput(letters = { sceneLetters() }, enabled = { glides }, filters = { InputMethodCatalog.filtersByStrokes(imeName) }, writes = { InputMethodCatalog.writesOnKeys(imeName) }) { path, layout ->
        onAction(KeyAction.GlideAction(Glide(path, layout, shift = capsState == CapsState.Once, capsLock = capsState == CapsState.Lock)))
        // as a letter typed: Shift-once used up in any input method, as a tap uses it up (only English reads it)
        if (capsState == CapsState.Once) switchCapsState()
    }

    private val autoCaps = AutoCaps()

    override val capsLocked: Boolean get() = capsState == CapsState.Lock

    override fun applyAutoCaps(wants: Boolean) {
        val current = when (capsState) {
            CapsState.None -> AutoCaps.Caps.NONE
            CapsState.Once -> AutoCaps.Caps.ONCE
            CapsState.Lock -> AutoCaps.Caps.LOCK
        }
        val next = when (autoCaps.next(current, wants)) {
            AutoCaps.Caps.NONE -> CapsState.None
            AutoCaps.Caps.ONCE -> CapsState.Once
            AutoCaps.Caps.LOCK -> CapsState.Lock
        }
        if (next == capsState) return
        capsState = next
        canvas?.invalidateScene()
    }

    private var punctuationMapping: Map<String, String> = mapOf()
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.HostKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    when (capsState) {
                        CapsState.None -> {
                            transformed = action.copy(act = action.act.lowercase())
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            // a word from the long press (or a letter's swipe, its first) is typed too: Shift-once is used up
            is KeyAction.CommitAction -> if (capsState == CapsState.Once) switchCapsState()
            is KeyAction.CapsAction -> switchCapsState(action.lock)
            else -> {}
        }
        super.onAction(transformed, source)
    }

    override fun onAttach() {
        capsState = CapsState.None
        autoCaps.changed()
        canvas?.invalidateScene()
        // the scheme may have changed in the settings with 双拼 kept, which reports no input method
        showSemicolonKey()
    }

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        returnAppearance = appearance
        canvas?.invalidateScene()
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        super.onPunctuationUpdate(mapping)
        punctuationMapping = mapping
        canvas?.invalidateScene()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        showInputMethodName(ime)
        imeName = ime.uniqueName
        canvas?.invalidateScene()
        // what the engine said last, if of this one: a table's state comes with its first answer
        showTable(EngineState.tableKeyboard.value)
        showSemicolonKey()
        glides = InputMethodCatalog.glides(ime.uniqueName)
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    private fun transformPopupPreview(key: KeyRef, c: String) = previewOf(c, key, ::letterShown, ::transformPunctuation)

    // a letter's bubble shows its letter whatever its face (Shift's case, a table's roots), as its view's did;
    // a getter: the keys are wired in init, before this class's properties are
    override val sceneTable: SceneKeyDef.Table get() = LetterBubbles

    private object LetterBubbles : SceneKeyDef.Table {
        override fun bubble(node: KeyNode) = node.id.takeIf { it.length == 1 && it[0] in 'a'..'z' }
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.key, action.content))
            // the roots stay shown till the key is let go
            is PopupAction.PreviewUpdateAction -> if (action.key == rootsShownOn) return else action.copy(content = transformPopupPreview(action.key, action.content))
            is PopupAction.DismissAction -> action.also { if (it.key == rootsShownOn) rootsShownOn = null }
            // let go: the bubble goes whether or not a press shows one (no long press's keys to take it down)
            is PopupAction.TriggerAction -> if (action.key == rootsShownOn) {
                rootsShownOn = null
                action.outConsumed = true
                super.onPopupAction(PopupAction.DismissAction(action.key))
                return
            } else action
            is PopupAction.ShowKeyboardAction -> {
                // a table's roots, large, in the key's bubble while the keys show them; nothing to pick
                rootsOf(action.keyboard)?.let { roots ->
                    rootsShownOn = action.key
                    super.onPopupAction(PopupAction.PreviewAction(action.key, roots, action.bounds))
                    return
                }
                action
            }
            else -> action
        }
        super.onPopupAction(newAction)
    }

    private fun switchCapsState(lock: Boolean = false) {
        autoCaps.changed()
        capsState =
            if (lock) {
                when (capsState) {
                    CapsState.Lock -> CapsState.None
                    else -> CapsState.Lock
                }
            } else {
                when (capsState) {
                    CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
            }
        // Shift's icon, and a letter's corner, which is its swipe and follows Shift (the resolver's, by KeyState.caps)
        canvas?.invalidateScene()
    }

    // [keyboard]'s, a letter's long press (by its name, full/q), as a table's roots in a line, while the keys show them
    private fun rootsOf(keyboard: KeyDef.Popup.Keyboard): String? {
        val id = (keyboard as? KeyDef.Popup.Keyboard.Named)?.name?.let(KeyId::of)?.takeIf { it.form == "full" } ?: return null
        val letter = id.key.singleOrNull()?.takeIf { it in 'a'..'z' } ?: return null
        return table?.rootsOf(letter)?.joinToString(" ")
    }

    // last, after every field its scene reads (sceneEnv: altsMade, capsState, punctuationMapping)
    init {
        // hidden until an input method says it types on it: shown, every one's A row had an 11th key
        // at a cold start, before fcitx reported the first
        showSemicolonKey()
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        // the keys drawn by the scene engine, each wired as qwerty.xml's node says (U4 B)
        drawKeysWithScene(LAYOUT)
    }
}

// here, not in the companion: previewOf's JVM test would otherwise load TextKeyboard, a View; the
// keys as the scene names them (qwerty.xml's ids); the 双拼 `;` is a key of the scheme, shown as it is too (; not ；)
private val ADDRESS_NAMES = setOf("slash", "at", "dot", "domain", "semicolon", "star")

// the bottom row's own keys a table's code may have: the comma and the full stop
private val BOTTOM_ROW_KEYS = setOf(',', '.')

/**
 * What the preview bubble of the key [key] shows for [content]: a letter as Shift has it
 * ([letter]), punctuation as the input method maps it ([punctuation]); an address's keys what they
 * commit, . not 。, as their labels show (TextKeyboard's scene).
 */
internal fun previewOf(content: String, key: KeyRef, letter: (String) -> String, punctuation: (String) -> String): String = when {
    content.length != 1 || key.id in ADDRESS_NAMES -> content
    content[0].isLetter() -> letter(content)
    else -> punctuation(content)
}
