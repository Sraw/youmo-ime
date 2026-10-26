/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Chinese input method's punctuation marks (`native-last:app/src/main/cpp/androidengine/androidengine.cpp:266-328,515-548,567-580`). */
class KeyRouterPunctuationTest {

    private val passOn = object : EngineHost {
        override fun event(im: String, event: Int, arg: Int, learning: Boolean) =
            Snapshot("", "", emptyList(), 0, false, false, handled = false, predicting = false)
        override fun candidates(im: String, from: Int, count: Int) = emptyList<Choice>()
        override fun offers(im: String, index: Int) = emptySet<Offer>()
        override fun settings(flat: String) = Unit
    }

    /** `,` one mark; `"` a pair typed a half at a time, with a second card; `.` two cards. */
    private class Punc : EnginePunctuation {
        var open = false
        val pushes = mutableListOf<Int>()
        override fun candidates(unicode: Int) = when (unicode) {
            '.'.code -> listOf("。", "．")
            '"'.code -> listOf("“", "”", "＂")
            ','.code -> listOf("，")
            else -> emptyList()
        }

        override fun get(unicode: Int) = if (unicode == '"'.code) "“" to "”" else (candidates(unicode).firstOrNull().orEmpty() to "")

        override fun push(ic: InputContext, unicode: Int): Pair<String, String> {
            pushes += unicode
            if (unicode != '"'.code) return get(unicode)
            open = !open
            return (if (open) "“" else "”") to ""
        }
    }

    private val punc = Punc()
    private val core = HostCore(passOn, VirtualHostClock(), Profile.load(null, Engines.LISTED.map { it.name }.toSet(), "D"), firstRun = true,
        modules = { HostModules(punctuation = punc) })
    private val log = mutableListOf<HostEvent>().also { l -> core.events.handlers += { l += it } }

    init {
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.COMMIT_STRING_WITH_CURSOR)
        core.focus(true)
    }

    private fun commits() = log.filterIsInstance<HostEvent.Commit>().map { it.text }

    @Test
    fun `a key with one mark commits it`() {
        assertTrue(core.sendKey(Key(','.code)))
        assertEquals(listOf("，"), commits())
    }

    @Test
    fun `a key with several offers them, the first going in with the next key`() {
        assertTrue(core.sendKey(Key('.'.code)))
        assertTrue(commits().isEmpty())
        assertEquals(listOf("。", "．"), log.filterIsInstance<HostEvent.Candidates>().last().candidates.map { it.text })
        core.sendKey(Key(','.code))
        assertEquals(listOf("。", "，"), commits())
    }

    @Test
    fun `a shortcut puts the first mark in before the app has the key`() {
        // F3814
        core.sendKey(Key('.'.code))
        val keys = log.size
        assertFalse(core.sendKey(Key('V'.code, KeyStates.CTRL)))
        assertEquals(listOf("。"), commits())
        // the commit first, so that the app's paste lands after it
        assertTrue(log.indexOfFirst { it is HostEvent.Commit } in keys until log.indexOfLast { it is HostEvent.Key })
    }

    @Test
    fun `Backspace takes the marks back, Space picks the first and types nothing more`() {
        core.sendKey(Key('.'.code))
        assertTrue(core.sendKey(Key(KeySyms.BACKSPACE)))
        assertTrue(commits().isEmpty())
        assertTrue(core.isEmpty())
        core.sendKey(Key('.'.code))
        assertTrue(core.sendKey(Key(KeySyms.SPACE)))
        assertEquals(listOf("。"), commits())
    }

    @Test
    fun `a pair's half turns back while offered and again as it goes in`() {
        core.sendKey(Key('"'.code))
        // pushed, then turned back: the pair not opened while offered
        assertFalse(punc.open)
        assertTrue(core.select(0))
        assertTrue(punc.open)
        assertEquals(listOf("“"), commits())
    }

    @Test
    fun `a keypad key types no mark`() {
        assertFalse(core.sendKey(Key(KeySyms.KP_0 + 1)))
        assertTrue(punc.pushes.isEmpty())
    }

    @Test
    fun `English types the key's own`() {
        core.setCurrentInputMethod(ImGroup.KEYBOARD_US)
        core.sendKey(Key(','.code), virtual = true)
        assertTrue(punc.pushes.isEmpty())
        assertTrue(log.any { it is HostEvent.Key && it.sym == ','.code })
    }
}
