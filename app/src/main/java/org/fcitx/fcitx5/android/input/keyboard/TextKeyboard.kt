/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
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
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.keys.KeyResolver
import org.fcitx.fcitx5.android.input.keys.KeyState as KeysState
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
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.look.ThemeLook

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme,
    /** `id@stamp` ([customLayout]); null for the app's. */
    private val custom: String? = null,
) : BaseKeyboard(context, theme, KeyNames.QWERTY), AutoCapsKeys, TypingKeys {

    // split, 双拼's ing is L's long press, and a table's code key has a key of its own
    private val isSplit = splits(context)

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"

        private const val LAYOUT = "qwerty"

        // qwerty.xml's 分词 in Shift's place while pinyin is typed
        private const val SEPARATOR_KEY = "apostrophe"

        // its send=, one of a–z. Not `code:U+0071`: that is a code typed as is (a SymAction), not a letter Shift
        // cases, so no letter key to a glide, a stroke, ink or the bubble either
        private fun letterSent(node: KeyNode): Char? = node.actions["send"]?.singleOrNull()?.takeIf { it in 'a'..'z' }

        /** The 键盘样式 of the screen's current shape ([KeyboardStyles]). */
        fun splits(context: Context): Boolean = KeyboardArea.splits(KeyboardArea.screen(context))

        /** Floating: its own. */
        fun widthDp(context: Context): Int = KeyboardArea.widthDp(KeyboardArea.screen(context))

        // the first wanted first
        private val EXTRA_SLOTS = listOf(';', '*')

    }

    override var address: AddressKeys = AddressKeys.NONE
        set(value) {
            field = value
            canvas?.invalidateScene()
        }

    // English's follows Shift (remade when it changes)
    private val letterCaseChoice by AppPrefs.getInstance().keyboard.letterCase
    private val letterCase: LetterCase get() = LetterCaseRule.forIme(letterCaseChoice.case, english = imeName == InputMethodNames.KEYBOARD)

    // [imeName]: the `;` key again when the 双拼 scheme changed meanwhile
    override val keysIm: String get() = imeName
    override val strokesDrawn get() = true

    // a table's roots in a letter's long press where the keys show them all, its letter in its corner where they show any
    override fun keyState(): KeysState = super.keyState().copy(
        caps = capsState != CapsState.None,
        roots = table?.takeIf { it.labels == KeyLabels.ROOTS }?.let { t -> rootsBy(t) },
        letterCorners = table?.labelled == true,
    )

    // one function per table, so the state and its marks compare equal while the table is the same
    private var rootsMade: Pair<TableKeyboard, (Char) -> String?>? = null

    private fun rootsBy(t: TableKeyboard): (Char) -> String? = rootsMade?.takeIf { it.first == t }?.second
        ?: { c: Char -> t.rootsOf(c)?.joinToString(" ") }.also { rootsMade = t to it }

    // null for another input method, or none said yet of the one shown
    private var table: TableKeyboard? = null

    // a table's roots fill every corner: the shortcuts unmarked, swiped all the same
    override val charShortcutMarks: Boolean
        get() = table?.let { it.labels == KeyLabels.ROOTS && it.roots != null } != true

    // the drawn keys' too
    private var extraKey: Char? = null

    private var rootsShownOn: KeyRef? = null

    private var scope: CoroutineScope? = null

    // Shift is 分词 meanwhile (ShiftSeparator)
    private var separating = false

    // told from any other `'` by identity ([pressed])
    private val separatorTyped = KeyAction.HostKeyAction(ShiftSeparator.TYPED)

    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
        val marks = keyMarks(layout)
        val state = TextBoardState(
            semicolonScheme = semicolonKey(imeName, EngineState.shuangpinScheme),
            table = imeName in InputMethodCatalog.TABLES,
            // the first frame comes before the engine tells the table: its 拼 is the method's own, not a ⇧ flashing
            tableLookUp = table?.lookUpKey ?: InputMethodCatalog.TABLES[imeName]?.options?.lookUpKey ?: false,
            tableExtra = extraKey,
            lookingUp = table?.lookingUp == true,
            composing = returnAppearance.composing,
            address = address,
            langKey = showLangSwitchKey.getValue(),
            holdVoice = holdsToTalk,
            roots = table?.labelled == true,
            separating = separating,
        )
        val texts = Texts(
            imeName = shownImeName.orEmpty(),
            comma = transformPunctuation(","),
            period = transformPunctuation("."),
            confirm = context.getString(R.string.key_confirm),
            letterCase = letterCase,
            punctuation = keysPunctuation,
            // the corners as said, and a table's roots
            alts = keyReadings(layout),
            // a letter with no root left empty: the engine shows it as {case:x} would
            other = SceneLetters.roots(table),
            // the formats as they are: the engine fills their places (getString with no arguments formats nothing)
            a11yGrouped = context.getString(R.string.a11y_key_grouped),
            a11ySpace = context.getString(R.string.a11y_key_space_with_ime),
        ).withReturn()
        return SceneEnvs.of(
            this, look, theme.isDark, width, height,
            input = state.input(),
            split = isSplit,
            shift = shift(),
            texts = texts,
            marks = marks,
        )
    }

    /** For KeyboardPerfBaselineTest to measure a scene's cost. */
    @VisibleForTesting
    fun forgetScene() {
        SceneBoard.forgetScenes()
        canvas?.invalidateScene()
    }

    private fun letterShown(letter: String) = LetterCaseRule.shown(letter, LetterCaseRule.upper(letterCase, shift()))

    private fun shift(): Shift = when (capsState) {
        CapsState.None -> Shift.None
        CapsState.Once -> Shift.Once
        CapsState.Lock -> Shift.Lock
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            s.launch { EngineState.tableKeyboard.collect(::showTable) }
            s.launch { EngineState.syllables.collect { showSeparator(ShiftSeparator.on(imeName, it.im, it.composing)) } }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    // or of another input method's (none of this one's then)
    private fun showTable(state: TableKeyboard?) {
        // 全部字根 shown as 键名 at 紧凑 (remade into and out of it)
        val shown = state?.takeIf { it.im == imeName }?.atHeight(keyboardCompact())
        if (shown == table) return
        // another table's roots too: 五笔's 键名 to 仓颉's, both NAMES
        val relabels = shown?.labels != table?.labels || shown?.roots !== table?.roots
        table = shown
        // another table's roots relabel the swipe marks too
        canvas?.invalidateScene()
        if (relabels) showSwipeMarks()
        showSemicolonKey()
    }

    private var capsState: CapsState = CapsState.None

    private var glides = false

    private fun showSemicolonKey() {
        // not on a corner its swipe types, unless the corners show roots
        val extra = table?.extraKey(EXTRA_SLOTS, BOTTOM_ROW_KEYS + if (table?.labelled == true) emptySet() else cornerKeys())
        extraKey = extra
        canvas?.invalidateScene()
    }

    // a letter's first changed may free the table's code key's place, or take it
    override fun onKeyListsChanged() {
        super.onKeyListsChanged()
        showSemicolonKey()
    }

    // kept while the resolver and state are the same: 26 keys resolved once
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

    // a letter's key is the one that sends it (send="q"), whatever its id: a theme pack's key of id q need not
    // send q, and by id a glide, stroke and ink would read another letter than typed. Two keys sending one
    // letter: the first in layout order wins (both its split copies)
    private fun sceneLetters(): List<Pair<Char, ScenePlace>> {
        val keyOf = HashMap<Char, String>()
        return canvas?.shownKeys.orEmpty().mapNotNull { node ->
            val letter = letterSent(node) ?: return@mapNotNull null
            if (keyOf.getOrPut(letter) { node.id } != node.id) null else letter to scenePlace(node.id, node.copy == 1)
        }
    }

    // both copies of a split row's, and 微软 双拼's `;`
    override fun typingKeys(): List<Pair<Char, KeyRef>> {
        val semicolon = semicolonKey(imeName, EngineState.shuangpinScheme)
        return canvas?.shownKeys.orEmpty().mapNotNull { node ->
            val typed = letterSent(node) ?: node.actions["send"]?.singleOrNull()?.takeIf { semicolon && it == ';' }
            typed?.let { it to KeyRef(node.id, node.copy == 1) }
        }
    }

    override val glide = glideInput(letters = { sceneLetters() }, enabled = { glides }) { path, layout ->
        onAction(KeyAction.GlideAction(Glide(path, layout, shift = capsState == CapsState.Once, capsLock = capsState == CapsState.Lock)))
        // Shift-once used up in any input method, as a tap uses it up
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

    // a scene only when that flips, kept ones reused (SceneBoard)
    private fun showSeparator(on: Boolean) {
        if (on == separating) return
        separating = on
        canvas?.invalidateScene()
    }

    private fun transformPunctuation(p: String) = keysPunctuation.getOrDefault(p, p)

    /**
     * 分词's whenever it lands, and Shift tapped as typing began (down while Shift was drawn, up once 分词
     * is) the same. Only where the scene shows 分词 in place of the key pressed: a qwerty with none keeps
     * its Shift.
     */
    override fun pressed(key: KeyRef, clickAt: Float, clickDown: Float, action: KeyAction): KeyAction = when {
        // 分词's alone: a theme's plain `'` key types as any other key
        key.id == SEPARATOR_KEY && action is KeyAction.HostKeyAction && action.act == ShiftSeparator.TYPED -> separatorTyped
        action is KeyAction.CapsAction && separating && separatorTookShift(key) -> separatorTyped
        else -> action
    }

    private fun separatorTookShift(key: KeyRef): Boolean {
        val shown = canvas?.shownKeys ?: return false
        return shown.any { it.id == SEPARATOR_KEY } && shown.none { it.id == key.id }
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        // Shift's state neither used up nor changed; a press landing once typing ended too
        if (action === separatorTyped) {
            super.onAction(action, source)
            return
        }
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
            // a word from the long press (or a letter's swipe) is typed too: Shift-once is used up
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

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        showInputMethodName(ime)
        super.onInputMethodUpdate(ime)
        // another input method's typing is none of this one's
        EngineState.syllables.value.let { separating = ShiftSeparator.on(imeName, it.im, it.composing) }
        canvas?.invalidateScene()
        // a table's state comes with its first answer
        showTable(EngineState.tableKeyboard.value)
        showSemicolonKey()
        glides = InputMethodCatalog.glides(ime.uniqueName)
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    private fun transformPopupPreview(key: KeyRef, c: String) = previewOf(c, key, ::letterShown, ::transformPunctuation)

    // a getter: the keys are wired in init, before this class's properties are
    override val sceneTable: SceneKeyDef.Table get() = LetterBubbles

    private object LetterBubbles : SceneKeyDef.Table {
        override fun bubble(node: KeyNode) = letterSent(node)?.toString()
    }

    // public for its test, which has no theme's key of no name to hold
    public override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.key, action.content))
            // the roots stay shown till the key is let go
            is PopupAction.PreviewUpdateAction -> when {
                action.key == rootsShownOn -> return
                action.shown -> action
                else -> action.copy(content = transformPopupPreview(action.key, action.content))
            }
            is PopupAction.DismissAction -> action.also { if (it.key == rootsShownOn) rootsShownOn = null }
            // the bubble goes whether or not a press shows one
            is PopupAction.TriggerAction -> if (action.key == rootsShownOn) {
                rootsShownOn = null
                action.outConsumed = true
                super.onPopupAction(PopupAction.DismissAction(action.key))
                return
            } else action
            is PopupAction.ShowKeyboardAction -> {
                // nothing to pick
                rootsOf(action.keyboard)?.let { roots ->
                    rootsShownOn = action.key
                    super.onPopupAction(PopupAction.PreviewAction(action.key, roots, action.bounds))
                    return
                }
                // a theme's letter written without shortcut=: in capitals as its face under Shift
                val k = action.keyboard
                if (k is KeyDef.Popup.Keyboard.Preset && capsState != CapsState.None) action.copy(keyboard = k.copy(caps = true)) else action
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
        // a letter's corner is its swipe and follows Shift (KeyState.caps)
        canvas?.invalidateScene()
    }

    private fun rootsOf(keyboard: KeyDef.Popup.Keyboard): String? {
        val id = (keyboard as? KeyDef.Popup.Keyboard.Named)?.name?.let(KeyId::of)?.takeIf { it.form == "full" } ?: return null
        val letter = id.key.singleOrNull()?.takeIf { it in 'a'..'z' } ?: return null
        return table?.rootsOf(letter)?.joinToString(" ")
    }

    // last, after every field its scene reads (sceneEnv: capsState)
    init {
        // hidden until an input method says it types on it: shown, every A row had an 11th key at a cold start
        showSemicolonKey()
        drawKeysWithScene(custom?.let { customLayout(context, it) } ?: SceneLayouts.of(context, LAYOUT))
    }
}

// here, not in the companion: previewOf's JVM test would otherwise load a View. The 双拼 `;` shows as is (; not ；)
private val ADDRESS_NAMES = setOf("slash", "at", "dot", "domain", "semicolon", "star")

private val BOTTOM_ROW_KEYS = setOf(',', '.')

/** An address's keys show what they commit, . not 。, as their labels do. */
internal fun previewOf(content: String, key: KeyRef, letter: (String) -> String, punctuation: (String) -> String): String = when {
    content.length != 1 || key.id in ADDRESS_NAMES -> content
    content[0].isLetter() -> letter(content)
    else -> punctuation(content)
}
