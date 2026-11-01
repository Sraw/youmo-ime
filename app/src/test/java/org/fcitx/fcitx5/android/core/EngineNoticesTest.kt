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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** What the engine says from its own threads: on the keyboard when it runs, a toast with none. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class EngineNoticesTest {

    private val said = mutableListOf<String>()

    @After
    fun noKeyboard() {
        EngineNotices.sayer = null
        // what a case left unseen is not said with the next one's
        EngineNotices.forgetToldOnce()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // F3464: fcitx's own tips (punctuation, full width, a group switched) went the failure's way,
    // 6 s and not typed away, and lapsed a pending clipboard delete as they replaced it
    @Test
    fun aTipIsAPlainLineAndOnlyAFailureIsSteady() {
        EngineNotices.sayer = EngineNotices.Sayer { message, steady -> said += "$message steady=$steady"; true }
        EngineNotices.tip("全角")
        // the framework's string: the app's are not in unit tests' resources
        EngineNotices.failed(android.R.string.ok)
        idle()
        assertEquals(listOf("全角 steady=false", "${appContext.getString(android.R.string.ok)} steady=true"), said)
    }

    // A4: the settings unreadable as the host starts, no keyboard shown: said again when one shows, once
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
        // said on the keyboard the first time: not again
        said.clear()
        EngineNotices.failedUntilSeen(android.R.string.ok)
        EngineNotices.keyboardShown()
        idle()
        assertEquals(listOf("$message shown=true"), said)
    }

    // two failures before any keyboard: the keyboard has one notice slot, so both said together, once
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

    // I1: news told until seen (九键拼音 merged) is no failure: plain on the keyboard, no toast with
    // none shown (as the host starts), said plain when one shows; with a failure among them, steady
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

    // F3521: no keyboard running, said all the same
    @Test
    fun withNoKeyboardItIsAToast() {
        EngineNotices.tip("全角")
        idle()
        assertEquals("全角", ShadowToast.getTextOfLatestToast())
    }

    // review 2: the input method's own sayer, no keyboard shown yet (the host starting as an app's field
    // takes focus): news until seen is no toast there either, and said on the keyboard when it shows
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

    // review 2: two waiting, the keyboard's window not on the screen the first time it shows: each waits
    // again as itself, so the next time says each once; each seen only then
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
        // said on the keyboard at once: seen at once
        EngineNotices.toldUntilSeen(android.R.string.ok) { seen += "ok again" }
        idle()
        assertEquals(listOf("ok", "cancel", "ok again"), seen)
    }

    // with no keyboard, a failure joined with news toasts the failure alone: news is no toast
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

    // news told again before it is seen (the host restarted first) is said once when the keyboard shows
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
        // told twice while the keyboard says it: the second not said again on top of the first
        said.clear()
        seen.clear()
        EngineNotices.toldUntilSeen(android.R.string.cancel) { seen += "cancel" }
        EngineNotices.toldUntilSeen(android.R.string.cancel) { seen += "cancel" }
        idle()
        assertEquals(listOf(appContext.getString(android.R.string.cancel)), said)
        assertEquals(listOf("cancel"), seen)
    }
}
