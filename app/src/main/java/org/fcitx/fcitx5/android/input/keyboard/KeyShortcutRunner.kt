/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.KeyEvent
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
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
    /** Whether something is typed (the engine's preedit shown): no clear is put back over it. */
    private val typing: () -> Boolean,
) {
    private val cleared: ClearedText get() = service.clearedText

    private val editor = InputConnectionEditor { service.currentInputConnection }

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
                // the field as it was, the cursor back: a run of Backspaces goes on after it, as after the panel's copy (review L3)
                service.quietly { apply(EditPlan.copy(range.start, range.end)) }
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
            // a key's swipe opens it in CommonKeyActionListener, as 符's long press; from here (a menu's) the same
            KeyCommand.QuickInput -> service.postFcitxJob { triggerQuickPhrase() }
            KeyCommand.Emoji -> windowManager.attachWindow(PickerWindow.Key.Emoji)
            KeyCommand.Voice -> VoiceFeature.window()?.let { windowManager.attachWindow(it) }
            KeyCommand.TradSimp -> tradSimp()
            KeyCommand.ChooseKeyboard -> chooseKeyboard()
            KeyCommand.HideKeyboard -> service.requestHideSelf(0)
        }
    }

    /**
     * 清除 in [field] (the input started then): [typing] what the engine dropped of what was typed
     * (nothing committed), kept to type again; with nothing typed, the field cleared as [EditPlan.clear]
     * says. Nothing done once input has moved on to another field.
     */
    fun clear(typing: HostCore.Retyping?, field: Any? = service.startedField) {
        if (field == null || field !== service.startedField) return
        val kept = if (typing != null) {
            // a field not read is said cleared, those before it broken off as for one not kept (review2).
            // A sensitive field's keys the host kept back itself (HostCore.clearTyped): nothing of it here
            typing.keys?.let { read()?.let { now -> EditPlan.Cleared(now, emptyList(), typing) } }
        } else {
            val now = read() ?: return
            // a field kept from learning is kept from this too (review2): nothing of it remembered
            val sensitive = service.inSensitiveField
            val range = service.currentInputSelection
            val clearing = EditPlan.clear(now, range.start, range.end, READ_LIMIT, sensitive)
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
        val field = service.startedField
        val back = field?.let { read()?.let { now -> cleared.take(field, now, typing()) } } ?: return service.say(R.string.nothing_to_restore)
        val typed = back.typed as? HostCore.Retyping
        if (typed == null) return apply(back.restore)
        // the input method changed since, its keyboard (review2), or the field: said, the clear gone all
        // the same (review S4)
        val keyboard = keyboardOf(typed.im)
        service.postFcitxJob {
            if (!retype(typed, keyboard)) ContextCompat.getMainExecutor(service).execute { service.say(R.string.nothing_to_restore) }
        }
    }

    // the field around its selection as a clear reads it; null without one, or where the app gives
    // neither side (no text read is no empty field: review S2)
    private fun read(): EditPlan.Field? {
        val ic = service.currentInputConnection ?: return null
        return EditPlan.Field(
            ic.getTextBeforeCursor(READ_LIMIT, 0)?.toString() ?: return null,
            ic.getSelectedText(0)?.toString() ?: "",
            ic.getTextAfterCursor(READ_LIMIT, 0)?.toString() ?: return null,
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

    companion object {
        // what a clear reads of each side to keep it: a field longer is cleared with nothing kept
        private const val READ_LIMIT = 10_000

        /** What [im] is typed with now, its layout and 双拼's scheme: what was typed is typed again on the same alone ([HostCore.retype]). */
        fun keyboardOf(im: String): ImKeyboards.TypedWith =
            ImKeyboards.typedWith(im, AppPrefs.getInstance().keyboard.imKeyboards(), EngineState.shuangpinScheme)
    }
}
