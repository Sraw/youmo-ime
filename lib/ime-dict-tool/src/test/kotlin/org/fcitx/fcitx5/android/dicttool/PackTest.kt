/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `words` and `pack` as engine-data.sh runs them, a test an option. */
class PackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, text: String) = tmp.newFile(name).apply { writeText(text.trimIndent()) }.path

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        return Triple(runCli(arrayOf(*args), out, err), out.toString(), err.toString())
    }

    /** pinyin.data of a model that has the characters of the words to pack (嗯 it has not, 丂 all but not), and [packs]. */
    private fun data(vararg packs: String): String {
        val lm = file(
            "lm.arpa",
            ReadersTest.TINY_ARPA.replace("ngram 1=3", "ngram 1=7").replace("-1.5\t好", "-1.5\t好\n-3.0\t耗\n-3.0\t子\n-2.0\t了\n-7.0\t丂"),
        )
        // 子了 is the dictionary's, read as it has it
        val dict = file("dict.txt", "你\tni\t0\n好 hao\n你好 ni'hao\n耗 hao\n子 zi\n了 le\n丂 kao\n你耗 ni'hao\n好耗 hao'hao\n子了 zi'liao\n")
        val output = tmp.root.resolve("pinyin.data").path
        assertEquals(0, run("pinyin", "-o", output, "--lm", lm, dict, *packs).first)
        return output
    }

    /** What `words` would have found: the test corpus is a parquet shard, which WebTextTest covers reading. */
    private fun candidates(): String = tmp.root.resolve("candidates.tsv").apply {
        writeText(
            "$HEADER\n# chars\t1000\n" +
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
                // a fragment of words the dictionary has: neither run is half of 耗了, both together are
                "耗了\t20\t2023\t2.000\t2.000\t2.000\t1.500\t0\n你耗了\t6\t2023\t0.500\t2.000\t2.000\t1.500\t0\n" +
                "好耗了\t6\t2023\t0.500\t2.000\t2.000\t1.500\t0\n",
        )
    }.path

    private fun passing(data: String): Triple<Int, String, String> =
        run("pack", "-o", tmp.root.resolve("new.words").path, "--data", data, "--layer", "2026q3", "--min-count", "5", "--min-surprise", "0.4", candidates())

    @Test
    fun theCandidatesThatPassArePackedScoredOnTheModelsScale() {
        val (code, out, err) = passing(data())
        assertEquals(err, 0, code)
        // 你 (99) and 好 (9) fit log10 P = -2 + 0.5 log10(c + 1) exactly; 你好, which the model lacks, does not count
        assertTrue(out, out.contains(FIT))
        // each left out for its own reason: pmi, entropy, too few neighbours, no surprise, no model word
        assertTrue(out, out.contains("pack: 4 words, 2 left out for a character the model hardly has, 0 for want of a reading"))
        assertEquals(PASSED, tmp.root.resolve("new.words").readText())
    }

    @Test
    fun aPacksWordInTheDataIsNoneOfTheFitAndStaysACandidate() {
        // 耗好 the data has from a pack, scored by an earlier fit: no score of the model's own
        val (code, out, err) = passing(data(file("earlier.words", "# youmo words 1\n# layer: new\n耗好\thao'hao\t-4.000\n")))
        assertEquals(err, 0, code)
        assertTrue(out, out.contains(FIT))
        assertEquals(PASSED, tmp.root.resolve("new.words").readText())
    }

    @Test
    fun aListOfWordsTakesWhatIsOnIt() {
        // the pmi and entropy asked for being low; 子了 read as the dictionary has the word
        val only = tmp.root.resolve("titles.txt").apply { writeText("# titles\n耗子\n好好\tzhwiki\n子了\n耗好\n# 好了\n") }.path
        val pack = tmp.root.resolve("wiki.words")
        val (code, _, err) = run("pack", "-o", pack.path, "--data", data(), "--layer", "wiki", "--min-count", "5", "--min-pmi", "0", "--min-entropy", "1", "--only", only, candidates())
        assertEquals(err, 0, code)
        assertEquals("# youmo words 1\n# layer: wiki\n耗好\thao'hao\t-1.000\n耗子\thao'zi\t-1.339\n好好\thao'hao\t-1.000\n子了\tzi'liao\t-1.000\n", pack.readText())
    }

    @Test
    fun aCuratedListTakesItsWordsAsItReadsThem() {
        // its words alone whatever the thresholds; one the candidates lack scored as the least count
        val lexicon = tmp.root.resolve("add.tsv").apply { writeText("# word\treading\n好好\thao'hao\tword\n你的好\tni'di'hao\n好你\thao'ni\n") }.path
        val pack = tmp.root.resolve("new.words")
        val (code, out, err) = run("pack", "-o", pack.path, "--data", data(), "--layer", "new", "--min-count", "5", "--lexicon", lexicon, candidates())
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("pack: 1 of the list's words not among the candidates, scored as seen 5 times\npack: 3 words\n"))
        assertEquals("# youmo words 1\n# layer: new\n好好\thao'hao\t-1.000\n你的好\tni'di'hao\t-1.000\n好你\thao'ni\t-1.611\n", pack.readText())
    }

    @Test
    fun aCuratedReadingTheEngineCannotTypeLeavesThePackAsItWas() {
        val data = data()
        val candidates = candidates()
        val pack = tmp.root.resolve("new.words").apply { writeText("# youmo words 1\n# layer: new\n") }
        val lexicon = tmp.root.resolve("add.tsv")
        // a syllable no one types, a syllable short
        for (reading in listOf("hao'hoa", "hao")) {
            lexicon.writeText("# word\treading\n好好\t$reading\n")
            val (code, _, err) = run("pack", "-o", pack.path, "--data", data, "--layer", "new", "--lexicon", lexicon.path, candidates)
            assertEquals(reading, 1, code)
            assertTrue(err, err.startsWith("${lexicon.path}:2: ") && err.contains("is no reading of 好好"))
            assertEquals("# youmo words 1\n# layer: new\n", pack.readText())
        }
    }

    @Test
    fun candidatesOfAnotherLayoutAreAnErrorAtTheirLine() {
        val data = data()
        val candidates = tmp.root.resolve("candidates.tsv")
        val pack = tmp.root.resolve("new.words").path
        // columns other than those read: an older words's, say
        candidates.writeText("# text\tcount\tpmi\n你\t99\t0.000\n")
        val (old, _, oldErr) = run("pack", "-o", pack, "--data", data, "--layer", "new", candidates.path)
        assertEquals(1, old)
        assertTrue(oldErr, oldErr.startsWith("${candidates.path}:1: "))
        // a row cut short
        candidates.writeText("$HEADER\n# chars\t1000\n你\t99\t0\n")
        val (short, _, shortErr) = run("pack", "-o", pack, "--data", data, "--layer", "new", candidates.path)
        assertEquals(1, short)
        assertTrue(shortErr, shortErr.startsWith("${candidates.path}:3: "))
    }

    @Test
    fun aPacksWordInTheDataIsStillANewWord() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        // 你好 the data has, but in a pack's layer: of the kind words looks for, scored there by true counts
        val pack = file("new.words", "# youmo words 1\n# layer: new\n你好\tni'hao\t-1.000\n")
        val data = tmp.root.resolve("pinyin.data").path
        assertEquals(0, run("pinyin", "-o", data, "--lm", lm, file("dict.txt", "你\tni\t0\n好 hao\n"), pack).first)
        val shard = tmp.root.resolve("s.parquet")
        val pages = listOf("你好吧，好你呢\n你好啊", "你好吗", "你好").mapIndexed { i, text -> WebText.Page("https://a.example.com/$i", text, "") }
        WebText.write(shard, pages)
        val candidates = tmp.root.resolve("candidates.tsv")
        val (code, out, err) = run("words", "-o", candidates.path, "--data", data, "--min-count", "2", "--sketch-bits", "12", shard.path)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("words: 1 runs of at least 2, 1 of them not in the data"))
        val row = candidates.readLines().single { !it.startsWith("#") }.split('\t')
        assertEquals(listOf("你好", "4", "0"), listOf(row[0], row[1], row[7]))
        // a sketch narrower than any
        assertEquals(2, run("words", "-o", candidates.path, "--data", data, "--sketch-bits", "11", shard.path).first)
    }

    @Test
    fun whatWordsWritesIsPackedAsPackReadsIt() {
        // 你好 and 好你 the model has, to fit their counts against; 好好 it has not
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA.replace("ngram 1=3", "ngram 1=5").replace("-1.5\t好", "-1.5\t好\n-2.0\t你好\n-2.5\t好你"))
        val data = tmp.root.resolve("pinyin.data").path
        assertEquals(0, run("pinyin", "-o", data, "--lm", lm, file("dict.txt", "你\tni\t0\n好 hao\n你好 ni'hao\n好你 hao'ni\n")).first)
        val shard = tmp.root.resolve("s.parquet")
        WebText.write(shard, listOf(WebText.Page("https://a.example.com/", "你好，你好，你好，你好，好你，好你，好好，好好，好好，好好", "")))
        val candidates = tmp.root.resolve("candidates.tsv")
        val (code, out, err) = run("words", "-o", candidates.path, "--data", data, "--min-count", "2", "--sketch-bits", "12", shard.path)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("words: 3 runs of at least 2, 1 of them not in the data"))
        // 好好 stands free but holds together little: with its pmi read as an entropy it would not pass
        val pack = tmp.root.resolve("new.words")
        val (packed, packOut, packErr) = run("pack", "-o", pack.path, "--data", data, "--layer", "new", "--min-count", "2", candidates.path)
        assertEquals(packErr, 0, packed)
        assertTrue(packOut, packOut.contains("over 2 words the model has"))
        assertEquals("# youmo words 1\n# layer: new\n好好\thao'hao\t-2.000\n", pack.readText())
    }

    private companion object {
        const val HEADER = "# text\tcount\tyear\tpmi\tleft_entropy\tright_entropy\tsurprise\tknown"
        const val FIT = "fit: log10 P = -2.000 + 0.500 log10(count + 1), over 2 words"
        const val PASSED = "# youmo words 1\n# layer: 2026q3\n耗好\thao'hao\t-1.000\n耗子\thao'zi\t-1.339\n好好\thao'hao\t-1.000\n好了\thao'le\t-1.000\n"
    }
}
