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

        fun of(traits: EditorTraits): AddressKeys = when {
            traits.isUri -> URL
            traits.isEmail -> EMAIL
            else -> NONE
        }
    }
}
