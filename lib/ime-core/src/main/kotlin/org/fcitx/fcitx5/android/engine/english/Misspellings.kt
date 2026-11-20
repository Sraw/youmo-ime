/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.lattice.CoarseWords

/** Misspellings the model learned as words (recieve, definately) to the word meant, from `misspellings.txt`. */
object Misspellings {

    val MAP: Map<String, String> by lazy {
        CoarseWords.listLines(Misspellings::class.java, "misspellings.txt").associate { it.substringBefore(' ') to it.substringAfter(' ') }
    }
}
