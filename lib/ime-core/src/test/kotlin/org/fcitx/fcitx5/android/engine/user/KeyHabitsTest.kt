/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyHabitsTest {

    private val habits = KeyHabits()
    private val pinyin = habits.scope("pinyin")

    @Test
    fun whatTheUserLookedForIsFirstNextTime() {
        assertNull(pinyin.habit("ee"))
        pinyin.learn("ee", "嗯嗯", own = "呃呃")
        assertEquals("嗯嗯", pinyin.habit("ee"))
    }

    @Test
    fun theEnginesOwnFirstNeedsNoHabit() {
        pinyin.learn("nihao", "你好", own = "你好")
        assertNull(pinyin.habit("nihao"))
        assertEquals(0, habits.size)
    }

    @Test
    fun passedOverOnceAHabitIsNoLonger() {
        pinyin.learn("ee", "嗯嗯", own = "呃呃")
        // the engine's own first picked over it: no habit of that, the other halved
        pinyin.learn("ee", "呃呃", own = "呃呃")
        assertNull(pinyin.habit("ee"))
        // passed over again, gone
        pinyin.learn("ee", "呃呃", own = "呃呃")
        assertEquals(0, habits.size)
    }

    @Test
    fun theTextCountedMostIsTheHabit() {
        repeat(3) { pinyin.learn("ee", "嗯嗯", own = "呃呃") }
        // a third text once: the habit halved to 1.5, the other 1
        pinyin.learn("ee", "额额", own = "呃呃")
        assertEquals("嗯嗯", pinyin.habit("ee"))
        pinyin.learn("ee", "额额", own = "呃呃")
        assertEquals("额额", pinyin.habit("ee"))
    }

    @Test
    fun eachInputMethodHasItsOwnKeys() {
        val shuangpin = habits.scope("shuangpin/ziranma")
        pinyin.learn("ee", "嗯嗯", own = "呃呃")
        assertNull(shuangpin.habit("ee"))
        assertThrows(IllegalArgumentException::class.java) { habits.scope("a\u0000b") }
    }

    @Test
    fun aForgottenWordTakesItsHabitsWithIt() {
        pinyin.learn("ee", "嗯嗯", own = "呃呃")
        pinyin.learn("en", "嗯嗯", own = "嗯")
        pinyin.learn("e", "饿", own = "呃")
        pinyin.forget("嗯嗯")
        assertNull(pinyin.habit("ee"))
        assertNull(pinyin.habit("en"))
        assertEquals("饿", pinyin.habit("e"))
    }

    @Test
    fun theLeastCountedGoFirst() {
        val small = KeyHabits(limit = 2)
        val s = small.scope("pinyin")
        s.learn("aa", "啊啊", own = null)
        s.learn("aa", "啊啊", own = null)
        s.learn("bb", "吧吧", own = null)
        s.learn("cc", "擦擦", own = null)
        assertEquals(2, small.size)
        assertEquals("啊啊", s.habit("aa"))
        assertNull(s.habit("bb"))
        assertEquals("擦擦", s.habit("cc"))
    }

    @Test
    fun tooLongIsNotKept() {
        pinyin.learn("a".repeat(65), "啊", own = null)
        pinyin.learn("aa", "啊".repeat(33), own = null)
        assertEquals(0, habits.size)
    }

    @Test
    fun eachChangeIsJournaledAndRestoresAsItWas() {
        val seen = ArrayList<Triple<String, String, Float>>()
        habits.journal = KeyHabits.Journal { keys, text, count -> seen += Triple(keys, text, count) }
        pinyin.learn("ee", "嗯嗯", own = "呃呃")
        pinyin.learn("ee", "嗯嗯", own = "呃呃")
        pinyin.forget("嗯嗯")
        assertEquals(listOf(1f, 2f, 0f), seen.map { it.third })
        // replayed, the last count is what it is
        val again = KeyHabits()
        seen.take(2).forEach { (keys, text, count) -> again.restore(keys, text, count) }
        assertEquals("嗯嗯", again.scope("pinyin").habit("ee"))
        val all = ArrayList<Float>()
        again.forEach { _, _, count -> all += count }
        assertEquals(listOf(2f), all)
        again.restore(seen[2].first, seen[2].second, seen[2].third)
        assertEquals(0, again.size)
    }
}
