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
}
