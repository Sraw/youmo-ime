/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.data.theme.Theme

object ThemeSlots {

    /** A pack's two versions each its own; any other theme both, so a one-version theme is used whatever the mode, not refused. */
    fun picked(theme: Theme, pair: Pair<Theme, Theme>?, following: Boolean): Pair<Theme, Theme>? = when {
        !following -> null
        pair != null -> pair
        else -> theme to theme
    }
}
