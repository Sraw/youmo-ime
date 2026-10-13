/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.core.FakeFcitxConnection
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class NineKeyLettersFragmentTest {

    /** Fcitx starting till [ready] completes: until then [runIfReady] drops its block, as the real one does. */
    private class Starting(scope: CoroutineScope, val fake: FakeFcitxConnection = FakeFcitxConnection(scope)) :
        FcitxConnection by fake {

        val ready = CompletableDeferred<Unit>()

        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T {
            ready.await()
            return fake.runOnReady(block)
        }

        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) {
            if (ready.isCompleted) fake.runIfReady(block)
        }
    }

    @Test
    fun aKeyEditedKeepsTheOthersAsTheyWere() {
        val edited = NineKeyLetters.STANDARD.withEdit('9', "WXYZL")
        assertEquals("wxyzl", edited.letters('9'))
        assertEquals("jkl", edited.letters('5'))
        // put back as it was: as on a phone again
        assertEquals(NineKeyLetters.STANDARD, edited.withEdit('9', "wxyz"))
    }

    @Test
    fun aKeyEmptiedIsAsOnAPhoneNotAKeyTypingNothing() {
        val edited = NineKeyLetters.STANDARD.withEdit('9', "wxyzl").withEdit('2', "abcq")
        val emptied = edited.withEdit('9', "")
        assertEquals("wxyz", emptied.letters('9'))
        assertEquals("abcq", emptied.letters('2'))
        // what keeps no letter is no letters either
        assertEquals("def", edited.withEdit('3', "1!").letters('3'))
    }

    @Test
    fun lettersSavedWhileFcitxStartsAreReadOnceItIsReady() = runTest {
        val fcitx = Starting(backgroundScope)
        val kept = mutableListOf<NineKeyLetters>()
        val edited = NineKeyLetters.STANDARD.withEdit('9', "wxyzl")
        saveLetters(edited, NineKeyLetters.STANDARD, { kept += it }, fcitx, EmptyCoroutineContext)
        runCurrent()
        assertEquals(listOf(edited), kept)
        assertEquals(emptyList<String>(), fcitx.fake.api.calls)
        fcitx.ready.complete(Unit)
        runCurrent()
        assertEquals(listOf("reloadEngine()"), fcitx.fake.api.calls)
    }

    @Test
    fun lettersAsTheyWereAreNeitherKeptNorReadAgain() = runTest {
        val fcitx = Starting(backgroundScope).apply { ready.complete(Unit) }
        val kept = mutableListOf<NineKeyLetters>()
        val edited = NineKeyLetters.STANDARD.withEdit('9', "wxyzl")
        // equal, not the same: an OK with the field unchanged
        saveLetters(NineKeyLetters.STANDARD.withEdit('9', "wxyzl"), edited, { kept += it }, fcitx, EmptyCoroutineContext)
        runCurrent()
        assertEquals(emptyList<NineKeyLetters>(), kept)
        assertEquals(emptyList<String>(), fcitx.fake.api.calls)
        // fcitx not running: kept, read as it starts
        saveLetters(NineKeyLetters.STANDARD, edited, { kept += it }, null, EmptyCoroutineContext)
        assertEquals(listOf(NineKeyLetters.STANDARD), kept)
    }
}
