/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.data.theme.Theme

/** What a theme picked in the list sets (060-builtin-themes). */
object ThemeSlots {

    /**
     * The 浅色、深色 slots a theme picked sets while 跟随系统深色模式 is on ([following]): a pack's two versions
     * ([pair]: it follows the system itself) each its own; any other theme both, so one of one version (墨灰, dark only)
     * is used whatever the system's mode, as picked, not refused. Null while not following: the normal slot is set.
     */
    fun picked(theme: Theme, pair: Pair<Theme, Theme>?, following: Boolean): Pair<Theme, Theme>? = when {
        !following -> null
        pair != null -> pair
        else -> theme to theme
    }
}
