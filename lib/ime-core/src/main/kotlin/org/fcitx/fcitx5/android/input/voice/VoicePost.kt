/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What is typed from the stretches a hold heard, for the evaluation (`ime-eval voice-post`, which replays
 * recorded stretches), so a change to the joining or the clean-up is measured before it ships: through
 * [VoiceLive] alone, as without the tagger (when it fails to load; no onnxruntime needed), or with a polish
 * stream given, through the tagger and its guard too (`--source tagged`, voice-4). Fillers,
 * stammers, slips and punctuation are the polish model's ([VoicePolishStream]; 060-voice-tagger.md),
 * not rules here: the user's choice 2026-11-06, the model doing them better than rules would.
 */
object VoicePost {

    /** A stretch the VAD cut, as the recognizer wrote it. */
    class Stretch(val raw: String)

    /**
     * [stretches] as typed; through [polish] as VoiceListener types them with the tagger in, else as without
     * it. Not all the app does to a talk: what it does after (VoiceHomophones, VoiceEnd's last mark) is not
     * run here, so this is not letter for letter what the user sees.
     */
    fun text(stretches: List<Stretch>, polish: VoicePolishStream? = null): String {
        val live = VoiceLive(polish)
        for (stretch in stretches) live.closed(stretch.raw)
        return live.finish().orEmpty()
    }
}
