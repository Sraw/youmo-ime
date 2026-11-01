/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/** The input methods as the app lists them, the entries androidengine gave (`androidengine.cpp:393-403`). */
object InputMethodEntries {
    /**
     * [im]'s entry: its text as the name, its label and icon, its language, the addon's name,
     * configurable; no sub mode (the addon has none). One the engines do not list (none should
     * reach here) is a bare entry of its name.
     */
    fun of(im: String): InputMethodEntry {
        val m = InputMethodCatalog.listed(im) ?: return InputMethodEntry(im, im, "", "", "", "", ADDON, false)
        return InputMethodEntry(m.name, m.text, m.icon, "", m.label, m.language, ADDON, true)
    }

    private const val ADDON = "androidengine"
}
