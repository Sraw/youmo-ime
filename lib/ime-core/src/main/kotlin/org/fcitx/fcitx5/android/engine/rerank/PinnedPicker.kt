/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import org.fcitx.fcitx5.android.device.CorePin

/** [picker] run within [pin]: the matrix work on the cores it chooses, the picks the same anywhere. */
class PinnedPicker(private val picker: SentencePicker, private val pin: CorePin) : SentencePicker {
    override fun pick(context: String, readings: List<String>, scores: List<Float>): Int = pin.run { picker.pick(context, readings, scores) }

    // not pinned here: a read handed to another thread is pinned where it runs, and a new thread would inherit the pin
    override fun prewarm(context: String) = picker.prewarm(context)
}
