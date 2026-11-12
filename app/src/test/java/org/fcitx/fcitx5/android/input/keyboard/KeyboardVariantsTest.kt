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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardVariantsTest {

    @Test
    fun aKeyboardIsTheSameForEveryInputMethodBut双拼s() {
        val ms = ShuangpinScheme.MICROSOFT
        assertEquals(KeyboardVariant(Form.Full), KeyboardVariants.of(Form.Full, InputMethodCatalog.PINYIN, ms))
        // English and a table's no case of their own (用户 2026-11-11: 以拼音为准)
        assertEquals(KeyboardVariant(Form.Full), KeyboardVariants.of(Form.Full, InputMethodCatalog.ENGLISH, ms))
        assertEquals(KeyboardVariant(Form.Nine), KeyboardVariants.of(Form.Nine, InputMethodCatalog.ENGLISH, ms))
        assertEquals(KeyboardVariant(Form.Full), KeyboardVariants.of(Form.Full, InputMethodCatalog.WUBI, ms))
        // 微软 types `;`: its 18 keys' L group has it; 小鹤 does not
        val sp = KeyboardVariants.of(Form.Grouped18, InputMethodCatalog.SHUANGPIN_KEYS18, ms)
        assertTrue(sp.semicolon)
        assertFalse(KeyboardVariants.of(Form.Grouped18, InputMethodCatalog.SHUANGPIN_KEYS18, ShuangpinScheme.XIAOHE).semicolon)
        assertEquals(KeyboardVariants.SHUANGPIN_NINE, KeyboardVariants.of(Form.Nine, InputMethodCatalog.SHUANGPIN_NINE, ms).nineLetters)
        // 注音's and 手写's keyboards are theirs whatever types on them
        assertEquals(KeyboardVariant(Form.ZhuyinNine), KeyboardVariants.of(Form.ZhuyinNine, InputMethodCatalog.ZHUYIN_NINE, ms))
        assertEquals(KeyboardVariant(Form.Handwriting), KeyboardVariants.of(Form.Handwriting, InputMethodCatalog.SHUANGPIN, ms))
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
