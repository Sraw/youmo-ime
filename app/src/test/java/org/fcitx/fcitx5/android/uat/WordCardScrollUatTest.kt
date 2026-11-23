/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.learn.control.WordKind
import org.fcitx.fcitx5.android.engine.reading.CandidateCard
import org.fcitx.fcitx5.android.engine.reading.CharCard
import org.fcitx.fcitx5.android.engine.reading.WordCard
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.candidates.CharCardView
import org.fcitx.fcitx5.android.input.candidates.CompactLineView
import org.fcitx.fcitx5.android.input.candidates.WordCardView
import org.fcitx.fcitx5.android.input.popup.CharCardLayout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import splitties.dimensions.dp

/** A word card taller than the room: the menu scrolls, and the card's rows still take a tap. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class WordCardScrollUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private val h = KeyboardHarness()

    @After
    fun close() = h.close()

    private val chars = "中华人民共和国"

    private val word = WordCard(
        chars, chars.map { "zhōng" }, chars.map { "ㄓㄨㄥ" },
        chars.map { WordCard.Row(it.toString(), "zhōng", "ㄓㄨㄥ", "丨", 4, WordCard.readingOf("zhōng", "ㄓㄨㄥ")) },
    )

    private fun head(): View? {
        val list = h.inputView.candidateActionMenu!!.listView!!
        return (list.adapter.getView(0, null, list) as? FrameLayout)?.getChildAt(0)
    }

    @Test
    @Uat("PY-166")
    fun aWordCardScrolledToItsEndStillOpensARowOnTheFirstTap() {
        val menuOf = LearningResult.Menu(chars, WordKind.DICTIONARY, listOf(MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), null, BlockScope.EVERYWHERE)
        h.api.learned = { op -> if (((op as? LearningOp.OnPage)?.op ?: op) is LearningOp.Menu) menuOf else LearningResult.Done() }
        h.api.candidateCards[chars] = CandidateCard.Word("zhōng", word)
        h.api.charCards["国"] = CharCard("国", listOf(CharCard.Row("guó", "ㄍㄨㄛˊ", listOf("中国"))), "囗", 8, emptyList(), emptyList())
        h.start()
        h.api.candidates = arrayOf(CandidateWord("", chars, ""), CandidateWord("", "因为", ""))
        h.emit(HostEvent.Candidates(2, h.api.candidates.toList()))
        val text = KeyboardHarness.all(h.inputView).filterIsInstance<TextView>().first { h.shown(it) && it.text.toString() == chars }
        generateSequence(text as View) { it.parent as? View }.first { it.isLongClickable }.performLongClick()
        h.idle()
        check((head() as CompactLineView).performClick())
        h.idle()
        val menu = h.inputView.candidateActionMenu!!
        val list = menu.listView!!
        val card = head() as WordCardView
        check(list.canScrollList(1)) { "the menu fits: nothing to scroll" }
        // a drag's start: the popup's scroll listener shows it again (measuring the list), then the list moves
        menu.show()
        list.scrollListBy(h.service.dp(CharCardLayout.MIN_ROW_DP))
        h.idle()
        assertTrue(card.isAttachedToWindow)
        val row = card.rows.last()
        val at = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val ev = MotionEvent.obtain(at, at, action, row.width / 2f, row.height / 2f, 0)
            row.dispatchTouchEvent(ev)
            ev.recycle()
        }
        h.idle()
        assertTrue(head() is CharCardView)
    }
}
