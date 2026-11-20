/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.SceneGroups
import org.fcitx.fcitx5.android.input.scene.TextBoardState
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.LetterCaseRule
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import timber.log.Timber

/**
 * A tap sends its group's code (private use), read by the engine as any of its letters, with where across
 * the key it fell so that side's letter weighs more (讯飞's 你好 for nihao). A layout's own groups (a theme's
 * 17 keys) go on codes of their own; any other grouping is logged and the built-in layout drawn.
 */
@SuppressLint("ViewConstructor")
class GroupedKeyboard private constructor(
    context: Context,
    theme: Theme,
    private val builtInLetters: NineKeyLetters,
    // named apart from the properties below, which an initializer would otherwise not see
    private val builtInWire: (Char) -> Int,
    private val builtInSemicolon: Boolean,
    private val grouping: Grouping,
) : BaseKeyboard(context, theme, themeBoard = layoutOf(builtInLetters)), TypingKeys {

    constructor(
        context: Context,
        theme: Theme,
        letters: NineKeyLetters = NineKeyLetters.G14,
        wire: (Char) -> Int = Char::code,
        semicolon: Boolean = true,
        ownGroups: Boolean = false,
        custom: String? = null,
    ) : this(context, theme, letters, wire, semicolon, grouping(context, letters, ownGroups, custom = custom))

    // not the app's own of its id, drawn where the engine refused the pack's (SceneBoard.ownLayout): those
    // keys are the built-in groups'
    private fun ownOn(layout: SceneLayout?): Boolean = grouping.own != null && layout === grouping.layout

    private val ownDrawn: Boolean get() = ownOn(drawnLayout)

    // the built-in ones as the drawn layout writes them, with each key's letters where they are on it
    // (NineKeyLetters.rowsOf, for where a tap falls)
    private val letters: NineKeyLetters get() = if (ownDrawn || drawnLayout === grouping.layout) grouping.letters else builtInLetters

    private val wire: (Char) -> Int get() = if (ownDrawn) Char::code else builtInWire

    private fun semicolonOn(layout: SceneLayout?): Boolean = builtInSemicolon || ownOn(layout)

    private val groups: List<String> = grouping.letters.tag().split(',')

    private val ownIds: List<String> = grouping.layout?.takeIf { grouping.own != null }?.let { SceneGroups.ids(it.layout, it.fragments) }.orEmpty()

    companion object {
        const val Name14 = "Grouped14"

        const val Name18 = "Grouped18"

        const val Name18Plain = "Grouped18Plain"

        const val NamePinyin18 = "GroupedPinyin18"

        const val NameShuangpin14 = "GroupedShuangpin14"

        internal class Grouping(val letters: NineKeyLetters, val own: List<String>?, val layout: SceneLayout?)

        internal fun grouping(context: Context, builtIn: NineKeyLetters, own: Boolean, log: Boolean = true, custom: String? = null): Grouping {
            val id = layoutOf(builtIn)
            val shown = custom?.let { customLayout(context, it) } ?: SceneLayouts.of(context, id) ?: return Grouping(builtIn, null, null)
            return when (val taken = SceneGroups.taken(shown.layout, builtIn, own, shown.fragments)) {
                SceneGroups.Taken.BuiltIn -> Grouping(NineKeyLetters.custom(SceneGroups.written(shown.layout, shown.fragments)), null, shown)
                is SceneGroups.Taken.Own -> Grouping(taken.letters, taken.written, shown)
                is SceneGroups.Taken.Refused -> {
                    // a settings page asks on each refresh ([KeyboardLabels]): only the keyboard made logs it
                    if (log) Timber.e("scene layout %s: %s", id, taken.why)
                    Grouping(builtIn, null, SceneLayouts.builtIn(context, id))
                }
            }
        }

        internal fun code(c: Char, wire: (Char) -> Int = Char::code) = KeyAction.SymAction(KeySym(UNICODE + wire(c)), KeyStates.Virtual)

        /**
         * A built-in group the layout stacks (Q over W) goes by [down]: the engine reads a built-in code's place along
         * its letters, so the top one is first. A click no touch made (NaN) is the code alone.
         */
        internal fun tapped(action: KeyAction, at: Float, letters: NineKeyLetters, leaning: Set<String>, down: Float = Float.NaN): KeyAction {
            val sym = (action as? KeyAction.SymAction)?.sym?.sym ?: return action
            val wired = sym - UNICODE
            // pinyin's 18 keys' code past the BMP (Keyboard.PINYIN_18): its G18 code
            val pinyin18 = Keyboard.keyedChar(wired)?.takeIf { wired in Keyboard.PINYIN_18 }
            val c = pinyin18 ?: wired.takeIf { it in 0..Char.MAX_VALUE.code }?.toChar() ?: return action
            // a one-letter key weighs nothing itself, but where it was tapped weighs a key it slipped from (T9Segmenter)
            val placed = pinyin18 != null || Keyboard.placesTaps(c)
            val leans = letters.isCode(c) && placed && (letters.letters(c) in leaning || leaning.isNotEmpty() && !letters.weighs(c))
            val own = c.code in NineKeyLetters.CUSTOM_BLOCK
            val along = if (!own && letters.rowsOf(c).size > 1) down else at
            if (along.isNaN() || !leans) return action
            if (pinyin18 != null) return action.copy(sym = KeySym(UNICODE + Keyboard.pinyin18Tap(c, along)), states = KeyStates.Virtual)
            if (own && down.isNaN()) return action
            val sent = if (own) Keyboard.tapKey(c, at, down) else Keyboard.tapKey(c, along)
            return action.copy(sym = KeySym(UNICODE + sent), states = KeyStates.Virtual)
        }

        internal fun layoutOf(letters: NineKeyLetters): String = when (letters) {
            NineKeyLetters.G14 -> KeyNames.K14
            NineKeyLetters.G18 -> KeyNames.K18
            else -> error("no grouped keyboard of ${letters.tag()}")
        }

        private fun groupsOf(letters: NineKeyLetters, semicolon: Boolean) =
            letters.tag().split(',').map { if (semicolon) it else it.replace(";", "") }

        /**
         * A long press's letter or digit is committed as it is (as 九键's): an ASCII letter sent as a key would
         * be the hardware keyboard's, full pinyin's.
         */
        internal fun resolve(action: KeyAction, source: KeyActionListener.Source): KeyAction {
            if (source != KeyActionListener.Source.Popup) return action
            val c = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull() ?: return action
            return if (c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z') KeyAction.CommitAction(c.toString()) else action
        }
    }

    override var address: AddressKeys = AddressKeys.NONE
        set(value) {
            field = value
            canvas?.invalidateScene()
        }

    private fun groupPlaces(): List<Pair<Char, ScenePlace>> =
        canvas?.shownKeys.orEmpty().mapNotNull { k -> k.letters?.let { it.group[0] to scenePlace(k.id, k.copy == 1) } }

    override val glide = glideInput(
        letters = { groupPlaces() },
        enabled = { false },
    ) { _, _ -> }

    override val strokesDrawn get() = true

    // the group's code itself, not what [wire] sends: pinyin 18's and 双拼 14's wire is past the BMP, no
    // key the engine reads as a Char
    override fun typingKeys(): List<Pair<Char, KeyRef>> = canvas?.shownKeys.orEmpty().mapNotNull { k ->
        k.letters?.let { l -> codeOf(l.group, k.id)?.let { it to KeyRef(k.id, k.copy == 1) } }
    }

    // their taps go by where down they fell ([tapped]); a layout's own codes carry both dimensions
    override fun stackedKeys(): Set<Char> = if (ownDrawn) emptySet() else letters.codes.filter { letters.rowsOf(it).size > 1 }.toSet()

    // a layout's own: its key's place among the engine's groups (a letter may be on more than one key); a key not
    // found by id (one of a when=), its group's first place
    private fun codeOf(group: String, id: String): Char? = if (!ownDrawn) {
        letters.key(group[0])
    } else {
        val place = ownIds.indexOf(id).takeIf { it >= 0 && groups.getOrNull(it) == group } ?: groups.indexOf(group)
        place.takeIf { it >= 0 }?.let(letters.codes::get)
    }

    override val sceneTable = object : SceneKeyDef.Table {
        override fun group(letters: String, node: KeyNode): KeyAction? = codeOf(letters, node.id)?.let { code(it, wire) }
    }

    // ， and 。 in Chinese, as keysPunctuation has them (the address keys commit as they are, and say so)
    private fun mapped(p: String) = keysPunctuation.getOrDefault(p, p)

    public override fun onPopupAction(action: PopupAction) {
        val shown = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = previewOf(cased(action.content), action.key, { it }, ::mapped))
            is PopupAction.PreviewUpdateAction -> if (action.shown) action
            else action.copy(content = previewOf(if (action.words) action.content else cased(action.content), action.key, { it }, ::mapped))
            else -> action
        }
        super.onPopupAction(shown)
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        super.onAction(resolve(action, source), source)
    }

    override fun pressed(key: KeyRef, clickAt: Float, clickDown: Float, action: KeyAction): KeyAction = tapped(action, clickAt, letters, leaning, clickDown)

    // the layout's lean= (按落点加权); a layout of other groups, or none read: as built in (SceneGroups.leaningOf)
    private val leaning: Set<String> get() {
        val layout = drawnLayout ?: grouping.layout
        leaningKept?.takeIf { it.first === layout }?.let { return it.second }
        return SceneGroups.leaningOf(letters, layout?.layout, layout?.fragments.orEmpty()).also { leaningKept = layout to it }
    }

    private var leaningKept: Pair<SceneLayout?, Set<String>>? = null

    override val keysIm: String get() = imeName

    private val letterCase by AppPrefs.getInstance().keyboard.letterCase
    private val upper: Boolean get() = LetterCaseRule.upper(letterCase.case, Shift.None, shiftless = true)

    private fun cased(content: String) =
        if (content.isNotEmpty() && content.all { it.isLetter() || it == ';' }) LetterCaseRule.shown(content, upper) else content

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        super.onInputMethodUpdate(ime)
        showInputMethodName(ime)
    }

    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
        val state = TextBoardState(
            semicolonScheme = groupsOf(if (ownOn(layout)) grouping.letters else builtInLetters, semicolonOn(layout)).any { ';' in it },
            address = address,
            langKey = showLangSwitchKey.getValue(),
            holdVoice = holdsToTalk,
        )
        val texts = Texts(
            alts = keyReadings(layout),
            imeName = shownImeName.orEmpty(),
            letterCase = letterCase.case,
            comma = mapped(","),
            period = mapped("."),
            confirm = context.getString(R.string.key_confirm),
            punctuation = keysPunctuation,
            // the formats as they are: the engine fills their places (getString with no arguments formats nothing)
            a11yGrouped = context.getString(R.string.a11y_key_grouped),
            a11ySpace = context.getString(R.string.a11y_key_space_with_ime),
        ).withReturn()
        return SceneEnvs.of(this, look, theme.isDark, width, height, input = state.input(), texts = texts, marks = keyMarks(layout))
    }

    // told before any key is tapped, and again by a keyboard kept while another's own groups were shown. Not as
    // made: a preview never shown would change the groups. Nor where the engine refused the layout
    override fun onAttach() {
        super.onAttach()
        attached = true
        tellOwnKeys()
    }

    private var attached = false

    override fun onDetach() {
        attached = false
        super.onDetach()
    }

    private fun tellOwnKeys() {
        val own = grouping.own ?: return
        if (ownDrawn) EngineState.ownKeys = own else if (EngineState.ownKeys === own) EngineState.ownKeys = null
    }

    override fun onSceneBuilt(scene: Scene?) {
        super.onSceneBuilt(scene)
        if (attached) tellOwnKeys()
    }

    // last: the scene reads the state above
    init {
        drawKeysWithScene(grouping.layout)
    }
}
