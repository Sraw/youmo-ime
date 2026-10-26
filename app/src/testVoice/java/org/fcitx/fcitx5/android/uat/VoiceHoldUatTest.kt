/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.Manifest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.voice.VoiceHoldSession
import org.fcitx.fcitx5.android.input.voice.VoiceListener
import org.fcitx.fcitx5.android.input.voice.VoicePermissionActivity
import org.fcitx.fcitx5.android.input.voice.VoiceStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * UAT cases of holding to talk (uat/12), everything after the recognizer: what it hears told
 * straight to the session ([VoiceHoldSession.listening]), the rest the app's own (the finger,
 * VoiceHold, VoiceText's clean-up and numbers, VoiceCommit, the commit into the field). Whether the
 * model hears it right is the voice-eval benchmark's; the microphone end to end is VO-02's device smoke.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class VoiceHoldUatTest {

    /**
     * The recognizer: what [said] holds is heard as the finger lifts; [opened] times the microphone
     * opened. [held]: the tail after the lift still being recorded, until [release] or a [cut].
     */
    private class Recognizer : VoiceHoldSession.Listening {
        lateinit var events: VoiceListener.Events
        val said = mutableListOf<String>()
        var opened = 0
        var held = false
        var cuts = 0
        var finishes = 0
        var liftedAt: Long? = null

        /** a recognizer that never comes back, even cut: the session's 15 s give-up ends it */
        var stuck = false
        private var waiting: (() -> Unit)? = null

        /** the microphone's first sample not zero comes as it opens; false: only when [on] says so */
        var warm = true

        override fun load() = Unit
        override fun start() {
            opened++
            // from the capture thread, as VoiceListener's
            if (warm) Handler(Looper.getMainLooper()).post { on() }
        }

        /** the microphone really on */
        fun on() = events.ready()

        /** 2 s of zeroes */
        fun silent() = events.silent()

        override fun finish(liftedAt: Long, done: () -> Unit) {
            finishes++
            this.liftedAt = liftedAt
            waiting = done
            if (!held) release()
        }

        /** the tail recorded: what was said heard, then the session's done */
        fun release() {
            said.forEach(events::recognized)
            said.clear()
            val done = waiting
            waiting = null
            done?.invoke()
        }

        // as VoiceListener's: the capture ends, and what was heard comes back from another thread
        override fun cut() {
            cuts++
            if (waiting != null && !stuck) Handler(Looper.getMainLooper()).post { release() }
        }

        override fun close() = Unit
    }

    private val voice = Recognizer()
    private val restore = mutableListOf<() -> Unit>()

    // the 正在听 cues (VoiceStyle.haptics's cue = true)
    private var cues = 0

    private val h = KeyboardHarness().also {
        VoiceHoldSession.listening = { events -> voice.also { v -> v.events = events } }
        val was = VoiceStyle.haptics
        VoiceStyle.haptics = { _, cue -> if (cue) cues++ }
        restore += { VoiceStyle.haptics = was }
    }

    @After
    fun close() {
        h.close()
        VoiceHoldSession.listening = null
        restore.asReversed().forEach { it() }
    }

    private val app get() = shadowOf(RuntimeEnvironment.getApplication())

    private fun allowMicrophone() = app.grantPermissions(Manifest.permission.RECORD_AUDIO)

    private val space: View
        get() = KeyboardHarness.all(h.inputView).first { h.shown(it) && it.contentDescription?.startsWith("Space") == true }

    // the long press fired, the finger still down
    private fun holdSpace(): KeyboardHarness.Finger = h.finger(space, h.at(space)).also { h.advance(700) }

    private fun KeyboardHarness.Finger.lift(dy: Float = 0f) {
        up(h.at(space, dy = dy))
        h.advance(300)
    }

    private fun say(vararg raw: String) {
        val f = holdSpace()
        voice.said += raw
        f.lift()
    }

    // every text shown in the keyboard's window, the hold's card and the panel included
    private fun shown(): List<String> = KeyboardHarness.all(h.service.window.window!!.decorView)
        .filterIsInstance<TextView>().filter { h.shown(it) }.map { it.text.toString() }.toList()

    private fun text() = h.field.text.toString()

    private fun string(id: Int) = h.service.getString(id)

    private fun panel(): View {
        h.tap(KeyboardHarness.all(h.inputView).first { h.shown(it) && it.contentDescription == string(R.string.voice_input) })
        h.advance(500)
        return KeyboardHarness.all(h.inputView).first { h.shown(it) && it.contentDescription == string(R.string.voice_press_to_talk) }
    }

    private companion object {
        const val RETURN = 0xff0d
        const val BACKSPACE = 0xff08
    }

    @Test
    @Uat("VO-01")
    fun theFirstHoldAsksForTheMicrophone() {
        h.start()
        holdSpace().lift()
        // no permission: the asking page, the microphone not opened
        val asked = app.nextStartedActivity
        assertEquals(VoicePermissionActivity::class.java.name, asked?.component?.className)
        assertEquals(0, voice.opened)
        val page = Robolectric.buildActivity(VoicePermissionActivity::class.java, asked).setup().get()
        assertTrue(shadowOf(page).lastRequestedPermission?.requestedPermissions.orEmpty().contains(Manifest.permission.RECORD_AUDIO))
        // allowed: the next hold talks
        allowMicrophone()
        say("你好")
        assertEquals(1, voice.opened)
        assertEquals("你好", text())
    }

    @Test
    @Uat("VO-02")
    fun whatIsSaidShowsThenIsTyped() {
        allowMicrophone()
        h.start()
        val f = holdSpace()
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        // a stretch heard at a pause: shown, not typed
        voice.events.recognized("今天天气")
        h.advance(100)
        assertTrue(shown().toString(), "今天天气" in shown())
        assertEquals("", text())
        voice.said += "很好。"
        f.lift()
        assertEquals("今天天气很好。", text())
    }

    @Test
    @Uat("VO-03")
    fun slidingUpDropsIt() {
        allowMicrophone()
        h.start()
        val f = holdSpace()
        voice.said += "你好"
        val up = h.keyboard.height / h.service.resources.displayMetrics.density * 0.4f
        f.move(h.at(space, dy = -up))
        assertTrue(shown().toString(), string(R.string.voice_hold_cancel) in shown())
        f.lift(dy = -up)
        assertEquals("", text())
        // dropped at once: no tail recorded after the lift
        assertEquals(0, voice.finishes)
    }

    @Test
    @Uat("VO-04")
    fun thePanelsMicrophoneTypesWhatIsSaid() {
        allowMicrophone()
        h.start()
        val mic = panel()
        val f = h.finger(mic, h.at(mic))
        voice.said += "我们走吧"
        f.up(h.at(mic))
        h.advance(300)
        assertEquals("我们走吧", text())
    }

    @Test
    @Uat("VO-26")
    fun gettingReadyShowsTillTheMicrophoneIsReallyOn() {
        allowMicrophone()
        h.start()
        voice.warm = false
        val f = holdSpace()
        // the HAL still warming up: not 正在听 yet, so nobody starts talking into nothing
        assertTrue(shown().toString(), string(R.string.voice_hold_preparing) in shown())
        assertFalse(shown().toString(), string(R.string.voice_hold_listening) in shown())
        assertEquals(0, cues)
        voice.on()
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        assertFalse(shown().toString(), string(R.string.voice_hold_preparing) in shown())
        // felt once, when it came on
        assertEquals(1, cues)
        voice.said += "你好"
        f.lift()
        assertEquals("你好", text())
        assertEquals(1, cues)
    }

    @Test
    @Uat("VO-27")
    fun aSilentMicrophoneIsSaidAndListeningComesWithTheFirstSound() {
        allowMicrophone()
        h.start()
        voice.warm = false
        val f = holdSpace()
        voice.silent()
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_silent) in shown())
        voice.on()
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        assertFalse(shown().toString(), string(R.string.voice_hold_silent) in shown())
        voice.said += "你好"
        f.lift()
        assertEquals("你好", text())
    }

    @Test
    @Uat("VO-27")
    fun thePanelSaysASilentMicrophoneToo() {
        allowMicrophone()
        h.start()
        voice.warm = false
        val mic = panel()
        val f = h.finger(mic, h.at(mic))
        voice.silent()
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_silent) in shown())
        voice.on()
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        f.up(h.at(mic))
        h.advance(300)
    }

    @Test
    @Uat("VO-26")
    fun theMicrophoneComingOnAfterTheLiftChangesNothing() {
        allowMicrophone()
        h.start()
        voice.warm = false
        voice.held = true
        val f = holdSpace()
        voice.said += "你"
        f.lift()
        // lifted while 准备中…: 识别中… shows, and the microphone coming on in the tail is not 正在听 again
        assertTrue(shown().toString(), string(R.string.voice_hold_recognizing) in shown())
        voice.on()
        h.advance(100)
        assertFalse(shown().toString(), string(R.string.voice_hold_listening) in shown())
        assertEquals(0, cues)
        voice.release()
        h.advance(300)
        assertEquals("你", text())
    }

    @Test
    @Uat("VO-26")
    fun theMicrophoneComingOnAfterItWasDroppedChangesNothing() {
        allowMicrophone()
        h.start()
        voice.warm = false
        val f = holdSpace()
        // slid up and lifted: dropped before the microphone came on
        val up = h.keyboard.height / h.service.resources.displayMetrics.density * 0.4f
        f.move(h.at(space, dy = -up))
        f.lift(dy = -up)
        voice.on()
        h.advance(100)
        assertFalse(shown().toString(), string(R.string.voice_hold_listening) in shown())
        assertEquals(0, cues)
        assertEquals("", text())
    }

    @Test
    @Uat("VO-26")
    fun theCancelHintOutlastsTheMicrophoneComingOn() {
        allowMicrophone()
        h.start()
        voice.warm = false
        val f = holdSpace()
        val up = h.keyboard.height / h.service.resources.displayMetrics.density * 0.4f
        f.move(h.at(space, dy = -up))
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_cancel) in shown())
        // the microphone comes on in the cancel zone: 松开手指，取消输入 stays
        voice.on()
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_cancel) in shown())
        // no 正在听 cue in the cancel zone
        assertEquals(0, cues)
        f.move(h.at(space))
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        // its cue put off till now, once
        assertEquals(1, cues)
        f.move(h.at(space, dy = -up))
        f.move(h.at(space))
        h.advance(100)
        assertEquals(1, cues)
        f.lift()
    }

    @Test
    @Uat("VO-26")
    fun slidingBackBeforeTheMicrophoneIsOnStillShowsGettingReady() {
        allowMicrophone()
        h.start()
        voice.warm = false
        val f = holdSpace()
        val up = h.keyboard.height / h.service.resources.displayMetrics.density * 0.4f
        f.move(h.at(space, dy = -up))
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_cancel) in shown())
        // back before the first sample: 准备中 again, no 正在听 cue yet
        f.move(h.at(space))
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_preparing) in shown())
        assertEquals(0, cues)
        voice.on()
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        assertEquals(1, cues)
        f.lift()
    }

    @Test
    @Uat("VO-26")
    fun thePanelSaysGettingReadyTillTheMicrophoneIsOn() {
        allowMicrophone()
        h.start()
        voice.warm = false
        val mic = panel()
        val f = h.finger(mic, h.at(mic))
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_preparing) in shown())
        voice.on()
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        voice.said += "我们走吧"
        f.up(h.at(mic))
        h.advance(300)
        assertEquals("我们走吧", text())
    }

    @Test
    @Uat("VO-26")
    fun thePanelPutsOffItsCueTillBackFromTheCancelZone() {
        allowMicrophone()
        h.start()
        voice.warm = false
        val mic = panel()
        val f = h.finger(mic, h.at(mic))
        f.move(h.at(mic, dx = 120f))
        h.advance(100)
        // the panel keeps its own cue state: the microphone come on in the cancel zone gives none there
        voice.on()
        h.advance(100)
        assertEquals(0, cues)
        f.move(h.at(mic))
        h.advance(100)
        assertEquals(1, cues)
        f.move(h.at(mic, dx = 120f))
        f.move(h.at(mic))
        h.advance(100)
        assertEquals(1, cues)
        f.up(h.at(mic))
        h.advance(300)
    }

    @Test
    @Uat("VO-26")
    fun aHoldDroppedInTheCancelZoneLeavesTheNextOnesCue() {
        allowMicrophone()
        h.start()
        voice.warm = false
        val mic = panel()
        val f = h.finger(mic, h.at(mic))
        f.move(h.at(mic, dx = 120f))
        f.up(h.at(mic, dx = 120f))
        h.advance(300)
        // the panel outlives the hold: lifting in the cancel zone must not leave it cancelling
        val g = h.finger(mic, h.at(mic))
        h.advance(100)
        voice.on()
        h.advance(100)
        assertEquals(1, cues)
        g.up(h.at(mic))
        h.advance(300)
    }

    @Test
    @Uat("VO-05")
    fun slidingOffThePanelsMicrophoneDropsIt() {
        allowMicrophone()
        h.start()
        val mic = panel()
        val f = h.finger(mic, h.at(mic))
        voice.said += "你好"
        f.move(h.at(mic, dx = 120f))
        f.up(h.at(mic, dx = 120f))
        h.advance(300)
        assertEquals("", text())
    }

    @Test
    @Uat("VO-06")
    fun numbersAreWrittenInDigits() {
        allowMicrophone()
        h.start()
        say("二零零二年")
        say("百分之五十七")
        assertEquals("2002年57%", text().filter { it.isLetterOrDigit() || it == '%' })
    }

    @Test
    @Uat("VO-07")
    fun wordsWithNumeralsAreLeftAlone() {
        allowMicrophone()
        h.start()
        say("这个电影十分有趣")
        assertEquals("这个电影十分有趣", text())
    }

    @Test
    @Uat("VO-08")
    fun englishInChineseIsTypedAsWritten() {
        allowMicrophone()
        h.start()
        // X-ASR spaces Chinese as it does English: the spaces by Chinese dropped, the word kept
        say("我想 买 一个 iPhone")
        assertEquals("我想买一个iPhone", text())
    }

    @Test
    @Uat("VO-09")
    fun aCoughTypesNothing() {
        allowMicrophone()
        h.start()
        // a noise comes back as a filler sound, or nothing
        val f = holdSpace()
        voice.said += "嗯。"
        f.up(h.at(space))
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_nothing) in shown())
        assertEquals("", text())
    }

    @Test
    @Uat("VO-12")
    fun onlyTheFieldItWasSaidForGetsIt() {
        allowMicrophone()
        h.start()
        val first = h.field
        val f = holdSpace()
        voice.said += "你好"
        // another field before the finger lifts
        h.start()
        f.lift()
        assertEquals("", h.field.text.toString())
        assertEquals("", first.text.toString())
    }

    @Test
    @Uat("VO-13")
    fun holdingTheSpaceForThePickerDoesNotTalk() {
        allowMicrophone()
        val pref = AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior
        val was = pref.getValue()
        restore += { pref.setValue(was) }
        pref.setValue(SpaceLongPressBehavior.ShowPicker)
        h.start()
        holdSpace().lift()
        assertNotNull(ShadowDialog.getLatestDialog())
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        assertEquals(0, voice.opened)
    }

    @Test
    @Uat("VO-16")
    fun holdingAgainWhileDidntCatchThatShowsListens() {
        allowMicrophone()
        h.start()
        val f = holdSpace()
        voice.said += "嗯"
        f.up(h.at(space))
        h.advance(300)
        assertTrue(shown().toString(), string(R.string.voice_hold_nothing) in shown())
        // still shown (it goes after a second or so): the next hold talks at once
        val again = holdSpace()
        assertEquals(2, voice.opened)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        voice.said += "你好"
        again.lift()
        assertEquals("你好", text())
    }

    @Test
    @Uat("VO-23")
    fun theTailCountsFromTheLiftAndTypesOnceRecorded() {
        allowMicrophone()
        h.start()
        voice.held = true
        val f = holdSpace()
        voice.said += "今天天气很好。"
        // the event's own time, before it is handled: not the time the session sees it
        val upAt = SystemClock.uptimeMillis() - 40
        f.up(h.at(space), eventTime = upAt)
        // the tail counted from this ACTION_UP's own time, "recognizing" shown at once
        assertEquals(upAt, voice.liftedAt)
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_recognizing) in shown())
        // the tail still being recorded: nothing typed yet
        h.advance(200)
        assertEquals("", text())
        voice.release()
        h.advance(100)
        assertEquals("今天天气很好。", text())
    }

    @Test
    @Uat("VO-25")
    fun pressingAgainEndsTheTailAndCountsForNothing() {
        allowMicrophone()
        h.start()
        voice.held = true
        val f = holdSpace()
        voice.said += "你好"
        f.lift()
        assertEquals("", text())
        // pressed again during the tail: it ends now, what was said is typed, the press is no new hold
        val again = h.finger(space, h.at(space))
        h.advance(100)
        assertEquals(1, voice.cuts)
        assertEquals("你好", text())
        h.advance(700)
        again.up(h.at(space))
        h.advance(300)
        assertEquals(1, voice.opened)
        assertEquals("你好", text())
        // once ended, a long press talks as ever (VO-16)
        voice.held = false
        val next = holdSpace()
        assertEquals(2, voice.opened)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        voice.said += "再见"
        next.lift()
        assertEquals("你好再见", text())
    }

    @Test
    @Uat("VO-25")
    fun pressingThePanelsMicrophoneAgainEndsTheTail() {
        allowMicrophone()
        h.start()
        val mic = panel()
        voice.held = true
        val f = h.finger(mic, h.at(mic))
        voice.said += "我们走吧"
        // held a moment: the lift's own time, 40 ms before it is handled, still after the press
        h.advance(100)
        val upAt = SystemClock.uptimeMillis() - 40
        f.up(h.at(mic), eventTime = upAt)
        assertEquals(upAt, voice.liftedAt)
        h.advance(100)
        val again = h.finger(mic, h.at(mic))
        h.advance(100)
        assertEquals(1, voice.cuts)
        again.up(h.at(mic))
        h.advance(300)
        assertEquals(1, voice.opened)
        assertEquals("我们走吧", text())
    }

    // the panel's own keys beside the microphone
    private fun panelKey(description: Int): View =
        KeyboardHarness.all(h.inputView).first { h.shown(it) && it.contentDescription == string(description) }

    private fun enters() = h.api.calls.count { it.startsWith("sendKey(sym=$RETURN,") }

    // the panel's microphone held with [said], lifted, its tail still being recorded
    private fun liftInTheTail(mic: View, said: String) {
        voice.held = true
        val f = h.finger(mic, h.at(mic))
        voice.said += said
        h.advance(100)
        f.up(h.at(mic))
        h.advance(50)
    }

    @Test
    @Uat("VO-28")
    fun enterInTheTailEndsItAndActsAfterTheText() {
        allowMicrophone()
        h.start()
        val mic = panel()
        // whether the Enter had reached fcitx when the text went into the field
        var enterBeforeText: Boolean? = null
        h.field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (enterBeforeText == null) enterBeforeText = enters() > 0
            }
        })
        liftInTheTail(mic, "我们走吧")
        // the cut's recognition still out: the Enter waits for it
        voice.stuck = true
        val enter = panelKey(R.string.a11y_key_enter)
        h.tap(enter)
        assertEquals(1, voice.cuts)
        assertEquals(0, enters())
        // pressed again while waiting: still one Enter
        h.tap(enter)
        voice.release()
        h.advance(300)
        assertEquals(1, voice.cuts)
        assertEquals("我们走吧", text())
        assertEquals(false, enterBeforeText)
        assertEquals(h.api.calls.toString(), 1, enters())
    }

    @Test
    @Uat("VO-28")
    fun backspaceInTheTailActsAtOnce() {
        allowMicrophone()
        h.start()
        val mic = panel()
        liftInTheTail(mic, "我们走吧")
        h.tap(panelKey(R.string.backspace))
        h.advance(50)
        assertEquals(0, voice.cuts)
        assertEquals(1, h.api.calls.count { it.startsWith("sendKey(sym=$BACKSPACE,") })
        voice.release()
        h.advance(100)
        assertEquals(h.api.calls.toString(), 0, enters())
    }

    @Test
    @Uat("VO-28")
    fun aWaitingEnterGoesWithTheField() {
        allowMicrophone()
        h.start()
        val mic = panel()
        liftInTheTail(mic, "我们走吧")
        voice.stuck = true
        h.tap(panelKey(R.string.a11y_key_enter))
        // another field before the text comes back: the session dropped, the Enter with it
        h.start()
        voice.release()
        h.advance(500)
        assertEquals(h.api.calls.toString(), 0, enters())
    }

    @Test
    @Uat("VO-28")
    fun aWaitingEnterIsDroppedByTheGiveUp() {
        allowMicrophone()
        h.start()
        val mic = panel()
        voice.stuck = true
        liftInTheTail(mic, "我们走吧")
        h.tap(panelKey(R.string.a11y_key_enter))
        h.advance(16_000)
        assertTrue(shown().toString(), string(R.string.voice_hold_nothing) in shown())
        assertEquals(h.api.calls.toString(), 0, enters())
    }

    @Test
    @Uat("VO-19")
    fun whatIsSaidIsContextNotLearned() {
        allowMicrophone()
        h.start()
        h.api.calls.clear()
        say("汪小明")
        assertEquals("汪小明", text())
        // put in the field over the engine (commitOverEngine: its input reset, the text the app's):
        // no word chosen, added or learned from it
        assertEquals(listOf("reset()"), h.api.calls)
    }
}
