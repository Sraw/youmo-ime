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
import java.lang.Float.floatToRawIntBits
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.random.Random

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
        val words = listOf("中国 zhong'guo 0.3", "中 zhong 0.1", "种 zhong 0.4", "中国人 zhong'guo'ren", "国 guo", "重 chong", "重 zhong 0.2")
        val (d, _) = compile(*words.toTypedArray())
        val seen = ArrayList<String>()
        // at its node, best first; a node's children after it (breadth first: a walk up ends at the root)
        fun walk(node: Int, path: List<Int>) {
            for (i in 0 until d.wordCount(node)) {
                val p = d.word(node, i)
                seen += d.text(p)
                assertArrayEquals(path.toIntArray(), d.syllables(p))
                if (i > 0) assertTrue(d.score(d.word(node, i - 1)) >= d.score(p))
            }
            for (c in d.firstChild(node) until d.firstChild(node) + d.childCount(node)) {
                assertTrue("breadth first", c > node)
                walk(c, path + d.nodeSyllable(c))
            }
        }
        walk(DictionaryFile.ROOT, emptyList())
        assertEquals(words.map { it.substringBefore(' ') }.sorted(), seen.sorted())
        assertEquals(listOf("种", "重", "中"), d.at("zhong"))
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
        // none weighted however written (no weight, 0, 0.0): 0 and +0 exactly, List equality tells -0f apart
        assertEquals(listOf(0f, 0f, 0f), scores("好 hao", "坏 huai 0", "幽默 you'mo 0.0"))
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
        // one the base has: a word apart, its syllables stored
        // three words: a place of 2 bits, so one at size can be written
        DictionaryCompiler.compile(listOf(sequenceOf("幽默 you'mo 0.5", "好 hao 0.2", "中国 zhong'guo 0.1")), file, inBase = { t, _ -> t == "中国" })
        val bytes = file.readBytes()
        assertEquals(3, DictionaryFile.of(rewritten(bytes)).size)
        // the message names the whole table: "syllables" must not pass on node syllables' message. A
        // run's or a bound's check names its table first ("text places: 3 at 0 is not below 3"), the
        // rest end on it
        data class Row(
            val why: String,
            val meta: (Map<String, String>) -> Map<String, String>,
            val change: (Int, ByteBuffer) -> Unit,
            val named: (why: String, message: String) -> Boolean,
        )
        val endsOn = { why: String, m: String -> m.endsWith("bad structure: $why") }
        val first = { why: String, m: String -> ": $why: " in m }
        val rows = listOf(
            Row("other syllables", { it + ("syllableChecksum" to "1") }, { _, _ -> }, { why, m -> m.endsWith("of $why") }),
            // BitPacked's values from byte 8, after its width and size; EliasFano's upper bits after
            // its 16 bytes of header and its low bits, cleared: no value where one must be
            Row("text buckets", { it }, { id, b -> if (id == DictionaryFile.TEXT_BUCKETS) b.putLong(eliasFanoUpper(b), 0L) }, first),
            // review F3634: a place past the words, the index every forEachOfText follows
            Row("text places", { it }, { id, b -> if (id == DictionaryFile.TEXT_PLACES) putPacked(b, 0, 3) }, first),
            // a node's syllable past the table, node 1 (the root's has none)
            Row("node syllables", { it }, { id, b -> if (id == DictionaryFile.NODE_SYLLABLE) putPacked(b, 1, Syllables.count) }, endsOn),
            Row("node words", { it }, { id, b -> if (id == DictionaryFile.WORD_START) b.putLong(8, -1L) }, first),
            Row("children", { it }, { id, b -> if (id == DictionaryFile.FIRST_CHILD) b.putLong(8, 0L) }, endsOn),
            Row("syllables", { it }, { id, b -> if (id == DictionaryFile.SYLLABLES) b.putShort(0, Short.MAX_VALUE) }, endsOn),
            Row("score codes", { it }, { id, b -> if (id == DictionaryFile.SCORES) b.putShort(0, Short.MAX_VALUE) }, endsOn),
        )
        assertRows(rows, { it.why }) { row ->
            val e = assertThrows(row.why, IOException::class.java) { DictionaryFile.of(rewritten(bytes, row.meta, row.change)) }
            assertTrue("${row.why}: ${e.message}", row.named(row.why, e.message!!))
        }
    }

    // a BitPacked section's [index]th value set to [value], which its width must hold
    private fun putPacked(b: ByteBuffer, index: Int, value: Int) {
        val width = b.getInt(0)
        assertTrue("$value in $width bits", value < 1 shl width && (index + 1) * width <= 64)
        val mask = ((1L shl width) - 1) shl (index * width)
        b.putLong(8, (b.getLong(8) and mask.inv()) or (value.toLong() shl (index * width)))
    }

    // where an EliasFano section's upper bits begin: after its header (low width, size, upper
    // words, samples) and its size low bits a value, in whole words (review F3635)
    private fun eliasFanoUpper(b: ByteBuffer): Int = 16 + 8 * ((b.getInt(0).toLong() * b.getInt(4) + 63) / 64).toInt()

    // review F3622: a damaged file read unverified (merged.ymd before the compile thread checks it)
    // whose first word or first child points past where a walk up from it ends: syllables and length
    // return, never a walk that does not end (v1's text chain guard, R12 F3360, gone with the chain);
    // the timeout makes a walk that hangs again fail rather than stall the build (F3653)
    @Test(timeout = 10_000)
    fun aWalkUpInAnUncheckedDamagedFileEnds() {
        val (d0, _) = compile("中国 zhong'guo", "中 zhong")
        val bytes = File(folder.root, "d.ymd").readBytes()
        assertEquals(2, d0.trieWords)
        val rows = listOf<Pair<String, (Int, ByteBuffer) -> Unit>>(
            "words start past 0" to { id, b -> if (id == DictionaryFile.WORD_START) putPacked(b, 0, 1) },
            "children start past 1" to { id, b -> if (id == DictionaryFile.FIRST_CHILD) putPacked(b, 0, 2) },
        )
        assertRows(rows, { it.first }) { (_, change) ->
            val d = DictionaryFile.of(rewritten(bytes, change = change), verify = false)
            for (p in 0 until d.size) assertEquals(d.syllables(p).size, d.length(p))
        }
    }

    // review F3636: the codebook's edge, 65536 scores (the last code 65535) told apart, one more linear
    @Test
    fun theCodebookHoldsExactlyAU16sScores() {
        // fewer than MAX_PER_READING a reading
        val readings = listOf("shi", "you", "mo", "zhong", "guo", "ren", "hao", "de").map { "$it'shi" }
        for (n in listOf(DictionaryFile.CODE_MASK + 1, DictionaryFile.CODE_MASK + 2)) {
            fun text(i: Int) = String(charArrayOf((0x4E00 + i % 20000).toChar(), (0x4E00 + i / 20000).toChar()))
            val lines = (0 until n).map { "${text(it)} ${readings[it % readings.size]} ${(it + 1).toFloat() / n}" }
            val (d, _) = compile(*lines.toTypedArray())
            assertEquals(n, d.size)
            val kind = DataFile.open(ByteBuffer.wrap(File(folder.root, "d.ymd").readBytes()), DataFile.KIND_USER_DICTIONARY, DictionaryFile.VERSION)
                .meta(DictionaryFile.META)["scores"]
            assertEquals("$n", if (n <= DictionaryFile.CODE_MASK + 1) "book" else "linear", kind)
            // the book's each code its own score, the last (65535) not wrapped onto another
            if (n == DictionaryFile.CODE_MASK + 1) assertEquals(n, (0 until n).map { d.score(it) }.toSet().size)
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

    // a random dictionary's words (surrogates, text longer in chars than its reading, some the
    // base's), each read back whole: text, syllables by the node's path or as stored, score exact
    // through the codebook; found by reading and by text, each at a place of its own
    @Test
    fun aRandomDictionaryReadsBackWordForWord() {
        val r = Random(3)
        val spellings = (0 until Syllables.count).filter { Syllables.spelling(it)[0].isLowerCase() }
        val weights = FloatArray(40) { (it + 1) / 40f }
        val words = LinkedHashMap<Pair<String, List<Int>>, Float>()
        while (words.size < 3000) {
            val reading = List(1 + r.nextInt(4)) { spellings[r.nextInt(spellings.size)] }
            // a character a syllable, some past the BMP: more chars than syllables. Never fewer: a
            // source's line of fewer characters than syllables is refused (LibimeImport.entry), review F3631
            val text = buildString {
                repeat(reading.size) { if (r.nextInt(6) == 0) appendCodePoint(0x20000 + r.nextInt(500)) else append((0x4E00 + r.nextInt(300)).toChar()) }
            }
            words.putIfAbsent(text to reading, weights[r.nextInt(weights.size)])
        }
        val apart = words.keys.filter { r.nextInt(5) == 0 }.toSet()
        val lines = words.map { (k, w) -> "${k.first} ${k.second.joinToString("'") { Syllables.spelling(it) }} $w" }
        val (d, result) = compile(*lines.toTypedArray()) { text, s -> (text to s.toList()) in apart }
        assertEquals(words.size, d.size)
        assertEquals(apart.size, result.inBase)
        assertEquals(words.size - apart.size, d.trieWords)
        val best = words.values.max()
        val seen = HashSet<Int>()
        for ((k, w) in words) {
            val (text, reading) = k
            val p = d.find(text, reading.toIntArray())
            assertTrue("$text $reading", p >= 0 && seen.add(p))
            assertEquals(k in apart, p >= d.trieWords)
            assertEquals(text, d.text(p))
            assertEquals(reading, d.syllables(p).toList())
            assertEquals(reading.size, d.length(p))
            assertEquals(UserModel.Entry(text, reading.toIntArray()), d.entry(p))
            assertEquals(log10(w / best), d.score(p), 0f)
            assertTrue(p in d.placesOf(text))
        }
    }

    // more scores than a u16 code tells apart: linear, within half a step, a node's order kept
    @Test
    fun moreDistinctScoresThanCodesReadWithinHalfAStepInTheirOrder() {
        val n = 70_000
        // fewer than MAX_PER_READING a reading
        val readings = listOf("shi", "you", "mo", "zhong", "guo", "ren", "hao", "de").map { listOf(it, "shi") }
        fun text(i: Int) = String(charArrayOf((0x4E00 + i % 20000).toChar(), (0x4E00 + i / 20000).toChar()))
        fun reading(i: Int) = readings[i % readings.size]
        val lines = (0 until n).map { "${text(it)} ${reading(it).joinToString("'")} ${(it + 1).toFloat() / n}" }
        val (d, _) = compile(*lines.toTypedArray())
        assertEquals(n, d.size)
        val floor = log10(1f / n)
        val step = -floor / DictionaryFile.CODE_MASK
        for (i in 0 until n step 7) {
            val p = d.find(text(i), ids(*reading(i).toTypedArray()))
            assertEquals(log10((i + 1).toFloat() / n), d.score(p), step / 2 + 1e-6f)
        }
        // the best +0f exactly, not floor * 0 = -0f, which assertEquals(0f, x, 0f) lets through (review F3623)
        assertEquals(0, floatToRawIntBits(d.score(d.find(text(n - 1), ids(*reading(n - 1).toTypedArray())))))
        val node = d.child(d.child(DictionaryFile.ROOT, Syllables.id("shi")), Syllables.id("shi"))
        val texts = (0 until d.wordCount(node)).map { d.text(d.word(node, it)) }
        assertEquals((0 until n step readings.size).reversed().map(::text), texts)
        for (i in 1 until d.wordCount(node)) assertTrue(d.score(d.word(node, i - 1)) >= d.score(d.word(node, i)))
    }

    // a file of the format before (one int a place for text and syllables, a chained text index) is refused, and the app compiles again
    @Test
    fun aFileOfTheFormerVersionIsRefused() {
        val file = File(folder.root, "v1.ymd")
        val meta = mapOf("words" to "0", "trieWords" to "0", "nodes" to "1", "hashSize" to "1")
        file.writeBytes(DataFile.Writer(DataFile.KIND_USER_DICTIONARY, DictionaryFile.VERSION - 1).addMeta(DictionaryFile.META, meta).toByteArray())
        val e = assertThrows(IOException::class.java) { DictionaryFile.open(file) }
        assertTrue(e.message, e.message!!.contains("version 1"))
    }
}
