/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.editing.EditorTraits

/**
 * How what was said ends, by the field it goes into (dev/design/060-voice-post.md B): in a chat a
 * message is not closed with 「。」, a question or an exclamation keeps its mark; a search, an
 * address, a one-line field takes no end mark at all; anywhere else the text ends as said.
 *
 * [field] reads [EditorTraits.sends], [EditorTraits.isMultiLine] and [EditorTraits.isShortMessage]:
 * traits that leave them unset make every field one line.
 */
object VoiceEnd {

    enum class Field {
        /** where a message is written: one goes out at a time, 「。」 not wanted at its end */
        CHAT,

        /** a search, an address, a field of one line (a number's, a phone's are never more): no end mark */
        LINE,
        OTHER,
    }

    /** The user's choice (语音输入 → 句末句号): the full stop's, so a one-line field ends as [AUTO] has it whichever. */
    enum class Mode {
        /** by the field, as [Field] says */
        AUTO,

        /** as said, in a chat too */
        KEEP,

        /** no closing mark (。 . ， , 、 ； ;) at the end of any field: a chat's end everywhere, "etc." too */
        DROP,
    }

    /**
     * Apps whose message box declares neither a send action nor a short message (WeChat's and QQ's
     * say only multi-line): their multi-line fields are chats. One line in them (a search) is not.
     */
    private val CHAT_APPS = setOf(
        "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "com.tencent.wework",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "tw.nekomimi.nekogram",
        "com.whatsapp", "jp.naver.line.android", "com.alibaba.android.rimet", "com.ss.android.lark",
        "com.discord", "com.google.android.apps.messaging",
    )

    /** The kind of the field [traits] describes, of the app [packageName]. */
    fun field(traits: EditorTraits, packageName: String?): Field = when {
        // first: a chat app's search box is a search, whatever it says of its lines
        traits.isSearch || traits.isUri || traits.isEmail -> Field.LINE
        traits.sends || traits.isShortMessage || (traits.isMultiLine && packageName in CHAT_APPS) -> Field.CHAT
        !traits.isMultiLine -> Field.LINE
        else -> Field.OTHER
    }

    // a full stop and what the recognizer leaves at a stretch's end that closes nothing (a comma);
    // not a colon, which says something follows ("如下："), nor a question's or an exclamation's, which were meant
    private const val CLOSING = "。.，,、；;"
    private const val ALL = "$CLOSING：:？?！!…"

    /** [text] ended for a [field] as [mode] has it. */
    fun apply(text: String, field: Field, mode: Mode): String = when {
        field == Field.LINE -> text.trimEnd { it in ALL || it.isWhitespace() }
        mode == Mode.KEEP -> text
        field == Field.CHAT || mode == Mode.DROP -> dropClosing(text)
        else -> text
    }

    // as said where nothing is dropped, its spaces too
    private fun dropClosing(text: String): String {
        var end = text.trimEnd()
        // a mark at a time, up to an ellipsis written as points, which is meant: "等等...。" -> "等等..."
        while (end.endsInClosing() && !end.endsInEllipsis()) end = end.dropLast(1)
        return if (end.length == text.trimEnd().length) text else end.trimEnd()
    }

    private fun String.endsInClosing() = isNotEmpty() && last() in CLOSING
    private fun String.endsInEllipsis() = endsWith("..") || endsWith("。。")
}
