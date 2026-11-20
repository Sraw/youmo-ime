/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.testData
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

// one Sources shared by checks on several threads at once (an import's and a migration's): every check's report the one a
// check alone makes. How often a first read is made is not seen here (its lazies are pure, any mode passes); a cache of
// its that is not safe across threads would be
class LayoutCheckThreadsTest {
    private val boards = listOf("k18", "nine", "number", "qwerty", "symbols")

    private fun sources() = LayoutCheck.Sources(boards.associateWith { testData("$it.xml") }, mapOf("grouped-bottom" to testData("grouped-bottom.xml")))

    private fun own(id: String) = testData("$id.xml").replaceFirst("id=\"$id\"", "id=\"my-$id\" based-on=\"$id\"")

    @Test
    fun checksOnSeveralThreadsShareOneSources() {
        val texts = (boards - "number").map(::own)
        val alone = texts.map { LayoutCheck.check(it, sources(), "0.6.1").toString() }
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            repeat(ROUNDS) {
                val shared = sources()
                val gate = CountDownLatch(1)
                val runs = (0 until THREADS).map { i ->
                    pool.submit<String> {
                        gate.await()
                        LayoutCheck.check(texts[i % texts.size], shared, "0.6.1").toString()
                    }
                }
                gate.countDown()
                runs.forEachIndexed { i, f -> assertEquals(alone[i % texts.size], f.get(1, TimeUnit.MINUTES)) }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val THREADS = 8
        const val ROUNDS = 4
    }
}
