/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * A golden file's field as written by lib/ime-core/tools: `\\` and `\t` (host-golden), `\s`
 * (host-golden's queries), `\n` (fcitx-ini's pages). Each generator escapes `\` first, so one set
 * reads them all.
 */
internal fun unescapeGolden(s: String) = buildString {
    var i = 0
    while (i < s.length) {
        if (s[i] == '\\' && i + 1 < s.length) {
            i++
            append(
                when (s[i]) {
                    't' -> '\t'
                    'n' -> '\n'
                    's' -> ' '
                    else -> s[i]
                },
            )
        } else {
            append(s[i])
        }
        i++
    }
}
