/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.app.AlertDialog
import android.view.View
import android.widget.PopupMenu
import androidx.annotation.StringRes
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.imeScope
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.editorinfo.EditorInfoWindow
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ClearRecent
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.FloatingToggle
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.HabitsToggle
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.InputMethod
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.Keyboard
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.OneHandedToggle
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ThemeList
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.styledColor
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.gridLayoutManager

class StatusAreaWindow : InputWindow.ExtendedInputWindow<StatusAreaWindow>(),
    InputBroadcastReceiver {

    private val imeScope by manager.imeScope()
    private val fcitx: FcitxConnection by manager.fcitx()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val editorInfoInspector by AppPrefs.getInstance().internal.editorInfoInspector

    private val service by manager.inputMethodService()

    private val useHabits = AppPrefs.getInstance().internal.useHabits

    // what fcitx last offered, for the entries to be shown again as one of the app's changes
    private var fcitxActions = emptyArray<Action>()

    private val staticEntries by lazy {
        arrayOf(
            StatusAreaEntry.Android(
                context.getString(R.string.theme),
                R.drawable.ic_baseline_palette_24,
                ThemeList
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.input_method_options),
                R.drawable.ic_baseline_language_24,
                InputMethod
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.virtual_keyboard),
                R.drawable.ic_baseline_keyboard_24,
                Keyboard
            )
        )
    }

    private fun activateAction(action: Action) {
        fcitx.launchOnReady {
            it.activateAction(action.id)
        }
    }

    var popupMenu: PopupMenu? = null

    private val adapter: StatusAreaAdapter by lazy {
        object : StatusAreaAdapter() {
            override fun onItemClick(view: View, entry: StatusAreaEntry) {
                when (entry) {
                    is StatusAreaEntry.Fcitx -> {
                        val actions = entry.action.menu
                        if (actions.isNullOrEmpty()) {
                            activateAction(entry.action)
                            return
                        }
                        val popup = PopupMenu(context, view)
                        val menu = popup.menu
                        val hasDivider =
                            if (!DeviceUtil.isHMOS && !DeviceUtil.isHonorMagicOS) {
                                menu.setGroupDividerEnabled(true)
                                true
                            } else {
                                false
                            }
                        var groupId = 0 // Menu.NONE; ungrouped
                        actions.forEach {
                            if (it.isSeparator) {
                                if (hasDivider) {
                                    groupId++
                                } else {
                                    val dividerString = buildSpannedString {
                                        color(context.styledColor(android.R.attr.colorForeground).alpha(0.4f)) {
                                            append("──────────")
                                        }
                                    }
                                    menu.add(groupId, 0, 0, dividerString).apply {
                                        isEnabled = false
                                    }
                                }
                            } else {
                                menu.add(groupId, 0, 0, it.shortText).apply {
                                    setOnMenuItemClickListener { _ ->
                                        activateAction(it)
                                        true
                                    }
                                }
                            }
                        }
                        popupMenu?.dismiss()
                        popupMenu = popup
                        popup.show()
                    }
                    is StatusAreaEntry.Android -> when (entry.type) {
                        InputMethod -> fcitx.runImmediately { inputMethodEntryCached }.let {
                            AppUtil.launchMainToInputMethodConfig(
                                context, it.uniqueName, InputMethodNames.of(context, it)
                            )
                        }
                        Keyboard -> AppUtil.launchMainToKeyboard(context)
                        ThemeList -> AppUtil.launchMainToThemeList(context)
                        // on, at the side it was last at, or off; then the keyboard as it now goes
                        OneHandedToggle -> {
                            val oneHanded = ThemeManager.prefs.oneHanded
                            val side = ThemeManager.prefs.oneHandedSide
                            val was = oneHanded.getValue()
                            oneHanded.setValue(if (was == OneHanded.Off) side.getValue() else OneHanded.Off)
                            windowManager.attachWindow(KeyboardWindow)
                        }
                        // the input view is made anew for it, on the letters
                        FloatingToggle -> ThemeManager.prefs.floatingKeyboard.run { setValue(!getValue()) }
                        // the app's preference (read when the engine is made), and the engine told now
                        HabitsToggle -> {
                            val on = !useHabits.getValue()
                            useHabits.setValue(on)
                            fcitx.launchOnReady { it.learning(LearningOp.UseHabits(on)) }
                            // said as it goes off and as it comes back, nothing on the bar showing which (§3 rule 1)
                            service.say(if (on) R.string.learning_habits_on_said else R.string.learning_habits_off_said)
                            onStatusAreaUpdate(fcitxActions)
                        }
                        ClearRecent -> clearRecent(view)
                    }
                }
            }

            override val theme = this@StatusAreaWindow.theme
        }
    }

    /** U-88: how far back, then asked once more, saying what stays and what goes with it. */
    private fun clearRecent(anchor: View) {
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

    private fun confirmClear(span: LearningOp.Span, @StringRes label: Int) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.learning_clear_recent_title, context.getString(label)))
            .setMessage(R.string.learning_clear_recent_message)
            .setPositiveButton(R.string.learning_clear) { _, _ ->
                imeScope.launch {
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

    private val keyBorder by ThemeManager.prefs.keyBorder

    val view by lazy {
        context.recyclerView {
            if (!keyBorder) {
                backgroundColor = theme.barColor
            }
            layoutManager = gridLayoutManager(4)
            adapter = this@StatusAreaWindow.adapter
        }
    }

    override fun onStatusAreaUpdate(actions: Array<Action>) {
        fcitxActions = actions
        val floating = ThemeManager.prefs.floatingKeyboard.getValue()
        // floating or the letters split, one-handed mode is of no use: not offered
        val oneHanded = if (floating || TextKeyboard.splits(context)) emptyArray() else arrayOf(
            StatusAreaEntry.Android(
                context.getString(R.string.one_handed),
                R.drawable.ic_baseline_pan_tool_24,
                OneHandedToggle,
                active = ThemeManager.prefs.oneHanded.getValue() != OneHanded.Off
            )
        )
        adapter.entries = arrayOf(
            *staticEntries,
            *oneHanded,
            StatusAreaEntry.Android(
                context.getString(R.string.floating_keyboard),
                R.drawable.ic_baseline_open_with_24,
                FloatingToggle,
                active = floating
            ),
            // off, shown lit: the keyboard is not as the user left it
            StatusAreaEntry.Android(
                context.getString(if (useHabits.getValue()) R.string.learning_habits_off else R.string.learning_habits_on),
                R.drawable.ic_view_private,
                HabitsToggle,
                active = !useHabits.getValue()
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.learning_clear_recent),
                R.drawable.ic_baseline_delete_sweep_24,
                ClearRecent
            ),
            *Array(actions.size) { StatusAreaEntry.fromAction(context, actions[it]) }
        )
    }

    override fun onCreateView() = view

    private val editorInfoButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_info_24, theme).apply {
            contentDescription = context.getString(R.string.editor_info_inspector)
            fixIconSize(context.dp(KawaiiBarComponent.ICON_DP))
            setOnClickListener { windowManager.attachWindow(EditorInfoWindow()) }
        }
    }

    private val settingsButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_settings_24, theme).apply {
            contentDescription = context.getString(R.string.open_input_method_settings)
            fixIconSize(context.dp(KawaiiBarComponent.ICON_DP))
            setOnClickListener { AppUtil.launchMain(context) }
        }
    }

    private val barExtension by lazy {
        context.horizontalLayout {
            val width = dp(KawaiiBarComponent.buttonWidthDp())
            val height = dp(KawaiiBarComponent.heightDp())
            if (editorInfoInspector) {
                add(editorInfoButton, lParams(width, height))
            }
            add(settingsButton, lParams(width, height))
        }
    }

    override fun onCreateBarExtension() = barExtension

    override fun onAttached() {
        fcitx.launchOnReady {
            val data = it.statusArea()
            imeScope.launch {
                onStatusAreaUpdate(data)
            }
        }
    }

    override fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }
}
