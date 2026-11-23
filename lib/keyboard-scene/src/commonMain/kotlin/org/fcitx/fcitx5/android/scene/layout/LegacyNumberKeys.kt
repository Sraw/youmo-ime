/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

/**
 * The 0.6.2 number board's keys of no name that its scroll column took the place of, by id, with what each sent: a
 * user's or a pack's board made from that one is checked as against its own base, so it gains no warning.
 */
internal object LegacyNumberKeys {
    val SENDS: Map<String, String> = mapOf(
        "+" to "sym:KP_Add",
        "-" to "sym:KP_Subtract",
        "*" to "sym:KP_Multiply",
        "/" to "sym:KP_Divide",
        "," to "sym:KP_Separator",
        "=" to "sym:KP_Equal",
        "." to "sym:KP_Decimal",
        "#" to "commit:#",
        "(" to "commit:(",
        ")" to "commit:)",
        ":" to "commit::",
    )
}
