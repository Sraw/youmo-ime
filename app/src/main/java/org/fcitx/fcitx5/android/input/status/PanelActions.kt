/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.PopupMenu
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.KeyboardPicker
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyles
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.learning.clearAndTell
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil

/** The panel and the bar's buttons call the same, so a button and its entry never differ. */
class PanelActions(
    private val context: Context,
    private val service: FcitxInputMethodService,
    private val fcitx: HostConnection,
    private val scope: CoroutineScope,
    private val windowManager: InputWindowManager,
) {

    private val useHabits = AppPrefs.getInstance().internal.useHabits

    val habitsOn get() = useHabits.getValue()

    // each shape its own 键盘样式: not the screen there is now, a turn's view not yet made
    private val screen get() = service.screenBuilt ?: KeyboardArea.screen(context)

    val floating get() = KeyboardStyles.of(screen).floating

    val oneHanded get() = KeyboardStyles.of(screen).oneHanded

    // floating, one-handed is of no use. Split, one-handed over it, and off is split again
    val oneHandedOffered get() = !floating

    private var popupMenu: PopupMenu? = null

    // as the keyboard last heard of it (asking fcitx would block)
    private val keyboardPicker by lazy { KeyboardPicker(context) { fcitx.runImmediately { inputMethodEntryCached }.uniqueName } }

    val keyboardsOffered get() = keyboardPicker.offers()

    fun activate(action: StatusEntry) {
        fcitx.launchOnReady { it.activateAction(action.id) }
    }

    fun inputMethodSettings() = fcitx.runImmediately { inputMethodEntryCached }.let {
        AppUtil.launchMainToInputMethodConfig(context, it.uniqueName, InputMethodNames.of(context, it))
    }

    fun keys() = AppUtil.launchMainToKeyboard(context)

    fun themes() = AppUtil.launchMainToThemeList(context)

    // split to or from it the input view is made anew, on its letters already: this one attaches nothing; from the
    // bar TalkBack's focus goes back to the new view's button
    fun toggleOneHanded(fromBar: Boolean = false) {
        val s = screen
        val splitBefore = KeyboardStyles.of(s).splits
        if (fromBar) service.focusAfterStyleRebuild = ToolbarButton.OneHanded
        try {
            KeyboardStyles.toggleOneHanded(s)
        } finally {
            service.focusAfterStyleRebuild = null
        }
        val rebuilt = KeyboardStyles.of(s).splits != splitBefore
        if (!fromBar && !rebuilt) windowManager.attachWindow(KeyboardWindow)
    }

    // the input view is made anew for it, on the letters
    fun toggleFloating() = KeyboardStyles.toggleFloating(screen)

    // the app's preference (read when the engine is made), and the engine told now
    fun toggleHabits() {
        val on = !useHabits.getValue()
        useHabits.setValue(on)
        fcitx.launchOnReady { it.learning(LearningOp.UseHabits(on)) }
        // nothing on the bar shows which
        service.say(if (on) R.string.learning_habits_on_said else R.string.learning_habits_off_said)
    }

    /** Asked once more, saying what stays and what goes with it. */
    fun clearRecent(anchor: View) {
        val popup = PopupMenu(context, anchor)
        val spans = listOf(
            LearningOp.Span.TEN_MINUTES to R.string.learning_span_ten_minutes,
            LearningOp.Span.HOUR to R.string.learning_span_hour,
            LearningOp.Span.TODAY to R.string.learning_span_today,
        )
        for ((span, label) in spans) {
            popup.menu.add(label).setOnMenuItemClickListener {
                confirmClear(span, label)
                true
            }
        }
        popupMenu?.dismiss()
        popupMenu = popup
        popup.show()
    }

    fun chooseKeyboard(anchor: View) {
        popupMenu?.dismiss()
        popupMenu = keyboardPicker.show(anchor)
    }

    fun dismiss() {
        popupMenu?.dismiss()
        popupMenu = null
    }

    private fun confirmClear(span: LearningOp.Span, @StringRes label: Int) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.learning_clear_recent_title, context.getString(label)))
            .setMessage(R.string.learning_clear_recent_message)
            .setPositiveButton(R.string.learning_clear) { _, _ ->
                scope.launch {
                    // a restart first while learning is not saving
                    service.say(fcitx.runOnReady { clearAndTell(LearningOp.ClearRecent(span)) })
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        service.showDialog(dialog)
    }

    companion object {
        const val TRAD_SIMP = "chttrans"

        /** One with no slot on the bar is there already */
        val OWN = setOf(
            ToolbarButton.TradSimp, ToolbarButton.Floating, ToolbarButton.OneHanded, ToolbarButton.Habits, ToolbarButton.ClearRecent,
            ToolbarButton.Theme, ToolbarButton.InputMethodSettings, ToolbarButton.Keyboard,
        )
    }
}
