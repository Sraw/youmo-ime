/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HostReplay]'s steps that stand for a touch on the keyboard: each does what the app does with it. */
class HostReplayStepsTest {

    @Test
    fun aTapOnTheCodeRowPutsTheCursorWhereTapped() = with(HostReplay()) {
        type("nihao")
        assertEquals("ni hao", row)
        tapRowAfter("n")
        assertEquals("n|i hao", row)
        // the drawn cursor is no character of the code: a tap after it is where the code's next letter is
        tapRowAfter("n|i")
        assertEquals("ni| hao", row)
        tapRowEnd()
        assertEquals("ni hao", row)
    }

    @Test
    fun aTapOnTheCodeRowMovesWhereTheNextKeyGoes() = with(HostReplay()) {
        type("nhao")
        tapRowAfter("n")
        type("i")
        assertTrue("ni hao in 「$row」", row.startsWith("ni"))
    }

    @Test
    fun inkWrittenShowsItsFirstReadingComposingInTheField() = with(HostReplay(im = InputMethodCatalog.HANDWRITING)) {
        write("人", "入", "八")
        assertEquals("人", clientPreedit)
        assertEquals("人", text)
        assertEquals(0 to 1, editor.composingStart to editor.composingEnd)
    }

    @Test
    fun anEnglishGlideTypesItsWord() = with(HostReplay(im = InputMethodCatalog.ENGLISH)) {
        glide("hello")
        assertEquals("hello", clientPreedit.ifEmpty { text }.trim())
    }

    @Test
    fun aPinyinGlideIsReadAsItsSyllables() = with(HostReplay()) {
        glide("nihao")
        expectPreedit("ni'hao")
        expectFirst("你好")
    }

    @Test
    fun aChatBoxsSendEmptiesItAndTellsTheFieldWhatWasSent() = with(HostReplay()) {
        focus("", name = "chat")
        type("nihao")
        space()
        send()
        expectText("")
        // a box that cleared itself: told what was written there, as ContextMemory remembers it
        assertEquals("你好", contextTold)
    }
}
