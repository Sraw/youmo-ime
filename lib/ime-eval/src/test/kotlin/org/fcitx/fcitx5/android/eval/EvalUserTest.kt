/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

/** [EvalUser]: the eval's user learns as the app's does, its typing days moved by the eval alone. */
class EvalUserTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("你好", -3f, 0f)
                .entry("你", syl("ni"))
                .entry("好", syl("hao"))
                .entry("你好", syl("ni", "hao"))
                .build().toByteArray(),
        ),
    )

    private fun nihao() = listOf(Row(Row.PICK or Row.WHOLE, Code(Kinds.FULL, "nihao"), "", Words.of("你好", "ni hao"), 'A', Row.DICTIONARY, ""))

    @Test
    fun aTypingDayPassesOnlyWhenTheEvalSays() {
        val user = EvalUser(data)
        user.habits.record(nihao())
        val day = user.log.state.day
        user.habits.record(nihao())
        assertEquals(day, user.log.state.day)
        user.nextDay()
        user.habits.record(nihao())
        assertEquals(day + 1, user.log.state.day)
    }

    @Test
    fun whatIsCommittedIsTheDecodersCounts() {
        val user = EvalUser(data)
        repeat(2) { user.habits.record(nihao()) }
        assertEquals(2f, user.model.count(user.model.known(UserModel.Entry("你好", syl("ni", "hao")))), 0f)
    }

    // learn-ux's seed: typed into a file once, read back by each scenario's user
    @Test
    fun aUserOfAFileReadsWhatAnotherKept() {
        val file = tmp.root.resolve(LearnLog.FILE)
        EvalUser(data, file).apply { habits.record(nihao()) }.log.close()
        val again = EvalUser(data, file)
        assertTrue("你好" in again.log.state.wordUses)
        assertEquals(1f, again.model.count(again.model.known(UserModel.Entry("你好", syl("ni", "hao")))), 0f)
    }
}
