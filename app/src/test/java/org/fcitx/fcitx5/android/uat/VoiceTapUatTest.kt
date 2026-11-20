/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.graphics.Rect
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.voice.VoiceFeature
import org.fcitx.fcitx5.android.input.voice.VoicePolishStream
import org.fcitx.fcitx5.android.input.voice.VoicePolishTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tapping the bar's microphone to talk, everything after the recognizer; after each, the microphone closed once. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class VoiceTapUatTest : VoiceTapUat() {

    @Test
    @Uat("VO-04")
    fun tappingTheMicrophoneTalksTillItIsTappedAgain() {
        allowMicrophone()
        h.start()
        tapMic()
        assertTrue(talking)
        assertTrue(shown().toString(), string(R.string.voice_hold_listening) in shown())
        assertTrue(h.keys().any { it.id == "space" })
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
        tapMic()
        assertEquals(1, voice.finishes)
        assertEquals(0, voice.cuts)
        tapMic()
        h.advance(100)
        assertEquals(1, voice.cuts)
        assertEquals(1, voice.opened)
        assertEquals("我们走吧", text())
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }

    // hidden, the card cannot be seen: 语音整理's failure not said there, its one hint a process kept for a card that shows
    @Test
    fun hiddenThePolishHintIsKeptForATalkThatShowsIt() {
        allowMicrophone()
        voice.finishRaw = true
        h.start(MULTI_LINE)
        tapMic()
        voice.said += "你好"
        h.advance(100)
        h.hide()
        h.advance(300)
        assertTrue(text(), text().startsWith("你好"))
        assertFalse(shown().toString(), string(R.string.voice_polish_failed) in shown())
        h.start(MULTI_LINE)
        tapMic()
        voice.said += "再见"
        tapMic()
        assertTrue(shown().toString(), string(R.string.voice_polish_failed) in shown())
    }

    @Test
    @Uat("VO-29")
    fun whatIsSettledIsTypedAsItIsSaid() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        // one segment: the recognizer may still choose it otherwise, all of it composing
        voice.heard("今天天气，")
        h.advance(100)
        assertEquals("今天天气，", text())
        assertEquals(0 to 5, composing())
        voice.heard("很好，")
        h.advance(100)
        assertEquals("今天天气，很好，", text())
        assertEquals(5 to 8, composing())
        voice.hearing("我们")
        h.advance(100)
        assertEquals("今天天气，很好，我们", text())
        assertEquals(5 to 10, composing())
        voice.said += "我们走吧。"
        tapMic()
        assertEquals("今天天气，很好，我们走吧。", text())
        assertEquals(-1 to -1, composing())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-05", "VO-28")
    fun theMaskHasNoEnterStoppedTheKeyboardsSendsIt() {
        allowMicrophone()
        h.start()
        tapMic()
        val mask = mask()!!
        assertEquals(emptyList<String>(), KeyboardHarness.all(mask).mapNotNull { it.contentDescription?.toString() }.toList())
        voice.said += "我们走吧"
        h.tap(mask)
        h.advance(1000)
        assertEquals("我们走吧", text())
        assertNull(mask())
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
        voice.heard("今天天气，")
        voice.heard("很好，")
        h.advance(100)
        assertEquals("今天天气，很好，", text())
        h.field.setSelection(0)
        h.idle()
        assertEquals("今天天气，", text())
        voice.hearing("我们")
        h.advance(100)
        assertEquals("很好，我们今天天气，", text())
        voice.said += "我们走吧"
        tapMic()
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
        voice.heard("今天天气，")
        voice.heard("很好，")
        h.advance(100)
        assertEquals("今天天气，很好，", text())
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
        voice.heard("今天天气，")
        voice.heard("很好，")
        h.advance(100)
        h.field.setText("好的")
        h.field.setSelection(2)
        h.idle()
        assertEquals("好的", text())
        voice.said += "我们走吧"
        tapMic()
        assertEquals("好的我们走吧", text())
        assertEquals(1, voice.closes)
    }

    /** Sent, then talking on: what is said next starts afresh, nothing sent typed again. */
    @Test
    @Uat("VO-29")
    fun afterTheAppSentItWhatIsSaidNextStartsAfresh() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气，")
        voice.heard("很好，")
        h.advance(100)
        h.field.text.clear()
        h.idle()
        voice.heard("我们走吧")
        h.advance(100)
        assertEquals("我们走吧", text())
        voice.said += "明天见"
        tapMic()
        assertEquals("我们走吧明天见", text())
        assertEquals(1, voice.closes)
    }

    /** Sent while a segment was still being said: what the polish later writes of it is not typed again. */
    @Test
    @Uat("VO-29")
    fun afterTheAppSentMidSegmentWhatThePolishRewroteIsNotTypedAgain() {
        allowMicrophone()
        polishDroppingUmMarkingLe()
        h.start(MULTI_LINE)
        tapMic()
        voice.hearing("我到了吗")
        h.advance(100)
        assertEquals("我到了吗", text())
        h.field.text.clear()
        h.idle()
        voice.hearing("我到了吗好的")
        h.advance(100)
        assertEquals("好的", text())
        voice.said += "我到了吗好的"
        tapMic()
        assertEquals("好的", text())
        assertEquals(1, voice.closes)
    }

    /** The recognizer told of the send late: what it heard before is not shown as the last utterance's, nor lost. */
    @Test
    @Uat("VO-29")
    fun afterTheAppSentWhatTheRecognizerHeardBeforeItKnewIsNotLost() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气，")
        h.advance(100)
        h.field.text.clear()
        h.idle()
        voice.lagging = true
        voice.heard("很好")
        h.advance(100)
        assertEquals("", text())
        voice.lagging = false
        voice.hearing("我们")
        h.advance(100)
        assertEquals("很好我们", text())
        voice.said += "我们走吧"
        tapMic()
        assertEquals("很好我们走吧", text())
        assertEquals(1, voice.closes)
    }

    /** All recognized before the recognizer was told of the send: what comes back is what went, nothing typed. */
    @Test
    @Uat("VO-29")
    fun afterTheAppSentTheLastUtterancesTextAtTheEndTypesNothing() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气，")
        h.advance(100)
        h.field.text.clear()
        h.idle()
        voice.lagging = true
        tapMic()
        h.advance(100)
        assertEquals("", text())
        assertEquals(1, voice.closes)
    }

    private fun polishDroppingUmMarkingLe() {
        val tags = VoicePolishTags(listOf("keep", "del"), listOf("", "，", "。"), listOf("none"))
        val tagger = VoicePolishStream.Tagger { w ->
            VoicePolishStream.Labels(IntArray(w.size) { if (w[it] == '嗯'.code) VoicePolishTags.DEL else VoicePolishTags.KEEP }, IntArray(w.size) { if (w[it] == '了'.code) 2 else 0 })
        }
        voice.polish = { VoicePolishStream(tags, tagger, freezeAfter = 2, window = 8) }
    }

    @Test
    @Uat("VO-29")
    fun polishedTheFrozenPartIsTypedAsItIsSaid() {
        allowMicrophone()
        polishDroppingUmMarkingLe()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("嗯我到了马上走")
        h.advance(100)
        assertEquals("我到了。马上走", text())
        assertEquals(4 to 7, composing())
        tapMic()
        assertEquals("我到了。马上走", text())
        assertEquals(-1 to -1, composing())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-29")
    fun polishedThenThePolishFailingAtTheEndOnlyWhatFollowsIsTyped() {
        allowMicrophone()
        polishDroppingUmMarkingLe()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("嗯我到了马上走")
        h.advance(100)
        assertEquals("我到了。马上走", text())
        voice.finishRaw = true
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
        val english = InputMethodEntry(InputMethodCatalog.ENGLISH, "English", "", "En", "en")
        h.api.inputMethodEntryCached = english
        h.emit(HostEvent.InputMethodChanged(english))
        h.advance(100)
        tapMic()
        voice.heard("你好，")
        voice.heard("世界，")
        h.advance(100)
        assertEquals("你好，世界，", text())
        voice.said += "走吧。"
        tapMic()
        assertEquals("你好，世界，走吧。", text())
        assertEquals(-1 to -1, composing())
        assertEquals(1, voice.closes)
    }

    // the tail typed once, not twice, when the views are made anew
    @Test
    @Uat("VO-29")
    fun theViewsMadeAnewWhileTalkingTheTailIsTypedOnce() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气，")
        h.advance(100)
        assertEquals(0 to 5, composing())
        // a rebuild while the tail still composes: not finished in the field as text, then typed again by the old view's end
        TestStyles.set(KeyboardStyle.FLOATING)
        restore += { TestStyles.clear() }
        h.idle()
        h.relayout()
        h.advance(1000)
        assertEquals(text(), 1, text().split("今天天气").size - 1)
    }

    @Test
    @Uat("VO-04")
    fun floatingTheMaskGoesWithTheKeyboard() {
        allowMicrophone()
        TestStyles.set(KeyboardStyle.FLOATING)
        restore += { TestStyles.clear() }
        h.start()
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
        assertTrue("$seen over $bar", seen.top >= bar.bottom - 1)
        val key = h.keyNamed("space").bounds
        assertTrue("$key not under $seen", seen.contains(key))
        voice.said += "你好"
        tapMic()
        assertEquals("你好", text())
        assertEquals(1, voice.closes)
    }

    @Test
    @Uat("VO-04")
    fun floatingDraggingItsHandleTalksOn() {
        allowMicrophone()
        TestStyles.set(KeyboardStyle.FLOATING)
        restore += { TestStyles.clear() }
        // the preferences are one for every test of the JVM (OneDefaultPreferencesShadow): put back, or a later test
        // measures the floating keyboard there
        AppPrefs.getInstance().internal.run {
            restore += {
                floatingKeyboardX.setValue(floatingKeyboardX.defaultValue)
                floatingKeyboardY.setValue(floatingKeyboardY.defaultValue)
            }
        }
        h.start()
        tapMic()
        val handle = KeyboardHarness.find<FloatingHandle>(h.inputView)!!
        assertTrue(h.shown(handle))
        val at = h.box(handle)
        h.drag(handle, at.exactCenterX() + 30f, at.exactCenterY() - 150f)
        h.advance(100)
        assertTrue(talking)
        assertEquals(0, voice.finishes)
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
        VoiceFeature.tap(h.inputView) { h.service.commitText(it) }
        h.advance(100)
        assertTrue(talking)
        voice.held = true
        voice.said += "我们走吧"
        // a real touch: stops it, the tail not cut by the touch's going down
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
        tapMic()
        assertFalse(talking)
        assertEquals(1, voice.closes)
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
        assertFalse(talking)
        assertEquals(1, voice.closes)
    }
}
