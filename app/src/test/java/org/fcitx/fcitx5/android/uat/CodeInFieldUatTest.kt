/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.view.inputmethod.BaseInputConnection
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.CandidatesView
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.preedit.CodeRowStyle
import org.fcitx.fcitx5.android.input.preedit.FloatingCodeStrip
import org.fcitx.fcitx5.android.input.editing.CommitCheck
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 编码行 输入框里 (实验性), dev/design/061-code-in-field.md §9: the code in the field as its composing
 * text, the strip drawing none of it, and the fields and moments it floats instead. The rule over all
 * (§0): nothing the keyboard did not write is deleted. Run at SDK 29 too (testDebugUnitTestAt29).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class CodeInFieldUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness!!

    @After
    fun close() {
        harness?.close()
    }

    private fun open(
        style: CodeRowStyle = CodeRowStyle.InField,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        terminal: Boolean = false,
        prefill: (android.widget.EditText) -> Unit = {},
    ): KeyboardHarness {
        // the one before closed first: one left open keeps its service on the prefs' listeners, its keyboard made anew
        // at a later test's change and hearing 手写's switch there (StrokePageUatTest's 手写 row turned off under it)
        harness?.close()
        if (ThemeManager.prefs.codeRow.getValue() != style) prefs.set(ThemeManager.prefs.codeRow, style)
        return KeyboardHarness(codeRow = style).also {
            harness = it
            it.start(inputType = inputType, terminal = terminal, prefill = prefill)
        }
    }

    /** The engine's code [code] as it shows it, its cursor at the end. */
    private fun panel(code: String) =
        HostEvent.InputPanel(FormattedText(arrayOf(code), intArrayOf(0), code.length), FormattedText.Empty, FormattedText.Empty, code = true)

    private fun typing(code: String) {
        val panel = panel(code)
        h.api.inputPanelCached = panel
        h.emit(panel)
    }

    private fun typed(vararg codes: String) = codes.forEach(::typing)

    /** [text] chosen: the engine commits it, its code gone. */
    private fun chosen(text: String, cursor: Int = -1) {
        h.emit(HostEvent.Commit(text, cursor))
        h.api.inputPanelCached = HostEvent.InputPanel.EMPTY
        h.emit(HostEvent.InputPanel.EMPTY)
    }

    private val text get() = h.field.text.toString()
    private val composing get() = BaseInputConnection.getComposingSpanStart(h.field.text) to BaseInputConnection.getComposingSpanEnd(h.field.text)
    private val selection get() = h.field.selectionStart to h.field.selectionEnd

    /** What the floating strip shows of the code, "" when it is not shown. */
    private fun stripCode(): String {
        val strip = KeyboardHarness.find<FloatingCodeStrip>(h.inputView) ?: return ""
        if (strip.visibility != View.VISIBLE) return ""
        val row = h.inputView.findViewById<HorizontalScrollView>(R.id.code_row_text) ?: return ""
        return KeyboardHarness.all(row).filterIsInstance<TextView>().joinToString("") { it.text }
    }

    // the toolbar's 手写 with 编码行 输入框里: what is written is the session's code as typed code is (the host's panel
    // says so, HostCoreEnginesTest), so it is in the field underlined while written, and stays there as the input ends
    // (the host commits it then); no strip. Not a UAT case: 061-code-in-field.md §4.2, §7 item 7
    @Test
    fun handwritingsWrittenTextIsInTheFieldUnderlined() {
        val shared = HandwritingRecognition.shared
        val was = shared.reader
        shared.reader = object : HandwritingRecognition.Reader {
            override val loadable = true
            override fun load() = Unit
            override fun read(strokes: List<FloatArray>) = InkReading(listOf("人"), doubleArrayOf(-1.0))
        }
        try {
            prefs.set(AppPrefs.getInstance().internal.handwritingOpen, true)
            open(prefill = { it.setText("说："); it.setSelection(2) })
            val t = h.key("t")
            h.drag(t, h.box(t).exactCenterX(), h.box(t).bottom.toFloat())
            assertTrue("written on", KeyboardHarness.find<TextKeyboard>(h.keyboard)!!.keyInkForTest!!.writtenOn)
            typing("人")
            assertEquals("说：人", text)
            assertEquals(2 to 3, composing)
            assertEquals("", stripCode())
            chosen("人")
            assertEquals("说：人", text)
            assertEquals(-1 to -1, composing)
        } finally {
            shared.reader = was
        }
    }

    @Test
    @Uat("LC-21")
    fun theCodeIsTheFieldsComposingTextTillAWordIsChosenAndGoesAsTheKeyboardDoes() {
        open(prefill = { it.setText("说："); it.setSelection(2) })
        typed("n", "ni", "ni h", "ni hao")
        val whileTyping = Triple(text, composing, stripCode())
        chosen("你好")
        val afterChoice = text
        typed("n", "ni")
        h.hide()
        assertRows(
            "typed: in the field, underlined as composing text, after what was there" to {
                assertEquals("说：ni hao", whileTyping.first); assertEquals(2 to 8, whileTyping.second)
            },
            "the strip shows none of it" to { assertEquals("", whileTyping.third) },
            "a word chosen replaces it" to { assertEquals("说：你好", afterChoice) },
            "the keyboard put away: the code taken away, nothing left" to { assertEquals("说：你好", text); assertEquals(-1 to -1, composing) },
        )
    }

    @Test
    @Uat("LC-22")
    fun aSelectionAsTheCodeStartsKeepsTheCodeOnTheStripAndTheSelectionWhole() {
        open(prefill = { it.setText("一二三四五"); it.setSelection(1, 4) })
        typed("n", "ni")
        val typing = Triple(text, selection, stripCode())
        // backspace swiped up: the engine's code cleared
        h.api.inputPanelCached = HostEvent.InputPanel.EMPTY
        h.emit(HostEvent.InputPanel.EMPTY)
        val cleared = text to selection
        typed("n", "ni")
        // a tap elsewhere in the field
        h.field.setSelection(5)
        h.idle()
        val tapped = text
        h.hide()
        assertRows(
            "the selection as it was, nothing written over it" to { assertEquals("一二三四五", typing.first); assertEquals(1 to 4, typing.second) },
            "the code on the strip" to { assertEquals("ni", typing.third) },
            "the code cleared: the selection as it was" to { assertEquals("一二三四五" to (1 to 4), cleared) },
            "typed again, then a tap elsewhere: the field as it was" to { assertEquals("一二三四五", tapped) },
            "put away: the field as it was" to { assertEquals("一二三四五", text) },
        )
    }

    @Test
    @Uat("LC-24")
    fun theScreenTurnedTakesTheCodeAwayProvenOurs() {
        open(prefill = { it.setText("AB"); it.setSelection(2) })
        typed("n", "ni", "ni h")
        h.turn()
        assertEquals("AB", text)
        assertEquals(-1 to -1, composing)
    }

    @Test
    @Uat("LC-26")
    fun anAppsOwnRestartLeavesTheCodeAsTextAndFloatsItFromThen() {
        open(prefill = { it.setText("AB"); it.setSelection(2) })
        typed("n")
        // the app restarting input in the field (a setText echo, restartInput): another field maybe, never read
        h.restart()
        val left = text
        typed("ni")
        assertRows(
            "the letter left as text, nothing of the app's deleted" to { assertEquals("ABn", left) },
            "the next code on the strip, the field untouched" to { assertEquals("ABn", text); assertEquals("ni", stripCode()) },
        )
    }

    @Test
    @Uat("LC-27")
    fun anAppThatRewritesTheCodeHasItLeftAndFloatsTheRest() {
        open(prefill = { it.filters = arrayOf(InputFilter.AllCaps()) })
        typed("n")
        val first = text
        typed("ni", "ni h")
        assertRows(
            "its first letter as the app made it, let go of, not deleted" to { assertEquals("N", first) },
            "the rest on the strip, the field as the app left it" to { assertEquals("N", text); assertEquals("ni h", stripCode()) },
        )
    }

    @Test
    @Uat("LC-28")
    fun aFieldThatWillNotBeReadNeverHasTheCode() {
        open()
        h.nothingBeforeGiven = true
        typed("n", "ni")
        assertEquals("", text)
        assertEquals("ni", stripCode())
    }

    // the fields that say what they are keep the code on the strip (061-code-in-field.md §6.1; no UAT case)
    @Test
    fun aPasswordOrNumberFieldNeverHasTheCode() {
        val password = open(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD).let {
            typed("n", "ni")
            text to stripCode()
        }
        val number = open(inputType = InputType.TYPE_CLASS_NUMBER).let {
            typed("n")
            text to stripCode()
        }
        assertRows(
            "a password, shown or not" to { assertEquals("" to "ni", password) },
            "a number's" to { assertEquals("" to "n", number) },
        )
    }

    @Test
    @Uat("PY-123")
    fun aSelectionMadeInTheCodeLeavesItAsTextAndTheEngineReset() {
        open()
        typed("n", "ni", "ni h", "ni hao")
        h.api.calls.clear()
        h.field.setSelection(0, 6)
        h.idle()
        assertRows(
            "the code left as text, selected, no longer composing" to {
                assertEquals("ni hao", text); assertEquals(0 to 6, selection); assertEquals(-1 to -1, composing)
            },
            "the engine's code dropped" to { assertTrue(h.api.calls.toString(), "focusOutIn()" in h.api.calls) },
        )
    }

    @Test
    @Uat("PY-122")
    fun aTapInsideTheCodeMovesTheEnginesCursor() {
        open()
        typed("n", "ni", "ni h", "ni hao")
        h.api.calls.clear()
        h.field.setSelection(3)
        h.idle()
        val moved = h.api.calls.toList()
        val written = text
        // the engine's cached panel a key behind the one placed (the next key's not placed yet when the
        // move's write comes back to the main thread): what is written back is the placed one's
        h.api.inputPanelCached = panel("ni ha")
        h.field.setSelection(2)
        h.idle()
        assertRows(
            "the engine's cursor moved where the tap put it" to { assertTrue(moved.toString(), "moveCursor(3)" in moved) },
            "the code as it was" to { assertEquals("ni hao", written) },
            "a cached panel behind the placed one not written back" to { assertEquals("ni hao", text) },
        )
    }

    // 061-code-in-field-code-review.md item 10: a pair committed over the code in the field was not recorded
    @Test
    fun aPairChosenWithTheCodeInTheFieldIsSteppedOverAsOnTheStrip() {
        open(prefill = { it.setText("AB"); it.setSelection(2) })
        typed("k")
        // fcitx's punctuation gives a pair, the cursor between, in place of the code
        chosen("（）", cursor = 1)
        val paired = text to h.field.selectionStart
        h.emit(HostEvent.Commit("）", -1))
        assertRows(
            "the pair in place of the code, the cursor between" to { assertEquals("AB（）" to 3, paired) },
            "its closer typed: stepped over, not a second one" to { assertEquals("AB（）" to 4, text to h.field.selectionStart) },
        )
    }

    @Test
    @Uat("LC-23")
    fun aConnectionTheAppClosesFirstIsSentNothingAndTheNextFieldTakesItsOwnCode() {
        open(prefill = { it.setText("AB"); it.setSelection(2) })
        typedLetters("ni")
        val first = h.field
        // the app's view gone before the service is told: the input finished over a connection that drops all
        h.closeConnection()
        h.start(prefill = { it.setText("CD"); it.setSelection(2) })
        typedLetters("hao")
        chosen("好")
        assertRows(
            "the first: the code left as text, the app's whole, nothing sent it" to { assertEquals("ABni", first.text.toString()) },
            "the next: its own code, chosen" to { assertEquals("CD好", text) },
        )
    }

    @Test
    @Uat("LC-24")
    fun aScreenTheAppMakesAnewHasTheCodeLeftAsTextInTheNewField() {
        open(prefill = { it.setText("AB"); it.setSelection(2) })
        typedLetters("ni")
        h.recreate()
        val made = text to composing
        typedLetters("hao")
        chosen("好")
        assertRows(
            "the new field with the old one's text, the code in it as text: nothing deleted" to { assertEquals("ABni" to (-1 to -1), made) },
            "the next code goes in after it, chosen there" to { assertEquals("ABni好", text) },
        )
    }

    /** Each prefix of [letters] typed, as the engine shows them key by key. */
    private fun typedLetters(letters: String) = (1..letters.length).forEach { typing(letters.take(it)) }

    private fun watched(after: (android.widget.EditText) -> Unit): (android.widget.EditText) -> Unit = { field ->
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = after(field)
        })
    }

    @Test
    @Uat("LC-29")
    fun aFieldThatCutsTheCodeShortKeepsWhatItTookAndFloatsTheRest() {
        open(prefill = { it.filters = arrayOf(InputFilter.LengthFilter(10)) })
        typedLetters("nihaozhongguo")
        assertRows(
            "the first ten letters left as the field took them, not deleted" to { assertEquals("nihaozhong", text); assertEquals(-1 to -1, composing) },
            "the code on the strip, whole" to { assertEquals("nihaozhongguo", stripCode()) },
        )
    }

    /**
     * An app's search box ending the composing text as the field changes: at once, or [laterMs] later
     * while the keys come every [keyMs] (0: each key after the app's change is in). [unreported]: as a
     * device's EditText, each write reported as its batch ends, the app's end of the span after it
     * never told (the harness reports all it sees as the looper idles, a posted clear with it).
     */
    private fun clearsComposing(laterMs: Long, keyMs: Long = 0, unreported: Boolean = false): Pair<String, String> {
        val clears = watched { f -> if (laterMs < 0) f.clearComposingText() else f.postDelayed({ f.clearComposingText() }, laterMs) }
        open(prefill = if (unreported) ({}) else clears)
        h.spanAloneUnreported = unreported
        "nihao".indices.forEach {
            typing("nihao".take(it + 1))
            if (unreported) {
                h.field.clearComposingText()
                h.idle()
            }
            h.advance(keyMs)
        }
        h.advance(500)
        // the field's text and the strip's code as typed, "|" between
        val typing = text + "|" + stripCode()
        chosen("你好")
        return typing to text
    }

    @Test
    @Uat("LC-30")
    fun aFieldThatClearsTheComposingTextHasTheCodeTakenAwayAndFloated() {
        val now = clearsComposing(laterMs = -1)
        harness?.close()
        val posted = clearsComposing(laterMs = 0)
        harness?.close()
        val fast = clearsComposing(laterMs = 50, keyMs = 20)
        harness?.close()
        val device = clearsComposing(laterMs = 0, unreported = true)
        harness?.close()
        // cleared (posted) after the first letter, the editor's reports not in by the next key
        open(prefill = watched { f -> f.post { f.clearComposingText() } })
        h.selectionLags = true
        typed("n", "ni")
        h.selectionLags = false
        h.idle()
        typing("ni h")
        val lagging = text + "|" + stripCode()
        assertRows(
            "reports behind: Android 13 on, the editor's region asked, gone, the letter taken away; before it, the code as last written left" to {
                assertEquals(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "|ni h" else "nni|ni h", lagging)
            },
            "the clear reported, as it changes: no letter left, the code on the strip; chosen, the word alone" to { assertEquals("|nihao" to "你好", now) },
            "the clear reported, a moment later (posted): the same, no copy of a letter" to { assertEquals("|nihao" to "你好", posted) },
            "the clear reported, later than the next keys come: the same" to { assertEquals("|nihao" to "你好", fast) },
            "the clear never reported (a device's EditText): the first letter's copy left beside the code, which floats" to {
                assertEquals("nni|nihao" to "nni你好", device)
            },
        )
    }

    @Test
    @Uat("LC-31")
    fun aTerminalNeverHasTheCode() {
        open(terminal = true)
        typedLetters("nihao")
        assertEquals("" to "nihao", text to stripCode())
    }

    @Test
    @Uat("LC-32")
    fun anotherFieldTappedLeavesTheFirstWholeAndTheNextTakesItsOwnCode() {
        open(prefill = { it.setText("AB"); it.setSelection(2) })
        typedLetters("ni")
        val first = h.field
        // a field of the same app with no id of its own (NO_ID, as no_id_a and no_id_b)
        h.start(prefill = { it.setText("CD"); it.setSelection(2) })
        typedLetters("hao")
        val second = text to composing
        chosen("好")
        assertRows(
            "the first: what was there whole, the code taken away as input finished there (an app that ends it first leaves it, LC-23)" to {
                assertEquals("AB", first.text.toString())
            },
            "the next: its own code in it" to { assertEquals("CDhao" to (2 to 5), second) },
            "chosen there" to { assertEquals("CD好", text) },
        )
    }

    @Test
    @Uat("VO-53")
    fun voiceTypedOverTheCodeTakesItAwayAndTheCodeFloatsOn() {
        open(prefill = { it.setText("AB"); it.setSelection(2) })
        typed("n", "ni")
        h.service.voiceTalkBegan()
        h.service.commitVoice("今天")
        h.service.voiceTalkEnded()
        h.idle()
        assertRows(
            "the talk typed where the code was, the code not left" to { assertEquals("AB今天", text) },
            "the talk's text gathered for 语音先问再记, the code taken away not part of it" to {
                assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("voiceSaid(今天, ") })
            },
            "the engine's code on the strip" to { assertEquals("ni", stripCode()) },
        )
    }

    // 编码行's other choices leave the field as it was (061-code-in-field.md §4.1; no UAT case)
    @Test
    fun anotherCodeRowKeepsTheFieldFreeOfTheCode() {
        open(style = CodeRowStyle.Floating)
        typed("n", "ni")
        assertEquals("", text)
        assertEquals("ni", stripCode())
    }

    // what is read before the cursor leaves the code out (061-code-in-field.md §7 item 9; no UAT case)
    @Test
    fun theContextTheEngineIsToldLeavesTheCodeOut() {
        open(prefill = { it.setText("说：。"); it.setSelection(2) })
        typed("n", "ni")
        chosen("你")
        typed("h")
        h.api.calls.clear()
        // a commit, predicted, tells the engine nothing (its own text); the tap past 。 does: the code taken away,
        // the field read after it. Told at all first: none told would leave nothing to find the code in (review R26
        // F8156); then what is there, not the code typed since
        h.field.setSelection(5)
        h.idle()
        val told = h.api.calls.filter { it.startsWith("engineContext(") }
        assertTrue(h.api.calls.toString(), told.isNotEmpty())
        assertEquals(told.toString(), "说：你。", told.last().removePrefix("engineContext(").substringBefore(", "))
    }

    // the touch keyboard up: the floating window (the hardware keyboard's), with its 显示屏幕键盘 button, is
    // never shown, nor its touch window over the app (a chat's back button under it took no taps). It came
    // up at the screen's top left as the code moved into the field or out of it, or a commit was kept
    @Test
    @Uat("LC-37")
    fun theTouchKeyboardUpShowsNoFloatingWindowAsTheCodeMovesOrACommitIsKept() {
        fun floatingShown(): Boolean {
            val window = KeyboardHarness.find<CandidatesView>(h.service.window.window!!.decorView)!!
            return window.visibility == View.VISIBLE ||
                shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow?.isShowing == true
        }
        open(prefill = { it.setText("说："); it.setSelection(2) })
        typed("n", "ni")
        val typing = floatingShown()
        chosen("你")
        val afterChoice = floatingShown()
        // a field that took a commit and dropped it: the line 「没写进去」 on the keyboard, the code row floating
        h.close()
        harness = null
        open(style = CodeRowStyle.Floating)
        h.writesDropped = true
        typed("h", "hao")
        chosen("好")
        h.advance(CommitCheck.WAIT_MS + 50)
        val kept = floatingShown()
        assertRows(
            "the code in the field" to { assertFalse(typing) },
            "a word chosen, the code out of it" to { assertFalse(afterChoice) },
            "a commit kept" to { assertFalse(kept) },
        )
    }
}
