/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The input-method state with `ShareInputState=All`. */
class InputContextsTest {

    private val us = ImGroup.KEYBOARD_US
    private val available = setOf("pinyin", "wubi", "english", us)
    private val switches = mutableListOf<String>()
    private val contexts = InputContexts(ImGroup.of("Default", listOf("pinyin", us, "wubi"), us), { ic, reason, old, new ->
        switches += "${ic.uid} $reason $old>$new"
    })

    private fun open(uid: Int) = contexts.activate(uid).also { contexts.focus(it, true) }

    @Test
    fun `the state follows the user from app to app`() {
        // ShareInputState All on Android; InputContextManager propagateProperty
        val a = open(1)
        val b = open(2)
        assertEquals("pinyin", contexts.inputMethod(a))
        contexts.toggle(b)
        assertEquals(us, contexts.inputMethod(b))
        // back in the first app: the same input method
        open(1)
        assertEquals(us, contexts.inputMethod(a))
        contexts.enumerate(a, forward = true)
        assertEquals("wubi", contexts.inputMethod(b))
        assertEquals(listOf("2 Trigger pinyin>$us", "1 Enumerate $us>wubi"), switches)
    }

    @Test
    fun `a new context starts from the current state`() {
        // InputContextManager: copyTo from an existing context
        contexts.toggle(open(1))
        val c = open(3)
        assertEquals(us, contexts.inputMethod(c))
        // and the very first one from fcitx's default, inactive (ActiveByDefault false)
        val fresh = InputContexts(ImGroup.of("Default", listOf("pinyin", us)), { _, _, _, _ -> })
        assertEquals("pinyin", fresh.inputMethod(fresh.activate(9)))
    }

    @Test
    fun `a password field is temporarily english`() {
        // keyboard-us in a password field, switched out and in
        val a = open(1)
        contexts.setCapabilities(a, Capability.PASSWORD)
        assertEquals(us, contexts.inputMethod(a))
        contexts.setCapabilities(a, 0)
        assertEquals("pinyin", contexts.inputMethod(a))
        assertEquals(listOf("1 CapabilityChanged pinyin>$us", "1 CapabilityChanged $us>pinyin"), switches)
        // the state itself never changed: other apps stay on pinyin throughout
        assertFalse(a.state.active)
    }

