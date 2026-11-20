/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import java.util.zip.CRC32

/** Splits a set in two by expected text, so a sentence and the sets derived from it land in the same half. */
object Halves {

    const val TUNE = "tune"
    const val HELD_OUT = "held-out"
    val NAMES = listOf(TUNE, HELD_OUT)

    fun of(sample: Sample): String {
        val crc = CRC32().apply { update(sample.expected.toByteArray()) }
        return if (crc.value % 2 == 0L) TUNE else HELD_OUT
    }

    fun select(samples: List<Sample>, half: String?): List<Sample> =
        if (half == null) samples else samples.filter { of(it) == half }
}
