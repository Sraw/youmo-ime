/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.editing.EditorTraits

/** An address field's: its scroll lists show the address's marks; the input method stays. */
enum class AddressKeys {
    NONE,
    URL,
    EMAIL,
    ;

    companion object {
        fun of(traits: EditorTraits): AddressKeys = when {
            traits.isUri -> URL
            traits.isEmail -> EMAIL
            else -> NONE
        }
    }
}
