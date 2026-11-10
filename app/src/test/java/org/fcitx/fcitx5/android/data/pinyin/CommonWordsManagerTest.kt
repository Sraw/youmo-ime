/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.engine.phrase.CommonWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CommonWordsManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun words(text: String) = CommonWords.parse(text).words

    @Test
    fun noFileIsNoWord() {
        assertTrue(CommonWordsManager.load(File(folder.root, "commonwords.txt")).isEmpty)
    }

    @Test
    fun whatIsSavedLoadsBackAsAnExportReads() {
        val file = File(folder.root, "pinyin/commonwords.txt")
        val mine = words("米字旁[mi'zi'pang] 米 糸\n中国[zhong'guo] 华\n")
        assertEquals(mine.words, CommonWordsManager.saveOver(CommonWords.EMPTY, mine, file).words)
        assertEquals("# youmo-common-words 1\n米字旁[mi'zi'pang]\t米\t糸\n中国[zhong'guo]\t华\n", file.readText())
        assertEquals(mine.words, CommonWordsManager.load(file).words)
        assertEquals(listOf("commonwords.txt"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun whatTheKeyboardDeletedMeanwhileStaysDeletedWhenThePageSaves() {
        val file = File(folder.root, "commonwords.txt")
        val base = words("米字旁[mi'zi'pang] 米 糸\n中国[zhong'guo] 华\n")
        CommonWordsManager.write(base, file)
        // the keyboard took 米 off; the page added 木字旁
        CommonWordsManager.write(base.without("米字旁", "米"), file)
        val page = words("米字旁[mi'zi'pang] 米 糸\n中国[zhong'guo] 华\n木字旁[mu'zi'pang] 木\n")
        val saved = CommonWordsManager.saveOver(base, page, file)
        assertEquals(listOf("米字旁[mi'zi'pang] 糸", "中国[zhong'guo] 华", "木字旁[mu'zi'pang] 木"), saved.words.map { it.toString() })
        assertEquals(saved.words, CommonWordsManager.load(file).words)
    }
}
