/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.editing.CommitCheck
import org.fcitx.fcitx5.android.input.preedit.CodeRowStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A field that takes every write and does nothing with it (a WebView that lost focus as the screen turned): the
 * code row says 「没写进去：…，点一下输入框」, a tap writes it, and no field that is only slow gets the line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class CommitKeptUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness!!

    @After
    fun close() {
        harness?.close()
    }

    private fun open(
        style: CodeRowStyle,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        fieldId: Int = 7,
        imeOptions: Int = 0,
        terminal: Boolean = false,
        prefill: (android.widget.EditText) -> Unit = {},
    ): KeyboardHarness {
        if (ThemeManager.prefs.codeRow.getValue() != style) prefs.set(ThemeManager.prefs.codeRow, style)
        harness?.close()
        return KeyboardHarness(codeRow = style).also {
            harness = it
            it.start(inputType = inputType, imeOptions = imeOptions, fieldId = fieldId, terminal = terminal, prefill = prefill)
        }
    }

    private fun typed(vararg codes: String) = codes.forEach { code ->
        val panel = HostEvent.InputPanel(FormattedText(arrayOf(code), intArrayOf(0), code.length), FormattedText.Empty, FormattedText.Empty, code = true)
        h.api.inputPanelCached = panel
        h.emit(panel)
    }

    /** Then the wait for the field's report runs out. */
    private fun chosen(text: String) {
        committed(text)
        h.advance(CommitCheck.WAIT_MS + 50)
    }

    private fun committed(text: String) {
        h.emit(HostEvent.Commit(text, -1))
        h.api.inputPanelCached = HostEvent.InputPanel.EMPTY
        h.emit(HostEvent.InputPanel.EMPTY)
    }

    private fun key(sym: Int) = h.emit(HostEvent.Key(sym, KeyStates.Virtual.states.toInt(), 0, false, 0))

    private val text get() = h.field.text.toString()

    private fun rowText(): TextView? =
        h.inputView.findViewById<HorizontalScrollView>(R.id.code_row_text)?.let { KeyboardHarness.all(it).filterIsInstance<TextView>().firstOrNull() }

    /** "" while it shows nothing, without the gap the row puts before its line. */
    private fun row(): String = rowText()?.takeIf { it.isShown }?.text?.toString().orEmpty().trim()

    private fun line(kept: String) = h.service.getString(R.string.commit_kept, kept)

    @Test
    @Uat("LC-24")
    fun theCodeInTheFieldWhenTheFieldDropsItsWritesAfterATurnIsKeptAndWrittenByATapOnItsLine() {
        open(CodeRowStyle.InField)
        typed("n", "ni", "ni h", "ni hao")
        h.turn()
        val turned = text
        h.writesDropped = true
        typed("h", "ha", "hao")
        chosen("好")
        val dropped = text to row()
        h.tap(rowText()!!)
        h.advance(CommitCheck.WAIT_MS + 50)
        val tappedTooSoon = text to row()
        h.writesDropped = false
        h.tap(rowText()!!)
        h.advance(CommitCheck.WAIT_MS + 50)
        assertRows(
            "the turn takes the code away, proven the keyboard's" to { assertEquals("", turned) },
            "nothing went in, and the row says so with the text" to { assertEquals("" to line("好"), dropped) },
            "a tap on the line while the field still drops: kept, said again" to { assertEquals("" to line("好"), tappedTooSoon) },
            "a tap once the field answers: written, once" to { assertEquals("好", text) },
            "the line gone" to { assertEquals("", row()) },
        )
    }

    @Test
    @Uat("LC-36")
    fun withTheCodeOnTheStripOrInTheBarWhatADeafFieldDroppedIsKeptTheSame() {
        val seen = listOf(CodeRowStyle.Floating, CodeRowStyle.Embedded).map { style ->
            open(style, prefill = { it.setText("AB"); it.setSelection(2) })
            h.writesDropped = true
            typed("n", "ni")
            chosen("你")
            typed("h", "hao")
            chosen("好")
            val kept = text to row()
            h.writesDropped = false
            h.tap(rowText()!!)
            h.advance(CommitCheck.WAIT_MS + 50)
            Triple(kept, text, row())
        }
        assertRows(
            "floating: both words kept, in order; a tap writes them" to { assertEquals(Triple("AB" to line("你好"), "AB你好", ""), seen[0]) },
            "in the bar: the same" to { assertEquals(Triple("AB" to line("你好"), "AB你好", ""), seen[1]) },
        )
    }

    @Test
    @Uat("LC-36")
    fun aFieldThatTakesTheTextIsNeverSaidToHaveDroppedItHoweverLateItReports() {
        open(CodeRowStyle.Floating)
        typed("n", "ni")
        chosen("你")
        val prompt = text to row()
        // its cursor reports held back past the wait (a page busy with its own script)
        h.selectionLags = true
        typed("h", "hao")
        chosen("好")
        val late = text to row()
        h.selectionLags = false
        h.idle()
        // read before open() below puts another harness in its place
        val afterReports = row()
        open(CodeRowStyle.InField)
        h.selectionLags = true
        typed("h", "ha", "hao")
        chosen("好")
        val lateInField = text to row()
        assertRows(
            "reported at once" to { assertEquals("你" to "", prompt) },
            "reported late: read back there when the wait is up, no line" to { assertEquals("你好" to "", late) },
            "the reports in at last: still none" to { assertEquals("", afterReports) },
            "the code in the field, its reports late: none either" to { assertEquals("", lateInField.second); assertEquals(true, lateInField.first.endsWith("好")) },
        )
    }

    @Test
    @Uat("LC-36")
    fun whatIsKeptGoesWithTheKeyboardAnotherFieldOrSomethingElseTypedAndStaysThroughATurnAndARestart() {
        fun kept(): KeyboardHarness = open(CodeRowStyle.Floating).also {
            it.writesDropped = true
            typed("h", "hao")
            chosen("好")
        }
        val turned = kept().let { it.turn(); row() }
        val restarted = kept().let { it.restart(); row() }
        val typedOn = kept().let {
            it.writesDropped = false
            typed("m", "ma")
            chosen("吗")
            text to row()
        }
        val hidden = kept().let { it.hide(); it.service.keptLine }
        val another = kept().let {
            it.writesDropped = false
            it.start(fieldId = 8)
            row()
        }
        val password = open(CodeRowStyle.Floating, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).let {
            it.writesDropped = true
            typed("a")
            chosen("a")
            row()
        }
        fun keptIn(text: String, opened: () -> KeyboardHarness): String? = opened().let {
            it.writesDropped = true
            typed("h")
            chosen(text)
            it.service.keptLine
        }
        val unlearned = keptIn("好") { open(CodeRowStyle.Floating, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) }
        // a field of no type: the harness's terminal tells TYPE_NULL with its EditText's options, as Termux does, so the
        // input is started again with none
        val terminal = keptIn("好") {
            open(CodeRowStyle.Floating, terminal = true).also { it.info.imeOptions = EditorInfo.IME_NULL; it.service.onStartInput(it.info, true) }
        }
        val long = keptIn("一二三四五六七八九十".repeat(3)) { open(CodeRowStyle.Floating) }
        // cut where the last 24 begin with the second half of 𠀀 (two chars): the whole character left out
        val split = keptIn("甲乙丙丁戊𠀀" + "己".repeat(23)) { open(CodeRowStyle.Floating) }
        assertRows(
            "the screen turned: the same field, still kept" to { assertEquals(line("好"), turned) },
            "the app starts input anew in the same field (the web view tapped): still kept" to { assertEquals(line("好"), restarted) },
            "something else typed, and it went in: let go" to { assertEquals("吗" to "", typedOn) },
            "the keyboard put away: let go" to { assertEquals(null, hidden) },
            "another field: not offered there" to { assertEquals("", another) },
            "a password is not read, nothing of it shown" to { assertEquals("", password) },
            "a field that asks not to be learned from (隐私): not read, nothing kept" to { assertEquals(null, unlearned) },
            "a field of no type (TYPE_NULL, IME_NULL): not read, nothing kept" to { assertEquals(null, terminal) },
            "a long one: its last 24 characters after 「…」" to { assertEquals(line("…" + "一二三四五六七八九十".repeat(3).takeLast(24)), long) },
            "cut in the middle of a character of two chars: from the next" to { assertEquals(line("…" + "己".repeat(23)), split) },
        )
    }

    @Test
    @Uat("LC-36")
    fun aCommitMadeJustBeforeTheWaitForAnEarlierOneIsUpWaitsItsOwnTime() {
        // the next commit made just before the timer set for the first runs: an editor whose reads lag a frame
        // (Compose's old text field) has not shown it yet
        open(CodeRowStyle.Floating)
        committed("你")
        h.advance(CommitCheck.WAIT_MS - 50)
        h.writesDropped = true
        committed("好")
        h.advance(100)
        val atTheFirstTimer = row()
        h.advance(CommitCheck.WAIT_MS)
        val atItsOwn = row()
        open(CodeRowStyle.Floating)
        h.writesDropped = true
        committed("你")
        h.advance(CommitCheck.WAIT_MS - 50)
        h.writesDropped = false
        h.start(fieldId = 8)
        h.writesDropped = true
        committed("好")
        h.advance(100)
        val anotherAtTheFirstTimer = row()
        h.advance(CommitCheck.WAIT_MS)
        assertRows(
            "not judged at the first one's time" to { assertEquals("", atTheFirstTimer) },
            "judged at its own" to { assertEquals(line("好"), atItsOwn) },
            "another field: not judged at the first field's time" to { assertEquals("", anotherAtTheFirstTimer) },
            "judged at its own, the first field's commit not offered" to { assertEquals(line("好"), row()) },
        )
    }

    @Test
    @Uat("LC-36")
    fun aCommitFollowedByADeletionASendOrInputStartedAnewIsNotSaidToBeLostInAFieldThatReportsLate() {
        // its reports held back past the wait; before it is up the text is gone again or the field is another, and a read
        // finds what was there before the commit
        val restarted = open(CodeRowStyle.Floating).let {
            it.selectionLags = true
            typed("h", "hao")
            committed("好")
            it.field.setText("")
            it.restart()
            it.advance(CommitCheck.WAIT_MS + 50)
            text to row()
        }
        val deleted = open(CodeRowStyle.Floating, prefill = { it.setText("AB"); it.setSelection(2) }).let {
            it.selectionLags = true
            typed("h", "hao")
            committed("好")
            key(FcitxKeyMapping.FcitxKey_BackSpace)
            it.advance(CommitCheck.WAIT_MS + 50)
            text to row()
        }
        val sent = open(CodeRowStyle.Floating, imeOptions = EditorInfo.IME_ACTION_SEND).let {
            it.field.setOnEditorActionListener { v, _, _ -> v.text = ""; true }
            it.selectionLags = true
            typed("h", "hao")
            committed("好")
            key(FcitxKeyMapping.FcitxKey_Return)
            it.advance(CommitCheck.WAIT_MS + 50)
            text to row()
        }
        assertRows(
            "set empty and restarted: no line" to { assertEquals("" to "", restarted) },
            "deleted at once: no line" to { assertEquals("AB" to "", deleted) },
            "sent, the field emptied: no line" to { assertEquals("" to "", sent) },
        )
    }

    @Test
    @Uat("LC-36")
    fun theKeptLineTappedWhileAnotherCommitWaitsWritesBothInTheOrderTyped() {
        open(CodeRowStyle.Floating)
        h.writesDropped = true
        typed("n", "ni")
        chosen("你")
        typed("h", "hao")
        committed("好")
        h.tap(rowText()!!)
        h.writesDropped = false
        h.advance(CommitCheck.WAIT_MS + 50)
        assertRows(
            "both written, in order" to { assertEquals("你好", text) },
            "the line gone" to { assertEquals("", row()) },
        )
    }

    @Test
    @Uat("LC-36")
    fun theKeptLineTappedWhileACommitWaitsThatThenReportsIsStillWritten() {
        open(CodeRowStyle.Floating)
        h.writesDropped = true
        typed("n", "ni")
        chosen("你")
        h.writesDropped = false
        h.selectionLags = true
        typed("h", "hao")
        committed("好")
        h.tap(rowText()!!)
        h.selectionLags = false
        h.idle()
        h.advance(CommitCheck.WAIT_MS + 50)
        assertRows(
            "好 reported in, then 你 written: not let go by 好 landing" to { assertEquals("好你", text) },
            "the line gone" to { assertEquals("", row()) },
        )
    }

    /** 你 kept in a field that dropped it; the field answers again, 吗 committed, its report held back, the line tapped. */
    private fun tappedWhileMaWaits() {
        open(CodeRowStyle.Floating)
        h.writesDropped = true
        typed("n", "ni")
        chosen("你")
        h.writesDropped = false
        h.selectionLags = true
        typed("m", "ma")
        committed("吗")
        h.tap(rowText()!!)
    }

    // told straight to the service, nothing posted run yet
    private fun reportedMa() = h.service.onUpdateSelection(0, 0, 1, 1, -1, -1)

    private fun settled() {
        h.selectionLags = false
        h.idle()
        h.advance(CommitCheck.WAIT_MS + 50)
    }

    @Test
    @Uat("LC-36")
    fun theKeptLineAskedForIsWrittenWhenTheWaitEndsHoweverItEnds() {
        val readBack = tappedWhileMaWaits().let {
            h.advance(CommitCheck.WAIT_MS + 50)
            val written = text
            settled()
            written to row()
        }
        // Backspace while 吗 waits: the wait ends unjudged, 吗 deleted, then 你 written
        val actedOn = tappedWhileMaWaits().let {
            key(FcitxKeyMapping.FcitxKey_BackSpace)
            settled()
            text to row()
        }
        // 好 committed before the posted write runs (a key queued ahead of it): 你 first
        val typedBeforeThePost = tappedWhileMaWaits().let {
            reportedMa()
            h.service.commitText("好")
            settled()
            text to row()
        }
        // input finished before the posted write runs: not written into the finished connection, nor ahead of a late
        // commit; written once input starts again in the same field
        val finished = tappedWhileMaWaits().let {
            reportedMa()
            h.service.onFinishInput()
            h.idle()
            h.service.commitText("好")
            h.idle()
            val meanwhile = text
            h.selectionLags = false
            h.restart()
            settled()
            Triple(meanwhile, text, row())
        }
        assertRows(
            "read back in: 你 written after it" to { assertEquals("吗你" to "", readBack) },
            "the field acted on: 你 written" to { assertEquals("你" to "", actedOn) },
            "a commit before the post: 你 written first, the order typed" to { assertEquals("吗你好" to "", typedBeforeThePost) },
            "input finished first: nothing of 你 written then, a late commit's alone; 你 once it starts again" to {
                assertEquals(Triple("吗好", "吗好你", ""), finished)
            },
        )
    }

    @Test
    @Uat("LC-36")
    fun theKeptLineGoesBeforeAuxBelowWhichKeepsItsFormatting() {
        open(CodeRowStyle.Floating)
        h.writesDropped = true
        chosen("好")
        val aux = FormattedText(arrayOf("a", "b"), intArrayOf(0, 8), -1)
        val drawn = h.service.forStrip(HostEvent.InputPanel(FormattedText.Empty, FormattedText.Empty, aux, code = true)).auxDown
        assertRows(
            "the line, then aux's own parts" to { assertEquals(listOf(line("好") + "  ", "a", "b"), drawn.strings.toList()) },
            "their formats as they were" to { assertEquals(listOf(0, 0, 8), drawn.flags.toList()) },
        )
    }
}
