/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.ListedItem
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.stroke.StrokeLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A character found by its strokes (u then h s p n z), blocked under the strokes typed alone, as a table's under its code. */
class EnginesStrokeBlockTest : EnginesFixture() {

    private fun Engines.found(strokes: String): List<String> {
        onEvent(PINYIN, EngineEvent.RESET, 0)
        return type(PINYIN, "u$strokes").candidates
    }

    private fun Engines.listed() = (learning(null, LearningOp.ListItems(LearningOp.Listed.TABLE_BLOCKS)) as LearningResult.Listed).items

    @Test
    fun aCharacterBlockedUnderItsStrokesIsShownAgainFromTheSettings() {
        val dir = MemoryFiles()
        Engines(::load, dir, warmUps = inPlace).use { e ->
            val at = e.found("zp").indexOf("好")
            assertTrue(at >= 0)
            val menu = e.learning(PINYIN, LearningOp.Menu(at, "好")) as LearningResult.Menu
            assertEquals(listOf(MenuItem.BLOCK), menu.items)
            assertEquals(BlockScope.UNDER_CODE, menu.scope)
            val done = e.learning(PINYIN, LearningOp.Block(at, "好")) as LearningResult.Done
            assertFalse("好" in e.candidates(PINYIN, 0, 9).map { it.text })
            // taken back on the spot, then blocked again
            e.learning(PINYIN, LearningOp.Undo(done.undo))
            assertTrue("好" in e.found("zp"))
            e.onEvent(PINYIN, EngineEvent.BLOCK, e.found("zp").indexOf("好"))
            assertFalse("好" in e.found("zp"))
            // under those strokes alone: more of them, and its pinyin, still find it
            assertTrue("好" in e.found("zph"))
            e.onEvent(PINYIN, EngineEvent.RESET, 0)
            assertTrue("好" in e.type(PINYIN, "hao").candidates)
        }
        // kept in the learning record, listed with the tables' blocks
        Engines(::load, dir, warmUps = inPlace).use { e ->
            assertFalse("好" in e.found("zp"))
            val listed = e.listed()
            assertEquals(listOf(ListedItem(Code(Kinds.table(StrokeLookup.BLOCK_TABLE), "zp"), "", "好", blocked = true)), listed)
            e.learning(null, LearningOp.RemoveItems(listed))
            assertEquals(emptyList<ListedItem>(), e.listed())
            assertTrue("好" in e.found("zp"))
        }
    }
}
