/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.KeyEvent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.host.HostCore
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.editing.InputConnectionEditor
import org.fcitx.fcitx5.android.input.editing.TextEditingWindow
import org.fcitx.fcitx5.android.input.editing.copyIsSaid
import org.fcitx.fcitx5.android.input.editing.fieldEdgeCtrl
import org.fcitx.fcitx5.android.input.editing.toEditorTraits
import org.fcitx.fcitx5.android.input.learning.UndoChip
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.shortcut.ClearedText
import org.fcitx.fcitx5.android.input.shortcut.EditPlan
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.voice.VoiceFeature
import org.fcitx.fcitx5.android.input.wm.InputWindowManager

/**
 * A key's swipe shortcut carried out on the main thread, what was typed already put in: the field
 * edited through [EditPlan], the clears kept ([ClearedText]) and said with 撤销, the panels opened as
 * the bar's buttons open them ([tradSimp] and [chooseKeyboard] the bar's own).
 */
class KeyShortcutRunner(
    private val service: FcitxInputMethodService,
    private val windowManager: InputWindowManager,
    private val tradSimp: () -> Unit,
    private val chooseKeyboard: () -> Unit,
) {
    private val cleared = ClearedText()

    private val editor = InputConnectionEditor { service.currentInputConnection }

    private val field: String
        get() = service.currentInputEditorInfo?.let { "${it.packageName}/${it.fieldId}" } ?: ""

    fun run(shortcut: KeyShortcut) {
        when (shortcut) {
            is KeyShortcut.Text -> service.commitOverEngine(shortcut.text)
            is KeyShortcut.Command -> run(shortcut.command)
            KeyShortcut.None -> {}
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun run(command: KeyCommand) {
        val range = service.currentInputSelection
        when (command) {
            KeyCommand.SelectAll -> menu(EditPlan.Menu.SelectAll)
            KeyCommand.Copy -> {
                apply(EditPlan.copy(range.start, range.end))
                if (copyIsSaid(service.currentInputEditorInfo)) service.say(R.string.text_copied)
            }
            KeyCommand.Cut -> apply(EditPlan.cut(range.start, range.end))
            KeyCommand.Paste -> service.performContextMenuAction(android.R.id.paste)
            KeyCommand.Undo -> service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true)
            KeyCommand.Redo -> service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)
            // the listener's: 清除 drops what is typed first, 恢复 must not commit it
            KeyCommand.Clear -> clear(null)
            KeyCommand.Restore -> restore()
            KeyCommand.FieldStart -> fieldEdge(KeyEvent.KEYCODE_MOVE_HOME)
            KeyCommand.FieldEnd -> fieldEdge(KeyEvent.KEYCODE_MOVE_END)
            // the menu opens as the swipe passes its threshold; a lift that got here without it, the panel
            KeyCommand.EditMenu -> windowManager.attachWindow(TextEditingWindow())
            KeyCommand.Clipboard -> windowManager.attachWindow(ClipboardWindow())
            KeyCommand.Emoji -> windowManager.attachWindow(PickerWindow.Key.Emoji)
            KeyCommand.Voice -> VoiceFeature.window()?.let { windowManager.attachWindow(it) }
            KeyCommand.TradSimp -> tradSimp()
            KeyCommand.ChooseKeyboard -> chooseKeyboard()
            KeyCommand.HideKeyboard -> service.requestHideSelf(0)
        }
    }

    /**
     * 清除: [typing] what the engine dropped of what was typed (nothing committed), kept to type again;
     * with nothing typed, the field cleared as [EditPlan.clear] says.
     */
    fun clear(typing: HostCore.Retyping?) {
        val password = service.inPasswordField
        val kept = if (typing != null) {
            typing.keys?.let { EditPlan.Cleared(read() ?: return, emptyList(), typing) }
        } else {
            val now = read() ?: return
            val range = service.currentInputSelection
            val clearing = EditPlan.clear(now, minOf(range.start, range.end), READ_LIMIT, password)
            if (clearing.steps.isEmpty()) return
            apply(clearing.steps)
            clearing.cleared
        }
        cleared.remember(field, kept)
        if (kept == null) {
            service.say(R.string.field_cleared)
            return
        }
        service.say(service.getString(R.string.field_cleared), UndoChip.Undo({ restore() }))
    }

    fun restore() {
        val back = read()?.let { cleared.take(field, it) } ?: return service.say(R.string.nothing_to_restore)
        val typing = back.typed as? HostCore.Retyping
        if (typing != null) service.postFcitxJob { retype(typing) } else apply(back.restore)
    }

    // the field around its selection as a clear reads it; null without one
    private fun read(): EditPlan.Field? {
        val ic = service.currentInputConnection ?: return null
        return EditPlan.Field(
            ic.getTextBeforeCursor(READ_LIMIT, 0)?.toString() ?: "",
            ic.getSelectedText(0)?.toString() ?: "",
            ic.getTextAfterCursor(READ_LIMIT, 0)?.toString() ?: "",
        )
    }

    private fun fieldEdge(keyCode: Int) = service.sendCombinationKeyEvents(
        keyCode,
        ctrl = fieldEdgeCtrl(service.currentInputEditorInfo?.toEditorTraits()?.isRawKeyInput == true),
    )

    private fun apply(steps: List<EditPlan.Step>) = EditPlan.apply(steps, editor, ::menu)

    private fun menu(action: EditPlan.Menu) = service.performContextMenuAction(
        when (action) {
            EditPlan.Menu.SelectAll -> android.R.id.selectAll
            EditPlan.Menu.Copy -> android.R.id.copy
            EditPlan.Menu.Cut -> android.R.id.cut
        },
    )

    private companion object {
        // what a clear reads of each side to keep it: a field longer is cleared with nothing kept
        const val READ_LIMIT = 10_000
    }
}
