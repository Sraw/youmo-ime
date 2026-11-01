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

    // the nine keys are a keyboard of pinyin's: their keys reach the listener as pinyin's
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
        // a keypad's digit is no key of the nine keys': the input method is not even asked
        assertEquals("1", symTypedAsIs(one, address = true, im = notAsked, scheme = ShuangpinScheme.ZIRANMA))
        assertEquals("1", symTypedAsIs(one, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
        assertNull(symTypedAsIs(one, address = false, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
    }

    // the bar's digits are their own syms: the nine keys' keys are their codes since I1, so a digit is
    // no key of theirs, put in as it is in an address field
    @Test
    fun aCharactersOwnSymTypesIt() {
        assertEquals("1", symText('1'.code))
        assertEquals("@", symText('@'.code))
        assertFalse(isKeypadSym('1'.code))
        assertEquals("1", symTypedAsIs('1'.code, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
        // the nine keys' own codes type no character: to the engine, even in an address field
        assertNull(symTypedAsIs(UNICODE + Keyboard.pinyinNineKey('2'), address = true, im = notAsked, scheme = ShuangpinScheme.ZIRANMA))
        assertEquals("1", symTypedAsIs('1'.code, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
        assertEquals("@", symTypedAsIs('@'.code, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
    }

    @Test
    fun aKeyThatTypesNoCharacterHasNone() {
        assertNull(symText(FcitxKeyMapping.FcitxKey_BackSpace))
        assertNull(symText(FcitxKeyMapping.FcitxKey_Return))
        assertNull(symText(FcitxKeyMapping.FcitxKey_Left))
        // the nine keys' syllables and letters, sent past Unicode's private use
        assertNull(symText(UNICODE + 0xe000))
        // so it goes to the engine, even in an address field
        assertNull(symTypedAsIs(FcitxKeyMapping.FcitxKey_BackSpace, address = true, im = notAsked, scheme = ShuangpinScheme.ZIRANMA))
    }

    /** xiaoming2026@ came out 小明026@: an address field takes the digit, the nine keys' too, whose keys are their codes. */
    @Test
    fun aKeysCharacterGoesInAsItIsUnlessTheInputMethodTypesWithIt() {
        assertEquals("2", typedAsIs("2", address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
        assertEquals("_", typedAsIs("_", address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA))
        // with a syllable typed too: the digit, the nine keys' 0 and 1 among them, is the address's
        assertRows(listOf("0", "1", "2"), { "$it, a syllable typed" }) { digit ->
            assertEquals(digit, typedAsIs(digit, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA, composing = { true }))
        }
        // anywhere else, and for a letter, the key goes to the engine, the input method not asked
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

    /** Pinyin's `'` (Z's swipe) parts what is typed: in an address field it stayed a key only then. */
    @Test
    fun pinyinsSeparatorIsAKeyWhileSomethingIsTyped() {
        assertNull(typedAsIs("'", address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA, composing = { true }))
        assertEquals("'", typedAsIs("'", address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA, composing = { false }))
        // sent as its sym
        assertNull(symTypedAsIs('\''.code, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA, composing = { true }))
        assertEquals("'", symTypedAsIs('\''.code, address = true, im = pinyin, scheme = ShuangpinScheme.ZIRANMA, composing = { false }))
    }

    /** 二笔's codes hold `;`: put in as it is, it cut `a;`, 了, in an address field. */
    @Test
    fun aTablesKeysStayKeysWhileACodeIsTyped() {
        val erbi = { "engine-erbi" }
        assertNull(typedAsIs(";", address = true, im = erbi, scheme = ShuangpinScheme.ZIRANMA, composing = { true }) { setOf(';') })
        assertEquals(";", typedAsIs(";", address = true, im = erbi, scheme = ShuangpinScheme.ZIRANMA, composing = { false }) { error("asked") })
        // sent as its sym
        assertNull(symTypedAsIs(';'.code, address = true, im = erbi, scheme = ShuangpinScheme.ZIRANMA, composing = { true }) { setOf(';') })
    }

    /**
     * fcitx's jobs, queued as the service queues them and run in order by [runAll], onto [fcitx]. A commit is one
     * of them, as commitOverEngine posts it: run, it puts its text in the field, `commit(text)` in [fcitx]'s calls
     * among the keys sent, in order; cancelled before its turn, it never runs.
     */
    private class Queue(inputMethod: String = InputMethodCatalog.PINYIN) {
        val fcitx = FakeHostApi().apply { inputMethodEntryCached = InputMethodEntry("").copy(uniqueName = inputMethod) }

        /** The texts commits were asked for, run or not. */
        val commits = mutableListOf<String>()

        /** The input methods whose table's keys a job asked of fcitx. */
        val tableKeysAsked = mutableListOf<String>()
        private val jobs = mutableListOf<Pair<Job, suspend HostApi.() -> Unit>>()

        // fcitx as the jobs see it, its table's keys' asks recorded
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

        // as postFcitxJob's, LAZY: one cancelled before its turn never runs
        private fun enqueue(block: suspend HostApi.() -> Unit): Job = Job().also { jobs += it to block }

        fun runAll() = runBlocking { jobs.forEach { (job, block) -> if (!job.isCancelled) block(api) } }

        private val scheme = { ShuangpinScheme.ZIRANMA }

        /** [key] pressed in an address field, told what it types as the listener tells it ([keyAsIs]). */
        fun press(key: String) = typeAsIsOrSend(keyAsIs(key, address = true, scheme), this::post, this::commit) { sendKey(key) }

        /** A key sending [sym] pressed in an address field, as the listener's ([symAsIs]). */
        fun pressSym(sym: Int) = typeAsIsOrSend(symAsIs(sym, address = true, scheme), this::post, this::commit) { sendKey(sym) }
    }

    private fun typed(text: String) = FormattedText(arrayOf(text), intArrayOf(0), text.length)

    /** Z's `'` swiped as a space's commit was still queued: told what was typed as it was pressed, it went to the engine, ‘. */
    @Test
    fun whatIsTypedIsToldOnceTheKeysBeforeAreHandled() {
        val queue = Queue()
        queue.fcitx.clientPreeditCached = typed("xi")
        // Space, still queued: it commits what is typed
        queue.post { queue.fcitx.clientPreeditCached = FormattedText.Empty }
        queue.press("'")
        queue.runAll()
        assertEquals(listOf("'"), queue.commits)
        // in the field, nothing sent to the engine
        assertEquals(listOf("commit(')"), queue.fcitx.calls)
    }

    @Test
    fun aSeparatorSwipedAfterKeysStillQueuedPartsWhatTheyType() {
        val queue = Queue()
        // x i, still queued
        queue.post { queue.fcitx.clientPreeditCached = typed("xi") }
        queue.press("'")
        // a letter is the engine's, no commit asked for
        queue.press("a")
        queue.runAll()
        // its commit never ran: posted before the job that decides, ' would go in, then to the engine too
        assertEquals(listOf("sendKey(key=', states=0, code=0, up=false)", "sendKey(key=a, states=0, code=0, up=false)"), queue.fcitx.calls)
        // pinyin has no table: fcitx is not asked for one's keys
        assertEquals(emptyList<String>(), queue.tableKeysAsked)
    }

    /** 二笔's `;` once its code's first key, still queued, is handled: the table's keys, the engine's, asked of fcitx. */
    @Test
    fun aTablesKeysAreAskedOfFcitxOnceACodeIsTyped() {
        val erbi = "engine-erbi"
        val queue = Queue(erbi)
        queue.fcitx.tableKeys[erbi] = setOf(';')
        // a, still queued
        queue.post { queue.fcitx.clientPreeditCached = typed("a") }
        queue.press(";")
        // sent as its sym
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

    // a 14-key tap with its place (Keyboard.TAPS, design k14-tap-position NIT 1): never text in an address field.
    // What holds it is symText, which has no text for a code past Latin-1; '_' beside it shows the check can fail
    @Test
    fun aFourteenKeyTapIsNoTextAsItIs() {
        val sym = UNICODE + Keyboard.tapKey(g14Code('b'), 0.75f)
        for (im in listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH, "")) {
            assertNull(im, symAsIs(sym, address = true) { ShuangpinScheme.ZIRANMA }({ im }, { false }, { emptySet() }))
            assertEquals(im, "_", symAsIs('_'.code, address = true) { ShuangpinScheme.ZIRANMA }({ im }, { false }, { emptySet() }))
        }
    }
}
