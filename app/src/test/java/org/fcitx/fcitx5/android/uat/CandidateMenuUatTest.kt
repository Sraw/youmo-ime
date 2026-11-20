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

/** The menu the engine gives, worded and acted on by the keyboard; which rows it offers are the engine's tests'. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class CandidateMenuUatTest {

    @get:Rule
    val prefs = PrefsRule()

    // a var: some tables want a keyboard per row
    private var h = KeyboardHarness()

    @After
    fun close() = h.close()

    private val ops = mutableListOf<LearningOp>()

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
        assertEquals(listOf("是", s(R.string.learning_block_everywhere, "是"), s(android.R.string.cancel), s(R.string.learning_block_confirm)), lines())
        pick(s(R.string.learning_block_confirm))
        assertTrue(ops.toString(), ops.any { "Block(index=0, text=是)" in it.toString() })
    }

    // a word never picked has no 忘掉学到的, 删除 or 取消固定
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

    // an undo the record could not take: the act is no longer undoable, said in one sentence whatever the record answers
    @Test
    @Uat("WD-46")
    fun anUndoThatFailedSaysWhatToDo() {
        // each row on a keyboard of its own: the chip an earlier row left is the latest popup otherwise
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
        longPress("是")
        pick(s(R.string.learning_block))
        pick(s(R.string.learning_block_confirm))
        val w = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow!!
        assertTrue(w.isShowing)
        h.tap(h.key("a"))
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
        assertEquals("dl: 点了 2", h.service.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }

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

    // another candidate held before the card came: that menu stays, the first one closed
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

    private fun View.node(): AccessibilityNodeInfo = createAccessibilityNodeInfo()

    private fun AccessibilityNodeInfo.clickLabel() = actionList.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK }?.label?.toString()

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
        assertEquals(listOf("银行") + learning, lines())
        val line = head() as CompactLineView
        assertEquals("银行　yín háng", line.shown.text.toString())
        assertEquals(null, line.wrapped)
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
        tapIn(char, "‹ 银行　yín háng")
        assertTrue(head() is WordCardView)
        h.api.calls.clear()
        tapIn(head()!!, s(R.string.quick_phrase_commit))
        assertTrue(h.api.calls.toString(), "select(0, 银行)" in h.api.calls)
        assertEquals(null, menu)
    }

    // in the same window, not shown anew: it went blank for two frames and slid in again on every tap
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
        // put there, not slid by the system (which showed the new height at the old top)
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

    // API 34's switch, before it the private flag (0x40) it stands for
    private fun notSlid(decor: View): Boolean {
        val params = decor.layoutParams as WindowManager.LayoutParams
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return !params.canPlayMoveAnimation()
        return WindowManager.LayoutParams::class.java.getField("privateFlags").getInt(params) and 0x40 != 0
    }

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
        val copy = KeyboardHarness.all(head()!!).filterIsInstance<TextView>().single { it.isLabel(s(R.string.card_copy_char, "行")) }
        assertTrue(copy.node().isClickable && copy.measured().measuredHeight >= minRow())
        check(back.performClick())
        h.idle()
        h.relayout()
        assertEquals((head() as WordCardView).head, h.inputView.cardFocused)
        menu!!.dismiss()
        h.idle()
        assertEquals(null, h.inputView.cardFocused)
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
        check(head()!!.performClick())
        h.idle()
        val card = head() as WordCardView
        assertEquals(LinearLayout.HORIZONTAL, card.orientation)
        h.relayout()
        // over the docked keyboard the menu has some 190 dp, the card 212: the lines are there, scrolled to
        val list = menu!!.listView!!
        assertTrue("${card.height} in ${list.height}", list.canScrollVertically(1))
        assertFalse(list.isScrollbarFadingEnabled)
        assertEquals(4, list.adapter.count)
        h.capture("CandidateMenuUatTest/word_card_land", list.rootView)
        check(card.rows[1].performClick())
        h.idle()
        assertEquals(LinearLayout.HORIZONTAL, (head() as CharCardView).orientation)
    }

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

    @Test
    @Uat("PY-124")
    fun theSameFromTheExpandedGrid() {
        engine(dictionary.copy(text = "银行"))
        h.api.candidateCards["银行"] = wordCard
        h.start()
        candidates(*(listOf("因为") + List(14) { "字$it" } + "银行").toTypedArray())
        h.tap(h.described(s(R.string.expand_candidates_list)))
        assertTrue(h.texts(h.inputView).toString(), "银行" in h.texts(h.inputView))
        longPress("银行")
        val line = head() as CompactLineView
        assertEquals("银行　yín háng", line.shown.text.toString())
        check(line.performClick())
        h.idle()
        assertEquals(yinhang, (head() as WordCardView).card)
    }

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
