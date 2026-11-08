/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.recyclerview.widget.RecyclerView
import splitties.views.bottomPadding

fun RecyclerView.applyNavBarInsetsBottomPadding() {
    clipToPadding = false
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
        windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).also {
            bottomPadding = it.bottom
        }
        windowInsets
    }
    // a list added after the window's insets went out (a ViewPager2 page made when its tab is first
    // picked: 外观's 大小与样式) is never given them, and its last rows stayed under the navigation bar
    doOnAttach(ViewCompat::requestApplyInsets)
}
