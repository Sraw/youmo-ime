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
import org.fcitx.fcitx5.android.core.FakeFcitxAPI
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The keyboard reads the punctuation table on every focus: [PunctuationManager] keeps what it read
 * over JNI until the table is saved or fcitx reads its tables again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PunctuationManagerTest {

    /** Fcitx as it hands a table back, under `cfg`, which [FakeFcitxAPI] does not add. */
    private class Tables(private val fake: FakeFcitxAPI = FakeFcitxAPI()) : FcitxAPI by fake {
        /** Set, a read waits for it with the table it has read. */
        var held: CompletableDeferred<Unit>? = null

        override suspend fun getAddonSubConfig(addon: String, path: String): RawConfig {
            val raw = RawConfig(arrayOf(RawConfig("cfg", fake.getAddonSubConfig(addon, path).subItems ?: emptyArray())))
            held?.await()
            return raw
        }

        /** [lang]'s table changed in fcitx, not through [PunctuationManager]: an edited file read again. */
        suspend fun change(lang: String, entries: List<PunctuationMapEntry>) = fake.setAddonSubConfig(
            "punctuation", "punctuationmap/$lang",
            RawConfig(arrayOf(RawConfig(PunctuationManager.ENTRIES, entries.mapIndexed { i, it -> it.toRawConfig(i) }.toTypedArray())))
        )
    }

    private val comma = listOf(PunctuationMapEntry(",", "，", ""))
    private val both = comma + PunctuationMapEntry(".", "。", "")

    @Before
    fun forgetTheKeptTables() {
        PunctuationManager.forget()
    }

    @Test
    fun aTableReadIsKeptTillItIsSaved() = runTest {
        val fcitx = Tables()
        fcitx.change("zh_CN", comma)
        assertEquals(comma, PunctuationManager.load(fcitx, "zh_CN"))
        fcitx.change("zh_CN", both)
        assertEquals("kept, not read again", comma, PunctuationManager.load(fcitx, "zh_CN"))

        PunctuationManager.save(fcitx, "zh_CN", both)
        assertEquals(both, PunctuationManager.load(fcitx, "zh_CN"))
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
    fun aReadASaveOverlapsIsNotKept() = runTest {
        val fcitx = Tables()
        fcitx.change("zh_CN", comma)
        val gate = CompletableDeferred<Unit>().also { fcitx.held = it }
        val read = async { PunctuationManager.load(fcitx, "zh_CN") }
        runCurrent()
        fcitx.held = null

        // the save lands while the read, holding the table from before it, is on its way back
        PunctuationManager.save(fcitx, "zh_CN", both)
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
        // edited while fcitx was stopped: it reads the file as it starts
        PunctuationManager.starting { fcitx.change("zh_CN", both) }
        assertEquals(both, PunctuationManager.load(fcitx, "zh_CN"))
    }
}
