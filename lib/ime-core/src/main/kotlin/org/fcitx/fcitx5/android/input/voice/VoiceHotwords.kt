/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.engine.host.Engines

/**
 * Words the recognizer is told to listen for (sherpa-onnx's hotwords, a bonus on each of their
 * tokens as the beam search goes): the new-word pack's, put in the assets by VoiceDataPlugin by
 * [spelled] too, and the user's own. On the voice evaluation's new words, those in the list went
 * from 89 to some 104 of 138 heard, the rest of the speech much as it was; a larger bonus
 * hears more of them and mishears more of everything else.
 *
 * A word the user blocked is not written, the pack's or not, as a word: [VoiceBlocking] picks a
 * hypothesis without it, else our sherpa-onnx's beam search (lib/sherpa-onnx) is told not to let
 * one complete it. Only those [spelled]: two to twelve Chinese characters, [MAX_USER] of them;
 * and only once the engine has told the listener the user's words (VoiceEngine.userHotwords
 * waits so long).
 * Each stream with the user's words holds a graph of the pack's too, some 37 MB.
 */
object VoiceHotwords {

    /** the bonus on each token of a hotword: 1 to 2 measured, 3 too much for everyday speech */
    const val SCORE = 1.5f

    // the recognizer builds its graph again for each stretch from the pack's words and these:
    // a few thousand add little to the pack's 78 thousand
    const val MAX_USER = 3000

    // Character.UnicodeScript is API 24
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

    /** What a stream is told of the user's words: each list's words [spelled], apart by `/`. */
    data class Words(val hotwords: String, val blocked: String) {
        /** the blocked words as written, not [spelled] */
        val blockedWords: List<String> get() = blocked.split('/').filter { it.isNotEmpty() }.map { it.replace(" ", "") }

        companion object {
            val NONE = Words("", "")
        }
    }

    /**
     * The user's words for one stream: as hotwords, those they added first, then those they made
     * as they typed, most used first ([Engines.userWords]' order); blocked, those they blocked.
     */
    fun ofUser(words: List<Engines.UserWord>): Words {
        fun spell(kinds: (Engines.UserWord.Kind) -> Boolean) = words.asSequence()
            .filter { kinds(it.kind) }
            .mapNotNull { spelled(it.text) }
            .distinct()
            .take(MAX_USER)
            .joinToString("/")
        return Words(
            hotwords = spell { it != Engines.UserWord.Kind.BLOCKED },
            blocked = spell { it == Engines.UserWord.Kind.BLOCKED },
        )
    }
}
