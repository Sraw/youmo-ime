/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import java.util.Formatter
import java.util.Locale

/** Runs [write] with one Formatter over [out], then throws the IOException a Formatter keeps, so a cut file never passes for whole. */
internal fun formatting(out: Appendable, write: (Formatter) -> Unit) {
    val formatter = Formatter(out, Locale.ROOT)
    write(formatter)
    formatter.ioException()?.let { throw it }
}
