/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.table.dictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableGlideTest {

    private val scores = mapOf("你" to -2f, "好" to -2.5f, "妤" to -6f, "女" to -3f, "奻" to -7f)

    private val glide = TableGlide(dictionary("wqiy 你", "vbg 好", "vbgf 妤", "vv 奻", "vv 女"), { scores.getValue(it) })

    private fun path(keys: String) = keys.flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray()

    @Test
    fun aCodeByItsLikeliestEntry() {
        val best = glide.decode(path("wqiy"), GlideLayout.QWERTY, 3).first()
        assertEquals("wqiy", best.keys)
        assertEquals("你", best.word)
        // where the path lifts decides: vbg's own path, not vbgf's
        assertEquals("好", glide.decode(path("vbg"), GlideLayout.QWERTY, 3).first().word)
        assertEquals("vbgf", glide.decode(path("vbgf"), GlideLayout.QWERTY, 3).first().keys)
    }

    @Test
    fun noneForNoPathOrNoTable() {
        assertTrue(glide.decode(floatArrayOf(1f, 1f), GlideLayout.QWERTY, 3).isEmpty())
        assertTrue(glide.decode(path("wqiy"), GlideLayout.QWERTY, 0).isEmpty())
        assertTrue(TableGlide(dictionary(), { 0f }).decode(path("wqiy"), GlideLayout.QWERTY, 3).isEmpty())
    }

    @Test
    fun anEntrysScoreKeptTillTheVersionChanges() {
        var calls = 0
        var version = 0
        val counted = TableGlide(dictionary("wqiy 你", "vbg 好", "vbgf 妤", "vv 奻", "vv 女"), { calls++; scores.getValue(it) }, version = { version })
        counted.decode(path("wqiy"), GlideLayout.QWERTY, 3)
        val once = calls
        assertTrue(once > 0)
        counted.decode(path("wqiy"), GlideLayout.QWERTY, 3)
        assertEquals(once, calls)
        version++
        counted.decode(path("wqiy"), GlideLayout.QWERTY, 3)
        assertEquals(2 * once, calls)
    }

    @Test
    fun aLearnedWordsScoreEachGlideTheRestOnce() {
        val asked = HashMap<String, Int>()
        val now = scores.toMutableMap()
        val counted = TableGlide(
            dictionary("wqiy 你", "vbg 好", "vbgf 妤", "vv 奻", "vv 女"),
            { asked.merge(it, 1, Int::plus); now.getValue(it) },
            learned = { it == "妤" },
        )
        val first = counted.decode(path("vbgf"), GlideLayout.QWERTY, 3).first { it.word == "妤" }
        val once = HashMap(asked)
        now["妤"] = -7f
        val again = counted.decode(path("vbgf"), GlideLayout.QWERTY, 3).first { it.word == "妤" }
        // the learned word's score as now, the rest not asked again
        assertEquals(2 * once.getValue("妤"), asked.getValue("妤"))
        assertEquals(once - "妤", asked - "妤")
        assertEquals(-1f, again.score - first.score, 1e-4f)
    }

    @Test
    fun aBlockedEntryIsNotItsCodes() {
        val blocked = TableGlide(dictionary("wqiy 你", "vbg 好", "vbgf 妤", "vb 奻", "vb 女"), { scores.getValue(it) }, blocked = { it == "女" })
        val unblocked = TableGlide(dictionary("wqiy 你", "vbg 好", "vbgf 妤", "vb 奻", "vb 女"), { scores.getValue(it) })
        assertEquals("女", unblocked.decode(path("vb"), GlideLayout.QWERTY, 3).first { it.keys == "vb" }.word)
        // vb by its next entry, not dropped
        assertEquals("奻", blocked.decode(path("vb"), GlideLayout.QWERTY, 10).first { it.keys == "vb" }.word)
        val none = TableGlide(dictionary("wqiy 你", "vb 女"), { scores.getValue(it) }, blocked = { it == "女" })
        assertTrue(none.decode(path("vb"), GlideLayout.QWERTY, 3).none { it.keys == "vb" })
    }
}
