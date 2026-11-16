/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.MotionEvent
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut

sealed class KeyAction {

    /** A key typed as [act]; [touch], where a typing key's tap fell and the keys around it (061-neighbour-correction), the engine's to read. */
    data class HostKeyAction(
        val act: String,
        val code: Int = ScancodeMapping.charToScancode(act[0]),
        val states: KeyStates = KeyStates.Virtual,
        val touch: Touch? = null,
    ) : KeyAction() {
        // as before touches: a key's wiring (TextSceneWiringTest's snapshots) declares none
        override fun toString() = "HostKeyAction(act=$act, code=$code, states=$states${touch?.let { ", touch=$it" }.orEmpty()})"
    }

    /** A key typed as [sym]; [touch] as [HostKeyAction]'s; [hold], a held ⌫'s repeat's, which stops it ([BackspaceHold]). */
    data class SymAction(
        val sym: KeySym,
        val states: KeyStates = KeyStates.Virtual,
        val touch: Touch? = null,
        val hold: BackspaceHold? = null,
    ) : KeyAction() {
        override fun toString() = "SymAction(sym=$sym, states=$states${touch?.let { ", touch=$it" }.orEmpty()})"

        companion object {
            /** The engine's key for [stroke], one of 横竖撇点折 as Strokes.KEYS has them (笔画筛选: drawn on the keys, or a swipe slot's). */
            fun stroke(stroke: Char) = SymAction(KeySym(UNICODE + Keyboard.strokeKey(stroke)), KeyStates.Virtual)
        }
    }

    /** ⌫ going down, to be held maybe: what is typed as it does, told [hold] in its turn on the host's thread. */
    class BackspaceDownAction(val hold: BackspaceHold) : KeyAction()

    /**
     * [text] put in by the app, in place of [replacing] if that is what is before the cursor. [punctuation]: a mark as the
     * punctuation maps it (九键's 1 and its column), held back as the punctuation keys are while that is not known.
     * [voice]: a talk's text (语音先问再记 follows it).
     */
    data class CommitAction(val text: String, val replacing: String = "", val punctuation: Boolean = false, val voice: Boolean = false) : KeyAction()

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

    /** A key's swipe reached 滑选 ([org.fcitx.fcitx5.android.input.shortcut.KeyCommand.SwipeSelect]): the selection starts at the cursor; precedes its [SwipeSelectAction]s. */
    data object BeginSwipeSelectAction : KeyAction()

    /** 滑选's touch lifted or taken away: the selection stays, the editor's as it reported it. */
    data object EndSwipeSelectAction : KeyAction()

    /**
     * 滑选's free end moved [steps] characters, negative to the left, then [lines] up or down, the editor moving
     * the free end a line on screen as the space's slide moves the cursor ([SpaceCursor]); [moved] run where the selection did move (the key's buzz).
     */
    class SwipeSelectAction(val steps: Int, val lines: List<SpaceCursor.Line> = emptyList(), val moved: () -> Unit = {}) : KeyAction()

    data class PickerSwitchAction(val key: PickerWindow.Key? = null) : KeyAction()

    /** [pointer]: the finger holding the key, which hold to talk follows */
    data class SpaceLongPressAction(val pointer: Int = MotionEvent.INVALID_POINTER_ID) : KeyAction()

    /** The symbol panel's 返回: the keyboard it was opened from. */
    data object PanelBackAction : KeyAction()

    /** The symbol panel's lock: it stays after a symbol is picked, for a run of them. */
    data object PanelLockAction : KeyAction()

    /** The emoji panel's search by pinyin, begun ([on]) or left. */
    data class EmojiSearchAction(val on: Boolean) : KeyAction()

    /** A glide across the letters, for the input method to read (GlideInput, InputMethodCatalog.glides). */
    class GlideAction(val glide: Glide) : KeyAction()

    /** A key's swipe shortcut ([KeyShortcut], never None), run once what is typed is put in (KeyShortcutRunner). */
    data class ShortcutAction(val shortcut: KeyShortcut) : KeyAction()
}