    @Test
    fun `a capability switch that throws still puts the new flags in place`() {
        // the old flags are back for the old input method's leaving; a failing switch must not leave a password field on them
        val failing = InputContexts(ImGroup.of("Default", listOf("pinyin", us)), { _, _, _, _ -> throw IllegalStateException("boom") })
        val ic = failing.activate(1).also { failing.focus(it, true) }
        val thrown = runCatching { failing.setCapabilities(ic, Capability.PASSWORD) }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is IllegalStateException)
        assertEquals(Capability.PASSWORD, ic.capabilities)
        assertEquals(us, failing.inputMethod(ic))
    }

    @Test
    fun `a capability change switches only a focused context and only across the fallback`() {
        // hasFocus, shouldSwitchIM
        val a = open(1)
        contexts.focus(a, false)
        contexts.setCapabilities(a, Capability.PASSWORD)
        assertEquals(us, contexts.inputMethod(a))
        contexts.focus(a, true)
        contexts.setCapabilities(a, Capability.PASSWORD or Capability.DISABLE)
        contexts.setCapabilities(a, 1L shl 1)
        assertEquals(listOf("1 CapabilityChanged $us>pinyin"), switches)
        contexts.setCapabilities(a, Capability.DISABLE)
        assertEquals(us, contexts.inputMethod(a))
    }

    @Test
    fun `the group default is never the first while there is a second`() {
        // InputMethodGroup
        val g = ImGroup.of("Default", listOf("pinyin", us, "wubi"))
        assertEquals(us, g.defaultInputMethod)
        assertEquals(us, g.withDefault("pinyin").defaultInputMethod)
        assertEquals("wubi", g.withDefault("wubi").defaultInputMethod)
        assertEquals(us, g.withDefault("nope").defaultInputMethod)
        assertEquals("pinyin", ImGroup.of("G", listOf("pinyin"), "x").defaultInputMethod)
        assertEquals("", ImGroup.of("G", emptyList()).defaultInputMethod)
        // setGroup: unavailable dropped, old default kept
        val n = g.withDefault("wubi").withInputMethods(listOf("english", "gone", "wubi", us), available)
        assertEquals(listOf("english", "wubi", us), n.inputMethods)
        assertEquals("wubi", n.defaultInputMethod)
    }

    @Test
    fun `enumerate wraps and makes the next the default`() {
        val a = open(1)
        contexts.enumerate(a, forward = false)
        assertEquals("wubi", contexts.inputMethod(a))
        assertEquals("wubi", contexts.group.defaultInputMethod)
        contexts.enumerate(a, forward = true)
        assertEquals("pinyin", contexts.inputMethod(a))
        assertFalse(a.state.active)
    }

    @Test
    fun `set current input method, global or local`() {
        val a = open(1)
        contexts.setCurrentInputMethod(a, "wubi", local = true)
        assertEquals("wubi", contexts.inputMethod(a))
        assertEquals(us, contexts.group.defaultInputMethod)
        contexts.setCurrentInputMethod(a, "wubi", local = true)
        contexts.setCurrentInputMethod(a, us, local = false)
        assertEquals(us, contexts.inputMethod(a))
        contexts.setCurrentInputMethod(a, "pinyin", local = false)
        assertEquals("pinyin", contexts.inputMethod(a))
        contexts.setCurrentInputMethod(a, "absent", local = false)
        contexts.setCurrentInputMethod(null, "wubi", local = false)
        assertEquals("wubi", contexts.group.defaultInputMethod)
        assertEquals(listOf("1 Other pinyin>wubi", "1 Other wubi>$us", "1 Other $us>pinyin"), switches)
    }

    @Test
    fun `nothing switches with one input method`() {
        // canTrigger
        val one = InputContexts(ImGroup.of("Default", listOf("pinyin")), { _, _, _, _ -> switches += "x" })
        val ic = one.activate(1)
        assertFalse(one.toggle(ic))
        assertFalse(one.enumerate(ic, true))
        one.setCurrentInputMethod(ic, "pinyin", false)
        assertTrue(switches.isEmpty())
        assertEquals("", InputContexts(ImGroup.of("E", emptyList()), { _, _, _, _ -> }).let { it.inputMethod(it.activate(1)) })
    }

    @Test
    fun `a new group list switches the focused context`() {
        val a = open(1)
        contexts.setInputMethods(listOf("wubi", "pinyin", us), available)
        assertEquals("wubi", contexts.inputMethod(a))
        assertEquals(listOf("1 GroupChange pinyin>wubi"), switches)
        // unchanged, the focused context is switched all the same: deactivated and activated
        contexts.setInputMethods(listOf("wubi", "pinyin", us), available)
        assertEquals(listOf("1 GroupChange pinyin>wubi", "1 GroupChange wubi>wubi"), switches)
    }

    @Test
    fun `contexts past the cache size push out the least recently used`() {
        // inputcontextcache.h: LRU of 80
        val small = InputContexts(ImGroup.of("Default", listOf("pinyin", us)), { _, _, _, _ -> }, capacity = 2)
        val first = small.activate(1)
        small.activate(2)
        small.activate(1)
        small.activate(3)
        assertEquals(2, small.size)
        assertTrue(small.activate(1) === first)
        small.deactivate(1)
        assertEquals(null, small.active)
        small.deactivate(42)
        assertEquals(80, InputContexts.DEFAULT_CAPACITY)
    }

    @Test
    fun `deactivate of an unknown uid changes nothing, a known one clears only its own`() {
        val ics = InputContexts(ImGroup.of("Default", listOf("pinyin", us)), { _, _, _, _ -> })
        ics.activate(1)
        val second = ics.activate(2)
        ics.deactivate(9)
        assertTrue(ics.active === second)
        // an unbind of 1 arriving after the bind of 2
        ics.deactivate(1)
        assertTrue(ics.active === second)
        ics.deactivate(2)
        assertEquals(null, ics.active)
    }

    @Test
    fun `reading mostRecent does not reorder the cache`() {
        // reading contexts[uid] moves uid to the access-ordered map's end
        val small = InputContexts(ImGroup.of("Default", listOf("pinyin", us)), { _, _, _, _ -> }, capacity = 2)
        val first = small.activate(1)
        small.focus(first, true)
        small.focus(first, false)
        small.activate(2)
        assertTrue(small.mostRecent === first)
        // 1 is still the least recently used: the third pushes it out
        small.activate(3)
        assertFalse(1 in small)
        assertTrue(2 in small)
    }
}
