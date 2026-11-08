/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pinyin glide's other reading's word (after the first candidate), blocked from a long press as it reads. */
class EnginesGlideBlockTest : EnginesFixture() {

    init {
        // niao likelier than ni'hao: a path over n-i-h-a-o reads 鸟 first, 你好 another reading's
        pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("鸟", -2f, 0f)
            .unigram("你好", -3f, 0f)
            .unigram("好", -2.5f, 0f)
            .unigram("你", -2f, 0f)
            .entry("鸟", intArrayOf(Syllables.id("niao")))
            .entry("你好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
            .entry("好", intArrayOf(Syllables.id("hao")))
            .entry("你", intArrayOf(Syllables.id("ni")))
            .build().toByteArray()
    }

    private fun Engines.glided(keys: String): List<String> {
        onEvent(PINYIN, EngineEvent.RESET, 0)
        val path = Glide(keys.flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray(), GlideLayout.QWERTY)
        return onEvent(PINYIN, EngineEvent.CHAR, Keyboard.GLIDE, payload = checkNotNull(glide(PINYIN, path))).candidates
    }

    private val blocked = UserWord("你好", "ni hao", UserWord.Kind.BLOCKED)

    @Test
    fun anotherReadingsWordIsBlockedAsItReadsTillUnblocked() {
        val dir = MemoryFiles()
        Engines(::load, dir, warmUps = inPlace).use { e ->
            val shown = e.glided("nihao")
            assertEquals(listOf("鸟", "你好"), shown.take(2))
            val menu = e.learning(PINYIN, LearningOp.Menu(1, "你好")) as LearningResult.Menu
            assertEquals(listOf(MenuItem.BLOCK), menu.items)
            assertEquals(BlockScope.EVERYWHERE, menu.scope)
            val done = e.learning(PINYIN, LearningOp.Block(1, "你好")) as LearningResult.Done
            assertFalse("你好" in e.candidates(PINYIN, 0, 9).map { it.text })
            assertEquals(listOf(blocked), e.userWords())
            e.learning(PINYIN, LearningOp.Undo(done.undo))
            assertEquals(emptyList<UserWord>(), e.userWords())
            assertTrue("你好" in e.glided("nihao"))
            e.onEvent(PINYIN, EngineEvent.BLOCK, 1)
            assertFalse("你好" in e.glided("nihao"))
        }
        Engines(::load, dir, warmUps = inPlace).use { e ->
            assertFalse("你好" in e.glided("nihao"))
            e.removeWord(blocked)
            assertTrue("你好" in e.glided("nihao"))
        }
    }
}
