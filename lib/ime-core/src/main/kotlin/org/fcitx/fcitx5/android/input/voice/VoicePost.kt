/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** What is typed from recorded stretches, for `ime-eval voice-post`: through [VoiceLive], with or without a polish stream. Fillers and punctuation are the model's, not rules here. */
object VoicePost {

    class Stretch(val raw: String)

    /** Not all the app does (VoiceHomophones, VoiceEnd's last mark are not run), so not letter for letter what the user sees. */
    fun text(stretches: List<Stretch>, polish: VoicePolishStream? = null): String {
        val live = VoiceLive(polish)
        for (stretch in stretches) live.closed(stretch.raw)
        return live.finish().orEmpty()
    }
}
