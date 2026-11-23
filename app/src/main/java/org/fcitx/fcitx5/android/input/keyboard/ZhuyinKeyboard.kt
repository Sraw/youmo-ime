/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
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
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.TextBoardState
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.look.ThemeLook

/**
 * Pinyin typed in zhuyin, on two keyboards sending codes of their own that pinyin's zhuyin sessions read.
 * [nine] false: 大千, the 41 keys where Taiwan's keyboards print them, tones on the number row; a space after
 * a syllable is its first tone. [nine]: 注音九键, 11 groups three a row, the tones a key each on the left (˙ on
 * ˉ's long press), each ending the syllable as 元書's do. The key after the last group is 分词 while typing,
 * punctuation else. Keys send the layouts' `action:` placeholders ([placeholder]).
 */
@SuppressLint("ViewConstructor")
class ZhuyinKeyboard(
    context: Context,
    theme: Theme,
    private val nine: Boolean,
) : BaseKeyboard(
    context,
    theme,
    // named by their place in its own 4×5 grid (the tones in the first column), ⌫ by its role
    themeBoard = if (nine) KeyNames.ZHUYIN_NINE else KeyNames.ZHUYIN,
), TypingKeys {

    companion object {
        const val NameNine = "ZhuyinNine"
        const val NameFull = "ZhuyinFull"

        private fun sym(code: Int) = KeyAction.SymAction(KeySym(UNICODE + code), KeyStates.Virtual)

        /** Null for anything but a zhuyin symbol or a tone mark of [ZhuyinKeys.MARKS]. */
        internal fun codeOf(c: Char, nine: Boolean): Int? {
            val tone = ZhuyinKeys.MARKS.indexOf(c) + 1
            return when {
                tone > 0 -> if (nine) Keyboard.zhuyinNineTone(tone) else Keyboard.zhuyinTone(tone)
                c !in ZhuyinKeys.SYMBOLS -> null
                nine -> Keyboard.zhuyinNineSymbol(c)
                else -> Keyboard.zhuyinKey(c)
            }
        }

        internal fun layoutOf(nine: Boolean): String = if (nine) KeyNames.ZHUYIN_NINE else KeyNames.ZHUYIN

        /**
         * `zhuyin` the shown symbol's code (注音九键: its group's), `tone-1`…`tone-5` the tone's, `zhuyin-slot`
         * the slot with nothing typed ([onAction] makes it 分词 while typing); null else (the key does nothing, logged).
         */
        internal fun placeholder(name: String, label: String?, nine: Boolean): KeyAction? = when {
            name.startsWith(TONE) -> name.removePrefix(TONE).toIntOrNull()?.let { ZhuyinKeys.MARKS.getOrNull(it - 1) }?.let { codeOf(it, nine) }?.let(::sym)
            name == "zhuyin-slot" -> if (nine) slotAction(typing = false) else null
            name != "zhuyin" -> null
            nine -> ZhuyinKeys.GROUP_SYMBOLS.indexOf(label).takeIf { it >= 0 }?.let { sym(Keyboard.zhuyinNineKey(it)) }
            else -> label?.singleOrNull()?.let { codeOf(it, nine = false) }?.let(::sym)
        }

        private const val TONE = "tone-"

        private const val ZHUYIN_ACTION = "action:zhuyin"

        /** Its comma a key sym, not the bottom row's HostKeyAction: [onAction] tells the two apart by it. */
        internal fun slotAction(typing: Boolean): KeyAction =
            if (typing) sym(Keyboard.zhuyinNineTone(0)) else KeyAction.SymAction(KeySym(','.code), KeyStates.Virtual)

        /** Not a tone: one tapped with nothing typed types nothing, and the engine would never tell the slot back to punctuation. */
        internal fun typesSymbol(action: KeyAction): Boolean {
            val code = (action as? KeyAction.SymAction)?.sym?.sym?.minus(UNICODE) ?: return false
            return code in Keyboard.ZHUYIN_NINE && (code - Keyboard.ZHUYIN_NINE.first).let { it < ZhuyinKeys.GROUPS || it > ZhuyinKeys.GROUPS + ZhuyinKeys.TONES }
        }

        internal fun resolve(action: KeyAction, source: KeyActionListener.Source, nine: Boolean): KeyAction {
            if (source != KeyActionListener.Source.Popup) return action
            val c = (action as? KeyAction.HostKeyAction)?.act?.singleOrNull() ?: return action
            return codeOf(c, nine)?.let(::sym) ?: action
        }
    }

    override fun onAddressChanged() {
        canvas?.invalidateScene()
    }

    private fun mapped(p: String) = keysPunctuation.getOrDefault(p, p)

    // a bubble types what its key shows: ? ! ~ , . as punctuation maps them, as GroupedKeyboard's; public for its test
    public override fun onPopupAction(action: PopupAction) {
        val shown = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = previewOf(action.content, action.key, { it }, ::mapped))
            is PopupAction.PreviewUpdateAction -> if (action.shown) action else action.copy(content = previewOf(action.content, action.key, { it }, ::mapped))
            else -> action
        }
        super.onPopupAction(shown)
    }

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
        val out = if (nine && source == KeyActionListener.Source.Keyboard && action == slotAction(typing = false)) slotAction(typing) else action
        val sent = resolve(out, source, nine)
        // the engine has not told yet; not to another input method, which would never tell it back
        if (nine && typesSymbol(sent) && EngineState.syllables.value.im == InputMethodCatalog.PINYIN) showTyping(true)
        super.onAction(sent, source)
    }

    override val keysIm: String get() = imeName

    // no glide, no strokes: 手写 alone. With no places GlideInput's down finds no key and nothing is written
    override val glide = glideInput(
        letters = { canvas?.shownKeys.orEmpty().filter { it.kind == "letter" }.map { ' ' to scenePlace(it.id, it.copy == 1) } },
        enabled = { false },
    ) { _, _ -> }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        super.onInputMethodUpdate(ime)
        showInputMethodName(ime)
    }

    override val sceneTable = object : SceneKeyDef.Table {
        override fun action(name: String, node: KeyNode): KeyAction? = placeholder(name, node.label, nine)

        // action:zhuyin's code is made from the face as the key is made: a key kept through a face changed went on
        // sending the last face's symbol
        override fun reads(node: KeyNode): Any? = node.label.takeIf { ZHUYIN_ACTION in node.actions.values }
    }

    // not a tone's
    override fun typingKeys(): List<Pair<Char, KeyRef>> = canvas?.shownKeys.orEmpty().mapNotNull { node ->
        if (ZHUYIN_ACTION !in node.actions.values) return@mapNotNull null
        val code = if (nine) {
            ZhuyinKeys.GROUP_SYMBOLS.indexOf(node.label).takeIf { it >= 0 }?.let(Keyboard::zhuyinNineKey)
        } else {
            node.label?.singleOrNull()?.let { codeOf(it, nine = false) }
        }
        code?.let(Keyboard::keyedChar)?.let { it to KeyRef(node.id, node.copy == 1) }
    }

    override fun sceneEnv(look: ThemeLook, layout: SceneLayout, width: Int, height: Int): Env {
        val state = TextBoardState(address = address, langKey = showLangSwitchKey.getValue(), holdVoice = holdsToTalk)
        // TextBoardState's composing is a table's alone (its 确定): the slot's said here
        val input = state.input().copy(composing = typing)
        val texts = Texts(
            alts = keyReadings(layout),
            imeName = shownImeName.orEmpty(),
            comma = mapped(","),
            period = mapped("."),
            confirm = context.getString(R.string.key_confirm),
            punctuation = keysPunctuation,
            // the format as it is: the engine fills its place (getString with no arguments formats nothing)
            a11ySpace = context.getString(R.string.a11y_key_space_with_ime),
        ).withReturn()
        return SceneEnvs.of(this, look, theme.isDark, width, height, input = input, texts = texts, marks = keyMarks(layout))
    }

    // last: the scene reads the state above
    init {
        drawKeysWithScene(layoutOf(nine))
    }
}
