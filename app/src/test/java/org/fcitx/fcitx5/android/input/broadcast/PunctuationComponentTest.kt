/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.broadcast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.core.FakeHostConnection
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.host.StatusEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign

/**
 * The first test of an input-layer component built the way InputView builds it: a
 * [DynamicScope] holding a [FakeHostConnection] and a coroutine scope, no Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PunctuationComponentTest {

    private class Recorder : UniqueComponent<Recorder>(), InputBroadcastReceiver {
        val updates = mutableListOf<Map<String, String>>()
        override fun onPunctuationUpdate(mapping: Map<String, String>) {
            updates += mapping
        }
    }

    /** [runOnReady] waits, while [hold] is on, till [release], as the real one waits for fcitx. */
    private class HeldConnection(private val fake: FakeHostConnection) : HostConnection by fake {
        var hold = false
        private val waiting = mutableListOf<CompletableDeferred<Unit>>()

        fun release() {
            waiting.forEach { it.complete(Unit) }
            waiting.clear()
        }

        override suspend fun <T> runOnReady(block: suspend HostApi.() -> T): T {
            if (hold) CompletableDeferred<Unit>().also { waiting += it }.await()
            return fake.runOnReady(block)
        }
    }

    private class Fixture(testScope: TestScope) {
        val fcitx = FakeHostConnection(testScope)
        val held = HeldConnection(fcitx)
        val recorder = Recorder()
        val punctuation = PunctuationComponent()

        init {
            val scope = DynamicScope()
            val connection: HostConnection = held
            val coroutines: CoroutineScope = testScope
            scope += connection.wrapToUniqueComponent()
            scope += coroutines.wrapToUniqueComponent()
            scope += InputBroadcaster()
            scope += recorder
            scope += punctuation
        }
    }

    private fun punctuationAction(active: Boolean) = StatusEntry(
        id = 1, name = "punctuation", icon = if (active) "fcitx-punc-active" else "fcitx-punc-inactive", shortText = ""
    )

    private fun entry(key: String, mapping: String) = PunctuationMapEntry(key, mapping, "")

    private suspend fun Fixture.configure(lang: String, vararg entries: PunctuationMapEntry) {
        fcitx.api.inputMethodEntryCached = InputMethodEntry("pinyin").copy(languageCode = lang)
        fcitx.api.setPunctuationMap(lang, entries.toList())
    }

    // PunctuationManager keeps the tables it read for the whole process: each test's fake has its own
    @Before
    fun forgetTheKeptTables() {
        PunctuationManager.forget()
    }

    @Test
    fun anActivePunctuationActionLoadsTheLanguagesMappingAndBroadcastsIt() = runTest(StandardTestDispatcher()) {
        val f = Fixture(this)
        f.configure("zh_CN", entry(",", "，"), entry(".", "。"))

        f.punctuation.updatePunctuationMapping(listOf(punctuationAction(active = true)))
        advanceUntilIdle()

        assertTrue(f.punctuation.enabled)
        assertEquals("，", f.punctuation.transform(","))
        assertEquals("。", f.punctuation.transform("."))
        assertEquals("unmapped keys pass through", "!", f.punctuation.transform("!"))
        assertEquals(listOf(mapOf("," to "，", "." to "。")), f.recorder.updates)
    }

    @Test
    fun aDuplicateKeyKeepsTheFirstMapping() = runTest(StandardTestDispatcher()) {
        val f = Fixture(this)
        f.configure("zh_CN", entry("'", "‘"), entry("'", "’"))

        f.punctuation.updatePunctuationMapping(listOf(punctuationAction(active = true)))
        advanceUntilIdle()

        assertEquals("‘", f.punctuation.transform("'"))
    }

    @Test
    fun anInactivePunctuationActionBroadcastsAnEmptyMapping() = runTest(StandardTestDispatcher()) {
        val f = Fixture(this)
        f.configure("zh_CN", entry(",", "，"))

        f.punctuation.updatePunctuationMapping(listOf(punctuationAction(active = false)))
        advanceUntilIdle()

        assertFalse(f.punctuation.enabled)
        assertEquals(",", f.punctuation.transform(","))
        assertEquals(listOf(emptyMap<String, String>()), f.recorder.updates)
    }

    @Test
    fun anOlderUpdateStillLoadingDoesNotOverwriteANewerOne() = runTest(StandardTestDispatcher()) {
        val f = Fixture(this)
        f.configure("zh_CN", entry(",", "，"))
        f.held.hold = true

        f.punctuation.updatePunctuationMapping(listOf(punctuationAction(active = true)))
        advanceUntilIdle()
        f.punctuation.updatePunctuationMapping(listOf(punctuationAction(active = false)))
        advanceUntilIdle()
        // the on update's load finishes last
        f.held.release()
        advanceUntilIdle()

        assertFalse(f.punctuation.enabled)
        assertEquals(",", f.punctuation.transform(","))
        assertEquals(listOf(emptyMap<String, String>()), f.recorder.updates)
    }
}
