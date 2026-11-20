/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.HostApi
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The keyboard reads the punctuation table on every focus: [PunctuationManager] caches it until fcitx rereads its tables. */
@OptIn(ExperimentalCoroutinesApi::class)
class PunctuationManagerTest {

    /** A read held till [held] is completed. */
    private class Tables(private val fake: FakeHostApi = FakeHostApi()) : HostApi by fake {
        var held: CompletableDeferred<Unit>? = null

        override suspend fun punctuationMap(lang: String): List<PunctuationMapEntry> {
            val entries = fake.punctuationMap(lang)
            held?.await()
            return entries
        }

        /** Changed in fcitx, not through [PunctuationManager]. */
        suspend fun change(lang: String, entries: List<PunctuationMapEntry>) = fake.setPunctuationMap(lang, entries)
    }

    private val comma = listOf(PunctuationMapEntry(",", "，", ""))
    private val both = comma + PunctuationMapEntry(".", "。", "")

    @Before
    fun forgetTheKeptTables() {
        PunctuationManager.forget()
    }

    @Test
    fun aTableReadIsKept() = runTest {
        val fcitx = Tables()
        fcitx.change("zh_CN", comma)
        assertEquals(comma, PunctuationManager.load(fcitx, "zh_CN"))
        fcitx.change("zh_CN", both)
        assertEquals("kept, not read again", comma, PunctuationManager.load(fcitx, "zh_CN"))
    }

    @Test
    fun forgettingReadsTheTableAgain() = runTest {
        val fcitx = Tables()
        fcitx.change("zh_CN", comma)
        PunctuationManager.load(fcitx, "zh_CN")
        fcitx.change("zh_CN", both)

        PunctuationManager.forget()
        assertEquals(both, PunctuationManager.load(fcitx, "zh_CN"))
    }

    @Test
    fun aReadAForgetOverlapsIsNotKept() = runTest {
        val fcitx = Tables()
        fcitx.change("zh_CN", comma)
        val gate = CompletableDeferred<Unit>().also { fcitx.held = it }
        val read = async { PunctuationManager.load(fcitx, "zh_CN") }
        runCurrent()
        fcitx.held = null

        // fcitx rereads its tables while the read, holding the old table, is on its way back
        fcitx.change("zh_CN", both)
        PunctuationManager.forget()
        gate.complete(Unit)
        assertEquals("what it read", comma, read.await())
        assertEquals(both, PunctuationManager.load(fcitx, "zh_CN"))
    }

    @Test
    fun fcitxReloadingItsConfigThroughTheDaemonReadsTheTableAgain() = runTest {
        val tables = Tables()
        val fcitx = PunctuationManager.forgettingOnReload(tables)
        tables.change("zh_CN", comma)
        assertEquals(comma, PunctuationManager.load(fcitx, "zh_CN"))
        // the file changed on disk, which then has fcitx reload
        tables.change("zh_CN", both)
        fcitx.reloadConfig()
        assertEquals(both, PunctuationManager.load(fcitx, "zh_CN"))
    }

    @Test
    fun fcitxStartingReadsTheTableAgain() = runTest {
        val fcitx = Tables()
        fcitx.change("zh_CN", comma)
        assertEquals(comma, PunctuationManager.load(fcitx, "zh_CN"))
        // edited while fcitx was stopped: read as it starts
        PunctuationManager.starting { fcitx.change("zh_CN", both) }
        assertEquals(both, PunctuationManager.load(fcitx, "zh_CN"))
    }
}
