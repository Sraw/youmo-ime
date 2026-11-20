/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** Apart from VoiceHotwords so the dict tool's code (and fingerprint) does not reach Engines. */
object HotwordSpelling {

    private val HAN_WORD = Regex("\\p{IsHan}{2,12}")

    /** X-ASR's bpe hotwords: characters apart by spaces; null for non-Chinese or one character (it would be pushed everywhere). */
    fun spelled(word: String): String? {
        // a character beyond the BMP is two chars: not split in halves, left out as the pack's are
        if (!HAN_WORD.matches(word) || word.any { it.isSurrogate() }) return null
        return word.toList().joinToString(" ")
    }
}
