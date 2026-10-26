/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

/** fcitx's key for a Unicode character: this plus its code point. */
const val UNICODE = 0x01000000

@JvmInline
value class KeySym(val sym: Int) {

    override fun toString() = "0x" + sym.toString(16).padStart(4, '0')

    // for the Android side's extensions (KeySym.fromKeyEvent in :app)
    companion object
}
