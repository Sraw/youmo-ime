/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.content.Context
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.bar.BarIcons
import org.fcitx.fcitx5.android.input.bar.ToolbarButtonLooks

/**
 * A cell of the status panel: [icon] the app's image of it (0: its label's first character), [named] the name a
 * theme's icon replaces it by (061-toolbar-icons §2.8), null where none does (unikey's, an engine's unknown).
 */
sealed class StatusAreaEntry(
    val label: String,
    @DrawableRes
    val icon: Int,
    val active: Boolean,
    val named: BarIcons.Icon?,
) {
    class Android(label: String, val type: Type, active: Boolean = false) :
        StatusAreaEntry(label, ToolbarButtonLooks.drawable(iconOf(type).fallback), active, iconOf(type)) {
        enum class Type {
            InputMethod,
            Keyboard,
            ThemeList,
            OneHandedToggle,
            FloatingToggle,

            /** U-93 「不用我的输入习惯」 / 「恢复我的输入习惯」 */
            HabitsToggle,

            /** U-88 「清掉最近」 */
            ClearRecent,

            /** I1 「键盘」: the input method's keyboards (KeyboardPicker) */
            KeyboardChoice
        }
    }

    class Host(val action: StatusEntry, label: String, icon: Int, active: Boolean) :
        StatusAreaEntry(label, icon, active, BarIcons.engine(action.icon))

    companion object {
        /** A cell of the app's own by the names of the bar's icons: the toolbar's button doing its job where there is one. */
        fun iconOf(type: Android.Type): BarIcons.Icon = when (type) {
            Android.Type.InputMethod -> BarIcons.INPUT_METHOD_SETTINGS
            Android.Type.Keyboard -> BarIcons.KEYBOARD_SETTINGS
            Android.Type.ThemeList -> BarIcons.THEME
            Android.Type.OneHandedToggle -> BarIcons.ONE_HANDED
            Android.Type.FloatingToggle -> BarIcons.FLOATING
            Android.Type.HabitsToggle -> BarIcons.HABITS_OFF
            Android.Type.ClearRecent -> BarIcons.CLEAR_RECENT
            Android.Type.KeyboardChoice -> BarIcons.CHOOSE_KEYBOARD
        }

        // fcitx5-chinese-addons' by the bar's names (BarIcons.engine), the rest the app's alone
        private fun drawableFromIconName(icon: String) = BarIcons.engine(icon)?.let { ToolbarButtonLooks.drawable(it.fallback) } ?: when (icon) {
            // fcitx5-unikey
            "document-edit" -> R.drawable.ic_baseline_edit_24
            "character-set" -> R.drawable.ic_baseline_text_format_24
            "edit-find" -> R.drawable.ic_baseline_search_24
            // fallback
            "" -> 0
            else -> {
                if (icon.endsWith("-inactive")) {
                    R.drawable.ic_baseline_code_off_24
                } else {
                    R.drawable.ic_baseline_code_24
                }
            }
        }

        fun fromEntry(context: Context, it: StatusEntry): Host {
            return Host(it, label(context, it.name, it.active) ?: it.shortText, drawableFromIconName(it.icon), it.active)
        }

        // fcitx names its actions in its own language, not the app's (InputMethodNames)
        private fun label(context: Context, action: String, active: Boolean) = when (action) {
            "punctuation" -> if (active) R.string.status_punctuation_chinese else R.string.status_punctuation_english
            "fullwidth" -> if (active) R.string.status_full_width else R.string.status_half_width
            "chttrans" -> if (active) R.string.status_traditional else R.string.status_simplified
            else -> null
        }?.let(context::getString)
    }
}