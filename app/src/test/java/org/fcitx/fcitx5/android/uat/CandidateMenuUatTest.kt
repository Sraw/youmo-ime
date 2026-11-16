/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.ClipboardManager
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.learn.control.WordKind
import org.fcitx.fcitx5.android.engine.reading.CandidateCard
import org.fcitx.fcitx5.android.engine.reading.CharCard
import org.fcitx.fcitx5.android.engine.reading.WordCard
import org.fcitx.fcitx5.android.engine.stroke.StrokeShape
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.candidates.CharCardView
import org.fcitx.fcitx5.android.input.candidates.CompactLineView
import org.fcitx.fcitx5.android.input.candidates.WordCardView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.popup.CharCardLayout
import org.fcitx.fcitx5.android.input.preedit.CodeRowStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import splitties.dimensions.dp

/**
 * UAT cases of a candidate's long-press menu (uat/06): the menu the engine gives, worded and acted
 * on by the keyboard, the chip that says it with 撤销. Which rows the engine offers for a word, and
 * what blocking does to its candidates, are the engine's (its learning tests), not asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class CandidateMenuUatTest {

    @get:Rule
    val prefs = PrefsRule()

    // a var for the tables whose rows each want a keyboard of their own (anUndoThatFailedSaysWhatToDo)
    private var h = KeyboardHarness()

    @After
    fun close() = h.close()

    private val ops = mutableListOf<LearningOp>()

    // [undone]: what the chip's 撤销 answers; [saving]: what LearningOp.Saving answers
    private fun engine(
        menu: LearningResult.Menu,
        report: String = "",
        undone: LearningResult = LearningResult.Done(),
        saving: LearningResult = LearningResult.Saving(true),
    ) {
        h.api.learned = { op ->
            ops += op
            val inner = (op as? LearningOp.OnPage)?.op ?: op
            when (inner) {
                is LearningOp.Menu -> menu
                is LearningOp.Report -> LearningResult.Text(report)
                is LearningOp.Undo -> undone
                LearningOp.Saving -> saving
                else -> LearningResult.Done(TOKEN)
            }
        }
    }

    private fun candidates(vararg words: String) {
        h.api.candidates = Array(words.size) { CandidateWord("", words[it], "") }
        h.emit(HostEvent.Candidates(words.size, h.api.candidates.toList()))
    }

    private fun s(id: Int, vararg args: Any) = h.service.getString(id, *args)

    private val menu: ListPopupWindow?
        get() = h.inputView.candidateActionMenu

    /** The open menu's lines, its title (the word) first. */
    private fun lines(): List<String> {
        val m = menu ?: error("no menu")
        return (0 until m.listView!!.adapter.count).map { m.listView!!.adapter.getItem(it).toString() }
    }

    private fun pick(label: String) {
        val at = lines().indexOf(label)
        check(at > 0) { "no $label in ${lines()}" }
        menu!!.performItemClick(at)
        h.idle()
    }

    private fun longPress(word: String) {
        val text = KeyboardHarness.all(h.inputView).filterIsInstance<TextView>().first { h.shown(it) && it.text.toString() == word }
        val holder = generateSequence(text as View) { it.parent as? View }.first { it.isLongClickable }
        holder.performLongClick()
        h.idle()
    }

    // the chip saying what was done: a popup window over the keyboard
    private fun chip(): List<String>? = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        ?.takeIf { it.isShowing }
        ?.let { w -> KeyboardHarness.all(w.contentView).filterIsInstance<TextView>().map { it.text.toString() }.toList() }

    private fun undo() {
        val w = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow!!
        val button = KeyboardHarness.all(w.contentView).filterIsInstance<TextView>().first { it.text.toString() == s(R.string.learning_undo) }
        check(button.performClick())
        h.idle()
    }

    private val dictionary = LearningResult.Menu("是", WordKind.DICTIONARY, listOf(MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), null, BlockScope.EVERYWHERE)

    @Test
    @Uat("PY-29")
    fun blockingAWordSaysWhereAndAsksFirst() {
        engine(dictionary)
        h.start()
        candidates("是", "时", "事")
        longPress("是")
        assertEquals(listOf("是", s(R.string.learning_block), s(R.string.learning_pin), s(R.string.learning_why)), lines())
        pick(s(R.string.learning_block))
        // the reach first: typing, handwriting, voice and predictions alike
        assertEquals(listOf("是", s(R.string.learning_block_everywhere, "是"), s(android.R.string.cancel), s(R.string.learning_block_confirm)), lines())
        pick(s(R.string.learning_block_confirm))
        assertTrue(ops.toString(), ops.any { "Block(index=0, text=是)" in it.toString() })
    }

    // the rows the engine gives, worded: a word never picked has no 忘掉学到的, 删除 or 取消固定 (KeyboardHalvesUatTest's)
    @Test
    @Uat("PY-33")
    fun theMenuShowsTheRowsTheEngineGivesAndForgetOnceLearned() {
        engine(dictionary.copy(text = "试"))
        h.start()
        candidates("是", "时", "事", "试")
        longPress("试")
        assertEquals(listOf("试", s(R.string.learning_block), s(R.string.learning_pin), s(R.string.learning_why)), lines())
        for (absent in listOf(R.string.learning_forget, R.string.learning_delete, R.string.learning_unpin)) assertFalse(s(absent) in lines())
        menu!!.dismiss()
        h.idle()
        engine(LearningResult.Menu("试", WordKind.DICTIONARY, listOf(MenuItem.DELETE, MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), null, BlockScope.EVERYWHERE))
        longPress("试")
        assertEquals(listOf("试", s(R.string.learning_forget), s(R.string.learning_block), s(R.string.learning_pin), s(R.string.learning_why)), lines())
    }

    // F8038: an undo the record could not take is said on the chip, neither "try again" nor a restart: the act is no
    // longer undoable once its undo failed (R25r7). Whether the record saves is not asked (R25r8 F8057): the one
    // sentence whatever it answers, so the failed row's record is one that stopped, where the restart would be said
    // were it asked; an act no longer undoable says so
    @Test
    @Uat("WD-46")
    fun anUndoThatFailedSaysWhatToDo() {
        // each row on a keyboard of its own: the chip an earlier row left is the latest popup otherwise; the chip's
        // words and the ops its undo asked
        fun saidAfterUndo(undone: LearningResult): Pair<List<String>, List<LearningOp>> {
            h.close()
            h = KeyboardHarness()
            h.start()
            candidates("是", "时", "事")
            engine(dictionary, undone = undone, saving = LearningResult.Saving(false, rewrites = false))
            longPress("是")
            pick(s(R.string.learning_block))
            pick(s(R.string.learning_block_confirm))
            ops.clear()
            undo()
            return chip().orEmpty() to ops.toList()
        }
        assertRows(
            "failed: not undone, neither a retry nor a restart promised, the record not asked whether it saves" to {
                val (said, asked) = saidAfterUndo(LearningResult.Failed)
                assertTrue(said.toString(), s(R.string.learning_undo_failed) in said)
                // the chip says it with no 撤销 of its own: the act is not undone again (R25r9 F8071)
                assertFalse(said.toString(), s(R.string.learning_undo) in said)
                assertTrue(asked.toString(), asked.any { it is LearningOp.Undo } && LearningOp.Saving !in asked)
            },
            "no longer undoable" to {
                val (said, _) = saidAfterUndo(LearningResult.Unavailable)
                assertTrue(said.toString(), s(R.string.learning_undo_gone) in said)
            },
        )
    }

    @Test
    @Uat("PY-68")
    fun aBlockIsTakenBackOnTheSpot() {
        engine(dictionary)
        h.start()
        candidates("是", "时", "事")
        longPress("是")
        val blockAt = lines().indexOf(s(R.string.learning_block))
        pick(s(R.string.learning_block))
        // 确定屏蔽 not where the finger that picked 屏蔽 is: 取消 between
        assertNotEquals(blockAt, lines().indexOf(s(R.string.learning_block_confirm)))
        assertEquals(lines().indexOf(s(android.R.string.cancel)) + 1, lines().indexOf(s(R.string.learning_block_confirm)))
        pick(s(R.string.learning_block_confirm))
        val said = chip()
        assertTrue(said.toString(), said!!.contains(s(R.string.learning_done_block, "是")) && said.contains(s(R.string.learning_undo)))
        h.capture("CandidateMenuUatTest/blocked")
        undo()
        assertTrue(ops.toString(), ops.any { it == LearningOp.Undo(TOKEN) })
        // again, not taken back: typing on puts the chip away at once
        longPress("是")
        pick(s(R.string.learning_block))
        pick(s(R.string.learning_block_confirm))
        val w = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow!!
        assertTrue(w.isShowing)
        h.tap(h.key("a"))
        // the engine tells of the a it was sent: typing
        val typed = org.fcitx.fcitx5.android.core.FormattedText(arrayOf("a"), intArrayOf(0), 1)
        val empty = org.fcitx.fcitx5.android.core.FormattedText.Empty
        h.emit(HostEvent.InputPanel(typed, empty, empty))
        h.advance(500)
        assertFalse(w.isShowing)
    }

    @Test
    @Uat("PY-69")
    fun whyItIsThereAndTheReportCopied() {
        engine(
            LearningResult.Menu("点了", WordKind.DICTIONARY, listOf(MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), Why(org.fcitx.fcitx5.android.engine.learn.HabitRanker.Tier.FIRST_HERE), BlockScope.EVERYWHERE),
            report = "dl: 点了 2",
        )
        h.start()
        candidates("点了", "带来")
        longPress("点了")
        pick(s(R.string.learning_why))
        assertEquals(listOf("点了", s(R.string.learning_why_first_here), s(R.string.learning_copy_report)), lines())
        menu!!.dismiss()
        h.idle()
        engine(LearningResult.Menu("带来", WordKind.DICTIONARY, listOf(MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), Why(org.fcitx.fcitx5.android.engine.learn.HabitRanker.Tier.DICTIONARY), BlockScope.EVERYWHERE), report = "dl: 点了 2")
        longPress("带来")
        pick(s(R.string.learning_why))
        assertEquals(s(R.string.learning_why_dictionary), lines()[1])
        pick(s(R.string.learning_copy_report))
        // the engine's report, as it gave it, on the clipboard
        assertEquals("dl: 点了 2", h.service.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }

    // a word's and a character's cards as the engine makes them (EnginesWordCardTest has them from the real data)
    private val hang = CharCard(
        "行",
        listOf(
            CharCard.Row("háng", "ㄏㄤˊ", listOf("银行", "行业"), inWord = true),
            CharCard.Row("xíng", "ㄒㄧㄥˊ", listOf("进行", "行为")),
        ),
        "行", 6, listOf(CharCard.Part("彳", "chì"), CharCard.Part("亍", "chù")), StrokeShape.parse("P P S H H SG"),
    )
    private val yinhang = WordCard(
        "银行", listOf("yín", "háng"), listOf("ㄧㄣˊ", "ㄏㄤˊ"),
        listOf(
            WordCard.Row("银", "yín", "ㄧㄣˊ", "钅", 11, WordCard.readingOf("yín", "ㄧㄣˊ")),
            WordCard.Row("行", "háng", "ㄏㄤˊ", "行", 6, WordCard.readingOf("háng", "ㄏㄤˊ")),
        ),
    )
    private val wordCard = CandidateCard.Word("yín háng", yinhang)

    /** The menu's head, the view in the box it is shown in: null where it has the title alone. */
    private fun head(): View? = (menu!!.listView!!.adapter.getView(0, null, menu!!.listView!!) as? FrameLayout)?.getChildAt(0)

    private fun TextView.isLabel(label: String) = text.toString() == label

    private fun tapIn(view: View, label: String) {
        val v = KeyboardHarness.all(view).filterIsInstance<TextView>().firstOrNull { it.isLabel(label) } ?: error("no $label in $view")
        check(v.performClick())
        h.idle()
    }

    private fun clip() = h.service.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun minRow() = h.service.dp(CharCardLayout.MIN_ROW_DP)

    private fun View.measured(width: Int = h.service.dp(393)): View = also {
        measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    }

    // a prediction's menu: its reading line over 不再联想 and 屏蔽, as they were
    @Test
    @Uat("PY-129")
    fun aPredictionHasItsReadingLineOverItsLines() {
        engine(LearningResult.Menu("银行", WordKind.DICTIONARY, listOf(MenuItem.BLOCK, MenuItem.NO_PREDICT), null, BlockScope.EVERYWHERE))
        h.api.candidateCards["银行"] = wordCard
        h.start()
        candidates("银行", "因为")
        longPress("银行")
        assertEquals(listOf("银行", s(R.string.learning_block), s(R.string.learning_no_predict)), lines())
        assertEquals("银行　yín háng", (head() as CompactLineView).shown.text.toString())
        pick(s(R.string.learning_no_predict))
        assertTrue(ops.toString(), ops.any { ((it as? LearningOp.OnPage)?.op ?: it) is LearningOp.NoPredict })
    }

    // a character's card asked for, another candidate held before it came: that menu stays, the first one closed
    @Test
    @Uat("PY-124")
    fun aCharactersCardThatComesAfterAnotherMenuIsUpIsLeft() {
        engine(dictionary.copy(text = "银行"))
        h.api.candidateCards["银行"] = wordCard
        h.api.charCards["行"] = hang
        val late = CompletableDeferred<Unit>()
        h.api.charCardGate = late
        h.start()
        candidates("银行", "因为")
        longPress("银行")
        check((head() as CompactLineView).performClick())
        h.idle()
        val first = menu!!
        check((head() as WordCardView).rows[1].performClick())
        h.idle()
        longPress("因为")
        val second = menu!!
        assertNotSame(first, second)
        late.complete(Unit)
        h.idle()
        assertSame(second, menu)
        assertFalse(first.isShowing)
        assertEquals("因为", lines().first())
    }

    // the head's node as TalkBack has it
    private fun View.node(): AccessibilityNodeInfo = createAccessibilityNodeInfo()

    private fun AccessibilityNodeInfo.clickLabel() = actionList.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK }?.label?.toString()

    // in Chinese, as the user reviews the image
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
    @Test
    @Uat("PY-124")
    fun aHeldWordShowsItsReadingThenItsCardAndEachCharacters() {
        engine(dictionary.copy(text = "银行"))
        h.api.candidateCards["银行"] = wordCard
        h.api.charCards["行"] = hang
        h.start()
        candidates("银行", "因为")
        longPress("银行")
        val learning = listOf(s(R.string.learning_block), s(R.string.learning_pin), s(R.string.learning_why))
        // the same lines in the same places, the head over them
        assertEquals(listOf("银行") + learning, lines())
        val line = head() as CompactLineView
        assertEquals("银行　yín háng", line.shown.text.toString())
        assertEquals(null, line.wrapped)
        // in portrait the platform's scrollbar, fading
        assertTrue(menu!!.listView!!.isScrollbarFadingEnabled)
        h.relayout()
        h.capture("CandidateMenuUatTest/reading_line", menu!!.listView!!.rootView)
        check(line.performClick())
        h.idle()
        val card = head() as WordCardView
        assertEquals(yinhang, card.card)
        assertEquals(listOf("银行") + learning, lines())
        val texts = KeyboardHarness.all(card).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(texts.toString(), texts.containsAll(listOf("银行", "yín háng", "ㄧㄣˊ ㄏㄤˊ", "银", "行", "钅　" + s(R.string.char_card_strokes, 11))))
        assertEquals(listOf(s(R.string.quick_phrase_commit), s(R.string.quick_phrase_copy)), texts.takeLast(2))
        assertEquals(2, card.rows.size)
        card.measured()
        assertTrue(card.rows.all { it.measuredHeight >= minRow() })
        h.relayout()
        h.capture("CandidateMenuUatTest/word_card_port", menu!!.listView!!.rootView)
        // 行: its card, háng the word's reading first, 复制 it alone and the way back
        check(card.rows[1].performClick())
        h.idle()
        assertEquals(listOf("行" to WordCard.readingOf("háng", "ㄏㄤˊ")), h.api.charCardsAsked)
        val char = head() as CharCardView
        assertEquals(hang, char.card)
        val charTexts = KeyboardHarness.all(char).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(charTexts.toString(), "‹ 银行　yín háng" in charTexts)
        assertEquals(listOf(s(R.string.card_copy_char, "行")), charTexts.filter { it == s(R.string.card_copy_char, "行") || it == s(R.string.quick_phrase_commit) || it == s(R.string.quick_phrase_copy) })
        assertEquals(listOf("银行") + learning, lines())
        h.relayout()
        h.capture("CandidateMenuUatTest/char_of_word", menu!!.listView!!.rootView)
        // back: the word's card again
        tapIn(char, "‹ 银行　yín háng")
        assertTrue(head() is WordCardView)
        // 上屏: as the candidate tapped
        h.api.calls.clear()
        tapIn(head()!!, s(R.string.quick_phrase_commit))
        assertTrue(h.api.calls.toString(), "select(0, 银行)" in h.api.calls)
        assertEquals(null, menu)
    }

    // a word's character and the way back change the card in the same window: not shown anew, not grown again
    // from the candidate (it went blank for two frames and slid in again on every tap), as wide as before
    @Test
    @Uat("PY-144")
    fun aCharacterOfTheWordAndBackStayInTheSameWindow() {
        engine(dictionary.copy(text = "银行"))
        h.api.candidateCards["银行"] = wordCard
        h.api.charCards["行"] = hang
        h.start()
        candidates("银行", "因为")
        longPress("银行")
        val m = menu!!
        assertEquals(1, h.inputView.menuGrown)
        check((head() as CompactLineView).performClick())
        h.idle()
        // the line opened: a wider window, grown from the candidate as the menu first was
        assertEquals(2, h.inputView.menuGrown)
        val decor = m.listView!!.rootView
        val width = m.width
        check((head() as WordCardView).rows[1].performClick())
        h.idle()
        assertTrue(head() is CharCardView)
        assertSame(m, menu)
        assertTrue(m.isShowing)
        assertSame(decor, m.listView!!.rootView)
        assertEquals(width, m.width)
        assertEquals(2, h.inputView.menuGrown)
        assertEquals(1f, decor.alpha)
        assertEquals(1f, decor.scaleX)
        assertEquals(1f, decor.scaleY)
        // its top moved with the card's height: put there, not slid by the system (which showed the new height at the old top)
        assertTrue(notSlid(decor))
        tapIn(head()!!, "‹ 银行　yín háng")
        assertTrue(head() is WordCardView)
        assertSame(m, menu)
        assertEquals(width, m.width)
        assertEquals(2, h.inputView.menuGrown)
        assertEquals(1f, decor.alpha)
        assertEquals(1f, decor.scaleX)
        assertTrue(notSlid(decor))
    }

    // the window's move animation off: API 34's switch, before it the private flag (0x40) it stands for
    private fun notSlid(decor: View): Boolean {
        val params = decor.layoutParams as WindowManager.LayoutParams
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return !params.canPlayMoveAnimation()
        return WindowManager.LayoutParams::class.java.getField("privateFlags").getInt(params) and 0x40 != 0
    }

    // 复制 puts the word, or a character of it, on the clipboard, nothing typed
    @Test
    @Uat("PY-124")
    fun copyFromAWordCardAndFromItsCharacter() {
        engine(dictionary.copy(text = "银行"))
        h.api.candidateCards["银行"] = wordCard
        h.api.charCards["行"] = hang
        h.start()
        candidates("银行", "因为")
        longPress("银行")
        check(head()!!.performClick())
        h.idle()
        h.api.calls.clear()
        tapIn(head()!!, s(R.string.quick_phrase_copy))
        assertEquals("银行", clip())
        assertEquals(null, menu)
        longPress("银行")
        check(head()!!.performClick())
        h.idle()
        check((head() as WordCardView).rows[1].performClick())
        h.idle()
        tapIn(head()!!, s(R.string.card_copy_char, "行"))
        assertEquals("行", clip())
        assertFalse(h.api.calls.toString(), h.api.calls.any { it.startsWith("select") || it.startsWith("sendKey") })
        assertEquals(null, menu)
    }

    @Test
    @Uat("PY-126")
    fun aHeldCharacterOpensToItsFullCard() {
        engine(dictionary.copy(text = "行"))
        h.api.candidateCards["行"] = CandidateCard.Char("háng / xíng", hang)
        h.start()
        candidates("行", "航")
        longPress("行")
        val line = head() as CompactLineView
        assertEquals("行　háng / xíng", line.shown.text.toString())
        assertEquals(s(R.string.card_open_char), line.node().clickLabel())
        check(line.performClick())
        h.idle()
        val card = head() as CharCardView
        assertEquals(hang, card.card)
        assertTrue(card.node().isFocusable)
        assertTrue(card.node().contentDescription.startsWith("行，"))
        val texts = KeyboardHarness.all(card).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertEquals(listOf(s(R.string.quick_phrase_commit), s(R.string.quick_phrase_copy)), texts.takeLast(2))
        assertFalse(texts.toString(), texts.any { it.startsWith("‹") })
        assertEquals(listOf("行", s(R.string.learning_block), s(R.string.learning_pin), s(R.string.learning_why)), lines())
    }

    // no card from the engine (an emoji, English, a quick input entry: Q8): the title as before
    @Test
    @Uat("PY-128", "EN-29")
    fun noReadingLineWhereTheEngineHasNoCard() {
        engine(dictionary.copy(text = "hello"))
        h.start()
        candidates("hello", "😀")
        longPress("hello")
        assertEquals(null, head())
        assertEquals(listOf("hello", s(R.string.learning_block), s(R.string.learning_pin), s(R.string.learning_why)), lines())
        menu!!.dismiss()
        h.idle()
        // a quick input entry: its own lines, the card not asked for
        h.api.candidateCards["银行"] = wordCard
        h.api.quickPhrases[0] = QuickPhraseBook.Found("yh", "银行", mine = true)
        candidates("银行")
        longPress("银行")
        assertEquals(null, head())
    }

    @Config(qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
    @Test
    @Uat("PY-133")
    fun aLongCandidateHasItsReadingAloneWrapped() {
        engine(dictionary.copy(text = "我们今天下午去银行办事"))
        val long = "我们今天下午去银行办事"
        h.api.candidateCards[long] = CandidateCard.Line(long, "wǒ men jīn tiān xià wǔ qù yín háng bàn shì")
        h.start()
        candidates(long)
        longPress(long)
        val line = head() as CompactLineView
        assertEquals("wǒ men jīn tiān xià wǔ qù yín háng bàn shì", line.wrapped!!.text.toString())
        assertFalse(line.isClickable)
        assertFalse(line.node().isClickable)
        // a stop for TalkBack of its own, read whole
        assertTrue(line.node().isFocusable)
        assertEquals("$long，${s(R.string.char_card_readings)} wǒ men jīn tiān xià wǔ qù yín háng bàn shì", line.node().contentDescription.toString())
        assertFalse(KeyboardHarness.all(line).filterIsInstance<TextView>().any { it.text.toString() == "›" })
        line.performClick()
        h.idle()
        assertTrue(head() is CompactLineView)
        assertEquals(listOf(long, s(R.string.learning_block), s(R.string.learning_pin), s(R.string.learning_why)), lines())
        h.relayout()
        h.capture("CandidateMenuUatTest/long_line", menu!!.listView!!.rootView)
    }

    @Test
    @Uat("AX-16")
    fun talkBackReadsTheLineTheRowsAndTheWayBackAndIsTakenToWhatOpened() {
        engine(dictionary.copy(text = "银行"))
        h.api.candidateCards["银行"] = wordCard
        h.api.charCards["行"] = hang
        h.start()
        candidates("银行", "因为")
        longPress("银行")
        val line = head() as CompactLineView
        val node = line.node()
        assertEquals("银行，yín háng", node.contentDescription.toString())
        assertEquals(android.widget.Button::class.java.name, node.className.toString())
        assertTrue(node.isClickable && node.isEnabled)
        assertEquals(s(R.string.card_open_word), node.clickLabel())
        assertEquals(null, h.inputView.cardFocused)
        line.measured()
        assertTrue(line.measuredHeight >= minRow())
        check(line.performClick())
        h.idle()
        h.relayout()
        val card = head() as WordCardView
        // the new content's first node
        assertEquals(card.head, h.inputView.cardFocused)
        assertEquals("银行，${s(R.string.char_card_readings)} yín háng", card.head.contentDescription.toString())
        val row = card.rows[1].node()
        assertEquals("行，háng，${s(R.string.char_card_radical)} 行，${s(R.string.char_card_strokes, 6)}", row.contentDescription.toString())
        assertTrue(row.isClickable && row.isEnabled)
        assertEquals(s(R.string.card_see_char), row.clickLabel())
        check(card.rows[1].performClick())
        h.idle()
        h.relayout()
        val back = KeyboardHarness.all(head()!!).filterIsInstance<TextView>().single { it.text.startsWith("‹") }
        assertEquals(back, h.inputView.cardFocused)
        assertEquals(s(R.string.card_back, "银行"), back.node().contentDescription.toString())
        assertEquals(s(R.string.card_back, "银行"), back.node().clickLabel())
        back.measured()
        assertTrue(back.measuredHeight >= minRow())
        // the buttons each a node of their own
        val copy = KeyboardHarness.all(head()!!).filterIsInstance<TextView>().single { it.isLabel(s(R.string.card_copy_char, "行")) }
        assertTrue(copy.node().isClickable && copy.measured().measuredHeight >= minRow())
        check(back.performClick())
        h.idle()
        h.relayout()
        assertEquals((head() as WordCardView).head, h.inputView.cardFocused)
    }

    @Test
    @Uat("LS-32")
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-440dpi")
    fun inLandscapeTheWordCardIsTwoColumnsWithLinesUnderIt() {
        engine(dictionary.copy(text = "银行"))
        h.api.candidateCards["银行"] = wordCard
        h.api.charCards["行"] = hang
        h.start()
        candidates("银行", "因为")
        longPress("银行")
        // a line: as wide as it needs, one column
        check(head()!!.performClick())
        h.idle()
        val card = head() as WordCardView
        assertEquals(LinearLayout.HORIZONTAL, card.orientation)
        h.relayout()
        // over the docked keyboard the menu has some 190 dp, the card 212 (today's character card 161): the lines
        // are there, scrolled to (061-word-card-impl-report.md)
        val list = menu!!.listView!!
        assertTrue("${card.height} in ${list.height}", list.canScrollVertically(1))
        // its scrollbar not fading: the lines are down there (the user, 2026-11-14, A)
        assertFalse(list.isScrollbarFadingEnabled)
        assertEquals(4, list.adapter.count)
        h.capture("CandidateMenuUatTest/word_card_land", list.rootView)
        check(card.rows[1].performClick())
        h.idle()
        assertEquals(LinearLayout.HORIZONTAL, (head() as CharCardView).orientation)
    }

    // a menu of no card in landscape: its lines alone, the scrollbar fading as before
    @Test
    @Uat("LS-32")
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-440dpi")
    fun inLandscapeAMenuOfNoCardHasItsScrollbarAsBefore() {
        engine(dictionary.copy(text = "hello"))
        h.start()
        candidates("hello", "😀")
        longPress("hello")
        assertEquals(null, head())
        assertTrue(menu!!.listView!!.isScrollbarFadingEnabled)
    }

    // from the expanded grid: the same line and card (the grid's index the list's, as the bar's)
    @Test
    @Uat("PY-124")
    fun theSameFromTheExpandedGrid() {
        engine(dictionary.copy(text = "银行"))
        h.api.candidateCards["银行"] = wordCard
        h.start()
        candidates(*(listOf("因为") + List(14) { "字$it" } + "银行").toTypedArray())
        h.tap(h.described(s(R.string.expand_candidates_list)))
        // in the grid, past what the bar showed
        assertTrue(h.texts(h.inputView).toString(), "银行" in h.texts(h.inputView))
        longPress("银行")
        val line = head() as CompactLineView
        assertEquals("银行　yín háng", line.shown.text.toString())
        check(line.performClick())
        h.idle()
        assertEquals(yinhang, (head() as WordCardView).card)
    }

    // floating, in landscape: one column, as upright
    @Test
    @Uat("LS-32")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun onTheFloatingKeyboardTheWordCardIsOneColumn() {
        prefs.setStyle(KeyboardStyle.FLOATING)
        AppPrefs.getInstance().internal.run {
            prefs.set(floatingKeyboardX, floatingKeyboardX.defaultValue)
            prefs.set(floatingKeyboardY, floatingKeyboardY.defaultValue)
        }
        engine(dictionary.copy(text = "银行"))
        h.api.candidateCards["银行"] = wordCard
        h.start()
        candidates("银行", "因为")
        longPress("银行")
        check(head()!!.performClick())
        h.idle()
        assertEquals(LinearLayout.VERTICAL, (head() as WordCardView).orientation)
    }

    @Test
    @Uat("PY-132")
    fun withTheCodeInTheFieldTheCardLeavesItAsItIs() {
        if (ThemeManager.prefs.codeRow.getValue() != CodeRowStyle.InField) prefs.set(ThemeManager.prefs.codeRow, CodeRowStyle.InField)
        h.close()
        h = KeyboardHarness(codeRow = CodeRowStyle.InField)
        engine(dictionary.copy(text = "银行"))
        h.api.candidateCards["银行"] = wordCard
        h.api.charCards["行"] = hang
        h.start()
        val panel = HostEvent.InputPanel(FormattedText(arrayOf("yinhang"), intArrayOf(0), 7), FormattedText.Empty, FormattedText.Empty, code = true)
        h.api.inputPanelCached = panel
        h.emit(panel)
        candidates("银行", "因为")
        fun field() = h.field.text.toString() to (BaseInputConnection.getComposingSpanStart(h.field.text) to BaseInputConnection.getComposingSpanEnd(h.field.text))
        val before = field()
        assertEquals("yinhang", before.first)
        longPress("银行")
        check(head()!!.performClick())
        h.idle()
        check((head() as WordCardView).rows[1].performClick())
        h.idle()
        tapIn(head()!!, s(R.string.card_copy_char, "行"))
        assertEquals("行", clip())
        assertEquals(before, field())
    }

    // a field that learns nothing: the card alone, no lines (Q4)
    @Test
    @Uat("PR-54")
    fun aFieldThatLearnsNothingHasTheCardAndNoLines() {
        h.api.learned = { op -> ops += op; LearningResult.Done() }
        h.api.candidateCards["银行"] = wordCard
        h.api.charCards["行"] = hang
        h.start(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        candidates("银行", "因为")
        longPress("银行")
        assertEquals(listOf("银行"), lines())
        check(head()!!.performClick())
        h.idle()
        check((head() as WordCardView).rows[1].performClick())
        h.idle()
        assertTrue(head() is CharCardView)
        assertEquals(listOf("银行"), lines())
        assertFalse(ops.toString(), ops.any { it !is LearningOp.Menu && (it as? LearningOp.OnPage)?.op !is LearningOp.Menu })
    }

    private companion object {
        const val TOKEN = 5L
    }
}
