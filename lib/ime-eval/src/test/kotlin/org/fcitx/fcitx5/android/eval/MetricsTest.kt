/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricsTest {

    private val samples = listOf(
        Sample("nihao", "你好", "daily"),
        Sample("zhongguo", "中国", "daily"),
        Sample("nh", "你好", "abbrev"),
        Sample("xianzai", "现在", "ambiguous"),
    )

    private val results = listOf(
        RunResult("nihao", listOf("你好", "拟好"), listOf(1000, 3000)),
        RunResult("zhongguo", listOf("种过", "肿", "中国"), listOf(2000)),
        RunResult("nh", listOf("那会", "你会", "年后", "女孩", "你好"), listOf(4000)),
        // "xianzai" missing: the runner never got to it
    )

    private fun scores() = Metrics.score(samples, results).associateBy { it.group }

    @Test
    fun groupsFollowTheOrderTagsFirstAppearThenAll() {
        assertEquals(
            listOf("daily", "abbrev", "ambiguous", Metrics.ALL),
            Metrics.score(samples, results).map { it.group }
        )
    }

    @Test
    fun ranksCountTowardsEveryCutoffTheyFallWithin() {
        val daily = scores().getValue("daily")
        assertEquals(0.5, daily.top1, 0.0)
        assertEquals(1.0, daily.top3, 0.0)
        val abbrev = scores().getValue("abbrev")
        assertEquals(0.0, abbrev.top3, 0.0)
        assertEquals(1.0, abbrev.top5, 0.0)
    }

    @Test
    fun aMissingResultIsAMissEverywhereAndIsCounted() {
        val ambiguous = scores().getValue("ambiguous")
        assertEquals(1, ambiguous.missing)
        assertEquals(0.0, ambiguous.top5, 0.0)
        assertEquals(0.0, ambiguous.charAccuracy, 0.0)
        assertNull(ambiguous.latencyP95Micros)
    }

    @Test
    fun characterAccuracyIsPooledOverAllExpectedCharacters() {
        // 你好 right (0/2), 种过 vs 中国 (2/2), 那会 vs 你好 (2/2), nothing vs 现在 (2/2): 6 errors in 8
        assertEquals(1 - 6 / 8.0, scores().getValue(Metrics.ALL).charAccuracy, 1e-9)
    }

    @Test
    fun theSameInputTypedAfterDifferentContextsIsMatchedToItsOwnResult() {
        val s = listOf(Sample("jingli", "经理", "pair", "公司新来的"), Sample("jingli", "经历", "pair", "他有丰富的工作"))
        val r = listOf(RunResult("jingli", listOf("经理", "经历"), emptyList()), RunResult("jingli", listOf("经历", "经理"), emptyList()))
        assertEquals(1.0, Metrics.score(s, r).first().top1, 0.0)
        // a result for the first only: the second is missing, not scored against it
        assertEquals(1, Metrics.score(s, r.take(1)).first().missing)
    }

    @Test
    fun aRunawayFirstCandidateCostsNoMoreThanTheWholeSample() {
        val s = listOf(Sample("a", "啊", "t"))
        val r = listOf(RunResult("a", listOf("阿姨你好吗"), emptyList()))
        assertEquals(0.0, Metrics.score(s, r).first().charAccuracy, 0.0)
    }

    @Test
    fun latencyPercentilesPoolEveryKeystrokeInTheGroup() {
        val all = scores().getValue(Metrics.ALL)
        assertEquals(2000L, all.latencyP50Micros)
        assertEquals(4000L, all.latencyP99Micros)
    }

    @Test
    fun percentileUsesTheNearestRank() {
        val sorted = (1L..20L).toList()
        assertEquals(10L, Metrics.percentile(sorted, 50))
        assertEquals(19L, Metrics.percentile(sorted, 95))
        assertEquals(20L, Metrics.percentile(sorted, 99))
        assertEquals(7L, Metrics.percentile(listOf(7L), 1))
        assertNull(Metrics.percentile(emptyList(), 50))
    }

    @Test
    fun editDistanceCountsInsertionsDeletionsAndSubstitutions() {
        assertEquals(0, Metrics.editDistance("你好", "你好"))
        assertEquals(1, Metrics.editDistance("你好", "您好"))
        assertEquals(2, Metrics.editDistance("", "中国"))
        assertEquals(1, Metrics.editDistance("我到楼下了", "我到楼下"))
        assertEquals(3, Metrics.editDistance("kitten", "sitting"))
    }
}
