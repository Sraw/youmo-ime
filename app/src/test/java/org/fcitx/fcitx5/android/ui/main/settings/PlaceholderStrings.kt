/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources

/** :app unit tests run without the app's resources, yet every preference resolves its title eagerly: "#<id>" answers. */
internal class PlaceholderStrings(base: Context) : ContextWrapper(base) {
    private val res = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
        override fun getText(id: Int): CharSequence = placeholder(id)
        override fun getString(id: Int): String = placeholder(id)
        override fun getString(id: Int, vararg formatArgs: Any?): String = placeholder(id)
        override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String = placeholder(id)
    }

    override fun getResources(): Resources = res
}

internal fun placeholder(id: Int) = "#$id"
