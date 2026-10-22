/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.editing.EditorTraits

/**
 * What the letters' bottom row has either side of the space: the comma and the full stop, or in
 * an address field what addresses are made of, a URL's `/` or an email address's `@` in place of
 * the comma, and after the full stop [DOMAINS]' first, its long press the rest. The input method
 * stays as it is.
 */
enum class AddressKeys {
    NONE,
    URL,
    EMAIL,
    ;

    companion object {
        /** The ends of addresses the full stop's place offers, the commonest first. */
        val DOMAINS = listOf(".com", ".cn", ".net", ".org")

        /**
         * The nine keys' column in an address field, in place of its punctuation, the commonest
         * first (it shows four): its bottom row has no room for keys of their own beside the space,
         * which they left an eighth of the width, its label pushed off it, and the globe hidden.
         */
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
