/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 空格上屏联想词 in pinyin. */
class PinyinSpacePicksTest {

    private val data = sessionTestData()

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    @Uat("PY-160")
    fun aSpaceTakesTheFirstPredictionShownOnlyWithTheSwitchOn() {
        class Row(val why: String, val on: Boolean, val wait: Long, val takes: Boolean)
        assertRows(
            listOf(
                Row("off: a space as ever", on = false, wait = 1000, takes = false),
                Row("on: the first prediction", on = true, wait = 1000, takes = true),
                Row("on, but the predictions came just now: a space", on = true, wait = 100, takes = false),
            ),
            { it.why },
        ) { row ->
            var clock = 0L
            val session = PinyinSession(data, PinyinSegmenter(), spacePicksPrediction = row.on, uptime = { clock })
            session.type("wo")
            val shown = session.apply(Select(0))
            assertEquals(listOf("再"), shown.candidates)
            assertEquals(if (row.on) 0 else -1, shown.spacePicks)
            // off, the bar draws the first as ever
            assertEquals(row.on, shown.spaceMarks)
            assertEquals(row.on, session.reads(' '))
            clock += row.wait
            val space = session.apply(Key(' '))
            if (row.takes) {
                assertEquals("再", space.commit)
                // the next space is a space, though 见 is shown
                assertEquals(listOf("见"), space.candidates)
                assertFalse(session.reads(' '))
                // marked as none: this space is a space
                assertEquals(-1 to true, space.spacePicks to space.spaceMarks)
                // the space the keyboard passes on ends the offer, never typed into the input
                assertEquals("", session.apply(Key(' ')).preedit)
            } else {
                assertEquals("", space.commit)
            }
        }
    }

    // a prediction tapped brings the next round: a space right after it is a space, as after any new round
    @Test
    @Uat("PY-160")
    fun aSpaceJustAfterATappedPredictionsNextRoundIsASpace() {
        var clock = 0L
        val session = PinyinSession(data, PinyinSegmenter(), spacePicksPrediction = true, uptime = { clock })
        session.type("wo")
        assertEquals(listOf("再"), session.apply(Select(0)).candidates)
        clock += 1000
        assertEquals(listOf("见"), session.apply(Select(0)).candidates)
        clock += 100
        assertEquals("", session.apply(Key(' ')).commit)
    }
}
