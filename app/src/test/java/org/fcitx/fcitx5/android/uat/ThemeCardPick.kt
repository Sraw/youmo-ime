/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeListAdapter
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeThumbnailUi

/** As a user taps it: a built-in theme's other version shown first switched to with the card's 浅/深. */
internal fun pickTheme(list: RecyclerView, name: String, idle: () -> Unit) {
    val adapter = list.adapter as ThemeListAdapter
    val file = name.substringBefore('#')
    val at = adapter.entries.indexOfFirst { it.name == name || (it is Theme.Builtin && '#' in name && it.file == file) }
    check(at >= 0) { "no card of $name: ${adapter.entries.map { it.name }}" }
    val position = at + ThemeListAdapter.OFFSET
    list.scrollToPosition(position)
    idle()
    fun holder() = list.findViewHolderForAdapterPosition(position) as ThemeListAdapter.ViewHolder
    if (adapter.entries[at].name != name) {
        (holder().ui as ThemeThumbnailUi).version.performClick()
        idle()
        check(adapter.entries[at].name == name) { "the card shows ${adapter.entries[at].name}, not $name" }
    }
    holder().itemView.performClick()
    idle()
}
