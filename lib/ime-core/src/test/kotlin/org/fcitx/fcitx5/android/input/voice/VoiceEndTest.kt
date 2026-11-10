/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.editing.EditorTraits
import org.fcitx.fcitx5.android.input.voice.VoiceEnd.Field
import org.fcitx.fcitx5.android.input.voice.VoiceEnd.Mode
import org.fcitx.fcitx5.android.Uat
import org.junit.Test
import org.junit.Assert.assertEquals

class VoiceEndTest {

    private val multi = EditorTraits(isMultiLine = true)

    @Test
    fun aFieldThatSendsOrTakesAShortMessageIsAChat() {
        assertEquals(Field.CHAT, VoiceEnd.field(EditorTraits(sends = true), "any.app"))
        assertEquals(Field.CHAT, VoiceEnd.field(EditorTraits(isShortMessage = true, isMultiLine = true), null))
    }

    @Test
    fun aKnownChatAppsMultiLineFieldIsAChatItsOneLineFieldIsNot() {
        assertEquals(Field.CHAT, VoiceEnd.field(multi, "com.tencent.mm"))
        assertEquals(Field.LINE, VoiceEnd.field(EditorTraits(), "com.tencent.mm"))
        assertEquals("a chat app's search box is a search", Field.LINE, VoiceEnd.field(EditorTraits(isSearch = true, isMultiLine = true), "com.tencent.mm"))
        assertEquals(Field.OTHER, VoiceEnd.field(multi, "com.example.notes"))
    }

    @Uat("VO-35")
    @Test
    fun aSearchAnAddressOrOneLineIsALine() {
        assertEquals(Field.LINE, VoiceEnd.field(EditorTraits(), null))
        // each said of a field of more lines, so that the line count does not decide it
        assertEquals(Field.LINE, VoiceEnd.field(EditorTraits(isSearch = true, isMultiLine = true), null))
        assertEquals(Field.LINE, VoiceEnd.field(EditorTraits(isUri = true, isMultiLine = true), null))
        assertEquals(Field.LINE, VoiceEnd.field(EditorTraits(isEmail = true, isMultiLine = true), null))
        assertEquals("before a send action", Field.LINE, VoiceEnd.field(EditorTraits(isUri = true, sends = true), null))
        assertEquals(Field.LINE, VoiceEnd.field(EditorTraits(isNumber = true), null))
    }

    @Uat("VO-35")
    @Test
    fun aChatLosesItsClosingMarkAQuestionKeepsItsOwn() {
        assertEquals("明天见", VoiceEnd.apply("明天见。", Field.CHAT, Mode.AUTO))
        assertEquals("ok", VoiceEnd.apply("ok.", Field.CHAT, Mode.AUTO))
        assertEquals("你来吗？", VoiceEnd.apply("你来吗？", Field.CHAT, Mode.AUTO))
        assertEquals("太好了！", VoiceEnd.apply("太好了！", Field.CHAT, Mode.AUTO))
        assertEquals("然后", VoiceEnd.apply("然后，", Field.CHAT, Mode.AUTO))
        assertEquals("先这样，回头说", VoiceEnd.apply("先这样，回头说。", Field.CHAT, Mode.AUTO))
        assertEquals("abbreviations too: a chat's end", "and so on etc", VoiceEnd.apply("and so on etc.", Field.CHAT, Mode.AUTO))
        assertEquals("a colon says something follows", "如下：", VoiceEnd.apply("如下：", Field.CHAT, Mode.AUTO))
    }

    @Test
    fun nothingDroppedLeavesTheTextAsSaidItsSpacesToo() {
        assertEquals("你好 ", VoiceEnd.apply("你好 ", Field.CHAT, Mode.AUTO))
        assertEquals("你来吗？ ", VoiceEnd.apply("你来吗？ ", Field.CHAT, Mode.AUTO))
        assertEquals("明天见", VoiceEnd.apply("明天见。 ", Field.CHAT, Mode.AUTO))
        assertEquals("a space before the mark goes with it", "ok", VoiceEnd.apply("ok . ", Field.CHAT, Mode.AUTO))
    }

    @Test
    fun anEllipsisIsMeant() {
        assertEquals("等等...", VoiceEnd.apply("等等...", Field.CHAT, Mode.AUTO))
        assertEquals("等等。。。", VoiceEnd.apply("等等。。。", Field.CHAT, Mode.DROP))
        assertEquals("a stop after it goes, the points stay", "等等...", VoiceEnd.apply("等等...。", Field.CHAT, Mode.AUTO))
        assertEquals("等等…", VoiceEnd.apply("等等…", Field.CHAT, Mode.AUTO))
    }

    @Uat("VO-35")
    @Test
    fun aLineLosesEveryEndMark() {
        assertEquals("明天天气怎么样", VoiceEnd.apply("明天天气怎么样？", Field.LINE, Mode.AUTO))
        assertEquals("youmo ime", VoiceEnd.apply("youmo ime. ", Field.LINE, Mode.DROP))
        assertEquals("the setting is the full stop's: a line ends so whichever", "你好", VoiceEnd.apply("你好？", Field.LINE, Mode.KEEP))
        assertEquals("example.com", VoiceEnd.apply("example.com.", Field.LINE, Mode.KEEP))
    }

    @Uat("VO-35")
    @Test
    fun elsewhereTheTextEndsAsSaidUnlessDropped() {
        assertEquals("今天开会。", VoiceEnd.apply("今天开会。", Field.OTHER, Mode.AUTO))
        assertEquals("今天开会", VoiceEnd.apply("今天开会。", Field.OTHER, Mode.DROP))
        assertEquals("今天开会。", VoiceEnd.apply("今天开会。", Field.CHAT, Mode.KEEP))
        assertEquals("为什么？", VoiceEnd.apply("为什么？", Field.OTHER, Mode.DROP))
    }

    @Test
    fun nothingButMarksLeavesNothing() {
        assertEquals("", VoiceEnd.apply("。", Field.CHAT, Mode.AUTO))
        assertEquals("", VoiceEnd.apply("？", Field.LINE, Mode.AUTO))
    }
}
