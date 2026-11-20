/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/** The input methods as androidengine listed them. */
object InputMethodEntries {
    /** One the engines don't list (none should) is a bare entry of its name. */
    fun of(im: String): InputMethodEntry {
        val m = InputMethodCatalog.listed(im) ?: return InputMethodEntry(im, im, "", "", "")
        return InputMethodEntry(m.name, m.text, m.icon, m.label, m.language)
    }
}
