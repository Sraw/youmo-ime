/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContextMemoryTest {

    private val memory = ContextMemory().apply { focus("chat") }

    @Test
    fun aClearedBoxFollowsWhatWasSentFromIt() {
        assertEquals("", memory.context("", empty = true, now = 0))
        memory.committed("明天", now = 1)
        memory.committed("一起吃饭吗", now = 2)
        // sent: the app clears the box
        assertEquals("明天一起吃饭吗", memory.context("", empty = true, now = 3))
    }

    @Test
    fun textTheEditorShowsIsTheContextAndWhatIsKept() {
        assertEquals("你好", memory.context("你好", empty = false, now = 0))
        memory.committed("，", now = 1)
        assertEquals("你好，", memory.context("", empty = true, now = 2))
        // the start of a field with text after the cursor: none
        memory.committed("在吗", now = 3)
        assertEquals("", memory.context("", empty = false, now = 4))
        assertEquals("", memory.context("", empty = true, now = 5))
    }

    @Test
    fun backspaceTakesFromWhatIsKeptAWholeCodePoint() {
        memory.committed("好的😀", now = 0)
        memory.deleted()
        assertEquals("好的", memory.context("", empty = true, now = 1))
        memory.deleted()
        memory.deleted()
        memory.deleted()
        assertEquals("", memory.context("", empty = true, now = 2))
    }

    @Test
    fun aPairCommittedKeepsWhatIsBeforeTheCursor() {
        memory.committed("（）", cursor = 1, now = 0)
        assertEquals("（", memory.context("", empty = true, now = 1))
    }

    @Test
    fun keptForHalfAnHourOnly() {
        memory.committed("好", now = 0)
        assertEquals("", memory.context("", empty = true, now = ContextMemory.FRESH_MILLIS + 1))
        // gone, not back once the time is in range again
        assertEquals("", memory.context("", empty = true, now = 1))
    }

    @Test
    fun eachAppHasItsOwnAndOnlyTheLastFewAreKept() {
        val small = ContextMemory(keep = 4, apps = 2)
        small.focus("a")
        small.committed("123456", now = 0)
        small.focus("b")
        small.committed("b", now = 0)
        small.focus("a")
        assertEquals("3456", small.context("", empty = true, now = 1))
        small.focus("c")
        small.committed("c", now = 2)
        // a was used more lately than b: b goes
        small.focus("b")
        assertEquals("", small.context("", empty = true, now = 3))
        small.focus("a")
        assertEquals("3456", small.context("", empty = true, now = 3))
        // the editor's text is kept to the end too
        assertEquals("abcdef", small.context("abcdef", empty = false, now = 4))
        small.committed("g", now = 5)
        assertEquals("defg", small.context("", empty = true, now = 6))
    }

    @Test
    fun aSensitiveFieldKeepsNothing() {
        memory.committed("好", now = 0)
        memory.focus(null)
        memory.committed("secret", now = 1)
        memory.deleted()
        assertEquals("", memory.context("", empty = true, now = 2))
        memory.focus("chat")
        assertEquals("好", memory.context("", empty = true, now = 3))
    }

    @Test
    fun anEditorThatWillNotSayKeepsWhatWasWrittenAndACutDropsIt() {
        memory.committed("好", now = 0)
        assertEquals(true, memory.remembers(now = 1))
        assertEquals("", memory.context("", empty = null, now = 1))
        assertEquals("好", memory.context("", empty = true, now = 2))
        memory.cut()
        assertEquals(false, memory.remembers(now = 3))
        assertEquals("", memory.context("", empty = true, now = 3))
        assertEquals(false, ContextMemory().remembers(now = 0))
    }

    @Test
    fun aBackspaceTakesTheSelectionOrTheCodePointBeforeTheCursor() {
        memory.committed("你好", now = 0)
        // at the start of the text: nothing before the cursor to take
        memory.backspace(start = 0, end = 0)
        assertEquals("你好", memory.context("", empty = true, now = 1))
        memory.backspace(start = 2, end = 2)
        assertEquals("你", memory.context("", empty = true, now = 2))
        memory.backspace(start = 0, end = 1)
        assertEquals("", memory.context("", empty = true, now = 3))
    }

    @Test
    fun theFieldsOfOneViewAreToldApartByWhatTheySayOfThemselves() {
        val chat = ContextMemory.field("app", 1, type = 1, name = null, hint = "Message")
        val search = ContextMemory.field("app", 1, type = 1, name = null, hint = "Search")
        assertEquals(chat, ContextMemory.field("app", 1, type = 1, name = null, hint = "Message"))
        assertNotEquals(chat, ContextMemory.field("app", 1, type = 0x20001, name = null, hint = "Message"))
        assertNotEquals(chat, ContextMemory.field("app", 1, type = 1, name = "to", hint = "Message"))
        val shared = ContextMemory().apply { focus(chat) }
        shared.committed("在吗", now = 0)
        shared.focus(search)
        assertEquals("", shared.context("", empty = true, now = 1))
        shared.focus(chat)
        assertEquals("在吗", shared.context("", empty = true, now = 2))
    }
}
