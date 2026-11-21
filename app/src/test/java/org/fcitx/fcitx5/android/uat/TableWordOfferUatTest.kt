/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.session.OfferedWord
import org.fcitx.fcitx5.android.engine.session.WordOffer
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.WordOfferGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A table's offer on the notice strip: after the pause, the words its buttons, a tap remembers, 撤销 forgets. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class TableWordOfferUatTest {

    private val h = KeyboardHarness(im = InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "五", "zh_CN"))
        .start(prefill = { it.setText("汪小明"); it.setSelection(3) })

    @After
    fun close() = h.close()

    private val offer = WordOffer(7, listOf(OfferedWord("汪小明", "iije"), OfferedWord("小明", "ihje")))

    private fun View.find(text: String): View? = when {
        this is TextView && this.text.toString() == text -> this
        this is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).find(text) }
        else -> null
    }

    private fun shown(text: String) = h.notice()?.contentView?.find(text)

    @Test
    @Uat("TBL-11")
    fun anOfferGoesUpAfterThePauseAndATapRemembersIt() {
        h.emit(HostEvent.WordOffer(offer))
        h.advance(WordOfferGate.PAUSE_MS - 1)
        assertNull("not before the pause", h.notice())
        h.advance(1)
        // only an offer the user had time to read counts as left once it goes: told a second on, not as it goes up
        assertFalse(h.api.calls.toString(), "offerShown(7)" in h.api.calls)
        assertEquals(listOf(h.service.getString(R.string.word_offer), "汪小明 iije", "小明 ihje"), h.noticeTexts())
        h.advance(WordOfferGate.SEEN_MS)
        assertTrue(h.api.calls.toString(), "offerShown(7)" in h.api.calls)
        h.capture("TableWordOfferUatTest/offer", h.notice()!!.contentView)
        h.tap(shown("小明 ihje")!!)
        assertTrue(h.api.calls.toString(), "rememberWord(7, 1, 汪小明)" in h.api.calls)
        h.emit(HostEvent.WordRemembered(OfferedWord("小明", "ihje")))
        val said = h.noticeTexts()
        assertTrue(said.toString(), said.any { "小明" in it && "ihje" in it })
        h.tap(shown(h.service.getString(R.string.learning_undo))!!)
        assertTrue(h.api.calls.toString(), "unrememberWord(小明, ihje)" in h.api.calls)
    }

    // a tap is checked against what the field reads back: a word it does not end with would do nothing
    @Test
    fun onlyWordsTheFieldReadsBackAreOffered() {
        h.field.setText("明")
        h.emit(HostEvent.WordOffer(offer))
        h.advance(WordOfferGate.PAUSE_MS)
        assertNull("the field ends in neither", h.notice())
        h.field.setText("小明")
        h.field.setSelection(2)
        h.emit(HostEvent.WordOffer(offer.copy(id = 8)))
        h.advance(WordOfferGate.PAUSE_MS)
        assertEquals(listOf(h.service.getString(R.string.word_offer), "小明 ihje"), h.noticeTexts())
        h.tap(shown("小明 ihje")!!)
        assertTrue(h.api.calls.toString(), "rememberWord(8, 1, 小明)" in h.api.calls)
    }

    // the words were typed on the letters: symbols over them put the offer away, as a key would
    @Test
    fun symbolsPutTheOfferAway() {
        h.emit(HostEvent.WordOffer(offer))
        h.advance(WordOfferGate.PAUSE_MS)
        assertTrue(h.noticeTexts().toString(), "小明 ihje" in h.noticeTexts())
        h.tap(h.key("符"))
        h.advance(WordOfferGate.PAUSE_MS)
        assertNull(h.notice())
    }

    @Test
    @Uat("TBL-11a", "TBL-11b")
    fun noOfferWhileAFingerIsDownOverAnotherNoticeOrOnceWithdrawn() {
        val rows = listOf(
            "a finger held: timed from its lifting" to {
                val f = h.finger(h.keyboard, h.at(h.keyboard))
                h.emit(HostEvent.WordOffer(offer))
                h.advance(WordOfferGate.PAUSE_MS * 2)
                val held = h.notice() == null
                f.up(h.at(h.keyboard))
                h.advance(WordOfferGate.PAUSE_MS)
                held && shown("汪小明 iije") != null
            },
            "withdrawn before the pause: nothing, and not told seen" to {
                val seen = h.api.calls.count { it == "offerShown(7)" }
                h.emit(HostEvent.WordOffer(offer))
                h.emit(HostEvent.WordOffer(null))
                h.advance(WordOfferGate.PAUSE_MS)
                h.notice() == null && h.api.calls.count { it == "offerShown(7)" } == seen
            },
            "withdrawn while shown: it goes" to {
                h.emit(HostEvent.WordOffer(offer))
                h.advance(WordOfferGate.PAUSE_MS)
                h.emit(HostEvent.WordOffer(null))
                h.advance(WordOfferGate.PAUSE_MS)
                h.notice() == null
            },
            "another notice up: not over it, and not told seen" to {
                val seen = h.api.calls.count { it == "offerShown(7)" }
                h.emit(HostEvent.WordOffer(offer))
                h.emit(HostEvent.WordRemembered(OfferedWord("汪小", "iiig")))
                h.advance(WordOfferGate.PAUSE_MS)
                shown("汪小明 iije") == null && h.api.calls.count { it == "offerShown(7)" } == seen
            },
        )
        rows.forEach { (why, run) ->
            assertTrue(why, run())
            h.notice()?.dismiss()
            h.advance(WordOfferGate.PAUSE_MS)
        }
    }

    // the next key often comes as it goes up: a flash is no look, so the session may offer its words again
    @Test
    @Uat("TBL-11b")
    fun anOfferGoneWithinASecondIsNotToldSeen() {
        class Row(val why: String, val id: Int, val told: Boolean, val soon: () -> Unit)
        val rows = listOf(
            Row("left up", 11, told = true) {},
            Row("the next key withdrew it", 12, told = false) { h.emit(HostEvent.WordOffer(null)) },
            Row("the field changed: withdrawn as well", 13, told = false) {
                h.field.setText("汪小明了")
                h.emit(HostEvent.WordOffer(null))
            },
            Row("another notice took its place", 14, told = false) { h.emit(HostEvent.WordRemembered(OfferedWord("汪小", "iiig"))) },
            Row("tapped away", 15, told = false) { h.tap(h.notice()!!.contentView) },
            // last: the letters stay away
            Row("symbols put it away", 16, told = false) { h.tap(h.key("符")) },
        )
        rows.forEach { row ->
            h.field.setText("汪小明")
            h.field.setSelection(3)
            h.emit(HostEvent.WordOffer(offer.copy(id = row.id)))
            h.advance(WordOfferGate.PAUSE_MS)
            assertTrue(row.why, shown("汪小明 iije") != null)
            // a third of a second or so: the harness lays out as it goes, which takes a little time too
            h.advance(300)
            row.soon()
            h.advance(WordOfferGate.SEEN_MS)
            assertEquals(row.why, if (row.told) 1 else 0, h.api.calls.count { it == "offerShown(${row.id})" })
            h.notice()?.dismiss()
            h.emit(HostEvent.WordOffer(null))
            h.advance(WordOfferGate.PAUSE_MS)
        }
    }
}
