/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.pet

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.input.keyboard.BackspaceHold
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.scene.pet.PetTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only the kind of key pressed: a letter, ⌫, ⏎ or space; never voice, switching or selecting. */
class PetTriggersTest {

    private fun sym(s: Int) = KeyAction.SymAction(KeySym(s))

    @Test
    fun eachKindOfKey() {
        assertEquals(PetTrigger.TYPING, PetTriggers.of(KeyAction.HostKeyAction("a")))
        assertEquals(PetTrigger.TYPING, PetTriggers.of(KeyAction.HostKeyAction("5")))
        assertEquals(PetTrigger.TYPING, PetTriggers.of(KeyAction.CommitAction("，", punctuation = true)))
        assertEquals(PetTrigger.TYPING, PetTriggers.of(sym(FcitxKeyMapping.FcitxKey_Tab)))
        assertEquals(PetTrigger.DELETE, PetTriggers.of(sym(FcitxKeyMapping.FcitxKey_BackSpace)))
        // the press only begins the hold: the tap's own backspace follows, and two would play the state twice
        assertNull(PetTriggers.of(KeyAction.BackspaceDownAction(BackspaceHold())))
        assertEquals(PetTrigger.ENTER, PetTriggers.of(sym(FcitxKeyMapping.FcitxKey_Return)))
        assertEquals(PetTrigger.ENTER, PetTriggers.of(sym(FcitxKeyMapping.FcitxKey_KP_Enter)))
        assertEquals(PetTrigger.SPACE, PetTriggers.of(sym(FcitxKeyMapping.FcitxKey_space)))
    }

    @Test
    fun whatIsNoKey() {
        assertEquals(null, PetTriggers.of(KeyAction.CommitAction("你好", voice = true)))
        for (a in listOf(
            KeyAction.LangSwitchAction, KeyAction.LayoutSwitchAction("number"), KeyAction.CapsAction(false), KeyAction.PanelBackAction,
            KeyAction.BeginSwipeSelectAction, KeyAction.SwipeSelectAction(2), KeyAction.EndSwipeSelectAction, KeyAction.MoveSelectionAction(1, 1),
            KeyAction.QuickPhraseAction, KeyAction.ShowInputMethodPickerAction, KeyAction.PickerSwitchAction(), KeyAction.SpaceLongPressAction(0),
        )) assertEquals(a.toString(), null, PetTriggers.of(a))
    }
}
