/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.Misreadings
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
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
    fun aWordPackIsALayerOfItsOwnAndScoresTheWordsTheModelLacks() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val dict = file("dict.txt", "你\tni\t0\n好 hao\n")
        // 好 the model has (its score stays) and the dictionary too (its layer stays the base)
        val pack = file("new.words", "# youmo words 1\n# layer: new\n耗子\thao'zi\t-4.000\n好\thao\t-3.000\n")
        val output = tmp.root.resolve("pinyin.data").path
        val (code, out, err) = run("pinyin", "-o", output, "--lm", lm, dict, pack)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("new.words: 2 words in layer new, 1 of them scored as the pack says"))
        val data = PinyinData.load(ByteBuffer.wrap(tmp.root.resolve("pinyin.data").readBytes()))
        assertEquals(listOf("base", "new"), data.layers.names)
        val id = { word: String -> (0 until data.vocabulary.size).first { data.vocabulary.word(it) == word } }
        assertEquals(1, data.layers.layer(id("耗子")))
        assertEquals(0, data.layers.layer(id("好")))
        assertEquals(-4f, data.model.score(-1, -1, id("耗子")), 1e-6f)
        assertEquals(-1.5f, data.model.score(-1, -1, id("好")), 1e-6f)

        val bad = file("bad.words", "# youmo words 1\n耗子\thao\t-4\n")
        val (badCode, _, badErr) = run("pinyin", "-o", output, "--lm", lm, dict, bad)
        assertEquals(1, badCode)
        assertTrue(badErr, badErr.startsWith("$bad:2: "))
    }

    @Test
    fun theDictionarysWordsAreCorrectedByTheCuratedLists() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA.replace("ngram 1=3", "ngram 1=5").replace("-1.5\t好", "-1.5\t好\n-3.0\t长\n-3.0\t大"))
        val dict = file("dict.txt", "你 ni\n好 hao\n你好 ni'hao\n长 chang\n大 da\n长大 chang'da\n长大 chang'tai\n")
        // 你好 the dictionary should not have; 长大 it reads wrong, twice
        val remove = file("remove.tsv", "# word\tkind\n你好\tjunk\t2026-10-05\n")
        val readings = file("readings.tsv", "# word\treading\n长大\tzhang'da\t2026-10-05\t长 is zhang here\n")
        val output = tmp.root.resolve("pinyin.data").path
        val (code, out, err) = run("pinyin", "-o", output, "--lm", lm, "--remove", remove, "--readings", readings, dict)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("corrected: 3 readings of the dictionaries left out or replaced"))
        assertTrue(out, out.contains("dictionary: 5 readings"))
        val data = PinyinData.load(ByteBuffer.wrap(tmp.root.resolve("pinyin.data").readBytes()))
        val words = (0 until data.vocabulary.size).map { data.vocabulary.word(it) }
        assertFalse(words.toString(), "你好" in words)
        // the pack reads a run as the dictionary reads its words: 长大 now as corrected
        val candidates = tmp.root.resolve("candidates.tsv").path
        File(candidates).writeText(
            "# text\tcount\tyear\tpmi\tleft_entropy\tright_entropy\tsurprise\tknown\n# chars\t1000\n" +
                "你\t99\t0\t0.000\t3.000\t3.000\t0.000\t1\n好\t9\t0\t0.000\t3.000\t3.000\t0.000\t1\n" +
                "长大好\t20\t2024\t2.000\t2.000\t2.000\t1.500\t0\n",
        )
        val pack = tmp.root.resolve("p.words").path
        assertEquals(0, run("pack", "-o", pack, "--data", output, "--layer", "new", "--min-count", "5", candidates).first)
        assertEquals(listOf("长大好\tzhang'da'hao"), File(pack).readLines().drop(2).map { it.substringBeforeLast('\t') })
        // a negative threshold is a flag's value, not a flag
        val low = tmp.root.resolve("low.words").path
        assertEquals(0, run("pack", "-o", low, "--data", output, "--layer", "new", "--min-count", "5", "--min-pmi", "-1", candidates).first)
        assertEquals(File(pack).readLines(), File(low).readLines())
    }

    /** A character past U+FFFF (dict_extb's) is two chars and one syllable, as the dictionaries and misreadings.tsv count it. */
    @Test
    fun aCuratedReadingHasASyllableACharacterNotAChar() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val dict = file("dict.txt", "你 ni\n好 hao\n\uD840\uDC00 qu\n")
        val readings = file("readings.tsv", "# word\treading\n\uD840\uDC00\tqiu\n")
        val output = tmp.root.resolve("pinyin.data").path
        val (code, _, err) = run("pinyin", "-o", output, "--lm", lm, "--readings", readings, dict)
        assertEquals(err, 0, code)
        val data = PinyinData.load(ByteBuffer.wrap(File(output).readBytes()))
        val qiu = data.dictionary.find(intArrayOf(Syllables.id("qiu")))
        assertEquals(listOf("\uD840\uDC00"), (0 until data.dictionary.wordCount(qiu)).map { data.vocabulary.word(data.dictionary.word(qiu, it)) })
        assertEquals(-1, data.dictionary.find(intArrayOf(Syllables.id("qu"))))
    }

    @Test
    fun aMisreadWordIsTypedEitherWayAndEachPackIsALayer() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val dict = file("dict.txt", "你 ni\n好 hao\n般若 ban'ruo\n般若 pan're\n")
        // on both lists, as 般若, 关卡 and 包扎 are: the misreadings hold
        val readings = file("readings.tsv", "般若\tban're\n")
        val misreadings = file("misreadings.tsv", "# word\treading\tmisreadings\n般若\tbō rě\tban'ruo\n")
        val first = file("new.words", "# youmo words 1\n# layer: new\n耗子\thao'zi\t-4.000\n")
        // 耗子 of the first pack keeps its layer and its score
        val second = file("latin.words", "# youmo words 1\n# layer: latin\n好耶\thao'ye\t-5.000\n耗子\thao'zi\t-6.000\n")
        val output = tmp.root.resolve("pinyin.data").path
        val (code, out, err) = run("pinyin", "-o", output, "--lm", lm, "--readings", readings, "--misreadings", misreadings, dict, first, second)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("corrected: 2 readings of the dictionaries left out or replaced"))
        assertTrue(out, out.contains("latin.words: 2 words in layer latin, 1 of them scored as the pack says"))
        assertTrue(out, out.contains("misread: 1 words, typed by their misreadings too"))
        val data = PinyinData.load(ByteBuffer.wrap(File(output).readBytes()))
        assertEquals("bō rě\tban'ruo", data.meta[Misreadings.META_PREFIX + "般若"])
        fun weight(vararg reading: String): Float? {
            val node = data.dictionary.find(reading.map { Syllables.id(it) }.toIntArray())
            return if (node < 0 || data.dictionary.wordCount(node) == 0) null else data.dictionary.weight(node, 0)
        }
        assertEquals(0f, weight("bo", "re"))
        assertEquals(Misreadings.WEIGHT, weight("ban", "ruo")!!, 0.1f)
        assertNull(weight("ban", "re"))
        assertNull(weight("pan", "re"))
        assertEquals(listOf("base", "new", "latin"), data.layers.names)
        val id = { word: String -> (0 until data.vocabulary.size).first { data.vocabulary.word(it) == word } }
        assertEquals(1, data.layers.layer(id("耗子")))
        assertEquals(2, data.layers.layer(id("好耶")))
        assertEquals(-4f, data.model.score(-1, -1, id("耗子")), 1e-6f)
    }

    @Test
    fun aCuratedReadingTheEngineCannotTypeIsAnErrorAtItsLine() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val dict = file("dict.txt", "你 ni\n好 hao\n你好 ni'hao\n")
        val output = tmp.root.resolve("pinyin.data").path
        // a syllable no one types, a syllable short, none: each would take 你好 out of the data
        val readings = tmp.root.resolve("readings.tsv")
        for (line in listOf("你好\tni'hoa", "你好\tni", "你好")) {
            readings.writeText("# word\treading\n$line\n")
            val (code, _, err) = run("pinyin", "-o", output, "--lm", lm, "--readings", readings.path, dict)
            assertEquals(line, 1, code)
            assertTrue(err, err.startsWith("${readings.path}:2: ") && err.contains("is no reading of 你好"))
        }
        val misreadings = file("misreadings.tsv", "# word\treading\tmisreadings\n# a comment\n你好\tnǐ hǎo\tni'hoa\n")
        val (code, _, err) = run("pinyin", "-o", output, "--lm", lm, "--misreadings", misreadings, dict)
        assertEquals(1, code)
        assertTrue(err, err.startsWith("$misreadings:3: ") && err.contains("\"ni'hoa\" is no reading of 你好"))
        // a word listed twice, the second wrongly: at its own line, not the first's
        val twice = file("twice.tsv", "# word\treading\tmisreadings\n你好\tnǐ hǎo\tli'hao\n你好\tnǐ hǎo\tni'hoa\n")
        val (twiceCode, _, twiceErr) = run("pinyin", "-o", output, "--lm", lm, "--misreadings", twice, dict)
        assertEquals(1, twiceCode)
        assertTrue(twiceErr, twiceErr.startsWith("$twice:3: ") && twiceErr.contains("\"ni'hoa\" is no reading of 你好"))
    }

    @Test
    fun theListsTheAppBuildPassesCompile() {
        val lexicon = File("../../lexicon")
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val dict = file("dict.txt", "你 ni\n好 hao\n")
        val (code, out, err) = run(
            "pinyin", "-o", tmp.root.resolve("pinyin.data").path, "--lm", lm, "--remove", lexicon.resolve("remove.tsv").path,
            "--readings", lexicon.resolve("readings.tsv").path, "--misreadings", lexicon.resolve("misreadings.tsv").path, dict,
        )
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("misread: "))
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
        // into the shards' own directory: each cleaned one would replace its raw one
        val written = shard.lastModified()
        val (inPlace, _, inPlaceErr) = run("clean", "-o", tmp.root.path, shard.path)
        assertEquals(1, inPlace)
        assertTrue(inPlaceErr, inPlaceErr.contains("holds the shards"))
        assertEquals(written, shard.lastModified())
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

        // a pack's word joins the vocabulary, so the chat is split into it and its n-grams counted
        val pack = file("new.words", "# youmo words 1\n# layer: new\n你好\tni'hao\t-0.5\n你\tni\t-3.0\n")
        val (packed, packedOut, packedErr) = run("mix", "-o", mixed, "--lm", lm, "--weight", "0.5", "--cutoffs", "1,1", pack, chat.path)
        assertEquals(packedErr, 0, packed)
        assertTrue(packedOut, packedOut.contains("packs: 2 words, 1 of them new to the model"))
        // 你好 | 好 好 | 你 | 好 你: 你好 now one word
        assertTrue(packedOut, packedOut.contains("chat: 2 documents, 6 words"))
        assertTrue(File(mixed).readText().contains("\t你好\t"))

        // a field that is no number is not left out: 2,x,3 is no 2,3
        val cutoffs = listOf("2", "3,2", "2,x,3", "1,1,").map { listOf("--cutoffs", it) }
        for (bad in cutoffs + listOf(listOf("--weight", "2"), listOf("--weight"))) {
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
    fun aCodeTableTakesThePacksWordsAndTheDatasByUse() {
        // 式作 is coded abca by the rules, as the table's 工作 is; the model has it far rarer
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA.replace("ngram 1=3", "ngram 1=5").replace("-1.5\t好", "-1.5\t好\n-2.0\t工作\n-6.0\t式作"))
        val pinyin = tmp.root.resolve("pinyin.data").path
        assertEquals(0, run("pinyin", "-o", pinyin, "--lm", lm, file("dict.txt", "你 ni\n好 hao\n工作 gong'zuo\n式作 shi'zuo\n")).first)
        val table = file("t.txt", TABLE)
        val output = tmp.root.resolve("t.data")
        fun abca(packed: String): List<String> {
            val words = tmp.root.resolve("new.words").apply { writeText("# youmo words 1\n# layer: new\n$packed\n") }.path
            val (code, out, err) = run("table", "-o", output.path, "--words", words, "--pinyin", pinyin, table)
            assertEquals(err, 0, code)
            assertTrue(out, out.contains("words: 1 new words and 0 others of 2 added, 0 of them left out"))
            val t = CodeTable.load(ByteBuffer.wrap(output.readBytes()))
            return t.exactRange("abca").map(t::text)
        }
        // the pack's score, the higher, and a new word taken as ten times more used
        assertEquals(listOf("式作", "工作"), abca("式作\tshi'zuo\t-1.500"))
        // a tenth as common as 工作 and less, a new word stays all the same
        assertEquals(listOf("工作", "式作"), abca("式作\tshi'zuo\t-6.500"))
    }

    @Test
    fun emojiWordsAreCompiledTheCommonerFirstWithTheirPinyin() {
        val groups = listOf(
            "Smileys & Emotion" to "1F603", "People & Body" to "1F44D", "Animals & Nature" to "1F408", "Food & Drink" to "1F34E",
            "Travel & Places" to "26FD", "Activities" to "1F389", "Objects" to "1F4A1", "Symbols" to "1F197", "Flags" to "1F3C1",
        )
        val test = file("emoji-test.txt", groups.joinToString("\n") { (group, cp) -> "# group: $group\n$cp ; fully-qualified # x" })
        val xml = file("zh.xml", """
            <annotation cp="😃">哈哈 | 笑脸</annotation>
            <annotation cp="😃" type="tts">大笑</annotation>
            <annotation cp="🐈">宠物</annotation>
            <annotation cp="🐈" type="tts">猫</annotation>
        """)
        val lexicon = file("emoji.tsv", "-加油\t⛽\n-猫\t😃\n")
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA.replace("ngram 1=3", "ngram 1=5").replace("-1.5\t好", "-1.5\t好\n-0.5\t猫\n-6.0\t大笑"))
        val pinyin = tmp.root.resolve("pinyin.data").path
        assertEquals(0, run("pinyin", "-o", pinyin, "--lm", lm, file("dict.txt", "你 ni\n好 hao\n猫 mao\n大笑 da'xiao\n")).first)
        val output = tmp.root.resolve("emoji.data")
        val (code, out, err) = run("emoji", "-o", output.path, "--test", test, "--lexicon", lexicon, "--pinyin", pinyin, xml)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("emoji: 2 of the edits take out what is not there"))
        assertTrue(out, out.contains("words the pinyin data does not read"))
        val lines = output.readLines().filter { !it.startsWith('#') && !it.startsWith('@') }
        // the model has 猫 far commoner than 大笑
        assertTrue(lines.toString(), lines.indexOfFirst { it.startsWith("猫\t") } < lines.indexOfFirst { it.startsWith("大笑\t") })
        assertTrue(lines.toString(), lines.any { it.startsWith("猫\t🐈\t") && it.endsWith("mao") })
    }

    @Test
    fun chaiziSpellsACharacterByItsPartsRadicalsAsTheirCharacters() {
        val pinyin = tmp.root.resolve("pinyin.data").path
        val dict = file("dict.txt", "你\tni\t0\n好\thao\t0\n草\tcao\t0\n巴\tba\t0\n芭\tba\t0\n艹\tao\t0.1\n艹\tcao\t0\n")
        assertEquals(0, run("pinyin", "-o", pinyin, "--lm", file("lm.arpa", ReadersTest.TINY_ARPA), dict).first)
        val output = tmp.root.resolve("chaizi.data")
        val (code, out, err) = run("chaizi", "-o", output.path, "--pinyin", pinyin, file("chaizi-jt.txt", "芭\t草 巴\t艹 巴\n"))
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("chaizi: 1 characters, 1 of them by their parts, 1 spellings"))
        assertEquals(listOf("caoba\t芭"), output.readLines().filter { !it.startsWith('#') })
    }

    @Test
    fun strokesAreCompiled() {
        val dict = file("stroke.dict.yaml", "# Rime dictionary: stroke\n---\nname: stroke\n...\n\n一\th\n十\ths\n")
        val output = tmp.root.resolve("stroke.data").path
        val (code, out, err) = run("strokes", "-o", output, dict)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("strokes: read $dict"))
        val table = CodeTable.load(ByteBuffer.wrap(File(output).readBytes()))
        assertEquals(listOf("h" to "一", "hs" to "十"), (0 until table.size).map { table.code(it) to table.text(it) })
        val bad = file("bad.yaml", "...\n一\th\n二\tx\n")
        val (badCode, _, badErr) = run("strokes", "-o", output, bad)
        assertEquals(1, badCode)
        assertTrue(badErr, badErr.startsWith("$bad:3: "))
    }

    @Test
    fun aPacksWordsAreWrittenAsTheVoiceRecognizersHotwords() {
        // a comment, a character alone, a word not all Chinese and one listed twice: 新词 once
        val words = file("new.words", "# the pack\n新词\t1\n中\t2\nAPP词\t3\n新词\t4\n")
        val output = tmp.root.resolve("hotwords.txt")
        val (code, _, err) = run("hotwords", "-o", output.path, words)
        assertEquals(err, 0, code)
        assertEquals("新 词\n", output.readText())
    }

    @Test
    fun theChatLikePagesAreWrittenForTheEvalSetTools() {
        val shard = tmp.root.resolve("s.parquet")
        val chat = WebText.Page("https://a.example.com/1", "你们去哪儿吃饭啊？\n我还没吃呢，一起吧！好啊哈哈", "")
        // not a particle in it
        val news = WebText.Page("https://b.example.com/2", "今年一季度我国工业经济增势良好，新产业持续发力。", "")
        WebText.write(shard, listOf(chat, news))
        val output = tmp.root.resolve("chat.jsonl.gz")
        val (code, out, err) = run("text", "-o", output.path, shard.path)
        assertEquals(err, 0, code)
        assertEquals("text: 1 pages\n", out)
        val lines = GZIPInputStream(output.inputStream()).bufferedReader().use { it.readLines() }
        assertEquals(listOf(listOf("你们去哪儿吃饭啊？", "我还没吃呢，一起吧！好啊哈哈")), lines.map(JsonStrings::parse))
    }

    @Test
    fun examplesAreAsManyAWordAsAskedFor() {
        val shard = tmp.root.resolve("s.parquet")
        WebText.write(shard, listOf(WebText.Page("https://a/", "你们都是吗喽吧。今天又是吗喽的一天。", "")))
        val list = file("words.txt", "吗喽\n")
        val output = tmp.root.resolve("out.tsv")
        assertEquals(0, run("examples", "-o", output.path, "--only", list, "--per-word", "1", shard.path).first)
        assertEquals("吗喽\t你们都是吗喽吧。\n", output.readText())
        assertEquals(2, run("examples", "-o", output.path, "--only", list, "--per-word", "0", shard.path).first)
    }

    @Test
    fun aCrawlsFilesAreShardsAndARunGoesOnWhereItStopped() {
        // a page a WET file, in a directory laid out as CommonCrawl; shards of a hundred files are CommonCrawlTest's
        val base = tmp.newFolder("cc")
        val names = (0 until 3).map { "crawl-data/CC-TEST/segments/1/wet/$it.warc.wet.gz" }
        for (name in names) File(base, name).apply { parentFile.mkdirs() }.writeBytes(wet("https://a.example.com/$name"))
        File(base, "crawl-data/CC-TEST/wet.paths.gz").outputStream().use { stream ->
            GZIPOutputStream(stream).use { it.write(names.joinToString("\n", postfix = "\n").toByteArray()) }
        }
        val out = tmp.root.resolve("raw").path
        val crawl = arrayOf("cc", "-o", out, "--crawl", "CC-TEST", "--base", base.path)
        val (code, log, err) = run(*crawl, "--files", "2")
        assertEquals(err, 0, code)
        assertEquals("CC-TEST-00000-00002.parquet: 2 files, 2 pages, 128 Han characters\n", log)
        // again: the shard kept; a file more: the shorter one not taken for the longer
        assertEquals("CC-TEST-00000-00002.parquet: there already\n", run(*crawl, "--files", "2").second)
        val (more, moreLog, _) = run(*crawl, "--files", "3")
        assertEquals(0, more)
        assertEquals("CC-TEST-00000-00003.parquet: 3 files, 3 pages, 192 Han characters\n", moreLog)
        assertEquals(listOf("CC-TEST-00000-00002.parquet", "CC-TEST-00000-00003.parquet"), File(out).list()!!.sorted())
        // past the crawl's files
        assertEquals(1, run(*crawl, "--from", "2", "--files", "2").first)
        // a first file before the first, no files, how many not said
        for (bad in listOf(arrayOf("--from", "-1", "--files", "1"), arrayOf("--files", "0"), arrayOf("--from", "1"))) {
            assertEquals(bad.toList().toString(), 2, run(*crawl, *bad).first)
        }
    }

    /** A WET file of one page of [url], a WARC record in a gzip member of its own (CommonCrawlTest). */
    private fun wet(url: String): ByteArray {
        val text = "今天天气很好我们一起去公园散步吧".repeat(4).toByteArray()
        val headers = "WARC/1.0\r\nWARC-Type: conversion\r\nWARC-Target-URI: $url\r\nWARC-Identified-Content-Language: zho\r\n" +
            "Content-Length: ${text.size}\r\n\r\n"
        return ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(headers.toByteArray() + text) } }.toByteArray()
    }

    @Test
    fun anEnglishModelIsCompiledAndPruned() {
        val lm = file("english.arpa", ENGLISH_ARPA)
        val output = tmp.root.resolve("english.data").path
        val (code, out, err) = run("english", "-o", output, "--lm", lm)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("model: 4 / 2 / 1 n-grams"))
        val data = EnglishData.load(ByteBuffer.wrap(File(output).readBytes()))
        assertEquals(EnglishText.START, data.word(data.start))
        // the words typed, by their keys: the model's own are none
        assertEquals(listOf("am", "I"), (0 until data.keyed).map { data.word(data.keyedWord(it)) })

        // <s> I is worth 0.51 nats to its context: past 0.1 once a sentence's start is as likely as 0.9, not at English's 1/14
        val pruned = tmp.root.resolve("pruned.arpa").path
        val (pruneCode, pruneOut, pruneErr) = run("prune", "-o", pruned, "--lm", lm, "--threshold", "0.1")
        assertEquals(pruneErr, 0, pruneCode)
        assertTrue(pruneOut, pruneOut.contains("pruned: 4 / 0 / 0 n-grams"))
        val (startCode, startOut, startErr) = run("prune", "-o", pruned, "--lm", lm, "--threshold", "0.1", "--start-prob", "0.9")
        assertEquals(startErr, 0, startCode)
        assertTrue(startOut, startOut.contains("pruned: 4 / 1 / 0 n-grams"))
        assertEquals(0, run("english", "-o", output, "--lm", pruned).first)
        // a likelihood of none or all, or no number
        for (bad in listOf("0", "1", "x")) {
            assertEquals(bad, 2, run("prune", "-o", pruned, "--lm", lm, "--threshold", "0.1", "--start-prob", bad).first)
        }
        val (noStart, _, noStartErr) = run("prune", "-o", pruned, "--lm", file("lm.arpa", ReadersTest.TINY_ARPA), "--threshold", "0.1")
        assertEquals(1, noStart)
        assertTrue(noStartErr, noStartErr.contains("no <s>"))
    }

    @Test
    fun anEnglishModelIsCountedFromThePagesTheirSlipsLeftOut() {
        // the 300 times as common as teh, an edit from it: teh is a slip of the, unless a dictionary has it
        val shard = tmp.root.resolve("en.parquet")
        val text = "the ".repeat(300) + "teh cat sat on the mat. I'll go home. You and I are here."
        WebText.write(shard, listOf(WebText.Page("https://a.example.com/", text, "")))
        val lm = tmp.root.resolve("en.arpa").path
        val (code, out, err) = run("english-lm", "-o", lm, "--cutoffs", "1,1", shard.path)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains(" words, 1 slips"))
        assertEquals(listOf("teh\tthe"), File("$lm.slips").readLines())
        val known = tmp.root.resolve("en_dict.fscd").apply { writeBytes(fscd("teh", "cat")) }
        val (knownCode, knownOut, knownErr) = run("english-lm", "-o", lm, "--known", known.path, "--cutoffs", "1,1", shard.path)
        assertEquals(knownErr, 0, knownCode)
        assertTrue(knownOut, knownOut.contains(" words, 0 slips"))
        // as every app build compiles the release's
        val data = tmp.root.resolve("english.data").path
        val (compiled, _, compiledErr) = run("english", "-o", data, "--lm", lm)
        assertEquals(compiledErr, 0, compiled)
        val english = EnglishData.load(ByteBuffer.wrap(File(data).readBytes()))
        assertTrue(english.id("teh") >= 0 && english.id("I'll") >= 0)
    }

    @Test
    fun aSpellDictionarysWordsAreReadAsFarAsItHoldsThem() {
        assertEquals(listOf("teh", "don't"), spellWords(fscd("teh", "don't")))
        // a broken count, past what the file holds or below nothing: its words, no list made room for so many
        assertEquals(listOf("teh", "don't"), spellWords(fscd("teh", "don't", count = Int.MAX_VALUE)))
        assertEquals(emptyList<String>(), spellWords(fscd("teh", count = -1)))
        // the last word without its 0
        val whole = fscd("teh", "cat")
        assertEquals(listOf("teh", "cat"), spellWords(whole.copyOf(whole.size - 1)))
        assertThrows(IllegalArgumentException::class.java) { spellWords("FSCD0001".toByteArray() + ByteArray(Int.SIZE_BYTES)) }
    }

    /** fcitx's spell dictionary (`en_dict.fscd`) of [words], each of weight 1, saying it has [count]. */
    private fun fscd(vararg words: String, count: Int = words.size): ByteArray {
        val encoded = words.map { it.toByteArray() }
        val magic = "FSCD0000".toByteArray(Charsets.US_ASCII)
        val b = ByteBuffer.allocate(magic.size + Int.SIZE_BYTES + encoded.sumOf { Short.SIZE_BYTES + it.size + 1 }).order(ByteOrder.LITTLE_ENDIAN)
        b.put(magic).putInt(count)
        for (word in encoded) b.putShort(1.toShort()).put(word).put(0.toByte())
        return b.array()
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
        assertTrue(err, err.contains("<unk>"))
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
        assertTrue(err, err.contains("file kind"))
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
            // a flag the command does not take, or none does: no input either
            arrayOf("table", "-o", "x", "--lm", "lm.arpa", "t.txt"),
            arrayOf("table", "-o", "x", "--frobnicate", "t.txt"),
            arrayOf("check", "-o", "x", "p.data", "lm.arpa"),
            // and of a command of many inputs, which would take a misspelt flag and its value for two more
            arrayOf("clean", "-o", tmp.root.resolve("out").path, "--sketchbits", "12", "s.parquet"),
            arrayOf("pinyin", "-o", "x", "--lm", "lm.arpa", "--frobnicate", "d.txt"),
            // a value that is a flag, a flag twice
            arrayOf("table", "-o", "--words", "t.txt"),
            arrayOf("table", "-o", "x", "-o", "y", "t.txt"),
            // a layer no pack can name, no layer, no data
            arrayOf("pack", "-o", "p.words", "--data", "p.data", "--layer", "bad name", "c.tsv"),
            arrayOf("pack", "-o", "p.words", "--data", "p.data", "c.tsv"),
            arrayOf("words", "-o", "c.tsv", "p.data"),
        ).forEach { args ->
            val (code, _, err) = run(*args)
            assertEquals(args.toList().toString(), 2, code)
            assertEquals(USAGE + "\n", err)
        }
    }

    private companion object {
        // as KenLM writes one: <s>, never predicted, at -99
        const val ENGLISH_ARPA = "\\data\\\nngram 1=4\nngram 2=2\nngram 3=1\n\n" +
            "\\1-grams:\n-5.0\t<unk>\n-99\t<s>\t-0.3\n-1.0\tI\t-0.2\n-1.2\tam\n\n" +
            "\\2-grams:\n-0.3\t<s> I\t-0.1\n-0.4\tI am\n\n" +
            "\\3-grams:\n-0.2\t<s> I am\n\n\\end\\\n"

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
