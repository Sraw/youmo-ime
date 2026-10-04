/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExamplesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun theFirstDifferentSentencesOfEachWordAreTaken() {
        val examples = Examples(listOf("吗喽", "乘风破浪的姐姐", "尬聊", "佛"), perWord = 2)
        examples.page("今天又是吗喽的一天。今天又是吗喽的一天。你看乘风破浪的姐姐了吗？")
        examples.page("打工人都是吗喽啊！再来一个吗喽也不要了\n短吗喽。")
        val found = examples.sentences()
        // the same sentence once, the end kept; past two no more; under six characters none
        assertEquals(listOf("今天又是吗喽的一天。", "打工人都是吗喽啊！"), found["吗喽"])
        // longer than four characters
        assertEquals(listOf("你看乘风破浪的姐姐了吗？"), found["乘风破浪的姐姐"])
        assertNull(found["尬聊"])
        // a single character is no word to look for
        assertNull(found["佛"])
    }

    @Test
    fun aWordAtEitherEndOfASentenceIsFound() {
        val examples = Examples(listOf("尬聊", "佛系", "一个"))
        examples.page("尬聊真的很难受啊；我就是这么佛系")
        assertEquals(listOf("尬聊真的很难受啊；"), examples.sentences()["尬聊"])
        assertEquals(listOf("我就是这么佛系"), examples.sentences()["佛系"])
        // a sentence over 80 characters is none
        examples.page("一个" + "很".repeat(79) + "。")
        assertNull(examples.sentences()["一个"])
    }

    @Test
    fun theCommandWritesTheListsWordsInItsOrder() {
        val shard = tmp.root.resolve("s.parquet")
        WebText.write(shard, listOf(WebText.Page("https://a/", "你们都是吗喽吧。最近流行尬聊\t吗？", "")))
        val list = tmp.root.resolve("words.txt").apply { writeText("# words\n尬聊\t2\n吗喽\n没有\n") }
        val out = tmp.root.resolve("out.tsv")
        val log = StringBuilder()
        assertEquals(0, runCli(arrayOf("examples", "-o", out.path, "--only", list.path, shard.path), log, StringBuilder()))
        assertEquals("尬聊\t最近流行尬聊 吗？\n吗喽\t你们都是吗喽吧。\n", out.readText())
        assertEquals("examples: 2 of 3 words found in 1 pages\n", log.toString())
        // the list is wanted
        assertEquals(2, runCli(arrayOf("examples", "-o", out.path, shard.path), StringBuilder(), StringBuilder()))
        File(out.path).delete()
    }
}
