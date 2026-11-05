/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * A stretch that said a command and nothing else (dev/design/060-voice-post.md F): 句号, 换行, 删掉. Said in
 * a sentence (我用句号结尾) it is the words; only a stretch of the command alone, its marks and the polite
 * 请 or 来个 about it aside, is one. As the VAD cuts at a pause, a command is said after one and before one.
 */
sealed interface VoiceCommand {

    /** [mark] at the end of what was said before, in place of the mark it ended with */
    data class Mark(val mark: Char) : VoiceCommand

    /** a new line ([lines] 1) or a new paragraph (2) */
    data class Lines(val lines: Int) : VoiceCommand

    /** the last thing said taken back: the words of the stretch before, or the command before (问号 then 删掉: the 问号) */
    data object Delete : VoiceCommand

    companion object {
        private val WORDS = mapOf(
            "句号" to Mark('。'), "逗号" to Mark('，'), "问号" to Mark('？'), "感叹号" to Mark('！'), "叹号" to Mark('！'),
            "顿号" to Mark('、'), "冒号" to Mark('：'), "分号" to Mark('；'),
            "换行" to Lines(1), "回车" to Lines(1), "下一行" to Lines(1),
            "新段落" to Lines(2), "另起一段" to Lines(2), "换段" to Lines(2), "新的一段" to Lines(2),
            "删掉" to Delete, "删除" to Delete, "删掉上一句" to Delete, "撤销" to Delete,
        )

        // what the recognizer writes about a word said alone
        private val MARKS = Regex("[。，、！？.,!?\\s]+")

        // and the asking: 请换行, 来个句号
        private val ASKING = Regex("^(?:请|来个|加个|打个)")

        /** The command [raw] said alone, or null if it said anything else. */
        fun of(raw: String): VoiceCommand? {
            val words = raw.replace(MARKS, "")
            return WORDS[words] ?: WORDS[words.replaceFirst(ASKING, "")]
        }
    }
}
