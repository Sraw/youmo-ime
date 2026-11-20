/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.os.Looper
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class EngineNoticesTest {

    private val said = mutableListOf<String>()

    // another class may have left notices waiting in this process's object
    @Before
    fun nothingTold() = EngineNotices.forgetToldOnce()

    @After
    fun noKeyboard() {
        EngineNotices.sayer = null
        EngineNotices.forgetToldOnce()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // fcitx's tips once went the failure's way: 6 s, not typed away, and replacing a pending clipboard delete
    @Test
    fun aTipIsAPlainLineAndOnlyAFailureIsSteady() {
        EngineNotices.sayer = EngineNotices.Sayer { message, steady -> said += "$message steady=$steady"; true }
        EngineNotices.tip("全角")
        // the framework's string: the app's are not in unit tests' resources
        EngineNotices.failed(android.R.string.ok)
        idle()
        assertEquals(listOf("全角 steady=false", "${appContext.getString(android.R.string.ok)} steady=true"), said)
    }

    @Test
    fun aFailureUntilSeenIsSaidAgainWhenTheKeyboardShowsOnce() {
        var shown = false
        EngineNotices.sayer = EngineNotices.Sayer { message, _ -> said += "$message shown=$shown"; shown }
        val message = appContext.getString(android.R.string.ok)
        EngineNotices.failedUntilSeen(android.R.string.ok)
        idle()
        shown = true
        EngineNotices.keyboardShown()
        idle()
        EngineNotices.keyboardShown()
        idle()
        assertEquals(listOf("$message shown=false", "$message shown=true"), said)
        said.clear()
        EngineNotices.failedUntilSeen(android.R.string.ok)
        EngineNotices.keyboardShown()
        idle()
        assertEquals(listOf("$message shown=true"), said)
    }

    // the keyboard has one notice slot, so both said together, once
    @Test
    fun failuresUntilSeenAreSaidTogetherOnTheKeyboard() {
        EngineNotices.failedUntilSeen(android.R.string.ok)
        EngineNotices.failedUntilSeen(android.R.string.cancel)
        idle()
        EngineNotices.sayer = EngineNotices.Sayer { message, _ -> said += message; true }
        EngineNotices.keyboardShown()
        idle()
        assertEquals(listOf(appContext.getString(android.R.string.ok) + "\n" + appContext.getString(android.R.string.cancel)), said)
        EngineNotices.keyboardShown()
        idle()
        assertEquals(1, said.size)
    }

    // with a failure among them, steady
    @Test
    fun newsUntilSeenIsPlainAndWaitsForTheKeyboard() {
        val ok = appContext.getString(android.R.string.ok)
        val cancel = appContext.getString(android.R.string.cancel)
        ShadowToast.reset()
        EngineNotices.toldUntilSeen(android.R.string.ok)
        idle()
        assertEquals(0, ShadowToast.shownToastCount())
        EngineNotices.sayer = EngineNotices.Sayer { message, steady -> said += "$message steady=$steady"; true }
        EngineNotices.keyboardShown()
        idle()
        assertEquals(listOf("$ok steady=false"), said)
        said.clear()
        EngineNotices.sayer = null
        EngineNotices.toldUntilSeen(android.R.string.ok)
        EngineNotices.failedUntilSeen(android.R.string.cancel)
        idle()
        EngineNotices.sayer = EngineNotices.Sayer { message, steady -> said += "$message steady=$steady"; true }
        EngineNotices.keyboardShown()
        idle()
        assertEquals(listOf("$ok\n$cancel steady=true"), said)
    }

    // joined with others, the longest asked; the rest their kind's (null)
    @Test
    fun newsUntilSeenSaysTheTimeItAsks() {
        val ok = appContext.getString(android.R.string.ok)
        val cancel = appContext.getString(android.R.string.cancel)
        var shown = true
        EngineNotices.sayer = object : EngineNotices.Sayer {
            override fun say(message: String, steady: Boolean) = say(message, steady, null)
            override fun say(message: String, steady: Boolean, ms: Long?): Boolean {
                said += "$message ms=$ms"
                return shown
            }
        }
        EngineNotices.toldUntilSeen(android.R.string.ok, ms = 4000L)
        EngineNotices.tip("全角")
        idle()
        assertEquals(listOf("$ok ms=4000", "全角 ms=null"), said)
        said.clear()
        shown = false
        EngineNotices.toldUntilSeen(android.R.string.ok, ms = 4000L)
        EngineNotices.toldUntilSeen(android.R.string.cancel)
        idle()
        shown = true
        EngineNotices.keyboardShown()
        idle()
        assertEquals(listOf("$ok ms=4000", "$cancel ms=null", "$ok\n$cancel ms=4000"), said)
    }

    @Test
    fun aSayerThatTimesNoneSaysItAsAny() {
        EngineNotices.sayer = EngineNotices.Sayer { message, steady -> said += "$message steady=$steady"; true }
        EngineNotices.toldUntilSeen(android.R.string.ok, ms = 4000L)
        idle()
        assertEquals(listOf("${appContext.getString(android.R.string.ok)} steady=false"), said)
    }

    @Test
    fun withNoKeyboardItIsAToast() {
        EngineNotices.tip("全角")
        idle()
        assertEquals("全角", ShadowToast.getTextOfLatestToast())
    }

    // the host starting as an app's field takes focus, no keyboard yet
    @Test
    fun newsUntilSeenIsNoToastThroughTheKeyboardsSayerBeforeTheKeyboard() {
        KeyboardHarness().use { h ->
            ShadowToast.reset()
            EngineNotices.toldUntilSeen(android.R.string.ok)
            idle()
            assertEquals(0, ShadowToast.shownToastCount())
            h.start()
            h.service.onWindowShown()
            assertEquals(listOf(appContext.getString(android.R.string.ok)), h.noticeTexts())
            assertEquals(0, ShadowToast.shownToastCount())
        }
    }

    // the keyboard's window not on screen the first time: each waits again as itself, so the next says each once
    @Test
    fun noticesNotSaidAsTheKeyboardShowsAreEachSaidOnceTheNextTime() {
        val ok = appContext.getString(android.R.string.ok)
        val cancel = appContext.getString(android.R.string.cancel)
        val seen = mutableListOf<String>()
        EngineNotices.toldUntilSeen(android.R.string.ok) { seen += "ok" }
        EngineNotices.toldUntilSeen(android.R.string.cancel) { seen += "cancel" }
        idle()
        var shown = false
        EngineNotices.sayer = EngineNotices.Sayer { message, _ -> said += message; shown }
        EngineNotices.keyboardShown()
        idle()
        assertEquals(emptyList<String>(), seen)
        shown = true
        EngineNotices.keyboardShown()
        idle()
        assertEquals(listOf("$ok\n$cancel", "$ok\n$cancel"), said)
        assertEquals(listOf("ok", "cancel"), seen)
        EngineNotices.toldUntilSeen(android.R.string.ok) { seen += "ok again" }
        idle()
        assertEquals(listOf("ok", "cancel", "ok again"), seen)
    }

    @Test
    fun aFailureWithNewsWaitingToastsTheFailureAlone() {
        val cancel = appContext.getString(android.R.string.cancel)
        EngineNotices.toldUntilSeen(android.R.string.ok)
        EngineNotices.failedUntilSeen(android.R.string.cancel)
        idle()
        ShadowToast.reset()
        EngineNotices.sayer = EngineNotices.Sayer { _, _ -> false }
        EngineNotices.keyboardShown()
        idle()
        assertEquals(cancel, ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())
    }

    // the host restarted first
    @Test
    fun newsToldTwiceBeforeItIsSeenIsSaidOnce() {
        val seen = mutableListOf<String>()
        EngineNotices.toldUntilSeen(android.R.string.ok) { seen += "ok" }
        EngineNotices.toldUntilSeen(android.R.string.ok) { seen += "ok" }
        idle()
        EngineNotices.sayer = EngineNotices.Sayer { message, _ -> said += message; true }
        EngineNotices.keyboardShown()
        idle()
        assertEquals(listOf(appContext.getString(android.R.string.ok)), said)
        assertEquals(listOf("ok"), seen)
        // the second not said again on top of the first
        said.clear()
        seen.clear()
        EngineNotices.toldUntilSeen(android.R.string.cancel) { seen += "cancel" }
        EngineNotices.toldUntilSeen(android.R.string.cancel) { seen += "cancel" }
        idle()
        assertEquals(listOf(appContext.getString(android.R.string.cancel)), said)
        assertEquals(listOf("cancel"), seen)
    }
}
