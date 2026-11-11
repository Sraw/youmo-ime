/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keys.KeyState
import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
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
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.scene.TextBoardState
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.look.ThemeLook

/**
 * Pinyin typed in zhuyin (design 051), on one of two keyboards, both sending codes of their own
 * ([Keyboard.ZHUYIN_FULL], [Keyboard.ZHUYIN_NINE]) that pinyin's zhuyin sessions read.
 *
 * [nine] false: 大千, the 41 keys where Taiwan's keyboards print them ([ZhuyinKeys.DACHEN_QWERTY], the
 * keys named by their QWERTY place), the tones ˊˇˋ˙ on the number row; a space after a syllable is
 * its first tone, the session's. [nine]: 注音九键, the symbols in [ZhuyinKeys.GROUP_SYMBOLS]' 11 groups,
 * three a row, and on the left the tones ˉ ˊ ˇ ˋ a key each (˙ on ˉ's long press), as Taiwan's
 * 元書 has them: each ends the syllable and filters by its tone. The key after the last group is
 * 分词 while typing, punctuation else (pinyin's nine keys' 1). A group's long press each of its symbols. The bottom row is the grouped keyboards'.
 * A symbol key's swipe reaches its shortcut ([ZhuyinShortcutNames]: 大千's by QWERTY place, the groups' by
 * their place); the tones (ˉ swipes to ˙) and the slot have none, as the nine keys' 1.
 *
 * Both are drawn by the scene ([layoutOf]: 大千's zhuyin.xml, 060-render-p2 S4c;
 * 注音九键's zhuyin-nine.xml, S4d) and wired from their layouts: what their keys send is the layouts'
 * `action:` placeholders, each this keyboard's ([placeholder]), a group's long press its symbols
 * ([groupPopup]), and what the slot and the bottom row show their state ([sceneEnv]), drawn again as it changes.
 */
