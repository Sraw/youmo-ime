/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.Manifest
import android.graphics.Rect
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.voice.VoiceFeature
import org.fcitx.fcitx5.android.input.voice.VoicePolishStream
import org.fcitx.fcitx5.android.input.voice.VoicePolishTags
import org.fcitx.fcitx5.android.input.voice.VoiceSession
import org.fcitx.fcitx5.android.input.voice.VoiceStyle
import org.fcitx.fcitx5.android.input.voice.VoiceTap
import org.fcitx.fcitx5.android.input.voice.VoiceTapOverlay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UAT cases of tapping the bar's microphone to talk (uat/12), everything after the recognizer, as
 * [VoiceHoldUatTest]'s: the mask over the keys, the stop by the microphone or the mask, ×, what is
 * settled typed as it is said, the field left or restarted, the keyboard hidden or floating, the
 * app taking the text, the quiet stop. After each, the microphone closed once ([FakeRecognizer.closes]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class VoiceTapUatTest {

    private val voice = FakeRecognizer()
    private val restore = mutableListOf<() -> Unit>()

    private val h = KeyboardHarness().also {
        VoiceSession.listening = { events -> voice.also { v -> v.events = events } }
        val was = VoiceStyle.haptics
        VoiceStyle.haptics = { _, _ -> }
        restore += { VoiceStyle.haptics = was }
    }

    @After
    fun close() {
        h.close()
        VoiceSession.listening = null
        restore.asReversed().forEach { it() }
    }

    private fun allowMicrophone() = shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)

    private fun string(id: Int) = h.service.getString(id)

    private fun described(description: CharSequence, within: View = h.inputView): View? =
        KeyboardHarness.all(within).firstOrNull { h.shown(it) && it.contentDescription == description }

    // the bar's own microphone
    private fun mic(): View = described(string(R.string.voice_input)) ?: error("no microphone on the bar")

    private fun tapMic() {
        h.tap(mic())
        h.advance(100)
    }

    // the mask: what its hint sits on (hidden once it is stopped), taken by a click
    private fun mask(): View? {
        val hint = KeyboardHarness.all(h.inputView)
            .firstOrNull { it is TextView && it.text.toString() == string(R.string.voice_tap_hint) } ?: return null
        var at = hint.parent as? View
        while (at != null && !at.hasOnClickListeners()) at = at.parent as? View
        return at?.takeIf { h.shown(it) }
    }

    private fun cancel(): View = described(string(R.string.voice_tap_cancel), mask()!!) ?: error("no × on the mask")

    // the keyboard's own Enter, named as the field's action
    private fun enter(): View =
        described(ReturnKeyDrawableComponent.appearanceFromEditorInfo(h.info).label(h.service), h.keyboard) ?: error("no Enter on the keyboard")

    private fun shown(): List<String> = KeyboardHarness.all(h.inputView)
        .filterIsInstance<TextView>().filter { h.shown(it) }.map { it.text.toString() }.toList()

    private fun text() = h.field.text.toString()

    private fun composing() = h.field.text.let { BaseInputConnection.getComposingSpanStart(it) to BaseInputConnection.getComposingSpanEnd(it) }

    private fun enters() = h.api.calls.count { it.startsWith("sendKey(sym=$RETURN,") }

    private val talking get() = h.inputView.hold is VoiceTapOverlay

    private companion object {
        const val MULTI_LINE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        const val RETURN = 0xff0d
    }

    @Test
    @Uat("VO-04")
    fun tappingTheMicrophoneTalksTillItIsTappedAgain() {
        allowMicrophone()
        h.start()
        tapMic()
        assertTrue(talking)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        // no panel: the keys stay where they were, under the mask, the bar above it with its microphone
        assertTrue(KeyboardHarness.all(h.inputView).any { h.shown(it) && it.contentDescription?.startsWith("Space") == true })
        assertNotNullMask()
        assertTrue(h.shown(mic()))
        assertEquals(1, voice.opened)
        voice.said += "我们走吧"
        tapMic()
        assertEquals("我们走吧", text())
        assertEquals(1, voice.finishes)
        assertFalse(talking)
        h.advance(1000)
        assertNull(mask())
        // and again, as often as tapped
        tapMic()
        voice.said += "你好"
        tapMic()
        assertEquals(2, voice.opened)
        assertEquals("我们走吧你好", text())
        assertEquals(2, voice.closes)
    }

    private fun assertNotNullMask() = assertTrue("no mask over the keys", mask() != null)

    @Test
    @Uat("VO-04")
    fun tappingTheMaskStopsIt() {
        allowMicrophone()
        h.start()
        tapMic()
        voice.said += "我们走吧"
        h.tap(mask()!!)
        h.advance(100)
        assertEquals("我们走吧", text())
        assertFalse(talking)
        // the mask gone, the keys take presses again
        h.advance(1000)
        assertNull(mask())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-04")
    fun theMicrophoneTappedInTheTailEndsIt() {
        allowMicrophone()
        h.start()
        tapMic()
        voice.held = true
        voice.said += "我们走吧"
        // stopped, its tail still being recorded
        tapMic()
        assertEquals(1, voice.finishes)
        assertEquals(0, voice.cuts)
        // tapped again: the tail cut, what was said typed, no new talk started
        tapMic()
        h.advance(100)
        assertEquals(1, voice.cuts)
        assertEquals(1, voice.opened)
        assertEquals("我们走吧", text())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-05")
    fun cancelTakesOnlyTheUnsettledTail() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气")
        voice.heard("很好")
        h.advance(100)
        assertEquals("今天天气，很好", text())
        h.tap(cancel())
        h.advance(100)
        // what was already typed stays; the tail still composing goes, nothing recognized after
        assertEquals("今天天气，", text())
        assertEquals(-1 to -1, composing())
        assertEquals(0, voice.finishes)
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-05")
    fun cancelBeforeAnythingSettledLeavesTheFieldAsItWas() {
        allowMicrophone()
        h.start()
        h.field.setText("ab")
        h.field.setSelection(2)
        tapMic()
        voice.hearing("我们")
        h.advance(100)
        assertEquals("ab我们", text())
        h.tap(cancel())
        h.advance(100)
        assertEquals("ab", text())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-29")
    fun whatIsSettledIsTypedAsItIsSaid() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        // one segment: the recognizer may still choose it otherwise, all of it composing
        voice.heard("今天天气")
        h.advance(100)
        assertEquals("今天天气", text())
        assertEquals(0 to 4, composing())
        // the next one heard: the first, with its pause mark, typed for good; the rest composing
        voice.heard("很好")
        h.advance(100)
        assertEquals("今天天气，很好", text())
        assertEquals(5 to 7, composing())
        voice.hearing("我们")
        h.advance(100)
        assertEquals("今天天气，很好，我们", text())
        assertEquals(5 to 10, composing())
        voice.said += "我们走吧。"
        tapMic()
        // the end mark only at the end (VoiceEnd), nothing typed twice
        assertEquals("今天天气，很好，我们走吧。", text())
        assertEquals(-1 to -1, composing())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-30")
    fun anotherFieldGetsNothingTheFirstGetsWhatWasSaid() {
        allowMicrophone()
        h.start()
        val first = h.field
        tapMic()
        voice.heard("你好")
        h.advance(100)
        // another field tapped while it talks
        h.start()
        h.advance(500)
        assertEquals("你好", first.text.toString())
        assertEquals("", text())
        assertFalse(talking)
        assertNull(mask())
        // what was still to be heard does not come late into the new field
        voice.release()
        h.advance(500)
        assertEquals("", text())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-30")
    fun theKeyboardHiddenStopsItAndTypesTheRestOnceRecognized() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气")
        voice.heard("很好")
        h.advance(100)
        assertEquals("今天天气，很好", text())
        voice.held = true
        voice.said += "我们走吧"
        h.service.onFinishInputView(false)
        h.advance(100)
        // stopped as by a tap: what composed taken away, not left as text; the tail still recorded
        assertEquals("今天天气，", text())
        assertEquals(-1 to -1, composing())
        assertEquals(1, voice.finishes)
        assertEquals(0, voice.closes)
        // recognized: what is left typed straight into the field, once
        voice.release()
        h.advance(100)
        assertEquals("今天天气，很好，我们走吧", text())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-28")
    fun theMaskHasNoEnterStoppedTheKeyboardsSendsIt() {
        allowMicrophone()
        h.start()
        tapMic()
        // no Enter of its own on the mask: only × and the hint
        val mask = mask()!!
        assertEquals(listOf(string(R.string.voice_tap_cancel)), KeyboardHarness.all(mask).mapNotNull { it.contentDescription?.toString() }.toList())
        voice.said += "我们走吧"
        h.tap(mask)
        h.advance(1000)
        assertEquals("我们走吧", text())
        assertNull(mask())
        // the keyboard's own Enter, under the mask before: the field's action
        assertEquals(0, enters())
        val enter = enter()
        h.touch(enter, h.at(enter))
        assertEquals(h.api.calls.toString(), 1, enters())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-29")
    fun theCursorMovedWhileTalkingTheTailComposesWhereItWent() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气")
        voice.heard("很好")
        h.advance(100)
        assertEquals("今天天气，很好", text())
        // the user taps the field's start: what composed is taken away there
        h.field.setSelection(0)
        h.idle()
        assertEquals("今天天气，", text())
        // and composes at the cursor on the next heard
        voice.hearing("我们")
        h.advance(100)
        assertEquals("很好，我们今天天气，", text())
        voice.said += "我们走吧"
        tapMic()
        // nothing typed twice: the rest at the cursor, what was typed where it was
        assertEquals("很好，我们走吧今天天气，", text())
        assertEquals(-1 to -1, composing())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-29")
    fun whatTheAppSentIsNotTypedAgain() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气")
        voice.heard("很好")
        h.advance(100)
        assertEquals("今天天气，很好", text())
        // a chat app sends it, the composing tail with it, and empties its box
        h.field.text.clear()
        h.idle()
        assertEquals("", text())
        assertTrue(talking)
        tapMic()
        h.advance(100)
        assertEquals("", text())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-29")
    fun whatTheAppRewroteIsNotTypedAgainTheTalkGoesOn() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气")
        voice.heard("很好")
        h.advance(100)
        // the app puts its own text in place of it all (a template, a sent message's echo)
        h.field.setText("好的")
        h.field.setSelection(2)
        h.idle()
        assertEquals("好的", text())
        voice.said += "我们走吧"
        tapMic()
        // what it heard before the app took the text is not typed again, what came after is
        assertEquals("好的我们走吧", text())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-30")
    fun inputRestartedInTheSameFieldTalksOn() {
        allowMicrophone()
        h.start(MULTI_LINE, fieldId = 7)
        tapMic()
        voice.heard("今天天气")
        voice.heard("很好")
        h.advance(100)
        assertEquals("今天天气，很好", text())
        // the app restarts input in it (a WebView's field, an app's restartInput): a new EditorInfo
        h.restart()
        h.advance(100)
        assertTrue(talking)
        assertEquals("今天天气，", text())
        voice.hearing("我们")
        h.advance(100)
        assertEquals("今天天气，很好，我们", text())
        voice.said += "我们走吧"
        tapMic()
        assertEquals("今天天气，很好，我们走吧", text())
        assertEquals(1, voice.opened)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-29")
    fun polishedTheFrozenPartIsTypedAsItIsSaid() {
        allowMicrophone()
        // 语音整理 on: a tagger deleting 嗯, a 。 after 了 (as VoiceLiveTest's)
        val tags = VoicePolishTags(listOf("keep", "del"), listOf("", "，", "。"))
        val tagger = VoicePolishStream.Tagger { w ->
            IntArray(w.size) { if (w[it] == '嗯'.code) VoicePolishTags.DEL else VoicePolishTags.KEEP } to
                IntArray(w.size) { if (w[it] == '了'.code) 2 else 0 }
        }
        voice.polish = { VoicePolishStream(tags, tagger, freezeAfter = 2, window = 8) }
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("嗯我到了马上走")
        h.advance(100)
        // the tagger's frozen text of the closed segment, to its mark, typed; the rest composing
        assertEquals("我到了。马上走", text())
        assertEquals(4 to 7, composing())
        tapMic()
        assertEquals("我到了。马上走", text())
        assertEquals(-1 to -1, composing())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-29")
    fun underEnglishWhatIsSaidIsTypedOnce() {
        allowMicrophone()
        h.start(MULTI_LINE)
        // not Chinese: what composes is finished, not picked, before a commit (PendingInputPolicy)
        val english = InputMethodEntry(InputMethodCatalog.ENGLISH, "English", "", "", "En", "en", "androidengine", false)
        h.api.inputMethodEntryCached = english
        h.emit(HostEvent.InputMethodChanged(english))
        h.advance(100)
        tapMic()
        voice.heard("你好")
        voice.heard("世界")
        h.advance(100)
        assertEquals("你好，世界", text())
        voice.said += "走吧。"
        tapMic()
        assertEquals("你好，世界，走吧。", text())
        assertEquals(-1 to -1, composing())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-04")
    fun floatingTheMaskGoesWithTheKeyboard() {
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
        tapMic()
        val mask = mask()!!
        assertTrue("not the keyboard's own", mask.parent === h.keyboard)
        val board = Rect().also { h.keyboard.getGlobalVisibleRect(it) }
        val bar = Rect().also { h.bar.getGlobalVisibleRect(it) }
        val seen = Rect().also { mask.getGlobalVisibleRect(it) }
        assertTrue("$seen not in $board", board.contains(seen))
        // under the bar, over the keys
        assertTrue("$seen over $bar", seen.top >= bar.bottom - 1)
        val space = KeyboardHarness.all(h.keyboard).first { h.shown(it) && it.contentDescription?.startsWith("Space") == true }
        val key = Rect().also { space.getGlobalVisibleRect(it) }
        assertTrue("$key not under $seen", seen.contains(key))
        voice.said += "你好"
        tapMic()
        assertEquals("你好", text())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-04")
    fun startedFromAShortcutTheMicrophoneStillStopsItOnce() {
        allowMicrophone()
        h.start()
        // as a key's shortcut starts it: no button behind it
        VoiceFeature.tap(h.inputView) { h.service.commitText(it) }
        h.advance(100)
        assertTrue(talking)
        voice.held = true
        voice.said += "我们走吧"
        // the bar's microphone, a real touch: stops it, the tail not cut by the touch's going down
        val mic = mic()
        h.touch(mic, h.at(mic))
        h.advance(100)
        assertEquals(1, voice.finishes)
        assertEquals(0, voice.cuts)
        voice.release()
        h.advance(100)
        assertEquals("我们走吧", text())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-31")
    fun givenUpWhatWasHeardIsTypedAfterWhatWent() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气")
        voice.heard("很好")
        h.advance(100)
        voice.held = true
        voice.stuck = true
        tapMic()
        // the recognizer never back, the tail cut no matter: 15 s and given up
        tapMic()
        h.advance(15_100)
        assertEquals("今天天气，很好", text())
        assertEquals(-1 to -1, composing())
        assertTrue(shown().toString(), string(R.string.voice_tap_cut_short) in shown())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-31")
    fun givenUpWithNothingTypedSaysNothingWasHeard() {
        allowMicrophone()
        h.start()
        tapMic()
        voice.held = true
        voice.stuck = true
        voice.said += "我们走吧"
        tapMic()
        tapMic()
        h.advance(15_100)
        assertTrue(shown().toString(), string(R.string.voice_hold_nothing) in shown())
        assertEquals("", text())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-31")
    fun aMinuteWithNothingSaidStopsIt() {
        allowMicrophone()
        h.start()
        tapMic()
        h.advance(VoiceTap.QUIET_MS / 2)
        voice.hearing("你好")
        // counted from the last thing heard
        h.advance(VoiceTap.QUIET_MS - 1000)
        assertTrue(talking)
        assertEquals(0, voice.finishes)
        h.advance(2000)
        assertEquals(1, voice.finishes)
        assertEquals("你好", text())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-26")
    fun gettingReadyShowsTillTheMicrophoneIsOn() {
        allowMicrophone()
        h.start()
        voice.warm = false
        tapMic()
        assertTrue(shown().toString(), string(R.string.voice_hold_preparing) in shown())
        assertFalse(shown().toString(), string(R.string.voice_hold_listening) in shown())
        voice.on()
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
    }

    @Test
    @Uat("VO-27")
    fun aSilentMicrophoneIsSaid() {
        allowMicrophone()
        h.start()
        voice.warm = false
        tapMic()
        voice.silent()
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_silent) in shown())
        voice.on()
        h.advance(100)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        voice.said += "你好"
        tapMic()
        assertEquals("你好", text())
    }
}
