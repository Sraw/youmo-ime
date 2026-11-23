/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HostCore
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutRunner
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The swipe shortcuts (清除, 恢复, 粘贴) through a real [KotlinHost] on a scripted engine: main and host threads in device order. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class KeyShortcutsHostTest {

    @get:Rule
    val prefs = PrefsRule()

    @get:Rule(order = 1)
    val host = HostKeyboardRule("shortcuts-host")
    private val h get() = host.h
    private val fcitx get() = host.fcitx

    private fun start(prefill: String = "") {
        host.up()
        h.start(prefill = { it.setText(prefill); it.setSelection(prefill.length) })
    }

    @After
    fun tearDown() {
        AppPrefs.getInstance().keyboard.pinyinKeyboard.let { it.setValue(it.defaultValue) }
    }

    private val backspace: ShownKey get() = h.keyNamed("backspace")

    private fun swipe(v: ShownKey, up: Boolean) {
        val finger = h.finger(v, h.at(v))
        val way = if (up) -1f else 1f
        for (i in 1..6) finger.move(h.at(v, dy = way * 10f * i, ms = 16))
        finger.up(h.at(v, dy = way * 60f))
    }

    private fun type(keys: String) {
        val was = host.preedit
        keys.forEach { h.tap(h.key(it.toString())) }
        host.until("$keys typed") { host.preedit == was + keys }
    }

    @Test
    fun backspaceSwipedUpClearsWhatIsBeforeTheCursorAndDownPutsItBack() {
        start("你好世界")
        swipe(backspace, up = true)
        host.until("the field cleared") { h.field.text.isEmpty() }
        swipe(backspace, up = false)
        host.until("put back") { h.field.text.toString() == "你好世界" }
        assertEquals(4, h.field.selectionStart)
    }

    // the cursor moved to the start and 清除 swiped before the editor reports it: the undo leaves the cursor where the
    // editor then reports it, not where the stale report had it
    @Test
    @Uat("KB-62")
    fun aClearMadeBeforeTheCursorsReportIsInIsPutBackWithTheCursorWhereItIsReportedThen() {
        start("你好世界")
        host.synced()
        h.selectionLags = true
        h.field.setSelection(0)
        assertEquals("4..4", tracked())
        swipe(backspace, up = true)
        host.until("the field cleared") { h.field.text.isEmpty() }
        h.selectionLags = false
        host.synced()
        assertEquals("0..0", tracked())
        swipe(backspace, up = false)
        host.until("put back") { h.field.text.toString() == "你好世界" }
        host.synced()
        assertEquals(0, h.field.selectionStart)
        assertEquals(0, h.field.selectionEnd)
    }

    @Test
    fun pinyinClearedIsDroppedUncommittedAndTypedAgain() {
        start()
        type("nihao")
        swipe(backspace, up = true)
        host.until("nothing typed") { host.preedit.isEmpty() }
        assertEquals("nothing committed", "", h.field.text.toString())
        swipe(backspace, up = false)
        host.until("typed again") { host.preedit == "nihao" }
        assertEquals("", h.field.text.toString())
    }

    // pinyin typed since the clear is not dropped by putting the clear back
    @Test
    fun nothingIsPutBackOverWhatIsTypedSince() {
        start()
        type("ni")
        swipe(backspace, up = true)
        host.until("ni cleared") { host.preedit.isEmpty() }
        type("hao")
        swipe(backspace, up = false)
        host.synced()
        assertEquals("hao", host.preedit)
        // nor once hao is cleared in its turn: ni was typed over
        swipe(backspace, up = true)
        host.until("hao cleared") { host.preedit.isEmpty() }
        swipe(backspace, up = false)
        host.until("hao typed again") { host.preedit == "hao" }
        h.tap(h.key("a"))
        host.until("a typed") { host.preedit == "haoa" }
        swipe(backspace, up = false)
        host.synced()
        assertEquals("haoa", host.preedit)
    }

    // what another keyboard typed is no code of this one's and would reach the field as letters: that clear is dropped
    @Test
    fun pinyinClearedIsNotTypedAgainOnAnotherKeyboard() {
        start("你好")
        type("ni")
        swipe(backspace, up = true)
        host.until("ni cleared") { host.preedit.isEmpty() }
        swipe(backspace, up = true)
        host.until("text cleared") { h.field.text.isEmpty() }
        AppPrefs.getInstance().keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Keys14)
        h.idle()
        swipe(backspace, up = false)
        host.until("the text put back") { h.field.text.toString() == "你好" }
        swipe(backspace, up = false)
        host.synced()
        assertEquals("", host.preedit)
        assertEquals("你好", h.field.text.toString())
    }

    // a clear under way as another field starts does nothing there; the main thread's part is run by hand, so no timing is involved
    @Test
    fun aClearUnderWayIsNotMadeInAFieldStartedSince() {
        start("给 A 的话")
        val asked = h.service.startedField
        h.start(fieldId = 2, prefill = { it.setText("给 B 的话"); it.setSelection(it.text.length) })
        val runner = KeyShortcutRunner(h.service, InputWindowManager(), {}, {}, {}, {}, {}) { false }
        runner.clear(null, asked)
        runner.clear(HostCore.Retyping(InputMethodCatalog.PINYIN, emptyList()), asked)
        host.synced()
        assertEquals("给 B 的话", h.field.text.toString())
        runner.clear(null, h.service.startedField)
        host.synced()
        assertEquals("", h.field.text.toString())
    }

    // rotation restarts the same input, so its clear stays; finished and started again, it is gone
    @Test
    fun aClearOutlastsTheScreenTurningButNotTheInputFinishing() {
        start("你好")
        swipe(backspace, up = true)
        host.until("cleared") { h.field.text.isEmpty() }
        h.turn()
        swipe(backspace, up = false)
        host.until("put back") { h.field.text.toString() == "你好" }
        swipe(backspace, up = true)
        host.until("cleared again") { h.field.text.isEmpty() }
        h.service.onFinishInput()
        h.turn()
        swipe(backspace, up = false)
        host.synced()
        assertEquals("", h.field.text.toString())
    }

    // an app that gives nothing before its cursor: what is after goes and comes back, the cursor kept. The mirror too
    @Test
    @Uat("KB-88")
    fun whereOneSideOfTheCursorIsNotGivenTheOtherIsClearedAndPutBackAtTheCursor() {
        fun cleared(before: Boolean, left: String) = {
            host.down()
            start("abc def")
            h.field.setSelection(3)
            host.synced()
            if (before) h.nothingBeforeGiven = true else h.nothingAfterGiven = true
            swipe(backspace, up = true)
            host.until("the other side cleared") { h.field.text.toString() == left }
            if (before) assertTrue(said().toString(), appContext.getString(R.string.field_cleared) in said())
            swipe(backspace, up = false)
            host.until("put back") { h.field.text.toString() == "abc def" }
            assertEquals("3..3", selected())
        }
        assertRows(
            "nothing given before it: what is after it" to cleared(before = true, left = "abc"),
            "nothing given after it: what is before it" to cleared(before = false, left = " def"),
        )
    }

    // put back while nothing was typed since; not once a word was committed, which such a field reads the same after
    @Test
    @Uat("KB-88")
    fun whereNothingBeforeTheCursorIsGivenWhatIsTypedIsPutBackUnlessSomethingWasCommittedSince() {
        start()
        h.nothingBeforeGiven = true
        type("nihao")
        swipe(backspace, up = true)
        host.until("nothing typed") { host.preedit.isEmpty() }
        host.synced()
        swipe(backspace, up = false)
        host.until("typed again") { host.preedit == "nihao" }
        assertEquals("", h.field.text.toString())

        swipe(backspace, up = true)
        host.until("nothing typed") { host.preedit.isEmpty() }
        host.synced()
        h.service.commitOverEngine("啊")
        host.until("committed") { h.field.text.toString() == "啊" }
        swipe(backspace, up = false)
        host.synced()
        assertEquals("", host.preedit)
        assertEquals("啊", h.field.text.toString())
        assertTrue(said().toString(), appContext.getString(R.string.nothing_to_restore) in said())
    }

    // nothing on either side gives "" there, not null: the field is no empty one, so a copy takes it whole
    @Test
    @Uat("KB-88")
    fun whereNothingBeforeTheCursorIsGivenAFieldWithNothingAfterItIsNotTakenForAnEmptyOne() {
        prefs.setShortcuts(KeyShortcuts.Empty.with("full/c", KeyShortcut.Command(KeyCommand.Copy)))
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(null, "旧的"))
        start("abc")
        h.nothingBeforeGiven = true
        swipe(backspace, up = true)
        host.synced()
        assertEquals("abc", h.field.text.toString())
        swipe(h.key("c"), up = false)
        host.until("the field copied whole") { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "abc" }
        assertTrue(said().toString(), appContext.getString(R.string.nothing_to_copy) !in said())
    }

    private fun said(): List<String> = org.robolectric.Shadows.shadowOf(appContext as android.app.Application).latestPopupWindow
        ?.contentView?.let { v -> KeyboardHarness.all(v).filterIsInstance<android.widget.TextView>().map { it.text.toString() }.toList() }.orEmpty()

    // a no-learning field keeps nothing of a clear, as a password does
    @Test
    fun aFieldKeptFromLearningIsClearedWithNothingToPutBack() {
        host.up()
        h.start(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, prefill = { it.setText("秘密"); it.setSelection(2) })
        swipe(backspace, up = true)
        host.until("cleared") { h.field.text.isEmpty() }
        swipe(backspace, up = false)
        host.synced()
        assertEquals("", h.field.text.toString())
    }

    @Test
    fun aClearIsNeverPutBackInAnotherField() {
        start("给 A 的话")
        swipe(backspace, up = true)
        host.until("cleared") { h.field.text.isEmpty() }
        h.start(fieldId = 2)
        swipe(backspace, up = false)
        host.synced()
        assertEquals("", h.field.text.toString())
    }

    @Test
    fun vSwipedDownWhilePinyinIsTypedCommitsItThenPastes() {
        start()
        appContext.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, "X"))
        type("nihao")
        swipe(h.key("v"), up = false)
        host.until("committed, then pasted") { h.field.text.endsWith("X") && host.preedit.isEmpty() }
        assertEquals("你好X", h.field.text.toString())
    }

    // opens it as 符's long press and the bar's button do
    @Test
    fun aShortcutOfQuickInputOpensItAfterWhatIsTyped() {
        prefs.setShortcuts(KeyShortcuts.Empty.with("full/q", KeyShortcut.Command(KeyCommand.QuickInput)))
        start()
        type("nihao")
        swipe(h.key("q"), up = false)
        host.until("committed, then quick input open") { h.field.text.toString() == "你好" && fcitx.inputPanelCached.quickPhrase }
        assertEquals("", host.preedit)
    }

    // not left as the keyword's start, where the full keyboard's Shift would still be 分词
    @Test
    fun quickInputRunOpensItAfterWhatIsTyped() {
        start()
        type("nihao")
        KeyShortcutRunner(h.service, InputWindowManager(), {}, {}, {}, {}, {}) { true }.run(KeyShortcut.Command(KeyCommand.QuickInput))
        host.until("committed, then quick input open") { h.field.text.toString() == "你好" && fcitx.inputPanelCached.quickPhrase }
        assertEquals("", host.preedit)
    }

    // q down 选中至文首, w down 选中至文尾
    private fun selectToEdgesOnQW() = prefs.setShortcuts(
        KeyShortcuts.Empty.with("full/q", KeyShortcut.Command(KeyCommand.SelectToStart)).with("full/w", KeyShortcut.Command(KeyCommand.SelectToEnd)),
    )

    private fun selectTo(start: Boolean, then: String) {
        swipe(h.key(if (start) "q" else "w"), up = false)
        host.synced()
        host.until("$then selected", { "field [${h.field.text}] ${selected()}" }) { ordered() == then }
    }

    private fun ordered() = h.field.let { "${minOf(it.selectionStart, it.selectionEnd)}..${maxOf(it.selectionStart, it.selectionEnd)}" }

    @Test
    @Uat("KB-116")
    fun selectToStartAndToEndFromTheMiddleOfTheWholeField() {
        selectToEdgesOnQW()
        start()
        h.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, prefill = { it.setText("one\ntwo\nthree"); it.setSelection(5) })
        host.synced()
        selectTo(start = true, then = "0..5")
        h.field.setSelection(5)
        host.synced()
        selectTo(start = false, then = "5..13")
        assertEquals("nothing typed", "one\ntwo\nthree", h.field.text.toString())
    }

    // the end farther from the edge kept, whichever way the selection was made
    @Test
    @Uat("KB-116")
    fun selectToAnEdgeKeepsTheSelectionsFarEnd() {
        selectToEdgesOnQW()
        start()
        for ((a, b) in listOf(2 to 5, 5 to 2)) {
            h.start(prefill = { it.setText("hello world"); it.setSelection(a, b) })
            host.synced()
            selectTo(start = true, then = "0..5")
            h.field.setSelection(a, b)
            host.synced()
            selectTo(start = false, then = "2..11")
        }
    }

    @Test
    @Uat("KB-116")
    fun selectToAnEdgeCommitsWhatIsTypedFirst() {
        selectToEdgesOnQW()
        start("hi")
        type("nihao")
        selectTo(start = true, then = "0..4")
        assertEquals("hi你好", h.field.text.toString())
        assertEquals("", host.preedit)
    }

    @Test
    fun selectToAnEdgeAtItOrInAnEmptyFieldSelectsNothing() {
        selectToEdgesOnQW()
        start("hello")
        selectTo(start = false, then = "5..5")
        h.field.setSelection(0)
        host.synced()
        selectTo(start = true, then = "0..0")
        h.start()
        host.synced()
        selectTo(start = true, then = "0..0")
        selectTo(start = false, then = "0..0")
    }

    // past what a read of the field reaches (KeyShortcutRunner's 10 000); the EditText's own cap at 5000 taken off
    @Test
    fun selectToEndReachesALongFieldsVeryEnd() {
        selectToEdgesOnQW()
        start()
        val long = "字".repeat(30_000)
        h.start(prefill = { it.filters = arrayOf(); it.setText(long); it.setSelection(3) })
        host.synced()
        assertEquals(long, h.field.text.toString())
        selectTo(start = false, then = "3..${long.length}")
    }

    // a TextView here would take Shift+Home as its line's start (4..5): a sent key would show
    @Test
    @Uat("KB-116")
    fun aRawKeyFieldIsSentNothingAndToldSo() {
        selectToEdgesOnQW()
        start()
        h.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, terminal = true, prefill = { it.setText("one\ntwo\nthree"); it.setSelection(5) })
        host.synced()
        for (k in listOf("q", "w")) {
            swipe(h.key(k), up = false)
            host.until("told") { appContext.getString(R.string.cannot_select_here) in said() }
            host.synced()
            assertEquals(k, "5..5", ordered())
            assertEquals("one\ntwo\nthree", h.field.text.toString())
        }
    }

    // up past the threshold, then sideways to each of [dxs] (dp from the key's middle; 10 dp a character), lifted at the last
    private fun swipeSelect(vararg dxs: Float, on: ShownKey? = null, then: (Int) -> Unit = {}) {
        val k = on ?: h.key("q")
        val finger = swipeSelectHeld(k, *dxs, then = then)
        finger.up(h.at(k, dx = dxs.last(), dy = -60f))
    }

    private fun swipeSelectHeld(k: ShownKey, vararg dxs: Float, then: (Int) -> Unit = {}): KeyboardHarness.Finger {
        val finger = h.finger(k, h.at(k))
        for (i in 1..6) finger.move(h.at(k, dy = -10f * i, ms = 16))
        dxs.forEachIndexed { i, dx ->
            finger.move(h.at(k, dx = dx, dy = -60f, ms = 16))
            then(i)
        }
        return finger
    }

    private fun swipeSelectOnQ() =
        prefs.set(AppPrefs.getInstance().internal.keySwipes, SwipeSlots.Default.with("full/q", up = true, SwipeSlot.Command(KeyCommand.SwipeSelect)).serialize())

    private fun selected() = "${h.field.selectionStart}..${h.field.selectionEnd}"

    // left grows the selection a character a step, right shrinks it and past the cursor grows it the other way
    @Test
    @Uat("KB-69")
    fun swipeSelectSelectsFromTheCursorAsTheFingerMovesSideways() {
        swipeSelectOnQ()
        start()
        h.start(prefill = { it.setText("hello world"); it.setSelection(5) })
        host.synced()
        swipeSelect(-31f, 21f) { i ->
            if (i == 0) host.until("llo selected") { selected() == "2..5" }
        }
        host.until("past the cursor: \" w\" selected") { selected() == "5..7" }
        host.synced()
        assertEquals("nothing typed", "hello world", h.field.text.toString())
        assertEquals("lifted, it stays", "5..7", selected())
    }

    // the steps moved meanwhile are taken
    @Test
    @Uat("KB-69")
    fun swipeSelectCommitsWhatIsTypedFirst() {
        swipeSelectOnQ()
        start()
        type("nihao")
        swipeSelect(-21f)
        host.until("committed, then selected") { h.field.text.toString() == "你好" && selected() == "0..2" }
        assertEquals("", host.preedit)
    }

    // an emoji (a family, ZWJ) is one character
    @Test
    @Uat("KB-69")
    fun swipeSelectStepsOverAnEmojiWhole() {
        swipeSelectOnQ()
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        start("a$family")
        host.synced()
        swipeSelect(-11f)
        host.until("the family selected") { selected() == "1..${1 + family.length}" }
    }

    // begun before the editor reports the commit: the field is still read, so the emoji is stepped over whole
    @Test
    fun swipeSelectRightAfterPinyinStillStepsOverAnEmojiWhole() {
        swipeSelectOnQ()
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        start(family)
        host.synced()
        type("nihao")
        swipeSelect(-31f)
        host.until("你好 and the family selected") { h.field.text.toString() == "${family}你好" && selected() == "0..${family.length + 2}" }
    }

    // the touch taken by the system: the selection stays; the next touch is a touch of its own
    @Test
    fun swipeSelectCancelledKeepsTheSelectionAndTheNextTouchIsATap() {
        swipeSelectOnQ()
        start("hello")
        host.synced()
        val q = h.key("q")
        val finger = swipeSelectHeld(q, -11f, -21f)
        host.until("lo selected") { selected() == "3..5" }
        finger.send(MotionEvent.ACTION_CANCEL, h.at(q, dx = -21f, dy = -60f))
        host.synced()
        assertEquals("cancelled, it stays", "3..5", selected())
        type("q")
    }

    @Test
    fun swipeSelectsBubbleStaysAsTheFingerDriftsBack() {
        swipeSelectOnQ()
        start("hello")
        host.synced()
        val q = h.key("q")
        val finger = swipeSelectHeld(q, -11f)
        finger.move(h.at(q, dx = -11f, dy = 0f, ms = 16))
        val shown = h.popupChoices().map { it.text }.filter { it.isNotEmpty() }
        assertTrue("滑选 in $shown", "滑选" in shown)
        finger.up(h.at(q, dx = -11f))
        host.synced()
        assertEquals("no q typed", "", host.preedit)
        assertEquals("hello", h.field.text.toString())
    }

    private fun passwordSecret() {
        swipeSelectOnQ()
        start()
        h.start(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            prefill = { it.setText("secret"); it.setSelection(6) },
        )
        host.synced()
    }

    private fun tracked() = h.service.currentInputSelection.let { "${it.start}..${it.end}" }

    // a password's text is not read: units. A step past its end the editor ignores, and the selection follows the finger back
    @Test
    fun swipeSelectInAPasswordGoesByUnitsAndFollowsTheFingerBackFromItsEnd() {
        passwordSecret()
        swipeSelect(-21f)
        host.until("et selected") { selected() == "4..6" }
        host.synced()
        h.field.setSelection(6)
        host.synced()
        // right a step (ignored), another, back two, back two more: a step left of the end
        swipeSelect(0f, 11f, 21f, 9f, -11f) { if (it == 0) host.synced() }
        host.until("t selected") { selected() == "5..6" }
        host.synced()
        assertEquals("secret", h.field.text.toString())
        assertEquals("nothing predicted the editor did not take", "5..6", tracked())
    }

    // no selection left predicted that is not there, for the next 滑选 and Backspace to act on
    @Test
    fun swipeSelectLiftedPastAPasswordsEndLeavesNothingPredicted() {
        passwordSecret()
        swipeSelect(0f, 11f, 21f) { if (it == 0) host.synced() }
        host.synced()
        assertEquals("6..6", selected())
        assertEquals("the editor's, not the step it ignored", "6..6", tracked())
        swipeSelect(0f, -11f) { if (it == 0) host.synced() }
        host.until("t selected") { selected() == "5..6" }
        h.tap(backspace)
        host.until("the t deleted") { h.field.text.toString() == "secre" }
    }

    // where the field's end is not known (a password), the next goes on from the same anchor
    @Test
    fun swipeSelectAfterATouchEndingRightKeepsItsAnchor() {
        passwordSecret()
        h.field.setSelection(3)
        host.synced()
        swipeSelect(0f, 11f, 21f) { host.synced() }
        host.until("ret's re selected") { selected() == "3..5" }
        host.synced()
        swipeSelect(0f, -11f) { if (it == 0) host.synced() }
        host.until("back to r, the anchor kept") { selected() == "3..4" }
    }

    // the steps before it begins (the host still busy) taken together, past the end too
    @Test
    fun swipeSelectsStepsBeforeItBeginsAreTakenTogether() {
        passwordSecret()
        swipeSelect(11f, 21f, 9f, -11f)
        host.until("t selected") { selected() == "5..6" }
        host.synced()
        assertEquals("5..6", tracked())
    }

    // a theme's key of `shortcut swipe_select`: nothing, nothing committed
    @Test
    fun swipeSelectTappedOrRunElsewhereDoesNothing() {
        start("hello")
        type("nihao")
        val board = KeyboardHarness.all(h.keyboard).filterIsInstance<BaseKeyboard>().first { h.shown(it) }
        board.keyActionListener!!.onKeyAction(KeyAction.ShortcutAction(KeyShortcut.Command(KeyCommand.SwipeSelect)), KeyActionListener.Source.Keyboard)
        KeyShortcutRunner(h.service, InputWindowManager(), {}, {}, {}, {}, {}) { true }.run(KeyShortcut.Command(KeyCommand.SwipeSelect))
        host.synced()
        assertEquals("still typed", "nihao", host.preedit)
        assertEquals("nothing committed", "hello", h.field.text.toString())
        assertEquals("nothing selected", "5..5", selected())
    }

    // their own sideways swipes (the cursor, the selection deleted) not run
    @Test
    fun swipeSelectOnSpaceOrBackspaceMovesNoCursorAndDeletesNothing() {
        val select = SwipeSlot.Command(KeyCommand.SwipeSelect)
        prefs.set(
            AppPrefs.getInstance().internal.keySwipes,
            SwipeSlots.Default.with(SwipeSlots.functionKey("space"), up = true, select)
                .with(SwipeSlots.functionKey("backspace"), up = true, select).serialize(),
        )
        start("hello world")
        host.synced()
        swipeSelect(-21f, on = h.keyNamed("space"))
        host.until("ld selected") { selected() == "9..11" }
        host.synced()
        assertEquals("hello world", h.field.text.toString())
        h.field.setSelection(5)
        host.synced()
        swipeSelect(-21f, on = backspace)
        host.until("lo selected") { selected() == "3..5" }
        host.synced()
        assertEquals("nothing deleted", "hello world", h.field.text.toString())
        assertEquals("3..5", selected())
    }

    private fun backspaces(n: Int, leaving: String) {
        repeat(n) { h.tap(backspace) }
        host.until("deleted to [$leaving]") { h.field.text.toString() == leaving }
    }

    @Test
    fun backspacesOneAfterAnotherArePutBackWholeBySwipingDown() {
        start("你好世界")
        backspaces(3, leaving = "你")
        swipe(backspace, up = false)
        host.until("put back") { h.field.text.toString() == "你好世界" }
        assertEquals(4, h.field.selectionStart)
    }

    @Test
    fun aRunIsNotPutBackOnceSomethingIsCommitted() {
        start("你好世界")
        backspaces(2, leaving = "你好")
        h.service.commitOverEngine("啊")
        host.until("committed") { h.field.text.toString() == "你好啊" }
        swipe(backspace, up = false)
        host.synced()
        assertEquals("你好啊", h.field.text.toString())
    }

    // a copy changes nothing of the field: the run goes on
    @Test
    fun aCopyBetweenBackspacesEndsNoRun() {
        prefs.setShortcuts(KeyShortcuts.Empty.with("full/c", KeyShortcut.Command(KeyCommand.Copy)))
        start("你好世界")
        backspaces(1, leaving = "你好世")
        swipe(h.key("c"), up = false)
        host.until("copied") { appContext.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() == "你好世" }
        backspaces(1, leaving = "你好")
        assertEquals(2, h.field.selectionStart)
        swipe(backspace, up = false)
        host.until("both put back") { h.field.text.toString() == "你好世界" }
    }

    @Test
    fun aCopyOrCutOfAnEmptyFieldKeepsTheClipboard() {
        prefs.setShortcuts(KeyShortcuts.Empty.with("full/c", KeyShortcut.Command(KeyCommand.Copy)).with("full/x", KeyShortcut.Command(KeyCommand.Cut)))
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, "旧的"))
        start("")
        // each said: the empty field's branch ran, not a swipe that never reached the command
        swipe(h.key("c"), up = false)
        host.synced()
        assertTrue(said().toString(), appContext.getString(R.string.nothing_to_copy) in said())
        swipe(h.key("x"), up = false)
        host.synced()
        assertTrue(said().toString(), appContext.getString(R.string.nothing_to_cut) in said())
        assertEquals("旧的", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    // rotation is the same input: the run is kept
    @Test
    fun aRunOutlastsTheScreenTurning() {
        start("你好世界")
        backspaces(2, leaving = "你好")
        h.turn()
        host.synced()
        swipe(backspace, up = false)
        host.until("put back") { h.field.text.toString() == "你好世界" }
    }

    @Test
    fun aTapOnTheFieldStartsAnotherRun() {
        start("abc")
        backspaces(1, leaving = "ab")
        h.service.onViewClicked(false)
        h.field.setSelection(1)
        h.idle()
        backspaces(1, leaving = "b")
        swipe(backspace, up = false)
        host.until("the second put back") { h.field.text.toString() == "ab" }
        assertEquals(1, h.field.selectionStart)
    }

    // a cut and a paste are the editor's: the run is over, though the field reads as the run left it
    @Test
    fun aRunIsNotPutBackAfterACutAndAPaste() {
        start("你好世界")
        backspaces(1, leaving = "你好世")
        swipe(h.key("x"), up = false)
        host.until("cut") { h.field.text.isEmpty() }
        swipe(h.key("v"), up = false)
        host.until("pasted") { h.field.text.toString() == "你好世" }
        swipe(backspace, up = false)
        host.synced()
        assertEquals("你好世", h.field.text.toString())
    }
}
