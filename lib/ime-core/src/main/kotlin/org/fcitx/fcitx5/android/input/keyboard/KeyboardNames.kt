/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** `<app keyboard>|<id>@<stamp>`: a replaced file is a new keyboard; switching goes by the whole name. */
object KeyboardNames {
    private const val SEPARATOR = '|'

    /** [custom] null: the app's own layout. */
    fun of(name: String, custom: CustomChoice?): String = if (custom == null) name else "$name$SEPARATOR${custom.id}@${custom.stamp}"

    fun base(name: String): String = name.substringBefore(SEPARATOR)

    /** `id@stamp`; null for none. */
    fun custom(name: String): String? = name.substringAfter(SEPARATOR, "").ifEmpty { null }

    /** Any stamp. */
    fun sameLayout(name: String, custom: String): Boolean = custom(name)?.substringBeforeLast('@') == custom.substringBeforeLast('@')

    /** Another stamp of the same id (replaced since); the same stamp stays, or a number board's pads would drop each other. */
    fun stale(name: String, custom: String): Boolean = sameLayout(name, custom) && custom(name) != custom
}
