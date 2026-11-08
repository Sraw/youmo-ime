/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.Manifest
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.voice.VoiceSession
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
 * straight to the session ([VoiceSession.listening]) as segments closed, the rest the app's own
 * (the finger, VoiceHold, VoiceLive's joining with VoiceText's clean-up and numbers, the polish
 * tagger aside, VoiceEnd, VoiceCommit, the commit into the field). Whether the
 * model hears it right is the voice-eval benchmark's; the microphone end to end is VO-02's device smoke.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class VoiceHoldUatTest {

    private val voice = FakeRecognizer()
    private val restore = mutableListOf<() -> Unit>()

    // the 正在听 cues (VoiceStyle.haptics's cue = true)
    private var cues = 0

    private val h = KeyboardHarness().also {
        VoiceSession.listening = { events -> voice.also { v -> v.events = events } }
        val was = VoiceStyle.haptics
        VoiceStyle.haptics = { _, cue -> if (cue) cues++ }
        restore += { VoiceStyle.haptics = was }
    }

    @After
    fun close() {
        h.close()
        VoiceSession.listening = null
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

    // every text shown in the keyboard's window, the hold's card included
    private fun shown(): List<String> = KeyboardHarness.all(h.service.window.window!!.decorView)
        .filterIsInstance<TextView>().filter { h.shown(it) }.map { it.text.toString() }.toList()

    private fun text() = h.field.text.toString()

    // the field's composing region, -1 to -1 for none
    private fun composing() = h.field.text.let { BaseInputConnection.getComposingSpanStart(it) to BaseInputConnection.getComposingSpanEnd(it) }

    private fun string(id: Int) = h.service.getString(id)

    private companion object {
        const val MULTI_LINE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
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
    fun underEnglishWhatIsSaidIsTypedOnce() {
        allowMicrophone()
        h.start(MULTI_LINE)
        // not Chinese: what is composing is finished, not picked, before a commit (PendingInputPolicy);
        // voice's own text was finished so and the commit typed it again
        val english = InputMethodEntry(InputMethodCatalog.ENGLISH, "English", "", "", "En", "en", "androidengine", false)
        h.api.inputMethodEntryCached = english
        h.emit(HostEvent.InputMethodChanged(english))
        h.advance(100)
        val f = holdSpace()
        voice.heard("你好")
        h.advance(100)
        assertEquals(0 to 2, composing())
        voice.said += "世界。"
        f.lift()
        assertEquals("你好，世界。", text())
        assertEquals(-1 to -1, composing())
    }

    @Test
    fun theEndMarkGoesAsTheFieldHasIt() {
        allowMicrophone()
        // one line (a search, a name): no end mark at all
        h.start()
        say("你好吗？")
        assertEquals("你好吗", text())
        // a chat's message box: no full stop, a question keeps its mark
        h.start(MULTI_LINE, imeOptions = EditorInfo.IME_ACTION_SEND)
        say("我到了。")
        assertEquals("我到了", text())
        h.start(MULTI_LINE, imeOptions = EditorInfo.IME_ACTION_SEND)
        say("你到了吗？")
        assertEquals("你到了吗？", text())
    }

    @Test
    @Uat("VO-02")
    fun floatingTheCardGoesWithTheKeyboard() {
        allowMicrophone()
        ThemeManager.prefs.floatingKeyboard.setValue(true)
        restore += { ThemeManager.prefs.floatingKeyboard.setValue(false) }
        h.start()
        // dragged and made smaller
        h.keyboard.translationX = 40f
        h.keyboard.translationY = -120f
        h.keyboard.scaleX = 0.8f
        h.keyboard.scaleY = 0.8f
        h.relayout()
        val f = holdSpace()
        val listening = KeyboardHarness.all(h.keyboard).filterIsInstance<TextView>()
            .first { h.shown(it) && it.text.toString() == string(R.string.voice_hold_listening) }
        var card: View = listening
        while (card.parent !== h.keyboard) card = card.parent as View
        // over the whole keyboard where it is now, not where it would be docked
        val board = Rect().also { h.keyboard.getGlobalVisibleRect(it) }
        val seen = Rect().also { card.getGlobalVisibleRect(it) }
        assertEquals(board, seen)
        voice.said += "你好"
        f.lift()
        assertEquals("你好", text())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-02")
    fun whatIsSaidShowsThenIsTyped() {
        allowMicrophone()
        // a note's field, many lines: what is said ends as said (VoiceEnd)
        h.start(MULTI_LINE)
        val f = holdSpace()
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        // a segment heard at a pause: shown, and composing in the field, not typed yet
        voice.heard("今天天气")
        h.advance(100)
        assertTrue(shown().toString(), "今天天气" in shown())
        assertEquals("今天天气", text())
        assertEquals(0 to 4, composing())
        voice.said += "很好。"
        f.lift()
        // the segments joined with a pause mark where they have none (VoiceLive)
        assertEquals("今天天气，很好。", text())
        assertEquals(-1 to -1, composing())
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
        h.start(MULTI_LINE)
        voice.held = true
        val f = holdSpace()
        voice.said += "今天天气很好。"
        // the event's own time, before it is handled: not the time the session sees it
        val upAt = SystemClock.uptimeMillis() - 40
        f.up(h.at(space), eventTime = upAt)
        // the tail counted from this ACTION_UP's own time, "recognizing" shown at once
        assertEquals(upAt, voice.stoppedAt)
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