@SuppressLint("ViewConstructor")
class ZhuyinKeyboard(
    context: Context,
    theme: Theme,
    private val nine: Boolean,
) : BaseKeyboard(
    context,
    theme,
    if (nine) nineLayout() else fullLayout(keyboardCompact()),
    // 注音九键's keys are named by their place in its own 4×5 grid (the tones in the first column), ⌫ by its role
    themeBoard = if (nine) KeyNames.ZHUYIN_NINE else KeyNames.ZHUYIN,
    shortcuts = true,
) {

    companion object {
        const val NameNine = "ZhuyinNine"
        const val NameFull = "ZhuyinFull"

        // 大千's keys row by row, by their QWERTY place
        private val ROWS = listOf("1234567890-", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./")

        private const val SIDE = 0.14f
        private const val GROUP = 0.24f

        // the slot in the grid's last row: after the tone ˋ and the groups ㄈㄌㄝ and ㄖㄥㄩ
        private const val SLOT_ROW = 3
        private const val SLOT_COLUMN = 3

        private fun sym(code: Int) = KeyAction.SymAction(KeySym(UNICODE + code), KeyStates.Virtual)

        /** What [c], a zhuyin symbol or a tone mark of [ZhuyinKeys.MARKS], sends on the keyboard: null for anything else. */
        internal fun codeOf(c: Char, nine: Boolean): Int? {
            val tone = ZhuyinKeys.MARKS.indexOf(c) + 1
            return when {
                tone > 0 -> if (nine) Keyboard.zhuyinNineTone(tone) else Keyboard.zhuyinTone(tone)
                c !in ZhuyinKeys.SYMBOLS -> null
                nine -> Keyboard.zhuyinNineSymbol(c)
                else -> Keyboard.zhuyinKey(c)
            }
        }

        @Suppress("LongParameterList")
        private fun key(text: String, size: Float, width: Float, action: KeyAction, popup: Array<KeyDef.Popup>, shortcut: String, name: String? = null) = KeyDef(
            KeyDef.Appearance.Text(text, size, percentWidth = width),
            setOf(KeyDef.Behavior.Press(action)),
            popup,
            name = name,
            shortcut = shortcut,
        )

        /** 大千's rows; [compact] its symbols smaller ([KeyTextScale.dachen]), or they are cut off. */
        internal fun fullLayout(compact: Boolean = false): List<List<KeyDef>> = ROWS.mapIndexed { r, row ->
            val keys = row.map { q ->
                val c = ZhuyinKeys.DACHEN_QWERTY.getValue(q)
                // its shortcut and its theme name (KeyNames.ZHUYIN) by the QWERTY key it sits on, the swipe's preview naming it
                key(
                    c.toString(), 20f * KeyTextScale.dachen(compact), if (r == 0) 1f / 11 else if (r == 3) 0.085f else 0.1f, sym(codeOf(c, nine = false)!!),
                    arrayOf(KeyDef.Popup.AltPreview(c.toString(), c.toString())), ZhuyinShortcutNames.fullKey(q), name = q.toString(),
                )
            }
            if (r == 3) keys + BackspaceKey(0.15f) else keys
        } + listOf(GroupedKeyboard.bottomRow())

        /** The layout the keys of [nine] or 大千 are drawn from (lib/keyboard-scene's zhuyin.xml, zhuyin-nine.xml), named as the theme names the board. */
        internal fun layoutOf(nine: Boolean): String = if (nine) KeyNames.ZHUYIN_NINE else KeyNames.ZHUYIN

        /**
         * What the layout's `action:`[name] sends on a key showing [label] (060-render-p2 U4 B: the app's
         * table): `zhuyin` the code of the symbol shown ([codeOf]; 注音九键's, its group's of the symbols
         * shown), `tone-1`…`tone-5` the tone's (ˉ ˊ ˇ ˋ ˙), `zhuyin-slot` 注音九键's slot as it is while
         * nothing is typed ([onAction] makes it 分词 while typing); null for anything else (the key does nothing, logged).
         */
        internal fun placeholder(name: String, label: String?, nine: Boolean): KeyAction? = when {
            name.startsWith(TONE) -> name.removePrefix(TONE).toIntOrNull()?.let { ZhuyinKeys.MARKS.getOrNull(it - 1) }?.let { codeOf(it, nine) }?.let(::sym)
            name == "zhuyin-slot" -> if (nine) slotAction(typing = false) else null
            name != "zhuyin" -> null
            nine -> ZhuyinKeys.GROUP_SYMBOLS.indexOf(label).takeIf { it >= 0 }?.let { sym(Keyboard.zhuyinNineKey(it)) }
            else -> label?.singleOrNull()?.let { codeOf(it, nine = false) }?.let(::sym)
        }

        private const val TONE = "tone-"

        /**
         * 注音九键's group's long press (its layout's `popup from="group"`): each zhuyin symbol the key
         * shows, as its view's ([nineLayout]); null where it shows none. The built-in groups show their
         * symbols alone; the filter is for a layout P3's themes may bring, whose group's label could
         * carry something else (a space, a mark): never offered as a symbol the session has no code for.
         */
        internal fun groupPopup(label: String?): KeyDef.Popup.Keyboard? =
            label?.filter { it in ZhuyinKeys.SYMBOLS }?.ifEmpty { null }?.map { it.toString() }?.let { KeyDef.Popup.Keyboard.Explicit(it.toTypedArray()) }

        /** The 分词 key's face while something is typed; else [PUNCTUATION]'s first, which it types. */
        internal const val SEPARATOR = "分词"
        internal const val PUNCTUATION = "，"
        private val PUNCTUATION_MORE = arrayOf("。", "？", "！", "、", "：", "；")

        /**
         * The key in the grid after ㄖㄥㄩ's group: 分词 while [typing], else punctuation, as pinyin's nine keys' 1.
         * Its comma a key sym, not the bottom row's [KeyAction.HostKeyAction]: [onAction] tells the two apart
         * by it, and the bottom row's stays a comma while typing.
         */
        internal fun slotAction(typing: Boolean): KeyAction =
            if (typing) sym(Keyboard.zhuyinNineTone(0)) else KeyAction.SymAction(KeySym(','.code), KeyStates.Virtual)

        // ˉ ˊ ˇ ˋ: a tap each, as 元書's zhuyin nine keys; ˙ on ˉ's long press and swipe
        // its theme name (KeyNames.ZHUYIN_NINE): r1c1 … r4c1
        private fun toneKey(tone: Int): KeyDef {
            val mark = ZhuyinKeys.MARKS[tone - 1].toString()
            val neutral = ZhuyinKeys.MARKS[4].toString()
            return KeyDef(
                KeyDef.Appearance.Text(mark, 20f, percentWidth = SIDE, variant = KeyDef.Appearance.Variant.Alternative),
                if (tone == 1) {
                    setOf(KeyDef.Behavior.Press(sym(Keyboard.zhuyinNineTone(1))), KeyDef.Behavior.Swipe(sym(Keyboard.zhuyinNineTone(5))))
                } else {
                    setOf(KeyDef.Behavior.Press(sym(Keyboard.zhuyinNineTone(tone))))
                },
                if (tone == 1) arrayOf(KeyDef.Popup.Preview(mark), KeyDef.Popup.Keyboard.Explicit(arrayOf(neutral))) else arrayOf(KeyDef.Popup.Preview(mark)),
                name = "r${tone}c1",
            )
        }

        internal fun nineLayout(): List<List<KeyDef>> {
            val groups = ZhuyinKeys.GROUP_SYMBOLS.mapIndexed { g, symbols ->
                // its shortcut by its place in the grid, 1 to 11 (KeyShortcutPages.ZhuyinNine); the swipe's
                // preview naming it, as the nine keys' digits have
                key(
                    symbols, 18f, GROUP, sym(Keyboard.zhuyinNineKey(g)),
                    arrayOf(KeyDef.Popup.AltPreview(symbols, symbols), KeyDef.Popup.Keyboard.Explicit(symbols.map { it.toString() }.toTypedArray())),
                    ZhuyinShortcutNames.nineKey("${g + 1}"),
                    name = "r${g / 3 + 1}c${g % 3 + 2}",
                )
            }
            val slot = KeyDef(
                KeyDef.Appearance.Text(PUNCTUATION, 18f, percentWidth = GROUP),
                setOf(KeyDef.Behavior.Press(slotAction(typing = false))),
                arrayOf(KeyDef.Popup.Keyboard.Explicit(PUNCTUATION_MORE, asIs = true)),
                name = "r${SLOT_ROW + 1}c${SLOT_COLUMN + 1}",
            )
            val keys = groups + slot
            val side = listOf(BackspaceKey(SIDE)) + listOf("?", "!", "~").mapIndexed { i, c -> SymbolKey(c, SIDE, KeyDef.Appearance.Variant.Alternative, name = "r${i + 2}c5") }
            val rows = (0 until 4).map { r -> listOf(toneKey(r + 1)) + keys.subList(3 * r, 3 * r + 3) + side[r] }
            check(rows[SLOT_ROW][SLOT_COLUMN] === slot)
            return rows + listOf(GroupedKeyboard.bottomRow())
        }

        /**
         * Whether [action] types a zhuyin symbol on 注音九键 (a group's or a long press's symbol): the
         * slot is 分词 after it. Not a tone: one tapped with nothing typed types nothing, and the
         * engine, its state unchanged, would never tell the slot back to punctuation.
         */
        internal fun typesSymbol(action: KeyAction): Boolean {
            val code = (action as? KeyAction.SymAction)?.sym?.sym?.minus(UNICODE) ?: return false
            return code in Keyboard.ZHUYIN_NINE && (code - Keyboard.ZHUYIN_NINE.first).let { it < ZhuyinKeys.GROUPS || it > ZhuyinKeys.GROUPS + ZhuyinKeys.TONES }
        }

        /** A symbol or tone a long press offered, as its code; anything else as it is. */
        internal fun resolve(action: KeyAction, source: KeyActionListener.Source, nine: Boolean): KeyAction {
            if (source != KeyActionListener.Source.Popup) return action
            val c = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull() ?: return action
            return codeOf(c, nine)?.let(::sym) ?: action
        }
    }

    // as the grouped keyboards' (whose bottom row this is)
    override var address: AddressKeys = AddressKeys.NONE
        set(value) {
            field = value
            canvas?.invalidateScene()
        }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        showLanguageKey()
    }

    private fun showLanguageKey() {
        canvas?.invalidateScene()
    }

    init {
        showLanguageKey()
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    private var punctuationMapping: Map<String, String> = emptyMap()
    private fun mapped(p: String) = punctuationMapping.getOrDefault(p, p)

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        canvas?.invalidateScene()
    }

    // a bubble as its key shows it, what it types: ? ! ~ and the bottom row's comma and full stop as punctuation
    // maps them (060-render-p2 §5.3, the user's 「显示和输出一致」), as GroupedKeyboard's; public for its test
    public override fun onPopupAction(action: PopupAction) {
        val shown = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = previewOf(action.content, action.key, { it }, ::mapped))
            is PopupAction.PreviewUpdateAction -> action.copy(content = previewOf(action.content, action.key, { it }, ::mapped))
            else -> action
        }
        super.onPopupAction(shown)
    }

    // something typed, as the engine last told
    private var typing = false

    private var scope: CoroutineScope? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!nine) return
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            s.launch {
                EngineState.syllables.map { it.im == InputMethodCatalog.PINYIN && it.composing }.collectLatest(::showTyping)
            }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    @VisibleForTesting
    internal fun showTyping(now: Boolean) {
        if (now == typing) return
        typing = now
        canvas?.invalidateScene()
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        // the slot's tap as the engine last told: 分词 while typing, punctuation else
        val out = if (nine && source == KeyActionListener.Source.Keyboard && action == slotAction(typing = false)) slotAction(typing) else action
        val sent = resolve(out, source, nine)
        // a group or symbol sent to pinyin: the next tap of the slot is 分词, though the engine has not told
        // yet (not to another input method, which would never tell it back)
        if (nine && typesSymbol(sent) && EngineState.syllables.value.im == InputMethodCatalog.PINYIN) showTyping(true)
        super.onAction(sent, source)
    }

    // Return as last shown: its icon on the scene's key too
    private var returnAppearance = ReturnKeyAppearance.Enter

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        returnAppearance = appearance
        canvas?.invalidateScene()
    }

    // the input method shown, for whether 键盘手写 writes on the keys
    private var imeName = ""

    override val keysIm: String get() = imeName

    override fun keyState(): KeyState = super.keyState().copy(punctuation = punctuationMapping)

    // no glide, no strokes: 键盘手写 alone, written over the symbol keys as the grouped keyboards' are. Their places
    // are where a stroke may start (with none, GlideInput's down finds no key and nothing is written); the char is unread
    override val glide = glideInput(
        letters = { canvas?.shownKeys.orEmpty().filter { it.kind == "letter" }.map { ' ' to scenePlace(it.id, it.copy == 1) } },
        enabled = { false },
        writes = { InputMethodCatalog.writesOnKeys(imeName) },
    ) { _, _ -> }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        imeName = ime.uniqueName
        showInputMethodName(ime)
        canvas?.invalidateScene()
    }

    // a symbol's or a tone's code (the layout's action:zhuyin, action:tone-N) by what its key shows; 注音九键's
    // group's long press its symbols
    override val sceneTable = object : SceneKeyDef.Table {
        override fun action(name: String, node: KeyNode): KeyAction? = placeholder(name, node.label, nine)
        override fun popup(from: String, node: KeyNode): KeyDef.Popup.Keyboard? = if (nine && from == "group") groupPopup(node.label) else null
    }

    // what the bottom row shows now (grouped-bottom.xml's when= and bindings, as the grouped keyboards'): an
    // address's keys, the language key, the space's input method and microphone, Return, the comma and the
    // full stop as punctuation maps them; 注音九键's slot 分词 while typing (its `when="composing"`), ? ! ~ as mapped
    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
        val state = TextBoardState(address = address, langKey = showLangSwitchKey.getValue(), holdVoice = holdsToTalk)
        // TextBoardState's composing is a table's alone (its 确定): the slot's said here (typing is the nine's alone)
        val input = state.input().copy(composing = typing)
        val texts = Texts(
            imeName = shownImeName.orEmpty(),
            comma = mapped(","),
            period = mapped("."),
            returnLabel = returnAppearance.label(context).toString(),
            returnIcon = SceneIcons.returnName(returnAppearance.icon),
            confirm = context.getString(R.string.key_confirm),
            punctuation = punctuationMapping,
            // the format as it is: the engine fills its place (getString with no arguments formats nothing)
            a11ySpace = context.getString(R.string.a11y_key_space_with_ime),
        )
        return SceneEnvs.of(this, look, theme.isDark, width, height, input = input, texts = texts, marks = keyMarks(layout))
    }

    // last: the scene reads the state above (the keys drawn by the scene engine, 大千's and 注音九键's alike)
    init {
        drawKeysWithScene(layoutOf(nine))
    }
}
