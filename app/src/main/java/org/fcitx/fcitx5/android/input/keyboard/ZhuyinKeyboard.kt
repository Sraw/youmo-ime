/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
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
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance

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
 * A symbol key's swipe reaches its shortcut ([ZhuyinShortcutHook]: 大千's by QWERTY place, the groups' by
 * their place); the tones (ˉ swipes to ˙) and the slot have none, as the nine keys' 1.
 */
@SuppressLint("ViewConstructor")
class ZhuyinKeyboard(
    context: Context,
    theme: Theme,
    private val nine: Boolean,
) : BaseKeyboard(context, theme, if (nine) nineLayout() else fullLayout(keyboardCompact()), shortcuts = true) {

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

        private fun key(text: String, size: Float, width: Float, action: KeyAction, popup: Array<KeyDef.Popup>, shortcut: String) = KeyDef(
            KeyDef.Appearance.Text(text, size, percentWidth = width),
            setOf(KeyDef.Behavior.Press(action)),
            popup,
            shortcut = shortcut,
        )

        /** 大千's rows; [compact] its symbols smaller ([KeyTextScale.dachen]), or they are cut off. */
        internal fun fullLayout(compact: Boolean = false): List<List<KeyDef>> = ROWS.mapIndexed { r, row ->
            val keys = row.map { q ->
                val c = ZhuyinKeys.DACHEN_QWERTY.getValue(q)
                // its shortcut by the QWERTY key it sits on, the swipe's preview naming it
                key(
                    c.toString(), 20f * KeyTextScale.dachen(compact), if (r == 0) 1f / 11 else if (r == 3) 0.085f else 0.1f, sym(codeOf(c, nine = false)!!),
                    arrayOf(KeyDef.Popup.AltPreview(c.toString(), c.toString())), ZhuyinShortcutHook.fullKey(q),
                )
            }
            if (r == 3) keys + BackspaceKey(0.15f) else keys
        } + listOf(GroupedKeyboard.bottomRow())

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
            )
        }

        internal fun nineLayout(): List<List<KeyDef>> {
            val groups = ZhuyinKeys.GROUP_SYMBOLS.mapIndexed { g, symbols ->
                // its shortcut by its place in the grid, 1 to 11 (KeyShortcutPages.ZhuyinNine); the swipe's
                // preview naming it, as the nine keys' digits have
                key(
                    symbols, 18f, GROUP, sym(Keyboard.zhuyinNineKey(g)),
                    arrayOf(KeyDef.Popup.AltPreview(symbols, symbols), KeyDef.Popup.Keyboard.Explicit(symbols.map { it.toString() }.toTypedArray())),
                    ZhuyinShortcutHook.nineKey("${g + 1}"),
                )
            }
            val slot = KeyDef(
                KeyDef.Appearance.Text(PUNCTUATION, 18f, percentWidth = GROUP),
                setOf(KeyDef.Behavior.Press(slotAction(typing = false))),
                arrayOf(KeyDef.Popup.Keyboard.Explicit(PUNCTUATION_MORE, asIs = true)),
            )
            val keys = groups + slot
            val side = listOf(BackspaceKey(SIDE)) + listOf("?", "!", "~").map { SymbolKey(it, SIDE, KeyDef.Appearance.Variant.Alternative) }
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

    private val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    private val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }
    private val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }

    // as the grouped keyboards' (whose bottom row this is)
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
        lang.isVisible = showLangSwitchKey.getValue()
    }

    init {
        showAddressKeys()
        lang.isVisible = showLangSwitchKey.getValue()
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        listOf(R.id.button_comma to ",", R.id.button_period to ".").forEach { (id, p) ->
            findViewById<TextKeyView>(id).mainText.text = mapping.getOrDefault(p, p)
        }
        // the slot's comma the same: ，or, as the bottom row's
        comma = mapping.getOrDefault(",", ",")
        if (!typing) slot?.mainText?.text = comma
    }

    // something typed, as the engine last told
    private var typing = false

    // the slot's face when nothing is typed: the comma it types, as punctuation maps it
    private var comma = PUNCTUATION

    private val slot: TextKeyView? by lazy {
        if (!nine) null else keyRows[SLOT_ROW].getChildAt(SLOT_COLUMN) as TextKeyView
    }

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
        slot?.mainText?.text = if (now) SEPARATOR else comma
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

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        `return`.showReturnAction(appearance)
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        showInputMethodName(space, ime)
    }
}
