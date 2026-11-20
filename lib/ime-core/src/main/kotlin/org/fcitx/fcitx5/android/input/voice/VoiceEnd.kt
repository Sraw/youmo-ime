/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.editing.EditorTraits

/** The end by field: a chat drops 「。」 (keeps ? and !), a one-line field takes no end mark, elsewhere as said; traits unset make every field one line. */
object VoiceEnd {

    enum class Field {
        /** One message at a time, 「。」 not wanted at its end. */
        CHAT,

        /** A search, an address, a field of one line: no end mark. */
        LINE,
        OTHER,
    }

    /** 语音输入 → 句末句号; a one-line field ends as [AUTO] has it whichever. */
    enum class Mode {
        AUTO,

        /** As said, in a chat too. */
        KEEP,

        /** No closing mark at the end of any field, "etc." too. */
        DROP,
    }

    /** Their message boxes declare neither a send action nor a short message (WeChat, QQ): multi-line fields there are chats. */
    private val CHAT_APPS = setOf(
        "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "com.tencent.wework",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "tw.nekomimi.nekogram",
        "com.whatsapp", "jp.naver.line.android", "com.alibaba.android.rimet", "com.ss.android.lark",
        "com.discord", "com.google.android.apps.messaging",
    )

    fun field(traits: EditorTraits, packageName: String?): Field = when {
        // first: a chat app's search box is a search, whatever it says of its lines
        traits.isSearch || traits.isUri || traits.isEmail -> Field.LINE
        traits.sends || traits.isShortMessage || (traits.isMultiLine && packageName in CHAT_APPS) -> Field.CHAT
        !traits.isMultiLine -> Field.LINE
        else -> Field.OTHER
    }

    // a full stop and a comma left at a stretch's end, not a colon, a question's or an exclamation's
    private const val CLOSING = VoiceMarks.CLOSING
    private const val ALL = VoiceMarks.ENDING

    fun apply(text: String, field: Field, mode: Mode): String = when {
        field == Field.LINE -> text.trimEnd { it in ALL || it.isWhitespace() }
        mode == Mode.KEEP -> text
        field == Field.CHAT || mode == Mode.DROP -> dropClosing(text)
        else -> text
    }

    // as said where nothing is dropped, its spaces too
    private fun dropClosing(text: String): String {
        var end = text.trimEnd()
        // up to an ellipsis written as points, which is meant: "等等...。" -> "等等..."
        while (end.endsInClosing() && !end.endsInEllipsis()) end = end.dropLast(1)
        return if (end.length == text.trimEnd().length) text else end.trimEnd()
    }

    private fun String.endsInClosing() = isNotEmpty() && last() in CLOSING
    private fun String.endsInEllipsis() = endsWith("..") || endsWith("。。")
}
