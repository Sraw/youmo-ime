/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.editing.EditorTraits

/** In an address field `/` or `@` in the comma's place and [DOMAINS]' first after the full stop; the input method stays. */
enum class AddressKeys {
    NONE,
    URL,
    EMAIL,
    ;

    companion object {
        /** The commonest first. */
        val DOMAINS = listOf(".com", ".cn", ".net", ".org")

        /** The nine keys' column: its bottom row has no room beside the space (they left it an eighth, its label pushed off). */
        fun column(keys: AddressKeys): List<String> = when (keys) {
            NONE -> emptyList()
            URL -> listOf("/", ".", DOMAINS.first(), ":", "-", "_", "?", "=", "&", "#") + DOMAINS.drop(1)
            EMAIL -> listOf("@", ".", DOMAINS.first(), "-", "_", "+") + DOMAINS.drop(1)
        }

        fun of(traits: EditorTraits): AddressKeys = when {
            traits.isUri -> URL
            traits.isEmail -> EMAIL
            else -> NONE
        }
    }
}
