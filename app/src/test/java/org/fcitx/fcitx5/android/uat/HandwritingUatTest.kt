/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.pack.TestShelves
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.learn.control.WordKind
import org.fcitx.fcitx5.android.engine.reading.CandidateCard
import org.fcitx.fcitx5.android.engine.reading.CharCard
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.stroke.StrokeShape
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow
import org.fcitx.fcitx5.android.input.candidates.CharCardView
import org.fcitx.fcitx5.android.input.handwriting.AssetRecognizer
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPause
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.handwriting.LoadingReader
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.until as waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import splitties.dimensions.dp
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Handwriting over a fake engine; what the strokes are read as is the recognizer's tests', not asserted here. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class HandwritingUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var h = KeyboardHarness()

    // the app's recognizer is native, not on the JVM: swapped for one that loads and reads nothing
    private val reader = HandwritingRecognition.shared.reader.also {
        HandwritingRecognition.shared.reader = object : HandwritingRecognition.Reader {
            override val loadable = true
            override fun load() = Unit
            override fun read(strokes: List<FloatArray>): InkReading? = null
        }
    }

    @After
    fun close() {
        HandwritingRecognition.shared.reader = reader
        h.close()
        EngineState.candidateReadingState.value = ReadingStyle.OFF
    }

    private fun start(inputType: Int = InputType.TYPE_CLASS_TEXT) {
        prefs.set(AppPrefs.getInstance().internal.handwritingOpen, true)
        h.start(inputType)
    }

    private val pad get() = (KeyboardHarness.find<TextKeyboard>(h.keyboard) ?: error("no keyboard")).keyInkForTest?.pad ?: error("not written on")

    /** From ([x0], [y0]) to ([x1], [y1]), fractions of the letters' size (q's top left to m's row, p's right). */
    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, ms: Long = 200) {
        val q = h.box(h.key("q"))
        val area = Rect(q.left, q.top, h.box(h.key("p")).right, h.box(h.key("m")).bottom)
        val from = h.key("g")
        val at = h.box(from)
        val steps = 8
        val path = (0..steps).map { i ->
            val t = i / steps.toFloat()
            KeyboardHarness.Touch(
                area.left + area.width() * (x0 + (x1 - x0) * t) - at.left,
                area.top + area.height() * (y0 + (y1 - y0) * t) - at.top,
                if (i == 0) 0 else ms / steps,
            )
        }
        h.touch(from, *path.toTypedArray())
    }

    // the reading and finishing run on the recognizer's thread: waited for in real time
    private fun until(what: String, ms: Long = 5_000, test: () -> Boolean) =
        waitUntil(what, { h.api.calls }, ms, step = { shadowOf(Looper.getMainLooper()).idle() }, done = test)

    private fun ToolButton.checked() = createAccessibilityNodeInfo().isChecked

    @Test
    @Uat("HW-01")
    fun theKeyboardWithHandwritingOn() {
        start()
        val keys = h.texts()
        assertTrue(keys.toString(), "q" in keys && "m" in keys)
        h.describedKey("Backspace")
        assertTrue(keys.toString(), "123" in keys)
        assertTrue(h.toolbarHandwriting()!!.checked())
        assertTrue("letters faded", KeyboardHarness.find<KeyboardCanvasView>(h.keyboard)!!.faded != null)
        h.captureBoard("HandwritingUatTest/keyboard")
    }

    @Test
    @Uat("HW-41", "TB-55")
    fun theToolbarsButtonTurnsHandwritingOffAndOn() {
        start()
        val open = AppPrefs.getInstance().internal.handwritingOpen
        val canvas = KeyboardHarness.all(h.keyboard).filterIsInstance<KeyboardCanvasView>().first { it.keyAt("q") != null }
        val q = canvas.keyAt("q")!!
        h.toolbarHandwriting()!!.performClick()
        h.idle()
        assertFalse(open.getValue())
        assertFalse(h.toolbarHandwriting()!!.checked())
        assertFalse("letters as ever", canvas.faded?.invoke(q) == true)
        h.toolbarHandwriting()!!.performClick()
        h.idle()
        assertTrue(open.getValue())
        assertTrue(h.toolbarHandwriting()!!.checked())
        assertTrue("letters faded", canvas.faded?.invoke(q) == true)
    }

    @Test
    fun aLetterTypedAsHandwritingIsTurnedOffGoesInAfterTheReading() {
        val gate = CountDownLatch(1)
        HandwritingRecognition.shared.reader = object : HandwritingRecognition.Reader {
            override val loadable = true
            override fun load() = Unit
            override fun read(strokes: List<FloatArray>): InkReading {
                gate.await(5, TimeUnit.SECONDS)
                return InkReading(listOf("人"), doubleArrayOf(-1.0))
            }
        }
        start()
        stroke(0.5f, 0.2f, 0.3f, 0.8f)
        h.toolbarHandwriting()!!.performClick()
        h.idle()
        h.tap(h.key("n"))
        h.idle()
        gate.countDown()
        fun typed() = h.api.calls.indexOfFirst { it.startsWith("sendKey(key=n,") }
        until("typed") { typed() >= 0 }
        val read = h.api.calls.indexOfFirst { it.startsWith("offerInk") }
        assertTrue(h.api.calls.toString(), read in 0 until typed())
    }

    @Test
    fun aRecognizerGivenUpTurnsHandwritingOff() {
        HandwritingRecognition.shared.reader = object : HandwritingRecognition.Reader {
            override val loadable = false
            override fun load() = Unit
            override fun read(strokes: List<FloatArray>): InkReading? = null
        }
        start()
        assertFalse(AppPrefs.getInstance().internal.handwritingOpen.getValue())
        assertFalse(h.toolbarHandwriting()!!.checked())
        val canvas = KeyboardHarness.all(h.keyboard).filterIsInstance<KeyboardCanvasView>().first { it.keyAt("q") != null }
        assertFalse("letters as ever", canvas.faded?.invoke(canvas.keyAt("q")!!) == true)
        h.toolbarHandwriting()!!.performClick()
        h.idle()
        assertFalse(AppPrefs.getInstance().internal.handwritingOpen.getValue())
    }

    // turned on again, off again at once and told again (not a switch that silently does nothing), the recognizer not
    // tried again
    @Test
    fun aRecognizerThatCannotLoadTurnsHandwritingOffAndSaysSoEachTimeItIsTurnedOn() {
        val told = AtomicInteger()
        // failing once the keyboard is up: told on its notice, not as a toast
        val shown = CountDownLatch(1)
        HandwritingRecognition.shared.reader = LoadingReader({
            // bounded: a test failed before the count down leaves no recognizer thread blocked for the JVM's rest
            shown.await(5, TimeUnit.SECONDS)
            throw IOException("no model")
        }, {
            told.incrementAndGet()
            AssetRecognizer.told()
        })
        val open = AppPrefs.getInstance().internal.handwritingOpen
        start()
        h.idle()
        shown.countDown()
        until("given up") { !open.getValue() }
        assertEquals(1, told.get())
        val unloadable = h.service.getString(R.string.engine_unloadable)
        assertEquals(listOf(unloadable), h.noticeTexts())
        assertNull(ShadowToast.getTextOfLatestToast())
        assertFalse(h.toolbarHandwriting()!!.checked())
        h.notice()!!.dismiss()
        h.toolbarHandwriting()!!.performClick()
        h.idle()
        assertFalse(open.getValue())
        assertEquals(listOf(unloadable), h.noticeTexts())
        assertNull(ShadowToast.getTextOfLatestToast())
        h.tap(h.key("n"))
        until("typed") { h.api.calls.any { it.startsWith("sendKey(key=n,") } }
        assertEquals(1, told.get())
    }

    // a text pack imported while shown (the keyboard not made anew): what is written on worked out again
    @Test
    fun aPackImportedWhileShownIsLookedAtAgain() {
        var loadable = true
        HandwritingRecognition.shared.reader = object : HandwritingRecognition.Reader {
            override val loadable get() = loadable
            override fun load() = Unit
            override fun read(strokes: List<FloatArray>): InkReading? = null
        }
        val open = AppPrefs.getInstance().internal.handwritingOpen
        start()
        h.idle()
        assertTrue(open.getValue())
        val board = KeyboardHarness.find<TextKeyboard>(h.keyboard)!!
        loadable = false
        TestShelves.use()
        h.idle()
        assertFalse(open.getValue())
        assertSame(board, KeyboardHarness.find<TextKeyboard>(h.keyboard))
    }

    @Test
    fun turnedOnTheRecognizerIsLoadedBeforeTheFirstStroke() {
        val loads = AtomicInteger()
        HandwritingRecognition.shared.reader = object : HandwritingRecognition.Reader {
            override val loadable = true
            override fun load() {
                loads.incrementAndGet()
            }
            override fun read(strokes: List<FloatArray>): InkReading? = null
        }
        val open = AppPrefs.getInstance().internal.handwritingOpen
        prefs.set(open, false)
        h.start()
        h.advance(200)
        assertEquals(0, loads.get())
        h.toolbarHandwriting()!!.performClick()
        h.idle()
        until("loaded") { loads.get() == 1 }
        assertTrue(open.getValue())
    }

    // worked out as what it reads changes, not as each frame is drawn
    @Test
    fun nothingAsksTheRecognizerAsTheKeysAreDrawnInEnglish() {
        // counted on the recognizer's thread too (KeyInk.warmUp's read of loadable)
        val asked = AtomicInteger()
        HandwritingRecognition.shared.reader = object : HandwritingRecognition.Reader {
            override val loadable get() = true.also { asked.incrementAndGet() }
            override fun load() = Unit
            override fun read(strokes: List<FloatArray>): InkReading? = null
        }
        start()
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        h.idle()
        val canvas = KeyboardHarness.all(h.keyboard).filterIsInstance<KeyboardCanvasView>().first { it.keyAt("q") != null }
        assertFalse("English's letters typed", canvas.faded?.invoke(canvas.keyAt("q")!!) == true)
        asked.set(0)
        val keyboard = KeyboardHarness.find<TextKeyboard>(h.keyboard)!!
        val bitmap = Bitmap.createBitmap(keyboard.width, keyboard.height, Bitmap.Config.ARGB_8888)
        repeat(3) { keyboard.draw(Canvas(bitmap)) }
        assertEquals(0, asked.get())
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.PINYIN))
        h.idle()
        assertTrue("letters faded", canvas.faded?.invoke(canvas.keyAt("q")!!) == true)
    }

    @Test
    @Uat("HW-45", "AX-18")
    fun theCandidateBarsButtonIsLitWhileHandwritingIsOnAndTurnsItOff() {
        assertRows(
            "the usual keyboard" to { candidateBarsButton { prefs.setStyleDefault() } },
            "the one-handed keyboard" to { candidateBarsButton { prefs.setStyle(KeyboardStyle.ONE_HANDED, side = OneHanded.Right) } },
            "the floating keyboard" to {
                candidateBarsButton {
                    prefs.setStyle(KeyboardStyle.FLOATING)
                    AppPrefs.getInstance().internal.run {
                        prefs.set(floatingKeyboardX, floatingKeyboardX.defaultValue)
                        prefs.set(floatingKeyboardY, floatingKeyboardY.defaultValue)
                    }
                }
            },
        )
    }

    private fun candidateBarsButton(style: () -> Unit) {
        h.close()
        h = KeyboardHarness()
        style()
        start()
        h.api.candidates = arrayOf(CandidateWord("", "人", ""), CandidateWord("", "入", ""))
        h.emit(HostEvent.Candidates(2, h.api.candidates.toList()))
        h.idle()
        val button = h.inputView.findViewById<ToolButton>(R.id.candidate_handwriting_btn)
        assertTrue(h.shown(button))
        assertTrue(button.createAccessibilityNodeInfo().isCheckable)
        assertTrue(button.checked())
        button.performClick()
        h.idle()
        assertFalse(AppPrefs.getInstance().internal.handwritingOpen.getValue())
        assertFalse(h.shown(button))
        val other = KeyboardHarness.all(h.inputView).filterIsInstance<ToolButton>().first { it.id != R.id.candidate_handwriting_btn }
        assertFalse(other.createAccessibilityNodeInfo().isCheckable)
    }

    @Test
    @Uat("HW-06")
    fun backspaceTakesTheLastStrokeBack() {
        start()
        stroke(0.2f, 0.5f, 0.8f, 0.5f)
        assertEquals(1, pad.strokes.size)
        h.api.calls.clear()
        h.tap(h.describedKey("Backspace"))
        until("taken back") { pad.strokes.isEmpty() }
        assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") })
    }

    // the recognizer's model (native) swapped for a reader of fixed readings; the composing 人 and its candidates are
    // the engine's (HostCasesUatTest)
    @Test
    @Uat("HW-02")
    fun whileWritingTheReadingIsOfferedAndThePauseWipesThePad() {
        val shared = HandwritingRecognition.shared
        val was = shared.reader
        shared.reader = object : HandwritingRecognition.Reader {
            override val loadable = true
            override fun load() = Unit
            override fun read(strokes: List<FloatArray>) = InkReading(listOf("人", "入", "八"), doubleArrayOf(-1.0, -2.0, -3.0))
        }
        try {
            start()
            stroke(0.5f, 0.2f, 0.3f, 0.8f)
            until("read while writing") { h.api.calls.any { it.startsWith("offerInk") } }
            assertEquals("still writing", 1, pad.strokes.size)
            stroke(0.5f, 0.4f, 0.7f, 0.8f)
            h.advance(1500)
            until("wiped") { pad.strokes.isEmpty() }
        } finally {
            shared.reader = was
        }
    }

    @Test
    @Uat("HW-12", "ST-121")
    fun aShortAndALongPause() {
        val pause = AppPrefs.getInstance().keyboard.handwritingPause
        try {
            pause.setValue(HandwritingPause.Fastest)
            start()
            stroke(0.3f, 0.2f, 0.5f, 0.8f)
            stroke(0.5f, 0.4f, 0.7f, 0.8f)
            h.advance(500)
            until("finished") { pad.strokes.isEmpty() }
            pause.setValue(HandwritingPause.Slowest)
            stroke(0.2f, 0.5f, 0.8f, 0.5f)
            h.advance(1000)
            Thread.sleep(200)
            h.idle()
            assertEquals(1, pad.strokes.size)
            stroke(0.5f, 0.2f, 0.5f, 0.8f)
            h.advance(1600)
            until("finished again") { pad.strokes.isEmpty() }
        } finally {
            pause.setValue(pause.defaultValue)
        }
    }

    @Test
    @Uat("HW-16")
    fun aPasswordIsTypedOnAndHandwritingComesBackAfter() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        KeyboardHarness.find<NumberRow>(h.bar)!!.onCollapseListener!!.invoke()
        h.idle()
        h.assertHandwritingGreyed("in the password field")
        assertFalse(KeyboardHarness.find<TextKeyboard>(h.keyboard)!!.keyInkForTest?.writtenOn == true)
        // androidengine switches a password to English
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
        h.idle()
        h.assertHandwritingGreyed("in English")
        assertFalse(KeyboardHarness.find<TextKeyboard>(h.keyboard)!!.keyInkForTest?.writtenOn == true)
        start()
        h.emit(HostEvent.InputMethodChanged(KeyboardHarness.PINYIN))
        h.idle()
        assertTrue(h.toolbarHandwriting()!!.checked())
        stroke(0.2f, 0.5f, 0.8f, 0.5f)
        assertEquals(1, pad.strokes.size)
    }

    @Test
    @Uat("HW-19")
    fun handwritingsMenuHasNoPinAndItsNoticeIsOverTheCandidates() {
        var undone = false
        h.api.learned = { op ->
            when ((op as? LearningOp.OnPage)?.op ?: op) {
                is LearningOp.Menu -> LearningResult.Menu("入", WordKind.DICTIONARY, listOf(MenuItem.DELETE, MenuItem.BLOCK, MenuItem.WHY), null, BlockScope.EVERYWHERE)
                is LearningOp.Undo -> LearningResult.Done().also { undone = true }
                else -> LearningResult.Done(5L)
            }
        }
        start()
        h.api.candidates = arrayOf(CandidateWord("", "人", ""), CandidateWord("", "入", ""), CandidateWord("", "八", ""))
        h.emit(HostEvent.Candidates(3, h.api.candidates.toList()))
        val word = KeyboardHarness.all(h.inputView).filterIsInstance<TextView>().first { h.shown(it) && it.text.toString() == "入" }
        generateSequence(word as View) { it.parent as? View }.first { it.isLongClickable }.performLongClick()
        h.idle()
        val menu = h.inputView.candidateActionMenu!!
        fun lines() = (0 until menu.listView!!.adapter.count).map { menu.listView!!.adapter.getItem(it).toString() }
        val s = h.service
        assertTrue(lines().toString(), s.getString(R.string.learning_forget) in lines() && s.getString(R.string.learning_block) in lines())
        assertFalse(lines().toString(), s.getString(R.string.learning_pin) in lines())
        menu.performItemClick(lines().indexOf(s.getString(R.string.learning_block)))
        h.idle()
        menu.performItemClick(lines().indexOf(s.getString(R.string.learning_block_confirm)))
        h.idle()
        val notice = h.noticeBox()
        assertTrue("$notice over ${h.box(h.bar)}", notice.bottom <= h.box(h.bar).top + 2)
        val undo = KeyboardHarness.all(h.notice()!!.contentView).filterIsInstance<TextView>()
            .first { it.text.toString() == s.getString(R.string.learning_undo) && it.alpha > 0.5f }
        assertTrue(undo.performClick())
        h.idle()
        assertTrue(h.api.calls.toString(), undone)
    }

    private fun written(text: String) {
        h.emit(HostEvent.ClientPreedit(FormattedText(arrayOf(text), intArrayOf(0), text.length)))
        h.api.candidates = arrayOf(CandidateWord("", "行", "", readings = listOf("háng/xíng…")), CandidateWord("", "衍", "", readings = listOf("yǎn")))
        h.emit(HostEvent.Candidates(2, h.api.candidates.toList()))
    }

    private fun composing() = h.field.text.toString().let { t ->
        val start = BaseInputConnection.getComposingSpanStart(h.field.text)
        if (start < 0) "" else t.substring(start, BaseInputConnection.getComposingSpanEnd(h.field.text))
    }

    private val hang = CharCard(
        "行",
        listOf(
            CharCard.Row("háng", "ㄏㄤˊ", listOf("银行", "行业")),
            CharCard.Row("xíng", "ㄒㄧㄥˊ", listOf("进行", "行为")),
            CharCard.Row("héng", "ㄏㄥˊ", listOf("道行")),
            CharCard.Row("hàng", "ㄏㄤˋ", emptyList()),
        ),
        "行", 6, listOf(CharCard.Part("彳", "chì"), CharCard.Part("亍", "chù")), StrokeShape.parse("P P S H H SG"),
    )

    private fun held(text: String): ListPopupWindow {
        h.api.learned = { LearningResult.Menu(text, WordKind.DICTIONARY, listOf(MenuItem.DELETE, MenuItem.BLOCK), null, BlockScope.EVERYWHERE) }
        val word = KeyboardHarness.all(h.inputView).filterIsInstance<TextView>().first { h.shown(it) && it.text.toString() == text }
        generateSequence(word as View) { it.parent as? View }.first { it.isLongClickable }.performLongClick()
        h.idle()
        return h.inputView.candidateActionMenu!!
    }

    // the head's card, not the box it is shown in
    private fun ListPopupWindow.row(i: Int): View = listView!!.adapter.getView(i, null, listView!!).let { if (it is FrameLayout) it.getChildAt(0) else it }

    @Config(qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
    @Test
    @Uat("HW-29", "HW-40")
    fun aHeldCharactersCardThenItsButtonsAndTheMenu() {
        h.api.candidateCards["行"] = CandidateCard.Looked(hang)
        start()
        written("银行")
        val menu = held("行")
        val card = menu.row(0) as CharCardView
        assertEquals(hang, card.card)
        val texts = KeyboardHarness.all(card).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        val s = h.service
        val radical = s.getString(R.string.char_card_radical)
        val parts = s.getString(R.string.char_card_parts)
        val lines = listOf("$radical 行　" + s.getString(R.string.char_card_strokes, 6), "$parts 彳 亍（chì chù）", s.getString(R.string.char_card_order) + " 丿丿丨一一亅")
        assertTrue(texts.toString(), texts.containsAll(listOf("行", "háng", "ㄏㄤˊ", "银行 行业") + lines))
        assertEquals(
            "行，${s.getString(R.string.char_card_readings)} háng、xíng、héng、hàng，$radical 行，${s.getString(R.string.char_card_strokes, 6)}，$parts 彳 亍，" +
                "${s.getString(R.string.char_card_order)} 撇、撇、竖、横、横、竖钩",
            card.contentDescription,
        )
        assertEquals(listOf(s.getString(R.string.quick_phrase_commit), s.getString(R.string.quick_phrase_copy)), texts.takeLast(2))
        val menuLines = (1 until menu.listView!!.adapter.count).map { menu.listView!!.adapter.getItem(it).toString() }
        assertTrue(menuLines.toString(), h.service.getString(R.string.learning_block) in menuLines)
        assertFalse(menu.listView!!.adapter.isEnabled(0))
        h.relayout()
        h.capture("HandwritingUatTest/char_card_port", menu.listView!!.rootView)
        h.api.candidateCards.clear()
        menu.dismiss()
        val plain = held("衍")
        assertEquals("衍", plain.listView!!.adapter.getItem(0).toString())
        assertFalse(plain.row(0) is CharCardView)
    }

    @Test
    @Uat("HW-30")
    fun copyPutsTheCharacterAloneOnTheClipboardAndCommitIsATap() {
        h.api.candidateCards["行"] = CandidateCard.Looked(hang)
        start()
        written("银行")
        var menu = held("行")
        h.api.calls.clear()
        val copy = KeyboardHarness.all(menu.row(0)).filterIsInstance<TextView>().single { it.text == h.service.getString(R.string.quick_phrase_copy) }
        assertTrue(copy.performClick())
        h.idle()
        val clip = h.service.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("行", clip.getItemAt(0).text.toString())
        assertEquals("银行", composing())
        assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("select") || it.startsWith("sendKey") })
        assertEquals(null, h.inputView.candidateActionMenu)
        menu = held("行")
        KeyboardHarness.all(menu.row(0)).filterIsInstance<TextView>().single { it.text == h.service.getString(R.string.quick_phrase_commit) }.performClick()
        h.idle()
        assertTrue(h.api.calls.toString(), "select(0, 行)" in h.api.calls)
        assertEquals(null, h.inputView.candidateActionMenu)
    }

    @Test
    @Uat("HW-29")
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-440dpi")
    fun theCardInLandscapeIsTwoColumns() {
        h.api.candidateCards["行"] = CandidateCard.Looked(hang)
        start()
        written("银行")
        val card = held("行").row(0) as CharCardView
        card.measure(View.MeasureSpec.makeMeasureSpec(h.service.dp(567), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(LinearLayout.HORIZONTAL, card.orientation)
        assertTrue("${card.readingsScroll.measuredHeight}", card.readingsScroll.measuredHeight < card.readingsScroll.getChildAt(0).measuredHeight)
        h.relayout()
        val copy = KeyboardHarness.all(card).filterIsInstance<TextView>().single { it.text == h.service.getString(R.string.quick_phrase_copy) }
        val at = Rect().also(copy::getDrawingRect).also { card.offsetDescendantRectToMyCoords(copy, it) }
        val readings = Rect().also(card.readingsScroll::getDrawingRect).also { card.offsetDescendantRectToMyCoords(card.readingsScroll, it) }
        assertTrue("$at right of $readings", at.left >= readings.right)
        h.capture("HandwritingUatTest/char_card_land", h.inputView.candidateActionMenu!!.listView!!.rootView)
    }

    @Test
    @Uat("HW-29")
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-440dpi")
    fun theCardOnTheFloatingKeyboardIsOneColumn() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        // at its default place whatever an earlier test left: higher up, the card has more room
        AppPrefs.getInstance().internal.run {
            prefs.set(floatingKeyboardX, floatingKeyboardX.defaultValue)
            prefs.set(floatingKeyboardY, floatingKeyboardY.defaultValue)
        }
        h.api.candidateCards["行"] = CandidateCard.Looked(hang)
        start()
        written("银行")
        val menu = held("行")
        assertEquals(LinearLayout.VERTICAL, (menu.row(0) as CharCardView).orientation)
        h.relayout()
        h.capture("HandwritingUatTest/char_card_floating", menu.listView!!.rootView)
    }

    @Test
    @Uat("HW-29")
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
    fun aCardLeavesOutWhatIsNotKnownAndScrollsItsReadings() {
        h.api.candidateCards["乾"] = CandidateCard.Looked(CharCard("乾", listOf(CharCard.Row("gān", "ㄍㄢ", listOf("乾坤")), CharCard.Row("qián", "ㄑㄧㄢˊ", listOf("乾隆"))), "乙", 11, null, null))
        h.api.candidateCards["和"] = CandidateCard.Looked(CharCard(
            "和",
            listOf(
                CharCard.Row("hé", "ㄏㄜˊ", listOf("和平", "和谐")), CharCard.Row("hè", "ㄏㄜˋ", listOf("附和")),
                CharCard.Row("huó", "ㄏㄨㄛˊ", listOf("和面")), CharCard.Row("huò", "ㄏㄨㄛˋ", listOf("和稀泥")), CharCard.Row("hú", "ㄏㄨˊ", emptyList()),
                CharCard.Row("huo", "˙ㄏㄨㄛ", emptyList()),
            ),
            "口", 8, listOf(CharCard.Part("禾", "hé"), CharCard.Part("口", "kǒu")), StrokeShape.parse("P H S P D S HZ H"),
        ))
        start()
        h.emit(HostEvent.ClientPreedit(FormattedText(arrayOf("乾"), intArrayOf(0), 1)))
        h.api.candidates = arrayOf(CandidateWord("", "乾", ""), CandidateWord("", "和", ""))
        h.emit(HostEvent.Candidates(2, h.api.candidates.toList()))
        var menu = held("乾")
        val qian = menu.row(0) as CharCardView
        val texts = KeyboardHarness.all(qian).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertFalse(texts.toString(), texts.any { it.startsWith(h.service.getString(R.string.char_card_parts)) || it.startsWith(h.service.getString(R.string.char_card_order)) })
        h.relayout()
        h.capture("HandwritingUatTest/char_card_qian", menu.listView!!.rootView)
        menu.dismiss()
        menu = held("和")
        val he = menu.row(0) as CharCardView
        he.measure(View.MeasureSpec.makeMeasureSpec(h.service.dp(393), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("${he.readingsScroll.measuredHeight}", he.readingsScroll.measuredHeight < he.readingsScroll.getChildAt(0).measuredHeight)
        h.relayout()
        h.capture("HandwritingUatTest/char_card_he", menu.listView!!.rootView)
        // dragged up in the menu's list: the readings scroll, not the list
        val list = menu.listView!!
        val box = Rect().also(he.readingsScroll::getDrawingRect).also { list.offsetDescendantRectToMyCoords(he.readingsScroll, it) }
        val x = box.exactCenterX()
        val y = box.exactCenterY()
        val t = SystemClock.uptimeMillis()
        fun at(action: Int, dy: Float, ms: Long) = MotionEvent.obtain(t, t + ms, action, x, y + dy, 0).also { list.dispatchTouchEvent(it); it.recycle() }
        at(MotionEvent.ACTION_DOWN, 0f, 0)
        for (i in 1..10) at(MotionEvent.ACTION_MOVE, -i * box.height() / 10f, i * 16L)
        at(MotionEvent.ACTION_UP, -box.height().toFloat(), 200)
        h.idle()
        assertTrue("scrolled ${he.readingsScroll.scrollY}", he.readingsScroll.scrollY > 0)
    }
}
