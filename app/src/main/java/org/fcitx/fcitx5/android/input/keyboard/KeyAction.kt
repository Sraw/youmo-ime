/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.english.EnglishSession
import android.view.MotionEvent
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.input.picker.PickerWindow

sealed class KeyAction {

    data class FcitxKeyAction(
        val act: String,
        val code: Int = ScancodeMapping.charToScancode(act[0]),
        val states: KeyStates = KeyStates.Virtual
    ) : KeyAction()

    data class SymAction(val sym: KeySym, val states: KeyStates = KeyStates.Virtual) : KeyAction()

    /** [text] put in by the app, in place of [replacing] if that is what is before the cursor. */
    data class CommitAction(val text: String, val replacing: String = "") : KeyAction()

    data class CapsAction(val lock: Boolean) : KeyAction()

    data object QuickPhraseAction : KeyAction()

    data object UnicodeAction : KeyAction()

    data object LangSwitchAction : KeyAction()

    data object ShowInputMethodPickerAction : KeyAction()

    data class LayoutSwitchAction(val act: String = "") : KeyAction()

    data class MoveSelectionAction(val start: Int = 0, val end: Int = 0) : KeyAction()

    data class DeleteSelectionAction(val totalCnt: Int = 0) : KeyAction()

    /** A touch began on the key that swipes to select; precedes its [MoveSelectionAction]s. */
    data object BeginSelectionSwipeAction : KeyAction()

    data class PickerSwitchAction(val key: PickerWindow.Key? = null) : KeyAction()

    /** [pointer]: the finger holding the key, which hold to talk follows */
    data class SpaceLongPressAction(val pointer: Int = MotionEvent.INVALID_POINTER_ID) : KeyAction()

    /** The symbol panel's 返回: the keyboard it was opened from. */
    data object PanelBackAction : KeyAction()

    /** The symbol panel's lock: it stays after a symbol is picked, for a run of them. */
    data object PanelLockAction : KeyAction()

    /** The emoji panel's search by pinyin, begun ([on]) or left. */
    data class EmojiSearchAction(val on: Boolean) : KeyAction()

    /** A glide across the letters, for English to read (GlideInput): its word committed. */
    class GlideAction(val glide: EnglishSession.Glide) : KeyAction()
}
