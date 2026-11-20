/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.HostInstance
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.host.HostEvent
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import timber.log.Timber

/** [HostTest]'s English cases, split out for size. */
class HostEnglishTest {

    // a broken engine interaction fails the run instead of hanging it
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private companion object {

        val harness = TestHost()
        val fcitx: HostInstance get() = harness.fcitx
        val latestInputPanel get() = harness.latestInputPanel

        @BeforeClass
        @JvmStatic
        fun setup() = harness.start()

        @AfterClass
        @JvmStatic
        fun cleanup() = harness.stop()

        private suspend fun awaitQuiet() = harness.awaitQuiet()

        private suspend fun eventsTillQuiet() = harness.eventsTillQuiet()
    }

    @Before
    fun resetEngineAndDrainEvents() = harness.beforeTest()

    @After
    fun restoreEnabledIME() = harness.afterTest()

    /** `hel. `: a full stop replaces the committed word's space; a slip is corrected by space. */
    @Test
    fun englishCompletesCorrectsAndSpacesWordsAndLeavesAHardwareKeyToTheApp(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodNames.KEYBOARD))
        fcitx.activateIme(InputMethodNames.KEYBOARD)
        awaitQuiet()
        "hel".forEach { fcitx.sendKey(it, KeyState.Virtual.state) }
        awaitQuiet()
        val candidates = fcitx.getCandidates(0, 8).map { it.text }
        Assert.assertEquals("hel", candidates.firstOrNull())
        Assert.assertTrue("help among $candidates", "help" in candidates)
        fcitx.sendKey(' ', KeyState.Virtual.state)
        val space = eventsTillQuiet()
        Assert.assertEquals("hel ", space.filterIsInstance<HostEvent.Commit>().joinToString("") { it.text })
        fcitx.sendKey('.', KeyState.Virtual.state)
        val stop = eventsTillQuiet()
        val deleted = stop.filterIsInstance<HostEvent.DeleteSurrounding>()
        Assert.assertEquals("$stop", listOf(HostEvent.DeleteSurrounding(1, 0)), deleted)
        Assert.assertEquals(". ", stop.filterIsInstance<HostEvent.Commit>().joinToString("") { it.text })
        val started = System.nanoTime()
        "teh".forEach { fcitx.sendKey(it, KeyState.Virtual.state) }
        awaitQuiet()
        Timber.i("teh typed in ${(System.nanoTime() - started) / 1_000_000} ms, the first key building the word index")
        fcitx.sendKey(' ', KeyState.Virtual.state)
        val corrected = eventsTillQuiet()
        // the capital at a sentence start is AutoCaps', which these keys do not go through
        Assert.assertEquals("the ", corrected.filterIsInstance<HostEvent.Commit>().joinToString("") { it.text })
        // Sensitive (TestHost.start): nothing kept, so nothing predicted
        Assert.assertEquals("$corrected", 0, corrected.filterIsInstance<HostEvent.Candidates>().lastOrNull()?.total)
        val warm = System.nanoTime()
        "becuase".forEach { fcitx.sendKey(it, KeyState.Virtual.state) }
        awaitQuiet()
        Timber.i("becuase typed in ${(System.nanoTime() - warm) / 1_000_000} ms: ${fcitx.getCandidates(0, 4).map { it.text }}")
        fcitx.sendKey(' ', KeyState.Virtual.state)
        Assert.assertEquals("because ", eventsTillQuiet().filterIsInstance<HostEvent.Commit>().joinToString("") { it.text })
        fcitx.sendKey('a')
        Assert.assertTrue(eventsTillQuiet().any { it is HostEvent.Key })
        Assert.assertEquals("", latestInputPanel.preedit.toString())
        fcitx.reset()
    }

    /** `teh` stays as typed, with no space added: in a password, an address, and with English's switch off. */
    @Test
    fun englishTypesTheKeysThemselvesWhereItSuggestsNothing(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodNames.KEYBOARD))
        fcitx.activateIme(InputMethodNames.KEYBOARD)
        awaitQuiet()
        val suggestBefore = fcitx.imOptions(InputMethodNames.KEYBOARD)?.get(EngineSettings.ENGLISH_SUGGEST).orEmpty()
        try {
            for (flag in listOf(CapabilityFlag.Password, CapabilityFlag.Url)) {
                fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive, flag))
                awaitQuiet()
                assertTypedAsKeys(flag.name)
            }
            fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive))
            fcitx.setImOptions(InputMethodNames.KEYBOARD, mapOf(EngineSettings.ENGLISH_SUGGEST to "False"))
            awaitQuiet()
            assertTypedAsKeys("suggestions off")
        } finally {
            fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive))
            fcitx.setImOptions(InputMethodNames.KEYBOARD, mapOf(EngineSettings.ENGLISH_SUGGEST to suggestBefore))
            fcitx.reset()
        }
    }

    private suspend fun assertTypedAsKeys(where: String) {
        "teh ".forEach { fcitx.sendKey(it, KeyState.Virtual.state) }
        val events = eventsTillQuiet()
        Assert.assertEquals("$where: $events", "teh ".map { it.code }, events.filterIsInstance<HostEvent.Key>().map { it.unicode })
        Assert.assertTrue("$where: $events", events.none { it is HostEvent.Commit })
        Assert.assertEquals(where, "", latestInputPanel.preedit.toString())
    }

    /** English shows none of Chinese's status toggles, and pinyin's shared 全角 does not change its keys. */
    @Test
    fun aFullWidthTurnedOnInPinyinLeavesEnglishsKeysAsTheyAre(): Unit = runBlocking {
        val toggles = listOf("chttrans", "punctuation", "fullwidth")
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodNames.KEYBOARD))
        fcitx.activateIme(InputMethodCatalog.PINYIN)
        awaitQuiet()
        val pinyin = fcitx.statusArea().map { it.name }
        Assert.assertTrue("$pinyin", pinyin.containsAll(toggles))
        val fullWidth = fcitx.statusArea().first { it.name == "fullwidth" }
        val wasOn = fullWidth.active
        if (!wasOn) fcitx.activateAction(fullWidth.id)
        try {
            fcitx.activateIme(InputMethodNames.KEYBOARD)
            awaitQuiet()
            val english = fcitx.statusArea().map { it.name }
            Assert.assertTrue("$english", english.none { it in toggles })
            fcitx.sendKey('a')
            val typed = eventsTillQuiet()
            Assert.assertEquals("$typed", listOf('a'.code), typed.filterIsInstance<HostEvent.Key>().map { it.unicode })
            Assert.assertTrue("$typed", typed.none { it is HostEvent.Commit })
        } finally {
            if (!wasOn) fcitx.activateAction(fullWidth.id)
            fcitx.reset()
        }
    }
}
