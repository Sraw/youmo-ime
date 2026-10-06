/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** What a recognized stretch of speech types. */
object VoiceText {

    // a cough or a breath taken for speech comes back as one of these
    private val FILLER = Regex("^[嗯呃额啊唔哦噢欸诶呀。，、！？.,!?…\\s]+$")

    /**
     * [raw] trimmed; null when nothing is left worth typing: punctuation alone, or a filler sound
     * alone ("嗯。"), which the recognizer writes for a noise as often as for a word.
     */
    fun clean(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || FILLER.matches(text)) return null
        return text
    }
}
