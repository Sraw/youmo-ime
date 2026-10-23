/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.DataFile
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10

class DictionaryFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun ids(vararg s: String) = s.map(Syllables::id).toIntArray()

    private fun compile(vararg lines: String, inBase: (String, IntArray) -> Boolean = { _, _ -> false }): Pair<DictionaryFile, DictionaryCompiler.Result> {
        val file = File(folder.root, "d.ymd")
        val result = DictionaryCompiler.compile(listOf(lines.asSequence()), file, stamp = "s1", base = "b1", inBase = inBase)
        return DictionaryFile.open(file) to result
    }

    // the words under a reading, walked down the trie as the decoder does
    private fun DictionaryFile.at(vararg syllables: String): List<String> {
        var node = DictionaryFile.ROOT
        for (s in syllables) {
            node = child(node, Syllables.id(s))
            if (node < 0) return emptyList()
        }
        return (0 until wordCount(node)).map { text(word(node, it)) }
    }

    @Test
    fun theWordsOfATextDictionaryReadBackByPlaceTextReadingAndTrie() {
        val (d, result) = compile(
            "啊啊 a'a 0.5",
            "幽默 you'mo 0.001",
            "优美 you'mei 0.01",
            "有没 you'mei 0.1",
            "# a comment",
            "",
            "坏行 bad'xing",
            "坏 a'a",
            "三个字 san'ge'zi 0.001",
        )
        assertEquals(5, d.size)
        assertEquals(5, d.trieWords)
        assertEquals(2, result.unread)
        assertEquals("s1", d.stamp)
        assertEquals("b1", d.base)
        val p = d.find("幽默", ids("you", "mo"))
        assertEquals("幽默", d.text(p))
        assertArrayEquals(ids("you", "mo"), d.syllables(p))
        // against the source's best, 啊啊's 0.5
        assertEquals(log10(0.002f), d.score(p), 1e-5f)
        assertEquals(0f, d.score(d.find("啊啊", ids("a", "a"))), 1e-6f)
        assertEquals(-1, d.find("幽默", ids("you", "mei")))
        assertEquals(-1, d.find("没有", ids("mei", "you")))
        // a node's words best first
        assertEquals(listOf("有没", "优美"), d.at("you", "mei"))
        assertEquals(listOf("幽默"), d.at("you", "mo"))
        assertEquals(listOf("三个字"), d.at("san", "ge", "zi"))
        assertEquals(emptyList<String>(), d.at("san", "ge"))
        val ge = d.child(d.child(DictionaryFile.ROOT, Syllables.id("san")), Syllables.id("ge"))
        assertEquals(1, d.childCount(ge))
        assertEquals(Syllables.id("zi"), d.nodeSyllable(d.firstChild(ge)))
        assertEquals(-1, d.child(DictionaryFile.ROOT, Syllables.id("zhuang")))
        assertTrue(d.hasText("三个字"))
        assertFalse(d.hasText("三个"))
        assertEquals(5, DictionaryFile.wordCount(File(folder.root, "d.ymd")))
    }

    @Test
    fun theTrieWalksEveryWordOnceAndOnlyItsOwn() {
        val words = listOf("中国 zhong'guo", "中 zhong", "种 zhong", "中国人 zhong'guo'ren", "国 guo", "重 chong", "重 zhong")
        val (d, _) = compile(*words.toTypedArray())
        val seen = ArrayList<String>()
        fun walk(node: Int, path: List<Int>) {
            for (i in 0 until d.wordCount(node)) d.word(node, i).let { seen += d.text(it); assertArrayEquals(path.toIntArray(), d.syllables(it)) }
            for (c in d.firstChild(node) until d.firstChild(node) + d.childCount(node)) walk(c, path + d.nodeSyllable(c))
        }
        walk(DictionaryFile.ROOT, emptyList())
        assertEquals(words.map { it.substringBefore(' ') }.sorted(), seen.sorted())
        assertEquals(listOf("中", "种", "重"), d.at("zhong").sorted())
    }

    @Test
    fun theBasesWordsAreKeptButOutOfTheTrie() {
        val (d, result) = compile("中国 zhong'guo 0.5", "幽默 you'mo 0.1", "中 zhong 0.2") { text, _ -> text.startsWith('中') }
        assertEquals(3, d.size)
        assertEquals(1, d.trieWords)
        assertEquals(2, result.inBase)
        assertEquals(emptyList<String>(), d.at("zhong", "guo"))
        assertEquals(emptyList<String>(), d.at("zhong"))
        assertEquals(listOf("幽默"), d.at("you", "mo"))
        assertTrue(d.find("中国", ids("zhong", "guo")) >= d.trieWords)
        assertEquals(0f, d.score(d.find("中国", ids("zhong", "guo"))), 0f)
    }

    @Test
    fun aWordListedTwiceOrInTwoSourcesIsOneKeepingItsBestScore() {
        val (d, _) = compile("行 xing 0.001", "行 xing 0.1", "行 hang 0.01", "行 xing", "好 hao 1")
        assertEquals(3, d.size)
        assertEquals(-1f, d.score(d.find("行", ids("xing"))), 1e-6f)
        assertEquals(2, d.placesOf("行").size)
        val file = File(folder.root, "two.ymd")
        // counts in one, probabilities in the other: each against its own best
        DictionaryCompiler.compile(listOf(sequenceOf("行 xing 10", "好 hao 1000", "乱"), sequenceOf("行 xing 1", "坏 huai 0.5")), file, names = listOf("a", "b"))
        val two = DictionaryFile.open(file)
        // each source's words read, by name, for the settings' list
        assertEquals(mapOf("a" to 2, "b" to 2), DictionaryCompiler.sourceWords(file))
        assertEquals(emptyMap<String, Int>(), DictionaryCompiler.sourceWords(File(folder.root, "none.ymd")))
        assertEquals(0f, two.score(two.find("行", ids("xing"))), 1e-6f)
        assertEquals(0f, two.score(two.find("好", ids("hao"))), 1e-6f)
        assertEquals(log10(0.5f), two.score(two.find("坏", ids("huai"))), 1e-6f)
    }

    @Test
    fun weightsReadAsTheirSourcesKind() {
        val file = File(folder.root, "k.ymd")
        fun scores(vararg lines: String): List<Float> {
            DictionaryCompiler.compile(listOf(lines.asSequence()), file)
            val d = DictionaryFile.open(file)
            return lines.map { val (t, c) = it.split(' '); d.score(d.find(LibimeImport.entry(t, c)!!)) }
        }
        // libime's log10, 0.3.1's own 0.0 for none, counts
        assertEquals(listOf(0f, -2f), scores("好 hao -3", "坏 huai -5"))
        // none weighted: all alike, as the unknown word
        assertEquals(listOf(0f, 0f), scores("好 hao 0.0", "坏 huai 0.0"))
        assertEquals(listOf(-1f, 0f), scores("好 hao 12.0", "坏 huai 120"))
        // signs mixed: no kind, so as none
        assertEquals(listOf(0f, 0f), scores("好 hao -3", "坏 huai 5"))
    }

    // MUST-2: a word without a weight ranks with the least weighted, never above them, whichever source
    @Test
    fun aWordWithoutAWeightRanksLastAndItsTrieOrderAgrees() {
        val file = File(folder.root, "w.ymd")
        DictionaryCompiler.compile(
            listOf(sequenceOf("好 hao 0.5", "号 hao 0.005", "浩 hao 0.0"), sequenceOf("豪 hao 0", "毫 hao 0")),
            file,
        )
        val d = DictionaryFile.open(file)
        val node = d.child(DictionaryFile.ROOT, Syllables.id("hao"))
        val order = (0 until d.wordCount(node)).map { d.word(node, it) }
        assertEquals(listOf("好", "号", "毫", "浩", "豪"), order.map(d::text))
        assertEquals(listOf(0f, -2f, -2f, -2f, -2f), order.map { d.score(it) }.map { Math.round(it * 1000) / 1000f })
    }

    @Test
    fun anEmptyDictionaryIsAFileWithNoWords() {
        val (d, _) = compile()
        assertEquals(0, d.size)
        assertEquals(0, d.childCount(DictionaryFile.ROOT))
        assertEquals(-1, d.find("幽默", ids("you", "mo")))
    }

    @Test
    fun aFileCutShortOrDamagedOrOfAnotherKindDoesNotOpen() {
        val file = File(folder.root, "d.ymd")
        DictionaryCompiler.compile(listOf(sequenceOf("幽默 you'mo")), file)
        val bytes = file.readBytes()
        File(folder.root, "short").writeBytes(bytes.copyOf(bytes.size - 4))
        assertThrows(IOException::class.java) { DictionaryFile.open(File(folder.root, "short")) }
        File(folder.root, "bad").writeBytes(bytes.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() })
        assertThrows(IOException::class.java) { DictionaryFile.open(File(folder.root, "bad")) }
        File(folder.root, "text").writeText("幽默 you'mo\n".repeat(20))
        assertThrows(IOException::class.java) { DictionaryFile.open(File(folder.root, "text")) }
        assertThrows(IOException::class.java) { DictionaryFile.wordCount(File(folder.root, "text")) }
    }

    @Test
    fun aCompileThatFailsLeavesTheFileThatWasThereAndNothingElse() {
        val file = File(folder.root, "d.ymd")
        DictionaryCompiler.compile(listOf(sequenceOf("幽默 you'mo")), file)
        assertThrows(IllegalStateException::class.java) {
            DictionaryCompiler.compile(listOf(sequence { yield("优美 you'mei"); error("read failed") }), file)
        }
        assertEquals(1, DictionaryFile.open(file).size)
        assertEquals(listOf("d.ymd"), folder.root.list()!!.toList())
    }

    // [bytes]' sections written again, [change]d, their checksums right: what only the checks past the CRC can refuse
    private fun rewritten(
        bytes: ByteArray,
        meta: (Map<String, String>) -> Map<String, String> = { it },
        change: (Int, ByteBuffer) -> Unit = { _, _ -> },
    ): ByteBuffer {
        val file = DataFile.open(ByteBuffer.wrap(bytes), DataFile.KIND_USER_DICTIONARY, DictionaryFile.VERSION)
        val out = DataFile.Writer(DataFile.KIND_USER_DICTIONARY, DictionaryFile.VERSION)
        // the sections the file lists, not a range of ids: one added outside it not dropped (review F3436)
        for (id in file.ids.sorted()) {
            if (id == DictionaryFile.META) {
                out.addMeta(id, meta(file.meta(id)))
            } else {
                val section = file.section(id).let { s -> ByteArray(s.capacity()) { s.get(it) } }
                change(id, ByteBuffer.wrap(section).order(ByteOrder.LITTLE_ENDIAN))
                out.add(id, section)
            }
        }
        return ByteBuffer.wrap(out.toByteArray())
    }

    // a file whose checksums hold but whose indexes do not, or of another syllable table (the app
    // compiles again for that), is refused at a checked open, never read out of bounds (review NIT 5)
    @Test
    fun aWellChecksummedFileOfBadIndexesOrOtherSyllablesDoesNotOpen() {
        val file = File(folder.root, "d.ymd")
        DictionaryCompiler.compile(listOf(sequenceOf("幽默 you'mo 0.5", "中国 zhong'guo 0.1")), file)
        val bytes = file.readBytes()
        DictionaryFile.of(rewritten(bytes))
        val rows = listOf<Triple<String, (Map<String, String>) -> Map<String, String>, (Int, ByteBuffer) -> Unit>>(
            Triple("other syllables", { it + ("syllableChecksum" to "1") }, { _, _ -> }),
            Triple("text chain", { it }, { id, b -> if (id == DictionaryFile.TEXT_NEXT) b.putInt(0, 99) }),
            Triple("node words", { it }, { id, b -> if (id == DictionaryFile.WORD_COUNT) b.putInt(4, 99) }),
            Triple("syllables", { it }, { id, b -> if (id == DictionaryFile.SYLLABLES) b.putShort(0, Short.MAX_VALUE) }),
        )
        assertRows(rows, { it.first }) { (why, meta, change) ->
            val e = assertThrows(why, IOException::class.java) { DictionaryFile.of(rewritten(bytes, meta, change)) }
            assertTrue("$why: ${e.message}", e.message!!.endsWith(why))
        }
    }

    // the names a compile and a table's index build write on the way, and only those, told as work
    // files: the app's backup filter and sweep ask here (review F3373)
    @Test
    fun theWorkFilesOfACompileAndAnIndexBuildAreToldSo() {
        val work = listOf("merged.ymd.tmp", ".merged.ymd.build", "merged.engine-wubi.ymc.tmp", ".merged.engine-wubi.ymc.build")
        val not = listOf("merged.ymd", "merged.engine-wubi.ymc", "a.txt", "a.txt.tmp", "x.build", ".merged.failed", "notes.tmp")
        for (name in work) assertTrue(name, DictionaryCompiler.isWorkFile(name))
        for (name in not) assertFalse(name, DictionaryCompiler.isWorkFile(name))
    }

    // a file opened unchecked, its text chain a loop: a find ends, never spins on the fcitx thread (R12 F3360)
    @Test(timeout = 10_000)
    fun aLoopInTheTextChainOfAnUncheckedFileEndsAFind() {
        val file = File(folder.root, "d.ymd")
        DictionaryCompiler.compile(listOf(sequenceOf("幽默 you'mo 0.5", "中国 zhong'guo 0.1")), file)
        // each place's next itself
        val d = DictionaryFile.of(rewritten(file.readBytes()) { id, b -> if (id == DictionaryFile.TEXT_NEXT) for (p in 0 until 2) b.putInt(4 * p, p + 1) }, verify = false)
        assertEquals(-1, d.find("幽默", ids("you", "you")))
        // whether a word is still reached depends on which slot heads the loop; what counts is that the walk ends
        assertTrue(d.placesOf("幽默").size <= 2)
    }

    // words long enough to fill a run's syllables and characters before its count of words: spilled
    // part way, each word still once (review NIT 3)
    @Test
    fun longWordsSpillARunPartWayAndEachIsKeptOnce() {
        val spellings = (0 until Syllables.count).filter { Syllables.spelling(it)[0].isLowerCase() }.map(Syllables::spelling)
        val n = 30_000
        fun line(i: Int): String {
            val text = String(charArrayOf((0x4E00 + i % 20000).toChar(), (0x4E00 + i / 20000).toChar())) + "的".repeat(18)
            val code = listOf(spellings[i % spellings.size], spellings[i / spellings.size % spellings.size]) + List(18) { "de" }
            return "$text ${code.joinToString("'")} 0.5"
        }
        val file = File(folder.root, "long.ymd")
        val result = DictionaryCompiler.compile(listOf((0 until n).asSequence().map(::line)), file)
        assertEquals(n, result.words)
        val d = DictionaryFile.open(file)
        for (i in 0 until n) {
            val (text, code) = line(i).split(' ')
            assertTrue(line(i), d.find(LibimeImport.entry(text, code)!!) >= 0)
        }
        var walked = 0
        fun walk(node: Int) {
            walked += d.wordCount(node)
            for (c in d.firstChild(node) until d.firstChild(node) + d.childCount(node)) walk(c)
        }
        walk(DictionaryFile.ROOT)
        assertEquals(n, walked)
    }

    // the sources' names past what a reader takes in a header: the compile fails, the file there kept (R12 F3323)
    @Test
    fun sourcesTooManyForTheHeaderFailTheCompile() {
        val file = File(folder.root, "d.ymd")
        DictionaryCompiler.compile(listOf(sequenceOf("幽默 you'mo")), file)
        val names = List(2) { "名".repeat(20_000) + it }
        assertThrows(IOException::class.java) {
            DictionaryCompiler.compile(listOf(sequenceOf("优美 you'mei"), sequenceOf("中国 zhong'guo")), file, names = names)
        }
        assertEquals(1, DictionaryFile.open(file).size)
    }
}
