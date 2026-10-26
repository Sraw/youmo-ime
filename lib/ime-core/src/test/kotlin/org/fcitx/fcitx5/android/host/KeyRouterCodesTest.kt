/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.engine.pinyin.nineSpCode
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A grouped keyboard's code never reaches the app's text (E review S4), whatever the engine did with it. */
class KeyRouterCodesTest {

    private class Host(val fails: Boolean) : EngineHost {
        val events = mutableListOf<Pair<String, Int>>()
        val kinds = mutableListOf<Int>()
        override fun event(im: String, event: Int, arg: Int, learning: Boolean): Snapshot {
            events += im to arg
            kinds += event
            if (fails) error("the 14 keys' session not made")
            return Snapshot("", "", emptyList(), 0, false, false, handled = false, predicting = false)
        }
        override fun candidates(im: String, from: Int, count: Int) = emptyList<Choice>()
        override fun offers(im: String, index: Int) = emptySet<Offer>()
        override fun settings(flat: String) = Unit
    }

    private fun core(host: Host) = HostCore(host, VirtualHostClock(), Profile.load(null, Engines.LISTED.map { it.name }.toSet(), "D"), firstRun = true).also {
        it.activate(1)
        it.setCapabilities(Capability.PREEDIT or Capability.COMMIT_STRING_WITH_CURSOR)
        it.focus(true)
    }

    private fun HostCore.log() = mutableListOf<HostEvent>().also { l -> events.handlers += { l += it } }

    // a tap with its place (Keyboard.TAPS) is a code too
    private val codes = listOf(g14Code('a'), g14Code('b'), g14Code('z'), '\uF8C0', '\uF8D9', nineSpCode('2'), nineSpCode('9')).map { Key(KeySyms.fromUnicode(it.code)) } +
        listOf(Keyboard.tapKey(g14Code('b'), 0.75f), Keyboard.TAPS.first, Keyboard.TAPS.last).map { Key(KeySyms.fromUnicode(it)) }

    // (a) pinyin's session throws making itself: the code is swallowed, not typed
    @Test
    fun aCodePinyinFailsToTakeIsSwallowed() {
        val core = core(Host(fails = true))
        val log = core.log()
        for (key in codes) assertTrue(core.sendKey(key, virtual = true))
        assertTrue(log.toString(), log.none { it is HostEvent.Commit || it is HostEvent.Key })
    }

    // (b) another input method active, the 14 keys still shown: swallowed, its session never told
    @Test
    fun aCodeInAnotherInputMethodIsSwallowed() {
        for (im in listOf(Engines.ENGLISH, Engines.WUBI)) {
            val host = Host(fails = false)
            val core = core(host)
            core.setInputMethods(listOf(Engines.PINYIN, im))
            core.setCurrentInputMethod(im)
            val log = core.log()
            host.events.clear()
            for (key in codes) assertTrue(core.sendKey(key, virtual = true))
            assertTrue(log.toString(), log.none { it is HostEvent.Commit || it is HostEvent.Key })
            assertEquals(im, emptyList<Pair<String, Int>>(), host.events.filter { isGroupedOrTap(it.second) })
        }
    }

    // a grouped keyboard's code or a tap's, by the blocks they are taken from: the test follows them if they move
    private fun isGroupedOrTap(c: Int) =
        c in NineKeyLetters.G14_BLOCK || c in NineKeyLetters.G18_BLOCK || c in NineKeyLetters.NINE_SP_BLOCK || c in Keyboard.TAPS

    // (c) a code reaches the input method whose keyboard it is of alone: pinyin the 14 keys', 双拼
    // the 18 keys' and the nine keys' 双拼's; the other's dropped, never read as one of its groups
    @Test
    fun aCodeOfAnotherInputMethodsKeyboardIsSwallowed() {
        val tap = Keyboard.tapKey(g14Code('b'), 0.75f)
        for ((im, read) in listOf(Engines.PINYIN to listOf(g14Code('b').code, tap), Engines.SHUANGPIN to listOf(0xF8C1, nineSpCode('6').code))) {
            val host = Host(fails = false)
            val core = core(host)
            core.setInputMethods(listOf(Engines.PINYIN, Engines.SHUANGPIN))
            core.setCurrentInputMethod(im)
            val log = core.log()
            host.events.clear()
            for (c in listOf(g14Code('b').code, 0xF8C1, nineSpCode('6').code, g14Code('w').code, tap)) assertTrue(core.sendKey(Key(KeySyms.fromUnicode(c)), virtual = true))
            assertTrue(log.toString(), log.none { it is HostEvent.Commit || it is HostEvent.Key })
            val told = host.events.map { it.second }.filter(::isGroupedOrTap)
            assertEquals(im, read, told)
        }
    }

    // (c) pinyin's own: a tap's code goes to its session as it is, the place in it
    @Test
    fun aTapIsPinyinsToRead() {
        val host = Host(fails = false)
        val core = core(host)
        val tap = Keyboard.tapKey(g14Code('b'), 0.75f)
        core.sendKey(Key(KeySyms.fromUnicode(tap)), virtual = true)
        assertEquals(listOf(Engines.PINYIN to tap), host.events.filter { it.second == tap })
    }

    // review S1: 双拼 is told a hardware ; from the screen's, the screen's a mark after what is typed
    @Test
    fun aHardwareMarkReachesShuangpinAsAHardwareChar() {
        val host = Host(fails = false)
        val core = core(host)
        core.setInputMethods(listOf(Engines.PINYIN, Engines.SHUANGPIN))
        core.setCurrentInputMethod(Engines.SHUANGPIN)
        host.kinds.clear()
        core.sendKey(Key(KeySyms.fromUnicode(';'.code)), virtual = false)
        core.sendKey(Key(KeySyms.fromUnicode(';'.code)), virtual = true)
        core.sendKey(Key(KeySyms.fromUnicode('a'.code)), virtual = false)
        assertEquals(listOf(EngineEvent.HARD_CHAR, EngineEvent.CHAR, EngineEvent.CHAR), host.kinds.filter { it == EngineEvent.CHAR || it == EngineEvent.HARD_CHAR })
    }
}
