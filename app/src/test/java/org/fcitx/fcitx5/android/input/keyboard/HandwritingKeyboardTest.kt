/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.pack.AppData
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.pack.TestShelves
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.handwriting.InkNet
import org.fcitx.fcitx5.android.engine.handwriting.InkRecognizer
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPadView
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognitionTest.Queue
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognitionTest.Reader
import org.fcitx.fcitx5.android.input.handwriting.InkPad
import org.fcitx.fcitx5.android.input.handwriting.LoadingReader
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
    private val readings = ArrayList<ReadingStyle>()
    private val delivered = ArrayList<String>()

    // in a window, as on a phone: a held key is dropped once the keyboard is not
    private fun keyboard(recognition: HandwritingRecognition = this.recognition) = HandwritingKeyboard(
        PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark,
        deliver = { reading, _ -> delivered += reading.texts }, finished = { finished += it }, recognition = recognition, setReading = { readings += it },
    ).also {
        it.keyActionListener = KeyActionListener { action, _ -> sent += action }
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(it)
        it.laidOut()
    }

    // the key whose view had [id], as the scene draws it now
    private fun HandwritingKeyboard.key(id: Int): ShownKey = checkNotNull(shownKeyWithId(id))

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
        keyboard.shownKeys().single { it.id == "period" }.click()
        assertEquals(emptyList<KeyAction>(), sent)
        runAll()
        assertEquals(listOf<KeyAction>(KeyAction.HostKeyAction(".")), sent)
    }

    @Test
    fun aKeyHeldBehindAReadIsDroppedOnceAnotherInputStarted() {
        val keyboard = keyboard()
        runAll()
        reading(Int.MAX_VALUE)
        keyboard.shownKeys().single { it.id == "period" }.click()
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
        keyboard.key(R.id.button_backspace).click()
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
        keyboard.shownKeys().single { it.id == "period" }.click()
        keyboard.key(R.id.button_backspace).click()
        assertEquals(emptyList<KeyAction>(), sent)
        runAll()
        // 。 committed the piece: the backspace deletes in the field, it takes no stroke of that piece back
        assertEquals(
            listOf(KeyAction.HostKeyAction("."), KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))),
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

    @After
    fun readingsOff() {
        EngineState.candidateReadingState.value = ReadingStyle.OFF
        AppPrefs.getInstance().internal.lastCandidateReading.setValue("")
    }

    private fun setting(style: ReadingStyle) {
        EngineState.candidateReadingState.value = style
        shadowOf(Looper.getMainLooper()).idle()
    }

    // 注 turns 候选词注音 on in the style last used (pinyin, none yet), off where it is on
    @Test
    @Uat("HW-28")
    fun theReadingKeyTurnsTheReadingsOnAsLastUsedAndOff() {
        val keyboard = keyboard()
        runAll()
        val key = { keyboard.key(R.id.button_reading) }
        val name = keyboard.context.getString(R.string.im_candidate_reading)
        assertEquals(keyboard.context.getString(R.string.hw_reading_key_off, name), key().description)
        key().click()
        runAll()
        assertEquals(listOf(ReadingStyle.PINYIN), readings)
        // zhuyin picked in the settings (and kept as last used, by the service: CandidateReadingsUatTest): on,
        // the tap turns it off, the next on in zhuyin
        setting(ReadingStyle.ZHUYIN)
        AppPrefs.getInstance().internal.lastCandidateReading.setValue(ReadingStyle.ZHUYIN.key)
        assertEquals(keyboard.context.getString(R.string.hw_reading_key_on, name), key().description)
        key().click()
        runAll()
        setting(ReadingStyle.OFF)
        key().click()
        runAll()
        assertEquals(listOf(ReadingStyle.PINYIN, ReadingStyle.OFF, ReadingStyle.ZHUYIN), readings)
        // nothing typed for it
        assertEquals(emptyList<KeyAction>(), sent)
    }

    @Test
    @Uat("HW-28")
    fun itsLongPressSetsEachStyle() {
        val keyboard = keyboard()
        runAll()
        val menu = HandwritingKeyboard.layout(keyboard.context).flatten().first { it.appearance.viewId == R.id.button_reading }.popup!!.single() as KeyDef.Popup.Menu
        val c = keyboard.context
        assertEquals(
            listOf(R.string.im_candidate_reading_off, R.string.im_candidate_reading_pinyin, R.string.im_candidate_reading_zhuyin).map(c::getString),
            menu.items.map { it.label },
        )
        menu.items.forEach { keyboard.onAction(it.action, KeyActionListener.Source.Popup) }
        // each held behind the one before it, then behind its own read
        repeat(menu.items.size * 2) { runAll() }
        assertEquals(listOf(ReadingStyle.OFF, ReadingStyle.PINYIN, ReadingStyle.ZHUYIN), readings)
    }

    // the character on the pad finished as a pause would (in the sentence, not committed), its strokes wiped, then the setting
    @Test
    @Uat("HW-28")
    fun aTapMidCharacterFinishesItFirstAndCommitsNothing() {
        val keyboard = keyboard()
        runAll()
        val pad = keyboard.pad()
        pad.stroke()
        val piece = pad.pad.piece
        keyboard.key(R.id.button_reading).click()
        assertEquals(emptyList<ReadingStyle>(), readings)
        runAll()
        runAll()
        assertEquals(listOf(piece), finished)
        assertEquals(listOf(ReadingStyle.PINYIN), readings)
        assertEquals(0, pad.pad.strokes.size)
        assertEquals(emptyList<KeyAction>(), sent)
    }

    // the pad as high as the four keys on its right
    @Test
    fun thePadSpansTheFourKeysOnItsRight() {
        val keyboard = keyboard()
        val pad = IntArray(2).also(keyboard.pad()::getLocationInWindow)
        val backspace = keyboard.key(R.id.button_backspace)
        val reading = keyboard.key(R.id.button_reading)
        // a key's bounds are its canvas's
        val canvas = IntArray(2).also((backspace as SceneShownKey).canvas::getLocationInWindow)[1]
        assertEquals((canvas + backspace.bounds.top).toFloat(), pad[1].toFloat(), 1f)
        assertEquals((canvas + reading.bounds.bottom).toFloat(), (pad[1] + keyboard.pad().height).toFloat(), 1f)
    }

    /**
     * Shown under the gate, before the text pack is there: its warm-up and strokes find no pack. That
     * gave the recognizer up for the process (handwriting dead after the import) and told the user
     * it could not load; now neither: once the pack is imported a stroke is read.
     */
    @Test
    fun aRecognizerShownBeforeTheTextPackReadsOnceItIsImported() {
        var told = 0
        var opens = 0
        val net = InkNet { _, _ -> floatArrayOf(0f) }
        val loading = LoadingReader({ _ ->
            // the recognizer's files, as AssetRecognizer reads them: the text pack's
            AppData.open("handwriting/test.txt").close()
            opens++
            InkRecognizer(net, net, "一", floatArrayOf(0f))
        }, { told++ }, HandwritingRecognition::textPack)
        val recognition = HandwritingRecognition(worker, main, { -1 }, loading)
        TestShelves.use(text = false, voice = true)
        try {
            val keyboard = keyboard(recognition)
            runAll()
            keyboard.pad().stroke()
            runAll()
            assertEquals(emptyList<String>(), delivered)
            assertEquals(0, opens)
            TestShelves.use()
            keyboard.pad().stroke()
            runAll()
            assertEquals(listOf("一"), delivered)
            assertEquals(1, opens)
            assertEquals(0, told)
        } finally {
            TestShelves.use()
        }
    }

    /**
     * Given up on the text pack's files (they cannot load): an import of the voice pack leaves it
     * given up, not loaded again and told again (it followed PackStore's generation, which the
     * voice pack counts up too: review L-4); one of the text pack loads it anew.
     */
    @Test
    fun aRecognizerGivenUpIsNotTriedAgainForTheVoicePack() {
        var told = 0
        var opens = 0
        val net = InkNet { _, _ -> floatArrayOf(0f) }
        var loads = false
        val loading = LoadingReader({ _ ->
            opens++
            if (!loads) throw java.io.IOException("chars.txt missing")
            InkRecognizer(net, net, "一", floatArrayOf(0f))
        }, { told++ }, HandwritingRecognition::textPack)
        val recognition = HandwritingRecognition(worker, main, { -1 }, loading)
        val restart = PackStore.restartHost
        PackStore.restartHost = { true }
        TestShelves.use(text = true, voice = false)
        try {
            val keyboard = keyboard(recognition)
            runAll()
            keyboard.pad().stroke()
            runAll()
            assertEquals(1, opens)
            assertEquals(1, told)
            val generation = PackStore.state.value.generation
            kotlinx.coroutines.runBlocking { PackStore.import(TestShelves.pack(PackKind.VOICE).inputStream()) }
            assertEquals(generation + 1, PackStore.state.value.generation)
            keyboard.pad().stroke()
            runAll()
            assertEquals(1, opens)
            assertEquals(1, told)
            // the text pack imported: loaded anew from it
            loads = true
            kotlinx.coroutines.runBlocking { PackStore.import(TestShelves.pack(PackKind.TEXT, version = 2).inputStream()) }
            keyboard.pad().stroke()
            runAll()
            assertEquals(2, opens)
            assertEquals(listOf("一"), delivered)
            assertEquals(1, told)
        } finally {
            PackStore.restartHost = restart
            TestShelves.use()
        }
    }

    @Test
    fun theRecognizerIsLoadedAsTheKeyboardIsShown() {
        keyboard()
        runAll()
        assertEquals(1, reader.loads)
    }
}
