/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

/** What the corpus tools take for a Han character: one definition, as copies of it had drifted apart. */
object TextChars {
    /** CJK Unified Ideographs and Extension A: the Han characters a Char holds. */
    fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿'
}
