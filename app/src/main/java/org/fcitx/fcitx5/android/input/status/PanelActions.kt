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
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.keyboard.KeyboardPicker
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil

/**
 * What the ⋯ panel's own entries do, in one place: the panel (StatusAreaWindow) and the bar's
 * buttons (ToolbarConfig) call the same, so a button and its entry never differ.
 */
class PanelActions(
    private val context: Context,
    private val service: FcitxInputMethodService,
    private val fcitx: HostConnection,
    private val scope: CoroutineScope,
    private val windowManager: InputWindowManager,
) {

    private val useHabits = AppPrefs.getInstance().internal.useHabits

    val habitsOn get() = useHabits.getValue()

    val floating get() = ThemeManager.prefs.floatingKeyboard.getValue()

    val oneHanded get() = ThemeManager.prefs.oneHanded.getValue() != OneHanded.Off

    // floating or the letters split, one-handed mode is of no use: not offered
    val oneHandedOffered get() = !floating && !TextKeyboard.splits(context)

    private var popupMenu: PopupMenu? = null

    // 键盘: the input method now on, as the keyboard last heard of it (asking fcitx would block)
    private val keyboardPicker by lazy { KeyboardPicker(context) { fcitx.runImmediately { inputMethodEntryCached }.uniqueName } }

    /** Whether the input method on has keyboards to choose from (I1 Q1). */
    val keyboardsOffered get() = keyboardPicker.offered() != null

    fun activate(action: StatusEntry) {
        fcitx.launchOnReady { it.activateAction(action.id) }
    }

    fun inputMethodSettings() = fcitx.runImmediately { inputMethodEntryCached }.let {
        AppUtil.launchMainToInputMethodConfig(context, it.uniqueName, InputMethodNames.of(context, it))
    }

    fun keys() = AppUtil.launchMainToKeyboard(context)

    fun themes() = AppUtil.launchMainToThemeList(context)

    // on, at the side it was last at, or off; then, from the panel, the keyboard as it now goes
    fun toggleOneHanded(reattach: Boolean = true) {
        val oneHanded = ThemeManager.prefs.oneHanded
        val side = ThemeManager.prefs.oneHandedSide
        oneHanded.setValue(if (oneHanded.getValue() == OneHanded.Off) side.getValue() else OneHanded.Off)
        if (reattach) windowManager.attachWindow(KeyboardWindow)
    }

    // the input view is made anew for it, on the letters
    fun toggleFloating() = ThemeManager.prefs.floatingKeyboard.run { setValue(!getValue()) }

    // the app's preference (read when the engine is made), and the engine told now
    fun toggleHabits() {
        val on = !useHabits.getValue()
        useHabits.setValue(on)
        fcitx.launchOnReady { it.learning(LearningOp.UseHabits(on)) }
        // said as it goes off and as it comes back, nothing on the bar showing which (§3 rule 1)
        service.say(if (on) R.string.learning_habits_on_said else R.string.learning_habits_off_said)
    }

    /** U-88: how far back, then asked once more, saying what stays and what goes with it. */
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

    /** The input method's keyboards to pick from (KeyboardPicker), shown at [anchor]. */
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
                    val cleared = fcitx.runOnReady { learning(LearningOp.ClearRecent(span)) }
                    // not on disk (the phone locked since it started) is not now; a disk that failed, again (review-E-ui S6)
                    service.say(
                        when (cleared) {
                            is LearningResult.Cleared -> R.string.learning_cleared
                            LearningResult.Failed -> R.string.learning_clear_failed
                            else -> R.string.learning_not_now
                        }
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        service.showDialog(dialog)
    }

    companion object {
        /** fcitx's 简繁 action (chttrans), by the name it gives it */
        const val TRAD_SIMP = "chttrans"
    }
}
