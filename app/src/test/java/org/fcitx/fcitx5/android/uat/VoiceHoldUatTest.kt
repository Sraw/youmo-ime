/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.Manifest
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
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
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.voice.VoiceSession
import org.fcitx.fcitx5.android.input.voice.VoicePermissionActivity
import org.fcitx.fcitx5.android.input.voice.VoicePolishStream
import org.fcitx.fcitx5.android.input.voice.VoicePolishTags
import org.fcitx.fcitx5.android.input.voice.VoiceStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
 * Holding to talk, everything after the recognizer: what it hears told straight to the session as segments closed,
 * the rest the app's own. Whether the model hears it right is the voice-eval benchmark's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class VoiceHoldUatTest {

    private val voice = FakeRecognizer()
    private val restore = mutableListOf<() -> Unit>()

    private var cues = 0

    private val h = KeyboardHarness().also {
        VoiceSession.listening = { events, _ -> voice.also { v -> v.events = events } }
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

    private val space: ShownKey
        get() = h.keyNamed("space")

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

    private fun shown(): List<String> = KeyboardHarness.all(h.service.window.window!!.decorView)
        .filterIsInstance<TextView>().filter { h.shown(it) }.map { it.text.toString() }.toList()

    private fun text() = h.field.text.toString()

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
        val asked = app.nextStartedActivity
        assertEquals(VoicePermissionActivity::class.java.name, asked?.component?.className)
        assertEquals(0, voice.opened)
        val page = Robolectric.buildActivity(VoicePermissionActivity::class.java, asked).setup().get()
        assertTrue(shadowOf(page).lastRequestedPermission?.requestedPermissions.orEmpty().contains(Manifest.permission.RECORD_AUDIO))
        allowMicrophone()
        say("你好")
        assertEquals(1, voice.opened)
        assertEquals("你好", text())
    }

    @Test
    fun underEnglishWhatIsSaidIsTypedOnce() {
        allowMicrophone()
        h.start(MULTI_LINE)
        // not Chinese: what is composing is finished, not picked, before a commit; voice's own text was once typed again
        val english = InputMethodEntry(InputMethodCatalog.ENGLISH, "English", "", "En", "en")
        h.api.inputMethodEntryCached = english
        h.emit(HostEvent.InputMethodChanged(english))
        h.advance(100)
        val f = holdSpace()
        voice.heard("你好，")
        h.advance(100)
        assertEquals(0 to 3, composing())
        voice.said += "世界。"
        f.lift()
        assertEquals("你好，世界。", text())
        assertEquals(-1 to -1, composing())
    }

    @Test
    fun theEndMarkGoesAsTheFieldHasIt() {
        allowMicrophone()
        h.start()
        say("你好吗？")
        assertEquals("你好吗", text())
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
        TestStyles.set(KeyboardStyle.FLOATING)
        restore += { TestStyles.clear() }
        h.start()
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
        val board = Rect().also { h.keyboard.getGlobalVisibleRect(it) }
        val seen = Rect().also { card.getGlobalVisibleRect(it) }
        assertEquals(board, seen)
        voice.said += "你好"
        f.lift()
        assertEquals("你好", text())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-03")
    fun onAScaledKeyboardTheCancelDistanceIsTheKeyboardAsSeen() {
        allowMicrophone()
        TestStyles.set(KeyboardStyle.FLOATING)
        restore += { TestStyles.clear() }
        h.start()
        h.keyboard.scaleX = 0.8f
        h.keyboard.scaleY = 0.8f
        h.relayout()
        // a quarter of the keyboard's height as seen (scaled), in dp
        val line = h.keyboard.height * 0.8f * 0.25f / h.service.resources.displayMetrics.density
        val f = holdSpace()
        f.move(h.at(space, dy = -(line - 2f)))
        assertTrue(shown().toString(), string(R.string.voice_hold_cancel) !in shown())
        f.move(h.at(space, dy = -(line + 2f)))
        assertTrue(shown().toString(), string(R.string.voice_hold_cancel) in shown())
        f.lift(dy = -(line + 2f))
        assertEquals("", text())
    }

    @Test
    @Uat("VO-02")
    fun whatIsSaidShowsThenIsTyped() {
        allowMicrophone()
        h.start(MULTI_LINE)
        val f = holdSpace()
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        voice.heard("今天天气")
        h.advance(100)
        assertTrue(shown().toString(), "今天天气" in shown())
        assertEquals("今天天气", text())
        assertEquals(0 to 4, composing())
        voice.said += "很好。"
        f.lift()
        // the segments joined with nothing where they have no mark: one there is the tagger's (VoiceLive)
        assertEquals("今天天气很好。", text())
        assertEquals(-1 to -1, composing())
    }

    /** Sent while holding, the field cleared, its input restarted or not: then afresh. */
    @Test
    @Uat("VO-29")
    fun afterTheAppSentWhileHoldingWhatIsSaidNextStartsAfresh() {
        allowMicrophone()
        h.start(MULTI_LINE, fieldId = 7)
        for (restarted in listOf(false, true)) {
            val f = holdSpace()
            voice.heard("今天天气，")
            h.advance(100)
            h.field.text.clear()
            if (restarted) h.restart()
            h.advance(100)
            voice.said += "很好。"
            f.lift()
            assertEquals("很好。", text())
            h.field.text.clear()
            h.advance(1000)
        }
    }

    /** Input restarted in the same field while the finger is down (a WebView): what is said is kept. */
    @Test
    @Uat("VO-12")
    fun inputRestartedInTheSameFieldWhileHoldingKeepsWhatIsSaid() {
        allowMicrophone()
        h.start(MULTI_LINE, fieldId = 7)
        val f = holdSpace()
        voice.heard("今天天气，")
        h.advance(100)
        h.restart()
        h.advance(100)
        voice.said += "很好。"
        f.lift()
        assertEquals("今天天气，很好。", text())
    }

    // a hold whose recognizer never answers: what was heard is still typed when it is given up
    @Test
    @Uat("VO-31")
    fun aHoldCutShortTypesWhatWasHeard() {
        allowMicrophone()
        h.start(MULTI_LINE)
        val f = holdSpace()
        voice.heard("今天天气，")
        h.advance(100)
        voice.held = true
        voice.stuck = true
        f.lift()
        h.advance(15_100)
        assertEquals("今天天气，", text())
        assertTrue(shown().toString(), string(R.string.voice_tap_cut_short) in shown())
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
        voice.on()
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_cancel) in shown())
        assertEquals(0, cues)
        f.move(h.at(space))
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
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
        voice.polish = { VoicePolishStream(numberTags, numberTagger) }
        h.start()
        say("二零零二年")
        say("百分之五十七")
        assertEquals("2002年57%", text().filter { it.isLetterOrDigit() || it == '%' })
    }

    @Test
    @Uat("VO-06")
    fun aTaggerFailingTypesTheRawText() {
        allowMicrophone()
        voice.polish = { VoicePolishStream(numberTags, numberTagger) }
        voice.finishRaw = true
        h.start()
        say("二零零二年")
        assertEquals("二零零二年", text())
        assertTrue(shown().toString(), string(R.string.voice_polish_failed) in shown())
        h.advance(3000)
        say("百分之五十七")
        assertEquals("二零零二年百分之五十七", text())
        assertFalse(shown().toString(), string(R.string.voice_polish_failed) in shown())
        voice.finishRaw = false
        voice.polish = null
        voice.polishNotLoaded = true
        h.advance(3000)
        say("你好")
        assertTrue(shown().toString(), string(R.string.voice_polish_failed) in shown())
    }

    private val numberTags = VoicePolishTags(listOf("keep", "del"), listOf(""), listOf("none", "value", "digits", "date", "time", "money", "percent"))
    private val numberTagger = VoicePolishStream.Tagger { w ->
        val s = String(w, 0, w.size)
        VoicePolishStream.Labels(IntArray(w.size), IntArray(w.size), IntArray(w.size) { if (s[it] == '年') 0 else if ('百' in s) 6 else 3 })
    }

    @Test
    @Uat("VO-07")
    fun wordsWithNumeralsAreLeftAlone() {
        allowMicrophone()
        voice.polish = { VoicePolishStream(numberTags, VoicePolishStream.Tagger { w -> VoicePolishStream.Labels(IntArray(w.size), IntArray(w.size)) }) }
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
        h.start()
        f.lift()
        assertEquals("", h.field.text.toString())
        assertEquals("", first.text.toString())
    }

    // heard while held, then another field: typed into the first; its own test, as rows would share one recognizer and harness
    @Test
    @Uat("VO-12")
    fun whatWasHeardBeforeAnotherFieldGoesIntoTheFirst() {
        allowMicrophone()
        h.start()
        val first = h.field
        val f = holdSpace()
        voice.heard("你好，")
        h.advance(100)
        h.start()
        h.advance(500)
        assertEquals("你好", first.text.toString())
        assertEquals("", h.field.text.toString())
        f.lift()
        h.advance(500)
        assertEquals("你好", first.text.toString())
        assertEquals("", h.field.text.toString())
    }

    // the views made anew under the held finger: what was heard typed at once, not dropped by the old view's cancel
    @Test
    @Uat("VO-12")
    fun theViewsMadeAnewWhileHoldingTypeWhatWasHeardOnce() = heardWhileTheViewsAreMadeAnew {
        TestStyles.set(KeyboardStyle.FLOATING)
        restore += { TestStyles.clear() }
    }

    // onConfigurationChanged ends the hold itself
    @Test
    @Uat("VO-12")
    fun theScreenTurnedWhileHoldingTypesWhatWasHeardOnce() = heardWhileTheViewsAreMadeAnew {
        val turned = Configuration(h.service.resources.configuration).apply {
            orientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        }
        h.service.onConfigurationChanged(turned)
    }

    private fun heardWhileTheViewsAreMadeAnew(makeAnew: () -> Unit) {
        allowMicrophone()
        h.start()
        val f = holdSpace()
        voice.heard("你好，")
        h.advance(100)
        // as a device's: detaching the old view cancels the touch it held before anything under it hears of the detach;
        // the harness's finger goes straight to the input view, so a first child sends that cancel ahead of the card's detach
        val old = h.inputView
        old.addView(View(old.context).apply {
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    val now = SystemClock.uptimeMillis()
                    MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
                        old.dispatchTouchEvent(it)
                        it.recycle()
                    }
                }
            })
        }, 0)
        makeAnew()
        h.idle()
        h.relayout()
        assertNotSame(old, h.inputView)
        h.advance(500)
        assertEquals("你好", text())
        f.lift()
        h.advance(500)
        assertEquals("你好", text())
    }

    @Test
    @Uat("VO-12")
    fun theKeyboardHiddenWhileHoldingTypesWhatWasHeardOnce() {
        allowMicrophone()
        h.start()
        val f = holdSpace()
        voice.heard("你好，")
        h.advance(100)
        h.hide()
        h.advance(500)
        assertEquals("你好", text())
        f.lift()
        h.advance(500)
        assertEquals("你好", text())
    }

    // lifted, then hidden while the tail is still recorded: the recognizer waited for and all of it typed
    @Test
    @Uat("VO-12")
    fun theKeyboardHiddenAfterTheLiftStillTypesTheTail() {
        allowMicrophone()
        h.start(MULTI_LINE)
        voice.held = true
        val f = holdSpace()
        voice.said += "今天天气很好。"
        f.lift()
        h.hide()
        h.advance(300)
        assertEquals("", text())
        voice.release()
        h.advance(300)
        assertEquals("今天天气很好。", text())
    }

    @Test
    @Uat("VO-12")
    fun anotherFieldWhileInTheCancelZoneTypesNothing() {
        allowMicrophone()
        h.start()
        val first = h.field
        val f = holdSpace()
        voice.heard("你好，")
        val up = h.keyboard.height / h.service.resources.displayMetrics.density * 0.4f
        f.move(h.at(space, dy = -up))
        h.start()
        h.advance(500)
        f.lift(dy = -up)
        assertEquals("", first.text.toString())
        assertEquals("", h.field.text.toString())
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
        assertEquals(upAt, voice.stoppedAt)
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_recognizing) in shown())
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
        val again = h.finger(space, h.at(space))
        h.advance(100)
        assertEquals(1, voice.cuts)
        assertEquals("你好", text())
        h.advance(700)
        again.up(h.at(space))
        h.advance(300)
        assertEquals(1, voice.opened)
        assertEquals("你好", text())
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
        // put in over the engine (commitOverEngine): no word chosen, added or learned from it
        assertEquals(listOf("reset()", "voiceSaid(汪小明, 汪小明, false)"), h.api.calls)
    }
}
