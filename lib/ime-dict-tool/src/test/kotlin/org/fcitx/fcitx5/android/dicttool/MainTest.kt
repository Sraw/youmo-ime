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
    fun aPackedWordIsReadAsTheDictionarysWordsInIt() {
        val lm = file(
            "lm.arpa",
            ReadersTest.TINY_ARPA.replace("ngram 1=3", "ngram 1=7").replace("-1.5\t好", "-1.5\t好\n-3.0\t阿\n-3.0\t谁\n-3.0\t长\n-3.0\t大"),
        )
        // 长 alone is likelier chang, in 长大 zhang; 阿谁's two readings weigh the same, and 谁 alone is shui
        val dict = file("dict.txt", "你 ni\n好 hao\n阿 a\n谁 shui\n长 chang 0\n长 zhang -1\n大 da\n长大 zhang'da\n阿谁 a'shei\n阿谁 a'shui\n")
        val output = tmp.root.resolve("pinyin.data").path
        assertEquals(0, run("pinyin", "-o", output, "--lm", lm, dict).first)
        val candidates = tmp.root.resolve("candidates.tsv").path
        File(candidates).writeText(
            "# text\tcount\tyear\tpmi\tleft_entropy\tright_entropy\tsurprise\tknown\n# chars\t1000\n" +
                "你\t99\t0\t0.000\t3.000\t3.000\t0.000\t1\n好\t9\t0\t0.000\t3.000\t3.000\t0.000\t1\n" +
                "长大谁\t20\t2024\t2.000\t2.000\t2.000\t1.500\t0\n阿谁\t20\t2024\t2.000\t2.000\t2.000\t1.500\t0\n" +
                "长谁\t20\t2024\t2.000\t2.000\t2.000\t1.500\t0\n",
        )
        val pack = tmp.root.resolve("p.words").path
        assertEquals(0, run("pack", "-o", pack, "--data", output, "--layer", "new", "--min-count", "5", candidates).first)
        assertEquals(
            listOf("长大谁\tzhang'da'shui", "阿谁\ta'shui", "长谁\tchang'shui"),
            File(pack).readLines().drop(2).map { it.substringBeforeLast('\t') },
        )
    }

    @Test
    fun newWordsAreFoundAndPackedScoredOnTheModelsScale() {
        // the model has the characters of the words to pack; 嗯 it has not, 丂 all but not
        val lm = file(
            "lm.arpa",
            ReadersTest.TINY_ARPA.replace("ngram 1=3", "ngram 1=7").replace("-1.5\t好", "-1.5\t好\n-3.0\t耗\n-3.0\t子\n-2.0\t了\n-7.0\t丂"),
        )
        val dict = file("dict.txt", "你\tni\t0\n好 hao\n你好 ni'hao\n耗 hao\n子 zi\n了 le\n丂 kao\n你耗 ni'hao\n好耗 hao'hao\n")
        // 耗子 in the Rime layer: not a base word, so a candidate all the same; 子了 read as no
        // character of it alone is likeliest
        val rime = file("x.dict.yaml", "---\nname: x\n...\n耗子\thào zi\t9\n子了\tzǐ liǎo\t9\n")
        val output = tmp.root.resolve("pinyin.data").path
        assertEquals(0, run("pinyin", "-o", output, "--lm", lm, dict, rime).first)
        val candidates = tmp.root.resolve("candidates.tsv").path
        // the test corpus is a parquet shard: WebTextTest covers reading one; here the candidates are written
        File(candidates).writeText(
            "# text\tcount\tyear\tpmi\tleft_entropy\tright_entropy\tsurprise\tknown\n# chars\t1000\n" +
                "你\t99\t0\t0.000\t3.000\t3.000\t0.000\t1\n好\t9\t0\t0.000\t3.000\t3.000\t0.000\t1\n" +
                "你好\t99\t2020\t2.000\t3.000\t3.000\t0.300\t1\n耗好\t99\t2023\t2.000\t2.000\t2.000\t1.500\t0\n" +
                "耗子\t20\t2024\t2.000\t2.000\t2.000\t1.500\t0\n好好\t99\t2023\t0.500\t2.000\t2.000\t1.500\t0\n" +
                "你你\t99\t2023\t2.000\t1.000\t2.000\t1.500\t0\n嗯好\t99\t2023\t2.000\t2.000\t2.000\t1.500\t0\n" +
                "好你好\t30\t2023\t2.000\t2.000\t2.000\t0.300\t0\n丂子\t99\t2023\t2.000\t2.000\t2.000\t1.500\t0\n" +
                // a phrase (的 inside), a fragment (子好 is mostly 耗子好, which 1.0 entropy keeps out), a verb with 了 (typed as one)
                "你的好\t99\t2023\t2.000\t2.000\t2.000\t1.500\t0\n子好\t15\t2023\t2.000\t2.000\t2.000\t1.500\t0\n" +
                "耗子好\t9\t2023\t2.000\t1.000\t2.000\t1.500\t0\n好了\t99\t2023\t2.000\t2.000\t2.000\t1.500\t0\n" +
                // too low a pmi for its neighbours to be gathered
                "子了\t99\t2023\t0.300\tNaN\tNaN\t1.500\t0\n" +
                // a fragment of words the dictionary has, 你耗 and 好耗: neither run is half of 耗了 (20),
                // both together are
                "耗了\t20\t2023\t2.000\t2.000\t2.000\t1.500\t0\n你耗了\t6\t2023\t0.500\t2.000\t2.000\t1.500\t0\n" +
                "好耗了\t6\t2023\t0.500\t2.000\t2.000\t1.500\t0\n",
        )
        val pack = tmp.root.resolve("new.words").path
        val (code, out, err) = run("pack", "-o", pack, "--data", output, "--layer", "2026q3", "--min-count", "5", "--min-surprise", "0.4", candidates)
        assertEquals(err, 0, code)
        // 你 (99) and 好 (9) fit log10 P = -2 + 0.5 log10(c + 1) exactly; 你好, which the model lacks, does not count
        assertTrue(out, out.contains("fit: log10 P = -2.000 + 0.500 log10(count + 1), over 2 words"))
        // 好好 held together too little, 你你 too few different neighbours, 好你好 no more frequent than
        // the model says of 好 你好, 嗯 and 丂 the model has not got (嗯 no reading either)
        assertTrue(out, out.contains("pack: 3 words, 2 left out for a character the model hardly has, 0 for want of a reading"))
        assertEquals(
            "# youmo words 1\n# layer: 2026q3\n耗好\thao'hao\t-1.000\n耗子\thao'zi\t-1.339\n好了\thao'le\t-1.000\n",
            File(pack).readText(),
        )
        // with a list of the words: what is on it, the pmi and entropy asked for being low; an entropy not
        // measured no bar; 子了 read as the dictionary has the word, not as its characters alone
        val only = tmp.root.resolve("titles.txt").apply { writeText("# titles\n耗子\n好好\tzhwiki\n子了\n耗好\n# 好了\n") }.path
        assertEquals(0, run("pack", "-o", pack, "--data", output, "--layer", "wiki", "--min-count", "5", "--min-pmi", "0", "--min-entropy", "1", "--only", only, candidates).first)
        assertEquals(
            "# youmo words 1\n# layer: wiki\n耗好\thao'hao\t-1.000\n耗子\thao'zi\t-1.339\n好好\thao'hao\t-1.000\n子了\tzi'liao\t-1.000\n",
            File(pack).readText(),
        )
        // a curated list: its words alone, as it reads them, whatever the thresholds; one the
        // candidates lack scored as the least count (5: -2 + 0.5 log10 6)
        val lexicon = tmp.root.resolve("add.tsv").apply { writeText("# word\treading\n好好\thao'hao\tword\n你的好\tni'di'hao\n好你\thao'ni\n") }.path
        val (curated, curatedOut, _) = run("pack", "-o", pack, "--data", output, "--layer", "new", "--min-count", "5", "--lexicon", lexicon, candidates)
        assertEquals(0, curated)
        assertTrue(curatedOut, curatedOut.contains("pack: 1 of the list's words not among the candidates, scored as seen 5 times"))
        assertEquals(
            "# youmo words 1\n# layer: new\n好好\thao'hao\t-1.000\n你的好\tni'di'hao\t-1.000\n好你\thao'ni\t-1.611\n",
            File(pack).readText(),
        )
        // a reading the engine cannot type, or of the wrong length
        File(lexicon).writeText("好好\thao'hoa\n")
        assertEquals(1, run("pack", "-o", pack, "--data", output, "--layer", "new", "--lexicon", lexicon, candidates).first)
        File(lexicon).writeText("好好\thao\n")
        assertEquals(1, run("pack", "-o", pack, "--data", output, "--layer", "new", "--lexicon", lexicon, candidates).first)
        assertEquals(2, run("pack", "-o", pack, "--data", output, "--layer", "bad name", candidates).first)
        assertEquals(2, run("pack", "-o", pack, "--data", output, candidates).first)
        assertEquals(2, run("words", "-o", candidates, output).first)
    }

    @Test
    fun cleanTakesAWiderSketchForMoreCrawls() {
        val shard = tmp.root.resolve("CC-MAIN-2026-39-0-1.parquet")
        val prose = "这是一段足够长的中文句子，用来测试清洗之后页面仍然留下。".repeat(10)
        WebText.write(shard, listOf(WebText.Page("https://a/", prose, "")))
        val out = tmp.root.resolve("clean").path
        assertEquals(0, run("clean", "-o", out, "--sketch-bits", "20", shard.path).first)
        var kept = 0
        WebText.pages(File(out, shard.name)) { kept++ }
        assertEquals(1, kept)
        // past what an array holds, or a bad value
        assertEquals(2, run("clean", "-o", out, "--sketch-bits", "31", shard.path).first)
        assertEquals(2, run("clean", "-o", out, "--sketch-bits", "x", shard.path).first)
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
