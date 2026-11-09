/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What is typed from the stretches a hold heard without the tagger (as when it fails to load):
 * [VoiceLive] alone, for the evaluation (`ime-eval voice-post`, which replays recorded stretches
 * through it, no onnxruntime there), so a change to the joining or the clean-up is measured before it ships. Fillers,
 * stammers, slips and punctuation are the polish model's ([VoicePolishStream]; 060-voice-tagger.md),
 * not rules here: the user's choice 2026-11-06, the model doing them better than rules would.
 */
object VoicePost {

    /** A stretch the VAD cut, as the recognizer wrote it, and where it was in the recording (ms). */
    class Stretch(val raw: String, val startMs: Long = 0, val endMs: Long = 0)

    /** [stretches] as typed. */
    fun text(stretches: List<Stretch>): String {
        val live = VoiceLive()
        for (stretch in stretches) live.closed(stretch.raw)
        return live.finish().orEmpty()
    }
}
