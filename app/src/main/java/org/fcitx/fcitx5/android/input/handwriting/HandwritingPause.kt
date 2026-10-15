/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * How long the pen stays up before the character written counts as done ([InkPad.finishMillis]):
 * a fast hand starts the next one at once, a slow one pauses between strokes of the same.
 */
enum class HandwritingPause(override val stringRes: Int, val millis: Long) : ManagedPreferenceEnum {
    Fastest(R.string.handwriting_pause_fastest, 300),
    Fast(R.string.handwriting_pause_fast, 500),
    Medium(R.string.handwriting_pause_medium, InkPad.FINISH_MILLIS),
    Slow(R.string.handwriting_pause_slow, 1000),
    Slowest(R.string.handwriting_pause_slowest, 1500),
}
