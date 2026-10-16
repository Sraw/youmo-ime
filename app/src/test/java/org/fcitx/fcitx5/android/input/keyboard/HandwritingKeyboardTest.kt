/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPadView
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognitionTest.Queue
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognitionTest.Reader
import org.fcitx.fcitx5.android.input.handwriting.InkPad
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The handwriting keyboard's keys against its reads, the recognizer's queues run by hand: a key
 * pressed while a stroke is read goes in after its reading, and not at all if another input
 * started meanwhile; a backspace held so is decided in its turn, and is the engine's behind a key
 * that committed the piece; leaving the layout with strokes on
 * the pad finishes their piece, as a pause would have; the recognizer is loaded as it is shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class HandwritingKeyboardTest {

    private val worker = Queue()
    private val main = Queue()
    private val reader = Reader()
    private val recognition = HandwritingRecognition(worker, main, { -1 }, reader)
    private val sent = ArrayList<KeyAction>()
    private val finished = ArrayList<Int>()

    // in a window, as on a phone: a held key is dropped once the keyboard is not
    private fun keyboard() = HandwritingKeyboard(
        PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark,
        deliver = { _, _ -> }, finished = { finished += it }, recognition = recognition,
    ).also {
        it.keyActionListener = KeyActionListener { action, _ -> sent += action }
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(it)
    }

    private fun HandwritingKeyboard.mark(label: String): TextKeyView =
        (0 until childCount).map { getChildAt(it) }.filterIsInstance<ViewGroup>()
            .flatMap { row -> (0 until row.childCount).map { row.getChildAt(it) } }
            .filterIsInstance<TextKeyView>()
            .single { it.def is KeyDef.Appearance.Text && (it.def as KeyDef.Appearance.Text).displayText == label }

    private fun HandwritingKeyboard.pad() = (0 until childCount).map { getChildAt(it) }.filterIsInstance<HandwritingPadView>().single()

    private fun HandwritingPadView.stroke() {
        val now = SystemClock.uptimeMillis()
        for ((action, xy) in listOf(MotionEvent.ACTION_DOWN to 10f, MotionEvent.ACTION_MOVE to 20f, MotionEvent.ACTION_UP to 20f)) {
            MotionEvent.obtain(now, now, action, xy, xy, 0).also {
                onTouchEvent(it)
                it.recycle()
            }
        }
    }

    private fun runAll() {
        worker.run()
        main.run()
    }

    private fun reading(piece: Int) = recognition.read(InkPad.Read(listOf(floatArrayOf(0f, 0f, 1f, 1f)), piece, 1)) {}

    @Test
    fun aKeyPressedWhileAStrokeIsReadGoesInAfterItsReading() {
        val keyboard = keyboard()
        runAll()
        reading(Int.MAX_VALUE)
        keyboard.mark("。").performClick()
        assertEquals(emptyList<KeyAction>(), sent)
        runAll()
        assertEquals(listOf<KeyAction>(KeyAction.FcitxKeyAction(".")), sent)
    }

    @Test
    fun aKeyHeldBehindAReadIsDroppedOnceAnotherInputStarted() {
        val keyboard = keyboard()
        runAll()
        reading(Int.MAX_VALUE)
        keyboard.mark("。").performClick()
        // another field focused (KeyboardWindow.onStartInput): it was not typed for that one
        keyboard.restart()
        runAll()
        assertEquals(emptyList<KeyAction>(), sent)
    }

    @Test
    fun aBackspaceHeldBehindAReadIsDecidedInItsTurn() {
        val keyboard = keyboard()
        runAll()
        reading(Int.MAX_VALUE)
        keyboard.findViewById<ImageKeyView>(R.id.button_backspace).performClick()
        assertEquals(emptyList<KeyAction>(), sent)
        runAll()
        // the pad has no stroke then: the engine's
        assertEquals(listOf<KeyAction>(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))), sent)
    }

    @Test
    fun aBackspaceHeldBehindAKeyAndAStrokesReadIsTheEngines() {
        val keyboard = keyboard()
        runAll()
        val pad = keyboard.pad()
        // a stroke whose read is pending, then 。 and backspace held behind it
        pad.stroke()
        keyboard.mark("。").performClick()
        keyboard.findViewById<ImageKeyView>(R.id.button_backspace).performClick()
        assertEquals(emptyList<KeyAction>(), sent)
        runAll()
        // 。 committed the piece: the backspace deletes in the field, it takes no stroke of that piece back
        assertEquals(
            listOf(KeyAction.FcitxKeyAction("."), KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))),
            sent,
        )
        assertEquals(0, pad.pad.strokes.size)
    }

    @Test
    fun leavingTheLayoutWithStrokesOnThePadFinishesTheirPiece() {
        val keyboard = keyboard()
        val pad = keyboard.pad()
        pad.stroke()
        val piece = pad.pad.piece
        (keyboard.parent as ViewGroup).removeView(keyboard)
        runAll()
        assertEquals(listOf(piece), finished)
    }

    /** Only strokes ended are finished: one left mid-way, under the pen, is no piece written. */
    @Test
    fun leavingTheLayoutMidStrokeFinishesNothing() {
        val keyboard = keyboard()
        val pad = keyboard.pad()
        val now = SystemClock.uptimeMillis()
        for ((action, xy) in listOf(MotionEvent.ACTION_DOWN to 10f, MotionEvent.ACTION_MOVE to 20f)) {
            MotionEvent.obtain(now, now, action, xy, xy, 0).also {
                pad.onTouchEvent(it)
                it.recycle()
            }
        }
        (keyboard.parent as ViewGroup).removeView(keyboard)
        runAll()
        assertEquals(emptyList<Int>(), finished)
    }

    @Test
    fun theRecognizerIsLoadedAsTheKeyboardIsShown() {
        keyboard()
        runAll()
        assertEquals(1, reader.loads)
    }
}
