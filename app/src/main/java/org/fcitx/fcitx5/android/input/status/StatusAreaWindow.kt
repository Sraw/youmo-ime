/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.view.View
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.imeScope
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.editorinfo.EditorInfoWindow
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ClearRecent
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.FloatingToggle
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.HabitsToggle
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.InputMethod
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.Keyboard
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.KeyboardChoice
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.OneHandedToggle
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ThemeList
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
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
    private val fcitx: HostConnection by manager.fcitx()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val editorInfoInspector by AppPrefs.getInstance().internal.editorInfoInspector

    private val service by manager.inputMethodService()

    // what fcitx last offered, for the entries to be shown again as one of the app's changes
    private var hostEntries = emptyList<StatusEntry>()

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

    private val actions by lazy { PanelActions(context, service, fcitx, imeScope, windowManager) }

    private val adapter: StatusAreaAdapter by lazy {
        object : StatusAreaAdapter() {
            override fun onItemClick(view: View, entry: StatusAreaEntry) {
                when (entry) {
                    is StatusAreaEntry.Host -> actions.activate(entry.action)
                    is StatusAreaEntry.Android -> when (entry.type) {
                        InputMethod -> actions.inputMethodSettings()
                        Keyboard -> actions.keys()
                        ThemeList -> actions.themes()
                        OneHandedToggle -> actions.toggleOneHanded()
                        FloatingToggle -> actions.toggleFloating()
                        HabitsToggle -> {
                            actions.toggleHabits()
                            onStatusAreaUpdate(hostEntries)
                        }
                        ClearRecent -> actions.clearRecent(view)
                        KeyboardChoice -> actions.chooseKeyboard(view)
                    }
                }
            }

            override val theme = this@StatusAreaWindow.theme
        }
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

    override fun onStatusAreaUpdate(entries: List<StatusEntry>) {
        hostEntries = entries
        val floating = actions.floating
        val oneHanded = if (!actions.oneHandedOffered) emptyArray() else arrayOf(
            StatusAreaEntry.Android(
                context.getString(R.string.one_handed),
                R.drawable.ic_baseline_pan_tool_24,
                OneHandedToggle,
                active = actions.oneHanded
            )
        )
        // where the input method on has keyboards to choose from (I1 Q1)
        val keyboards = if (!actions.keyboardsOffered) emptyArray() else arrayOf(
            StatusAreaEntry.Android(context.getString(R.string.status_keyboard), R.drawable.ic_baseline_dialpad_24, KeyboardChoice)
        )
        adapter.entries = arrayOf(
            *staticEntries,
            *keyboards,
            *oneHanded,
            StatusAreaEntry.Android(
                context.getString(R.string.floating_keyboard),
                R.drawable.ic_baseline_open_with_24,
                FloatingToggle,
                active = floating
            ),
            // off, shown lit: the keyboard is not as the user left it
            StatusAreaEntry.Android(
                context.getString(if (actions.habitsOn) R.string.learning_habits_off else R.string.learning_habits_on),
                R.drawable.ic_view_private,
                HabitsToggle,
                active = !actions.habitsOn
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.learning_clear_recent),
                R.drawable.ic_baseline_delete_sweep_24,
                ClearRecent
            ),
            *Array(entries.size) { StatusAreaEntry.fromEntry(context, entries[it]) }
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
        actions.dismiss()
    }
}
