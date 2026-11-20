/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonKeyActionListenerTest {

    // the nine keys reach the listener as pinyin's
    private val pinyin = { InputMethodCatalog.PINYIN }
    private val notAsked: () -> String = { error("the input method is not asked") }

    // xiaoming then the 123 page's 1 in an address field: the 1 goes in as it is, not picking 小明
    @Test
    fun aKeypadKeyTypesItsOwnCharacterWhichAnAddressTakesAsItIs() {
        val one = FcitxKeyMapping.FcitxKey_KP_0 + 1
        assertEquals("1", symText(one))
        assertEquals(".", symText(FcitxKeyMapping.FcitxKey_KP_Decimal))
        assertEquals("-", symText(FcitxKeyMapping.FcitxKey_KP_Subtract))
        assertEquals("=", symText(0xffbd))
        assertTrue(isKeypadSym(one))
        assertEquals("1", symTypedAsIs(one, address = true, im = notAsked, scheme = ShuangpinScheme.ZIRANMA))
        assertEquals("1", symTypedAsIs(one, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
        assertNull(symTypedAsIs(one, address = false, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
    }

    // the bar's digits are their own syms; the nine keys' keys are their codes, so a digit is no key of theirs
    @Test
    fun aCharactersOwnSymTypesIt() {
        assertEquals("1", symText('1'.code))
        assertEquals("@", symText('@'.code))
        assertFalse(isKeypadSym('1'.code))
        assertEquals("1", symTypedAsIs('1'.code, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
        assertNull(symTypedAsIs(UNICODE + Keyboard.pinyinNineKey('2'), address = true, im = notAsked, scheme = ShuangpinScheme.ZIRANMA))
        assertEquals("1", symTypedAsIs('1'.code, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
        assertEquals("@", symTypedAsIs('@'.code, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
    }

    @Test
    fun aKeyThatTypesNoCharacterHasNone() {
        assertNull(symText(FcitxKeyMapping.FcitxKey_BackSpace))
        assertNull(symText(FcitxKeyMapping.FcitxKey_Return))
        assertNull(symText(FcitxKeyMapping.FcitxKey_Left))
        assertNull(symText(UNICODE + 0xe000))
        assertNull(symTypedAsIs(FcitxKeyMapping.FcitxKey_BackSpace, address = true, im = notAsked, scheme = ShuangpinScheme.ZIRANMA))
    }

    @Test
    fun aKeysCharacterGoesInAsItIsUnlessTheInputMethodTypesWithIt() {
        assertEquals("2", typedAsIs("2", address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
        assertEquals("_", typedAsIs("_", address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
        assertRows(listOf("0", "1", "2"), { "$it, a syllable typed" }) { digit ->
            assertEquals(digit, typedAsIs(digit, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA, composing = { true }))
        }
        assertNull(typedAsIs("2", address = false, im = notAsked, scheme = ShuangpinScheme.ZIRANMA))
        assertNull(typedAsIs("a", address = true, im = notAsked, scheme = ShuangpinScheme.ZIRANMA))
    }

    /** 微软双拼's ing is `;`: put in as it is, it cut the syllable typed in an address field. */
    @Test
    fun inShuangpinTheSchemesOwnKeysStayKeys() {
        val shuangpin = { InputMethodCatalog.SHUANGPIN }
        assertNull(typedAsIs(";", address = true, im = shuangpin, scheme = ShuangpinScheme.MICROSOFT))
        assertNull(symTypedAsIs(';'.code, address = true, im = shuangpin, scheme = ShuangpinScheme.MICROSOFT))
        assertEquals(";", typedAsIs(";", address = true, im = shuangpin, scheme = ShuangpinScheme.ZIRANMA))
        assertEquals("_", typedAsIs("_", address = true, im = shuangpin, scheme = ShuangpinScheme.MICROSOFT))
    }

    @Test
    fun pinyinsSeparatorIsAKeyWhileSomethingIsTyped() {
        assertNull(typedAsIs("'", address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA, composing = { true }))
        assertEquals("'", typedAsIs("'", address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA, composing = { false }))
        assertNull(symTypedAsIs('\''.code, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA, composing = { true }))
        assertEquals("'", symTypedAsIs('\''.code, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA, composing = { false }))
    }

    /** 二笔's codes hold `;`: put in as it is, it cut `a;` (了) in an address field. */
    @Test
    fun aTablesKeysStayKeysWhileACodeIsTyped() {
        val erbi = { "engine-erbi" }
        assertNull(typedAsIs(";", address = true, im = erbi, scheme = ShuangpinScheme.ZIRANMA, composing = { true }) { setOf(';') })
        assertEquals(";", typedAsIs(";", address = true, im = erbi, scheme = ShuangpinScheme.ZIRANMA, composing = { false }) { error("asked") })
        assertNull(symTypedAsIs(';'.code, address = true, im = erbi, scheme = ShuangpinScheme.ZIRANMA, composing = { true }) { setOf(';') })
    }

    /** fcitx's jobs queued as the service queues them, run in order by [runAll]; a commit is one, never run if cancelled first. */
    private class Queue(inputMethod: String = InputMethodCatalog.PINYIN) {
        val fcitx = FakeHostApi().apply { inputMethodEntryCached = InputMethodEntry("").copy(uniqueName = inputMethod) }

        val commits = mutableListOf<String>()

        val tableKeysAsked = mutableListOf<String>()
        private val jobs = mutableListOf<Pair<Job, suspend HostApi.() -> Unit>>()

        private val api = object : HostApi by fcitx {
            override suspend fun tableKeys(im: String): Set<Char> = fcitx.tableKeys(im).also { tableKeysAsked += im }
        }

        fun post(job: suspend HostApi.() -> Unit) {
            enqueue(job)
        }

        fun commit(text: String): Job {
            commits += text
            return enqueue { fcitx.calls += "commit($text)" }
        }

        // LAZY as postFcitxJob's: one cancelled before its turn never runs
        private fun enqueue(block: suspend HostApi.() -> Unit): Job = Job().also { jobs += it to block }

        fun runAll() = runBlocking { jobs.forEach { (job, block) -> if (!job.isCancelled) block(api) } }

        private val scheme = { ShuangpinScheme.ZIRANMA }

        fun press(key: String) = typeAsIsOrSend(keyAsIs(key, address = true, scheme), this::post, this::commit) { sendKey(key) }

        fun pressSym(sym: Int) = typeAsIsOrSend(symAsIs(sym, address = true, scheme), this::post, this::commit) { sendKey(sym) }
    }

    private fun typed(text: String) = FormattedText(arrayOf(text), intArrayOf(0), text.length)

    /** Z's `'` swiped while a space's commit was queued: told what was typed as it was pressed, it went to the engine. */
    @Test
    fun whatIsTypedIsToldOnceTheKeysBeforeAreHandled() {
        val queue = Queue()
        queue.fcitx.clientPreeditCached = typed("xi")
        queue.post { queue.fcitx.clientPreeditCached = FormattedText.Empty }
        queue.press("'")
        queue.runAll()
        assertEquals(listOf("'"), queue.commits)
        assertEquals(listOf("commit(')"), queue.fcitx.calls)
    }

    @Test
    fun aSeparatorSwipedAfterKeysStillQueuedPartsWhatTheyType() {
        val queue = Queue()
        queue.post { queue.fcitx.clientPreeditCached = typed("xi") }
        queue.press("'")
        queue.press("a")
        queue.runAll()
        // its commit never ran: posted before the job that decides, ' would go in, then to the engine too
        assertEquals(listOf("sendKey(key=', states=0, code=0, up=false)", "sendKey(key=a, states=0, code=0, up=false)"), queue.fcitx.calls)
        assertEquals(emptyList<String>(), queue.tableKeysAsked)
    }

    @Test
    fun aTablesKeysAreAskedOfFcitxOnceACodeIsTyped() {
        val erbi = "engine-erbi"
        val queue = Queue(erbi)
        queue.fcitx.tableKeys[erbi] = setOf(';')
        queue.post { queue.fcitx.clientPreeditCached = typed("a") }
        queue.press(";")
        queue.pressSym(';'.code)
        queue.runAll()
        assertEquals(listOf("sendKey(key=;, states=0, code=0, up=false)", "sendKey(sym=${';'.code}, states=0, code=0, up=false)"), queue.fcitx.calls)
    }

    @Test
    fun withNothingTypedATablesKeyGoesInAsItIsFcitxNotAsked() {
        val erbi = "engine-erbi"
        val queue = Queue(erbi)
        queue.fcitx.tableKeys[erbi] = setOf(';')
        queue.press(";")
        queue.runAll()
        assertEquals(emptyList<String>(), queue.tableKeysAsked)
        assertEquals(listOf("commit(;)"), queue.fcitx.calls)
    }

    // a 14-key tap's code is past Latin-1, so symText has no text for it; '_' beside it shows the check can fail
    @Test
    fun aFourteenKeyTapIsNoTextAsItIs() {
        val sym = UNICODE + Keyboard.tapKey(g14Code('b'), 0.75f)
        for (im in listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH, "")) {
            assertNull(im, symAsIs(sym, address = true) { ShuangpinScheme.ZIRANMA }({ im }, { false }, { emptySet() }))
            assertEquals(im, "_", symAsIs('_'.code, address = true) { ShuangpinScheme.ZIRANMA }({ im }, { false }, { emptySet() }))
        }
    }
}
