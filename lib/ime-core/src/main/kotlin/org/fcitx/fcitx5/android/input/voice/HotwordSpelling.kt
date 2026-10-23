/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * The hotword spelling [VoiceHotwords] and the dict tool's `hotwords` share, apart from
 * VoiceHotwords so that the tool's code, and so its fingerprint, does not reach Engines.
 */
object HotwordSpelling {

    private val HAN_WORD = Regex("\\p{IsHan}{2,12}")

    /**
     * [word] as sherpa-onnx reads a hotword of X-ASR's ("bpe" unit: each part apart by spaces is
     * encoded alone, and each Chinese character is a token of its own): its characters apart by
     * spaces. Null for one not all Chinese, or of one character, which would only push that
     * character everywhere it might be heard.
     */
    fun spelled(word: String): String? {
        // a character beyond the BMP is two chars: not split in halves, left out as the pack's are
        if (!HAN_WORD.matches(word) || word.any { it.isSurrogate() }) return null
        return word.toList().joinToString(" ")
    }
}
