/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream

class MainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, text: String) = tmp.newFile(name).apply { writeText(text.trimIndent()) }.path

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        return Triple(runCli(arrayOf(*args), out, err), out.toString(), err.toString())
    }

    @Test
    fun pinyinDataIsCompiledAndChecksOutAgainstItsModel() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val dict = file("dict.txt", "你\tni\t0\n好 hao\n你好 ni'hao\n嗯 xyz\n")
        val output = tmp.root.resolve("pinyin.data").path
        val (code, out, err) = run("pinyin", "-o", output, "--lm", lm, dict)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("model: 3 / 1 / 1 n-grams"))
        assertTrue(out, out.contains("dictionary: 3 readings"))
        assertTrue(out, out.contains("skipped 1 readings with unknown syllables: xyz×1"))
        assertTrue(out, out.contains("vocabulary: 4 words, 3 of them in the model"))
        val data = PinyinData.load(ByteBuffer.wrap(tmp.root.resolve("pinyin.data").readBytes()))
        assertEquals("lm.arpa,dict.txt", data.meta["source"])

        val (checkCode, checkOut, _) = run("check", output, lm)
        assertEquals(0, checkCode)
        assertTrue(checkOut, checkOut.contains("1-grams: 3, probability max error 0.0000, mean 0.00000, backoff max error 0.0000"))
        assertTrue(checkOut, checkOut.contains("2-grams: 1, probability max error 0.0000, mean 0.00000, backoff max error 0.0000"))
        assertTrue(checkOut, checkOut.contains("3-grams: 1, probability max error 0.0000, mean 0.00000\n"))
    }

    @Test
    fun rimeWordsGoInTheirOwnLayerAndThoseTheModelLacksAreScoredByTheirCounts() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val dict = file("dict.txt", "你\tni\t0\n好 hao\n")
        // 你 and 好 fit log10 P = -2 + 0.5 log10(c + 1) exactly; 耗子 (9999) then comes out at 0
        val rime = file("x.dict.yaml", "---\nname: x\n...\n你\tnǐ\t99\n好\thǎo\t9\n耗子\thào zi\t9999\n号\thào\t9\n")
        val output = tmp.root.resolve("pinyin.data").path
        val (code, out, err) = run("pinyin", "-o", output, "--lm", lm, "--rime-unigrams", "10", dict, rime)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("rime: 4 words\n"))
        assertTrue(out, out.contains("rime: 1 words the model lacks scored by their counts, log10 P = -2.000 + 0.500 log10(count + 1)"))
        assertTrue(out, out.contains("vocabulary: 5 words, 4 of them in the model"))
        val data = PinyinData.load(ByteBuffer.wrap(tmp.root.resolve("pinyin.data").readBytes()))
        assertEquals(listOf("base", "wanxiang"), data.layers.names)
        val id = { word: String -> (0 until data.vocabulary.size).first { data.vocabulary.word(it) == word } }
        assertEquals(0, data.layers.layer(id("你")))
        assertEquals(1, data.layers.layer(id("耗子")))
        assertEquals(1, data.layers.layer(id("号")))
        assertEquals(0f, data.model.score(-1, -1, id("耗子")), 1e-6f)
        // under the count asked for: scored as the unknown word still
        assertEquals(-5f, data.model.score(-1, -1, id("号")), 1e-6f)
        assertEquals(2, run("pinyin", "-o", output, "--lm", lm, "--rime-unigrams", "-1", dict, rime).first)
    }

    @Test
    fun newWordsAreFoundAndPackedScoredOnTheModelsScale() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val dict = file("dict.txt", "你\tni\t0\n好 hao\n你好 ni'hao\n耗 hao\n子 zi\n")
        // 耗子 in the Rime layer: not a base word, so a candidate all the same
        val rime = file("x.dict.yaml", "---\nname: x\n...\n耗子\thào zi\t9\n")
        val output = tmp.root.resolve("pinyin.data").path
        assertEquals(0, run("pinyin", "-o", output, "--lm", lm, dict, rime).first)
        val candidates = tmp.root.resolve("candidates.tsv").path
        // the test corpus is a parquet shard: WebTextTest covers reading one; here the candidates are written
        File(candidates).writeText(
            "# text\tcount\tyear\tpmi\tleft_entropy\tright_entropy\tsurprise\tknown\n# chars\t1000\n" +
                "你\t99\t0\t0.000\t3.000\t3.000\t0.000\t1\n好\t9\t0\t0.000\t3.000\t3.000\t0.000\t1\n" +
                "你好\t99\t2020\t2.000\t3.000\t3.000\t0.300\t1\n好你\t99\t2023\t2.000\t2.000\t2.000\t1.500\t0\n" +
                "耗子\t9\t2024\t2.000\t2.000\t2.000\t1.500\t0\n好好\t99\t2023\t0.500\t2.000\t2.000\t1.500\t0\n" +
                "你你\t99\t2023\t2.000\t1.000\t2.000\t1.500\t0\n嗯好\t99\t2023\t2.000\t2.000\t2.000\t1.500\t0\n" +
                "好你好\t99\t2023\t2.000\t2.000\t2.000\t0.500\t0\n",
        )
        val pack = tmp.root.resolve("new.words").path
        val (code, out, err) = run("pack", "-o", pack, "--data", output, "--layer", "2026q3", "--min-count", "5", candidates)
        assertEquals(err, 0, code)
        // 你 (99) and 好 (9) fit log10 P = -2 + 0.5 log10(c + 1) exactly; 你好, which the model lacks, does not count
        assertTrue(out, out.contains("fit: log10 P = -2.000 + 0.500 log10(count + 1), over 2 words"))
        // 好好 held together too little, 你你 too few different neighbours, 好你好 no more frequent than
        // the model says of 好 你好, 嗯 has no reading
        assertTrue(out, out.contains("pack: 2 words, 1 left out for want of a reading"))
        assertEquals(
            "# youmo words 1\n# layer: 2026q3\n好你\thao'ni\t-1.000\n耗子\thao'zi\t-1.500\n",
            File(pack).readText(),
        )
        assertEquals(2, run("pack", "-o", pack, "--data", output, "--layer", "bad name", candidates).first)
        assertEquals(2, run("pack", "-o", pack, "--data", output, candidates).first)
        assertEquals(2, run("words", "-o", candidates, output).first)
    }

    @Test
    fun aModelIsMixedWithChatAndCompiles() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val chat = tmp.root.resolve("chat.jsonl.gz")
        GZIPOutputStream(chat.outputStream()).bufferedWriter().use {
            it.write("[\"你 好\", \"好 好\"]\n\n[\"你 x 好 你\"]\n")
        }
        val mixed = tmp.root.resolve("mixed.arpa").path
        val (code, out, err) = run("mix", "-o", mixed, "--lm", lm, "--weight", "0.5", "--cutoffs", "1,1", chat.path)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("model: 3 / 1 / 1 n-grams"))
        // each line its own start, split at the x: 你 好 | 好 好 | 你 | 好 你
        assertTrue(out, out.contains("chat: 2 documents, 7 words, 5 bigrams, 3 trigrams"))
        // the model's 你 好 and the chat's 好 好 and 好 你; the chat's trigrams all begin at a start
        assertTrue(out, out.contains("mixed: 3 / 3 / 1 n-grams"))
        val dict = file("dict.txt", "你 ni\n好 hao\n")
        assertEquals(0, run("pinyin", "-o", tmp.root.resolve("p.data").path, "--lm", mixed, dict).first)

        for (bad in listOf(listOf("--cutoffs", "2"), listOf("--cutoffs", "3,2"), listOf("--weight", "2"), listOf("--weight"))) {
            assertEquals(bad.toString(), 2, run(*(listOf("mix", "-o", mixed, "--lm", lm, chat.path) + bad).toTypedArray()).first)
        }
    }

    @Test
    fun aCodeTableIsCompiled() {
        val table = file("t.txt", TABLE)
        val output = tmp.root.resolve("t.data").path
        val (code, out, _) = run("table", "-o", output, table)
        assertEquals(0, code)
        assertTrue(out, out.contains("table: 4 entries, 1 phrases coded by the rules"))
        assertTrue(out, out.contains("1 phrases the rules cannot code, left out"))
        assertTrue(out, out.contains("1 entries use characters outside 键码, e.g. a;b 怪"))
        assertEquals(5, CodeTable.load(ByteBuffer.wrap(tmp.root.resolve("t.data").readBytes())).size)
    }

    @Test
    fun badSourcesExitWithTheirLocation() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val dict = file("dict.txt", "你\n")
        val (code, _, err) = run("pinyin", "-o", tmp.root.resolve("x").path, "--lm", lm, dict)
        assertEquals(1, code)
        assertTrue(err, err.startsWith("$dict:1: "))
    }

    @Test
    fun inconsistentModelsAndMissingFilesExitWithAMessage() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA.replace("-5.0\t<unk>", "-5.0\t<s>"))
        val dict = file("dict.txt", "你 ni\n")
        val (code, _, err) = run("pinyin", "-o", tmp.root.resolve("x").path, "--lm", lm, dict)
        assertEquals(1, code)
        assertEquals("the model has no <unk>\n", err)
        val (missing, _, missingErr) = run("table", "-o", tmp.root.resolve("y").path, tmp.root.resolve("nope.txt").path)
        assertEquals(1, missing)
        assertTrue(missingErr, missingErr.contains("nope.txt"))
    }

    @Test
    fun checkingSomethingOtherThanPinyinDataExitsWithAMessage() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val table = tmp.root.resolve("t.data").path
        assertEquals(0, run("table", "-o", table, file("t.txt", TABLE)).first)
        val (code, _, err) = run("check", table, lm)
        assertEquals(1, code)
        assertEquals("file kind 2, expected 1\n", err)
    }

    @Test
    fun badArgumentsPrintUsage() {
        listOf(
            emptyArray<String>(),
            arrayOf("pinyin", "-o", "x", "d.txt"),
            arrayOf("table", "-o", "x"),
            arrayOf("table", "t.txt"),
            arrayOf("check", "x"),
            arrayOf("frobnicate", "x"),
        ).forEach { args ->
            val (code, _, err) = run(*args)
            assertEquals(args.toList().toString(), 2, code)
            assertEquals(USAGE + "\n", err)
        }
    }

    private companion object {
        const val TABLE = """
            键码=abc
            码长=4
            [组词规则]
            e2=p11+p12+p21+p22
            [数据]
            ab 工
            ab 式
            a;b 怪
            ca 作
            [词组]
            工作
            工无
        """
    }
}
