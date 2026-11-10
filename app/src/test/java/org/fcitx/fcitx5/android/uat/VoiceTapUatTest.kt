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

/**
 * UAT cases of tapping the bar's microphone to talk (uat/12), everything after the recognizer, as
 * [VoiceHoldUatTest]'s: the mask over the keys, the stop by the microphone or the mask, what is
 * settled typed as it is said, the keyboard floating, the app taking the text (the field left or
 * restarted, the keyboard hidden, the quiet stop: [VoiceTapFieldUatTest]). After each, the
 * microphone closed once ([FakeRecognizer.closes]).
 */
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
        // no panel: the keys stay where they were, under the mask, the bar above it with its microphone
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
        // the mask gone: nothing left over the keys
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
    @Uat("VO-05", "VO-28")
    fun theMaskHasNoEnterStoppedTheKeyboardsSendsIt() {
        allowMicrophone()
        h.start()
        tapMic()
        // no Enter of its own on the mask, nor × (the user's decision 2026-11-07): no button at all
        val mask = mask()!!
        assertEquals(emptyList<String>(), KeyboardHarness.all(mask).mapNotNull { it.contentDescription?.toString() }.toList())
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

    /** The polish (always on): a tagger deleting 嗯, a 。 after 了 (as VoiceLiveTest's). */
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
    fun polishedThenThePolishFailingAtTheEndOnlyWhatFollowsIsTyped() {
        allowMicrophone()
        polishDroppingUmMarkingLe()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("嗯我到了马上走")
        h.advance(100)
        assertEquals("我到了。马上走", text())
        // the end's polish failed: the raw text, its 嗯 back and no mark; what was typed found in it
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

    // R20 F5773: the tail typed once, not twice, when the views are made anew
    @Test
    @Uat("VO-29")
    fun theViewsMadeAnewWhileTalkingTheTailIsTypedOnce() {
        allowMicrophone()
        h.start(MULTI_LINE)
        tapMic()
        voice.heard("今天天气")
        h.advance(100)
        assertEquals(0 to 4, composing())
        // a theme's rebuild (floating on) while the tail still composes: it is not finished in the
        // field as text, then typed again by the talk the old view's end stops
        ThemeManager.prefs.floatingKeyboard.setValue(true)
        restore += { ThemeManager.prefs.floatingKeyboard.setValue(false) }
        h.idle()
        h.relayout()
        h.advance(1000)
        assertEquals(text(), 1, text().split("今天天气").size - 1)
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
        ThemeManager.prefs.floatingKeyboard.setValue(true)
        restore += { ThemeManager.prefs.floatingKeyboard.setValue(false) }
        // the drag below keeps where it ended, and the preferences are one for every test of the JVM
        // (OneDefaultPreferencesShadow): put back, or a later test measures the floating keyboard there
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
        // moved, not stopped
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
