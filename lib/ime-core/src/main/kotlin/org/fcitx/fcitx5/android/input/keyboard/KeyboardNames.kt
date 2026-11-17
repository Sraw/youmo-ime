/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * A keyboard's name with the layout of the user's it draws (061-custom-layout §5.3): `<the app's keyboard's>|<id>@<stamp>`,
 * the keyboard it is typed as and the layout's file as read, so a file replaced is a keyboard made anew. What the
 * keyboards are switched by ([LettersLayout], LayoutSwitchPolicy) is the name as a whole: the letters' own.
 */
object KeyboardNames {
    private const val SEPARATOR = '|'

    /** [name] drawing [custom] (null: the app's own layout). */
    fun of(name: String, custom: CustomChoice?): String = if (custom == null) name else "$name$SEPARATOR${custom.id}@${custom.stamp}"

    /** The app's keyboard [name] is typed as. */
    fun base(name: String): String = name.substringBefore(SEPARATOR)

    /** The layout of the user's [name] draws (`id@stamp`); null for none. */
    fun custom(name: String): String? = name.substringAfter(SEPARATOR, "").ifEmpty { null }

    /** Whether [name] draws the layout of [custom]'s id (any stamp). */
    fun sameLayout(name: String, custom: String): Boolean = custom(name)?.substringBeforeLast('@') == custom.substringBeforeLast('@')

    /**
     * Whether a keyboard [name] is dropped as one drawing [custom] (`id@stamp`) is made: one of the same layout's id at
     * another stamp (its file replaced since). One of the same stamp stays: a number board for several pads draws the
     * one `id@stamp` under each pad's name, each kept (L5 review S2: else 123's and a phone's pad drop each other).
     */
    fun stale(name: String, custom: String): Boolean = sameLayout(name, custom) && custom(name) != custom
}
