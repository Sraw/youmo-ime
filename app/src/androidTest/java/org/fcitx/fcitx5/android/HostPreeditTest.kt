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
import org.fcitx.fcitx5.android.host.HostEvent
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The input method decides where its code shows (androidengine's composesText): English's word in the app, every code above the candidates. */
class HostPreeditTest {

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

    @Test
    fun eachInputMethodsPreeditStaysWhereItPutsIt(): Unit = runBlocking {
        fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive, CapabilityFlag.Preedit))
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodNames.KEYBOARD))
        try {
            fcitx.activateIme(InputMethodNames.KEYBOARD)
            awaitQuiet()
            "hel".forEach { fcitx.sendKey(it, KeyState.Virtual.state) }
            val english = eventsTillQuiet().filterIsInstance<HostEvent.ClientPreedit>().lastOrNull()
            Assert.assertEquals("English's word in the app", "hel", english?.text?.toString())
            fcitx.reset()

            fcitx.activateIme(InputMethodCatalog.PINYIN)
            awaitQuiet()
            "ni".forEach { fcitx.sendKey(it) }
            val pinyin = eventsTillQuiet().filterIsInstance<HostEvent.ClientPreedit>()
            Assert.assertTrue("none of pinyin's code in the app: $pinyin", pinyin.all { it.text.isEmpty() })
            Assert.assertEquals("above the candidates", "ni", latestInputPanel.preedit.toString())
        } finally {
            fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive))
            fcitx.reset()
        }
    }

    /** Never in the app's text, whether started from the keyboard or by Ctrl+Shift+U. */
    @Test
    fun quickPhraseAndUnicodeCodeStayAboveTheCandidatesInEnglish(): Unit = runBlocking {
        fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive, CapabilityFlag.Preedit))
        fcitx.setEnabledIme(arrayOf(InputMethodNames.KEYBOARD))
        try {
            fcitx.activateIme(InputMethodNames.KEYBOARD)
            awaitQuiet()
            val triggers = listOf<Pair<String, suspend () -> Unit>>(
                // the keyboard's shows the search typed after it; Ctrl+Shift+U's the code, "U+…"
                "keyboard's Unicode" to suspend { fcitx.triggerUnicode(); fcitx.sendKey('a') },
                "Ctrl+Shift+U" to suspend { fcitx.sendKey("U", KeyState.Ctrl_Shift.state) },
                "keyboard's quick phrase" to suspend { fcitx.triggerQuickPhrase(); fcitx.sendKey('a') }
            )
            triggers.forEach { (name, trigger) ->
                trigger()
                val inApp = eventsTillQuiet().filterIsInstance<HostEvent.ClientPreedit>()
                Assert.assertTrue("$name: none of it in the app: $inApp", inApp.all { it.text.isEmpty() })
                val code = latestInputPanel.preedit.toString()
                val expected = if (name.startsWith("keyboard's")) "a" else "U+"
                Assert.assertTrue("$name: above the candidates: $code", code.contains(expected))
                fcitx.reset()
                awaitQuiet()
            }
        } finally {
            fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive))
            fcitx.reset()
        }
    }

    @Test
    fun englishsWordGoesInTheAppAfterAShortcut(): Unit = runBlocking {
        fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive, CapabilityFlag.Preedit))
        fcitx.setEnabledIme(arrayOf(InputMethodNames.KEYBOARD))
        try {
            fcitx.activateIme(InputMethodNames.KEYBOARD)
            awaitQuiet()
            fcitx.sendKey("v", KeyState.Ctrl.state)
            awaitQuiet()
            "hel".forEach { fcitx.sendKey(it, KeyState.Virtual.state) }
            val english = eventsTillQuiet().filterIsInstance<HostEvent.ClientPreedit>().lastOrNull()
            Assert.assertEquals("English's word in the app", "hel", english?.text?.toString())
        } finally {
            fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive))
            fcitx.reset()
        }
    }
}
