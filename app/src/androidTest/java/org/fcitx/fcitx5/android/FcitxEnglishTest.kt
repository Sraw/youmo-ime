/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import timber.log.Timber

/** [FcitxTest]'s English cases, in a class of their own for its size: the engine set up as there ([TestFcitx]). */
class FcitxEnglishTest {

    // as FcitxTest's: a broken engine interaction fails the run instead of hanging it
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private companion object {

        val harness = TestFcitx()
        val fcitx: Fcitx get() = harness.fcitx
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

    /** A clean engine and event queue for each test: see [TestFcitx.beforeTest]. */
    @Before
    fun resetEngineAndDrainEvents() = harness.beforeTest()

    @After
    fun restoreEnabledIME() = harness.afterTest()

    /**
     * English through the engine as the keyboard types it (virtual keys): the word typed offered
     * with what it starts, space committing it with a space, and a full stop typed next put in
     * that space's place, `hel. `; a slip corrected by space, and, nothing learned here, no words
     * offered after it; a hardware key types itself.
     */
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
        Assert.assertEquals("hel ", space.filterIsInstance<FcitxEvent.CommitStringEvent>().joinToString("") { it.data.text })
        fcitx.sendKey('.', KeyState.Virtual.state)
        val stop = eventsTillQuiet()
        val deleted = stop.filterIsInstance<FcitxEvent.DeleteSurroundingEvent>().map { it.data }
        Assert.assertEquals("$stop", listOf(FcitxEvent.DeleteSurroundingEvent.Data(1, 0)), deleted)
        Assert.assertEquals(". ", stop.filterIsInstance<FcitxEvent.CommitStringEvent>().joinToString("") { it.data.text })
        val started = System.nanoTime()
        "teh".forEach { fcitx.sendKey(it, KeyState.Virtual.state) }
        awaitQuiet()
        Timber.i("teh typed in ${(System.nanoTime() - started) / 1_000_000} ms, the first key building the word index")
        fcitx.sendKey(' ', KeyState.Virtual.state)
        val corrected = eventsTillQuiet()
        // the capital at a sentence's start is the keyboard's (AutoCaps), which these keys are not
        Assert.assertEquals("the ", corrected.filterIsInstance<FcitxEvent.CommitStringEvent>().joinToString("") { it.data.text })
        // the engine is Sensitive here (TestFcitx.start): nothing of the text kept, so nothing offered after it
        Assert.assertEquals("$corrected", 0, corrected.filterIsInstance<FcitxEvent.CandidateListEvent>().lastOrNull()?.data?.total)
        val warm = System.nanoTime()
        "becuase".forEach { fcitx.sendKey(it, KeyState.Virtual.state) }
        awaitQuiet()
        Timber.i("becuase typed in ${(System.nanoTime() - warm) / 1_000_000} ms: ${fcitx.getCandidates(0, 4).map { it.text }}")
        fcitx.sendKey(' ', KeyState.Virtual.state)
        Assert.assertEquals("because ", eventsTillQuiet().filterIsInstance<FcitxEvent.CommitStringEvent>().joinToString("") { it.data.text })
        // a hardware keyboard's key is the app's to type
        fcitx.sendKey('a')
        Assert.assertTrue(eventsTillQuiet().any { it is FcitxEvent.KeyEvent })
        Assert.assertEquals("", latestInputPanel.preedit.toString())
        fcitx.reset()
    }

    /**
     * Where English suggests nothing the keys type themselves, nothing composed or corrected: `teh`
     * stays as typed and no space is put after it, in a password, in an address, and with the
     * English page's switch off.
     */
    @Test
    fun englishTypesTheKeysThemselvesWhereItSuggestsNothing(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodNames.KEYBOARD))
        fcitx.activateIme(InputMethodNames.KEYBOARD)
        awaitQuiet()
        val suggestBefore = fcitx.getImConfig(InputMethodNames.KEYBOARD)["cfg"][EngineSettings.ENGLISH_SUGGEST].value
        try {
            for (flag in listOf(CapabilityFlag.Password, CapabilityFlag.Url)) {
                fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive, flag))
                awaitQuiet()
                assertTypedAsKeys(flag.name)
            }
            fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive))
            fcitx.setImConfig(InputMethodNames.KEYBOARD, RawConfig(arrayOf(RawConfig(EngineSettings.ENGLISH_SUGGEST, false))))
            awaitQuiet()
            assertTypedAsKeys("suggestions off")
        } finally {
            fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive))
            fcitx.setImConfig(InputMethodNames.KEYBOARD, RawConfig(arrayOf(RawConfig(EngineSettings.ENGLISH_SUGGEST, suggestBefore))))
            fcitx.reset()
        }
    }

    /** `teh ` typed as the keyboard types it (virtual keys) comes back to the app as those keys, nothing composed or committed. */
    private suspend fun assertTypedAsKeys(where: String) {
        "teh ".forEach { fcitx.sendKey(it, KeyState.Virtual.state) }
        val events = eventsTillQuiet()
        Assert.assertEquals("$where: $events", "teh ".map { it.code }, events.filterIsInstance<FcitxEvent.KeyEvent>().map { it.data.unicode })
        Assert.assertTrue("$where: $events", events.none { it is FcitxEvent.CommitStringEvent })
        Assert.assertEquals(where, "", latestInputPanel.preedit.toString())
    }

    /**
     * English has none of Chinese's toggles (简繁, 中文标点, 全角) in its status area: a 全角 turned on
     * in pinyin, which every input method showing it shares, leaves English's keys as they are.
     */
    @Test
    fun aFullWidthTurnedOnInPinyinLeavesEnglishsKeysAsTheyAre(): Unit = runBlocking {
        val toggles = listOf("chttrans", "punctuation", "fullwidth")
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN, InputMethodNames.KEYBOARD))
        fcitx.activateIme(Engines.PINYIN)
        awaitQuiet()
        val pinyin = fcitx.statusArea().map { it.name }
        Assert.assertTrue("$pinyin", pinyin.containsAll(toggles))
        val fullWidth = fcitx.statusArea().first { it.name == "fullwidth" }
        // its action is not checkable: the icon says whether it is on
        val wasOn = fullWidth.icon == "fcitx-fullwidth-active"
        if (!wasOn) fcitx.activateAction(fullWidth.id)
        try {
            fcitx.activateIme(InputMethodNames.KEYBOARD)
            awaitQuiet()
            val english = fcitx.statusArea().map { it.name }
            Assert.assertTrue("$english", english.none { it in toggles })
            // a hardware keyboard's key, which English passes on
            fcitx.sendKey('a')
            val typed = eventsTillQuiet()
            Assert.assertEquals("$typed", listOf('a'.code), typed.filterIsInstance<FcitxEvent.KeyEvent>().map { it.data.unicode })
            Assert.assertTrue("$typed", typed.none { it is FcitxEvent.CommitStringEvent })
        } finally {
            if (!wasOn) fcitx.activateAction(fullWidth.id)
            fcitx.reset()
        }
    }
}
