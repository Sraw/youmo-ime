/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxInstance
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Where what is typed shows is the input method's (androidengine's composesText, kept by the Kotlin
 * host): English's word in the app, every code above the candidates.
 */
class FcitxPreeditTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private companion object {

        val harness = TestFcitx()
        val fcitx: FcitxInstance get() = harness.fcitx
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

    /**
     * In a field that takes composing text, English's word goes into it and pinyin's code above the
     * candidates: the input method's, there being no fcitx-wide preedit option on the Kotlin host.
     */
    @Test
    fun eachInputMethodsPreeditStaysWhereItPutsIt(): Unit = runBlocking {
        fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive, CapabilityFlag.Preedit))
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN, InputMethodNames.KEYBOARD))
        try {
            fcitx.activateIme(InputMethodNames.KEYBOARD)
            awaitQuiet()
            "hel".forEach { fcitx.sendKey(it, KeyState.Virtual.state) }
            val english = eventsTillQuiet().filterIsInstance<FcitxEvent.ClientPreeditEvent>().lastOrNull()
            Assert.assertEquals("English's word in the app", "hel", english?.data?.toString())
            fcitx.reset()

            fcitx.activateIme(Engines.PINYIN)
            awaitQuiet()
            "ni".forEach { fcitx.sendKey(it) }
            val pinyin = eventsTillQuiet().filterIsInstance<FcitxEvent.ClientPreeditEvent>()
            Assert.assertTrue("none of pinyin's code in the app: $pinyin", pinyin.all { it.data.isEmpty() })
            Assert.assertEquals("above the candidates", "ni", latestInputPanel.preedit.toString())
        } finally {
            fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive))
            fcitx.reset()
        }
    }

    /**
     * Quick phrase's and Unicode's code shows above the candidates in English too, started from the
     * keyboard or by Ctrl+Shift+U, never in the app's text (androidengine's composesText).
     */
    @Test
    fun quickPhraseAndUnicodeCodeStayAboveTheCandidatesInEnglish(): Unit = runBlocking {
        fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive, CapabilityFlag.Preedit))
        fcitx.setEnabledIme(arrayOf(InputMethodNames.KEYBOARD))
        try {
            fcitx.activateIme(InputMethodNames.KEYBOARD)
            awaitQuiet()
            val triggers = listOf<Pair<String, suspend () -> Unit>>(
                // the keyboard's is the search, what is typed after it; Ctrl+Shift+U's the code, "U+…"
                "keyboard's Unicode" to suspend { fcitx.triggerUnicode(); fcitx.sendKey('a') },
                "Ctrl+Shift+U" to suspend { fcitx.sendKey("U", KeyState.Ctrl_Shift.state) },
                "keyboard's quick phrase" to suspend { fcitx.triggerQuickPhrase(); fcitx.sendKey('a') }
            )
            triggers.forEach { (name, trigger) ->
                trigger()
                val inApp = eventsTillQuiet().filterIsInstance<FcitxEvent.ClientPreeditEvent>()
                Assert.assertTrue("$name: none of it in the app: $inApp", inApp.all { it.data.isEmpty() })
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

    /** A shortcut turns the flag off for a code it may start; English's next word still goes in the app. */
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
            val english = eventsTillQuiet().filterIsInstance<FcitxEvent.ClientPreeditEvent>().lastOrNull()
            Assert.assertEquals("English's word in the app", "hel", english?.data?.toString())
        } finally {
            fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive))
            fcitx.reset()
        }
    }
}
