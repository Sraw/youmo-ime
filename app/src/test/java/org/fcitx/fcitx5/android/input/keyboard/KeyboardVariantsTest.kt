/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keys.KeyOutput
import org.fcitx.fcitx5.android.input.scene.SceneAction
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant.Form
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardVariantsTest {

    @Test
    fun aKeyboardsLanguageIsItsInputMethods() {
        val ms = ShuangpinScheme.MICROSOFT
        assertEquals(KeyboardVariant(Form.Full), KeyboardVariants.of(Form.Full, InputMethodCatalog.PINYIN, ms))
        assertEquals(Language.English, KeyboardVariants.of(Form.Full, InputMethodCatalog.ENGLISH, ms).language)
        assertEquals(Language.Table, KeyboardVariants.of(Form.Full, InputMethodCatalog.WUBI, ms).language)
        // 微软 types `;`: its 18 keys' L group has it; 小鹤 does not
        val sp = KeyboardVariants.of(Form.Grouped18, InputMethodCatalog.SHUANGPIN_KEYS18, ms)
        assertEquals(Language.Shuangpin, sp.language)
        assertTrue(sp.semicolon)
        assertFalse(KeyboardVariants.of(Form.Grouped18, InputMethodCatalog.SHUANGPIN_KEYS18, ShuangpinScheme.XIAOHE).semicolon)
        assertEquals(KeyboardVariants.SHUANGPIN_NINE, KeyboardVariants.of(Form.Nine, InputMethodCatalog.SHUANGPIN_NINE, ms).nineLetters)
        // 注音's and 手写's keyboards are theirs whatever types on them
        assertEquals(Language.Zhuyin, KeyboardVariants.of(Form.ZhuyinNine, InputMethodCatalog.ZHUYIN_NINE, ms).language)
        assertEquals(Language.Handwriting, KeyboardVariants.of(Form.Handwriting, InputMethodCatalog.HANDWRITING, ms).language)
    }

    @Test
    fun theEditorsVariantsOfAForm() {
        val nine = KeyboardVariants.all(Form.Nine, ShuangpinScheme.XIAOHE)
        assertEquals(listOf(Language.Pinyin, Language.Shuangpin, Language.English), nine.map { it.language })
        assertEquals(listOf(Language.Pinyin, Language.Shuangpin), KeyboardVariants.all(Form.Grouped14, ShuangpinScheme.XIAOHE).map { it.language })
    }

    @Test
    fun whatAnItemTypesAsTheAppsAction() {
        assertEquals(KeyAction.HostKeyAction("a"), KeyOutput.Press("a").toKeyAction())
        assertEquals(KeyAction.CommitAction("ing"), KeyOutput.Press("ing").toKeyAction())
        assertEquals(KeyAction.CommitAction(":-)"), KeyOutput.Text(":-)").toKeyAction())
        val keypad = KeyOutput.Keypad('1').toKeyAction() as KeyAction.SymAction
        assertEquals(KeySym(FcitxKeyMapping.FcitxKey_KP_1), keypad.sym)
        assertEquals(KeyStates.Virtual, keypad.states)
        assertEquals(KeyAction.LayoutSwitchAction("number"), KeyOutput.Action(SceneAction.Layout("number")).toKeyAction())
    }
}
