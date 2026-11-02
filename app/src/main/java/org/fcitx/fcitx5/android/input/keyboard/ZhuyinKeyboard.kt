/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
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
 * three a row, the tone key last: a tap ends the syllable, its tone left to the engine (分词 too), its long press each tone; a
 * group's long press each of its symbols, typed as it is. The bottom row is the grouped keyboards'.
 */
@SuppressLint("ViewConstructor")
class ZhuyinKeyboard(
    context: Context,
    theme: Theme,
    private val nine: Boolean,
) : BaseKeyboard(context, theme, if (nine) nineLayout() else fullLayout()) {

    companion object {
        const val NameNine = "ZhuyinNine"
        const val NameFull = "ZhuyinFull"

        // 大千's keys row by row, by their QWERTY place
        private val ROWS = listOf("1234567890-", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./")

        private const val SIDE = 0.16f
        private const val GROUP = 0.28f

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

        private fun key(text: String, size: Float, width: Float, action: KeyAction, popup: Array<KeyDef.Popup>) = KeyDef(
            KeyDef.Appearance.Text(text, size, percentWidth = width),
            setOf(KeyDef.Behavior.Press(action)),
            popup,
        )

        internal fun fullLayout(): List<List<KeyDef>> = ROWS.mapIndexed { r, row ->
            val keys = row.map { q ->
                val c = ZhuyinKeys.DACHEN_QWERTY.getValue(q)
                key(c.toString(), 20f, if (r == 0) 1f / 11 else if (r == 3) 0.085f else 0.1f, sym(codeOf(c, nine = false)!!), arrayOf(KeyDef.Popup.Preview(c.toString())))
            }
            if (r == 3) keys + BackspaceKey(0.15f) else keys
        } + listOf(GroupedKeyboard.bottomRow())

        internal fun nineLayout(): List<List<KeyDef>> {
            val groups = ZhuyinKeys.GROUP_SYMBOLS.mapIndexed { g, symbols ->
                key(symbols, 18f, GROUP, sym(Keyboard.zhuyinNineKey(g)), arrayOf(KeyDef.Popup.Keyboard.Explicit(symbols.map { it.toString() }.toTypedArray())))
            }
            val tone = key(
                ZhuyinKeys.MARKS, 16f, GROUP, sym(Keyboard.zhuyinNineTone(0)),
                arrayOf(KeyDef.Popup.Keyboard.Explicit(ZhuyinKeys.MARKS.map { it.toString() }.toTypedArray())),
            )
            val keys = groups + tone
            // no 分词: the tone key's tap ends a syllable as one does
            val side = listOf(BackspaceKey(SIDE)) + listOf("?", "!", "~").map { SymbolKey(it, SIDE, KeyDef.Appearance.Variant.Alternative) }
            return (0 until 4).map { r -> keys.subList(3 * r, 3 * r + 3) + side[r] } + listOf(GroupedKeyboard.bottomRow())
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
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        super.onAction(resolve(action, source, nine), source)
    }

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        `return`.showReturnAction(appearance)
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        showInputMethodName(space, ime)
    }
}
