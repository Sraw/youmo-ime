/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.canvas.ScenePanels
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.scene.look.PressEffect
import org.mechdancer.dependency.manager.must

class TextEditingWindow : InputWindow.ExtendedInputWindow<TextEditingWindow>(),
    InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private val buttonRipple get() = ThemeManager.shownLook(theme).keys.press == PressEffect.Ripple
    private val buttonBorder get() = ThemeManager.shownLook(theme).keys.blocks

    // not set, as its own setting had it: its dp a number of pixels
    private val buttons get() = ScenePanels.editing(context, theme)

    private var hasSelection = false

    /**
     * Where the tracked selection says none, the editor's own (the report may lag a select-all). getSelectedText's
     * null is none, as an editor without it or the binder's timeout cannot be told apart. A binder call on the
     * main thread (2 s at worst), only on cut or copy with the tracked selection collapsed.
     */
    private fun selectedLength(start: Int, end: Int): Int =
        if (start != end) maxOf(start, end) - minOf(start, end)
        else service.currentInputConnection?.getSelectedText(0)?.length ?: 0

    private var userSelection = false

    private fun sendDirectionKey(keyEventCode: Int) {
        service.sendCombinationKeyEvents(keyEventCode, shift = hasSelection || userSelection)
    }

    private fun sendFieldEdgeKey(keyEventCode: Int) = service.sendCombinationKeyEvents(
        keyEventCode,
        ctrl = fieldEdgeCtrl(service.currentInputEditorInfo?.toEditorTraits()?.isRawKeyInput == true),
        shift = hasSelection || userSelection,
    )

    private val ui by lazy {
        val look = buttons
        TextEditingUi(context, theme, buttonRipple, buttonBorder, ScenePanels.editingRadius(context, look), look.fill?.value).apply {
            fun CustomGestureView.onClickWithRepeating(block: () -> Unit) {
                setOnClickListener { block() }
                repeatEnabled = true
                onRepeatListener = { block() }
            }

            leftButton.onClickWithRepeating { sendDirectionKey(KeyEvent.KEYCODE_DPAD_LEFT) }

            upButton.onClickWithRepeating { sendDirectionKey(KeyEvent.KEYCODE_DPAD_UP) }

            downButton.onClickWithRepeating { sendDirectionKey(KeyEvent.KEYCODE_DPAD_DOWN) }

            rightButton.onClickWithRepeating { sendDirectionKey(KeyEvent.KEYCODE_DPAD_RIGHT) }

            homeButton.setOnClickListener { sendFieldEdgeKey(KeyEvent.KEYCODE_MOVE_HOME) }
            endButton.setOnClickListener { sendFieldEdgeKey(KeyEvent.KEYCODE_MOVE_END) }
            selectButton.setOnClickListener {
                if (hasSelection) {
                    userSelection = false
                    service.cancelSelection()
                } else {
                    userSelection = !userSelection
                    updateSelection(false, userSelection)
                }
            }
            selectAllButton.setOnClickListener {
                // activate select button after operation
                userSelection = true
                service.performContextMenuAction(android.R.id.selectAll)
            }
            cutButton.setOnClickListener {
                val range = service.currentInputSelection
                // cut and copy take the selection alone: with none, the clipboard kept, 选择 left as it was
                val selected = selectedLength(range.start, range.end)
                if (selected == 0) return@setOnClickListener service.say(R.string.nothing_to_cut)
                // deactivate select button after operation
                userSelection = false
                service.performContextMenuAction(android.R.id.cut)
                // the tracked selection lagging or not
                if (copyIsSaid(service.currentInputEditorInfo)) service.say(R.string.text_cut)
            }
            copyButton.setOnClickListener {
                val ic = service.currentInputConnection ?: return@setOnClickListener
                val selected = service.currentInputSelection.let { selectedLength(it.start, it.end) }
                if (selected == 0) return@setOnClickListener service.say(R.string.nothing_to_copy)
                userSelection = false
                ic.performContextMenuAction(android.R.id.copy)
                // only a selection is collapsed: a cursor alone stays where the editor has it, the tracked one may lag;
                // 选择 on with nothing selected sends no selection update
                val range = service.currentInputSelection
                // the bar's hint is behind this window: say so; not in a hidden password, whose editor copies nothing. The
                // tracked selection lagging: said all the same, the selection left as the editor has it
                val collapseAt = cursorAfterCopy(range.start, range.end)
                if (collapseAt != null) {
                    ic.setSelection(collapseAt, collapseAt)
                    service.changedPastSession()
                }
                if (copyIsSaid(service.currentInputEditorInfo)) service.say(R.string.text_copied)
                updateSelection(hasSelection, false)
            }
            pasteButton.setOnClickListener {
                userSelection = false
                service.performContextMenuAction(android.R.id.paste)
            }
            backspaceButton.onClickWithRepeating {
                userSelection = false
                service.sendBackspaceKey()
            }
            clipboardButton.setOnClickListener {
                windowManager.attachWindow(ClipboardWindow())
            }
        }
    }

    override fun onCreateView(): View = ui.root

    override fun onAttached() {
        val range = service.currentInputSelection
        onSelectionUpdate(range.start, range.end)
    }

    override fun onDetached() = Unit

    override fun onSelectionUpdate(start: Int, end: Int) {
        hasSelection = start != end
        ui.updateSelection(hasSelection, userSelection)
    }

    override val title by lazy {
        context.getString(R.string.text_editing)
    }

    override fun onCreateBarExtension(): View = ui.extension
}

/**
 * A bare Home / End moves only to the line's edge in a multi-line field; with Ctrl it is the field. A
 * raw-key field (TYPE_NULL, a terminal) gets them bare: Ctrl+Home there is `ESC[1;5H`, which a shell
 * seldom binds and echoes.
 */
internal fun fieldEdgeCtrl(rawKeyInput: Boolean): Boolean = !rawKeyInput

/** Not in a password whose characters are hidden, which TextView refuses to copy; inPasswordField counts a shown one too. */
internal fun copyIsSaid(info: EditorInfo?): Boolean =
    info == null || !CapabilityFlags.fromEditorInfo(info).has(CapabilityFlag.Password)

internal fun cursorAfterCopy(start: Int, end: Int): Int? = if (start == end) null else maxOf(start, end)
