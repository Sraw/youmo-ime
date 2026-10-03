/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PredictRunTest {

    @Test
    fun anOfferIsAHitWhenWhatCameNextStartsWithIt() {
        val samples = listOf(Continuation("我", "们走吧"), Continuation("你", "好"), Continuation("他", "说"))
        val offers = listOf(listOf("们走", "们"), listOf("们", "", "x", "y", "z", "好"), emptyList())
        // the first: top1, 2 chars saved; the second: 好 is sixth, past what is shown; the empty offer never counts
        assertEquals(PredictRun.Score(3, 1, 1, 2), PredictRun.score(samples, offers))
    }

    @Test
    fun aSetIsContextTabNextWithComments() {
        assertEquals(
            listOf(Continuation("我先去", "走了"), Continuation("", "你好")),
            PredictRun.parse(sequenceOf("# header", "我先去\t走了", "", "\t你好")),
        )
        assertThrows(IllegalArgumentException::class.java) { PredictRun.parse(sequenceOf("no tab")) }
        assertThrows(IllegalArgumentException::class.java) { PredictRun.parse(sequenceOf("我\t")) }
    }
}
