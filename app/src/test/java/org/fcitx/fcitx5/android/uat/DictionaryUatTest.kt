/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries
import org.fcitx.fcitx5.android.data.pinyin.LegacyTextConversion
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.dict.SourceNames
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.DictionaryFormat
import org.fcitx.fcitx5.android.ui.main.settings.Incoming
import org.fcitx.fcitx5.android.ui.main.settings.USER_MAX_WORDS
import org.fcitx.fcitx5.android.ui.main.settings.startsInUser
import org.fcitx.fcitx5.android.ui.main.settings.tooLargeForUser
import org.fcitx.fcitx5.android.ui.main.settings.userCapMessage
import org.fcitx.fcitx5.android.ui.main.settings.wordsForUser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * uat/13-words.md, the base dictionary's half: a file imported (its format as the confirmation
 * names it, or refused and why), turned off and on, deleted, cancelled, and what the merged file
 * the engine maps then holds. The files are uat/fixtures', imported as the page imports them
 * ([PinyinDictManager.prepare], then [PinyinDictManager.importPrepared]); a dictionary's row is
 * its state (on or off, its words, unreadable or not converted), the page's text of it its own.
 * "候选里有 X" is X in the merged file: what typing reads of an import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class DictionaryUatTest {

    private val dir get() = PinyinDictManager.pinyinDicDir

    private fun fixture(name: String) = File("../uat/fixtures", name).also { check(it.isFile) { "no fixture $it" } }

    // the files of the directory but the merged file and its dot files
    private fun files() = dir.list()!!.filter { !it.startsWith(".") && !MergedDictionary.isCompiled(File(dir, it)) }.sorted()

    private fun merged(): DictionaryFile? = File(dir, MergedDictionary.FILE).takeIf { it.isFile }?.let { DictionaryFile.open(it) }

    private fun typed(text: String) = merged()?.hasText(text) == true

    private fun prepare(name: String, file: File = fixture(name)) = PinyinDictManager.prepare(file.inputStream(), name, file.length())

    private fun detected(name: String) = prepare(name).use { it.analysis as DictionaryFormat.Detected }

    // the page's 导入词库 into the base: analysed, confirmed, imported
    private fun import(name: String, file: File = fixture(name)) = prepare(name, file).use { PinyinDictManager.importPrepared(it, Incoming(name).name) }

    private fun refused(name: String): String? = import(name).exceptionOrNull()?.message

    // as the page waits for the merged file to be made again after a change (合好)
    private fun made() = MergedDictionary.refreshNow(dir)

    private fun listed() = PinyinDictManager.listDictionaries().associate { it.name to it.isEnabled }

    @Before
    fun empty() {
        MergedDictionary.reset()
        MergedDictionary.made = {}
        MergedDictionary.base = { null }
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.mkdirs()
    }

    @After
    fun done() {
        MergedDictionary.told = {}
        dir.listFiles()?.forEach { it.deleteRecursively() }
        MergedDictionary.reset()
    }

    @Test
    @Uat("WD-11")
    fun aTextDictionaryIntoTheBase() {
        assertFalse(startsInUser(Incoming("dict-words.txt")))
        import("dict-words.txt").getOrThrow()
        assertEquals(mapOf("dict-words" to true), listed())
        assertTrue(typed("氪喵糖铺"))
    }

    @Test
    @Uat("WD-13")
    fun turnedOffThenDeleted() {
        val dictionary = import("dict-words.txt").getOrThrow()
        assertEquals(listOf("dict-words.txt.gz"), files())
        assertTrue(PinyinDictManager.turn(dictionary, false))
        made()
        assertEquals(listOf("dict-words.txt.gz.disable"), files())
        assertFalse(typed("氪喵糖铺"))
        assertTrue(PinyinDictManager.delete(PinyinDictManager.listDictionaries().single()))
        made()
        assertEquals(emptyList<String>(), files())
        assertTrue(listed().isEmpty())
        assertFalse(typed("氪喵糖铺"))
    }

    @Test
    @Uat("WD-14e")
    fun aRimeDictionaryKnownAndImported() {
        assertFalse(startsInUser(Incoming("rime-words.dict.yaml")))
        val d = detected("rime-words.dict.yaml")
        // Rime 词库 · 拼音用空格分开 · 第三列是词频 · 3 个词
        assertEquals(DictionaryFormat.Source.RIME, d.source)
        assertEquals(DictionaryFormat.Reading.SPACES, d.reading)
        assertEquals(DictionaryFormat.Weight.COUNT, d.weight)
        assertEquals(3, d.words)
        import("rime-words.dict.yaml").getOrThrow()
        assertEquals(mapOf("rime-words" to true), listed())
        assertTrue(typed("氪喵星港"))
    }

    @Test
    @Uat("WD-14f")
    fun aFileNotSurelyReadIsRefusedWithWhy() {
        assertEquals(PinyinDictManager.refusal(DictionaryFormat.Reason.MIXED), refused("mixed-words.txt"))
        assertEquals(PinyinDictManager.refusal(DictionaryFormat.Reason.INITIALS), refused("initials-words.txt"))
        assertTrue(listed().isEmpty())
        val gbk = detected("gbk-words.txt")
        assertEquals("GB18030", gbk.charset.name())
        assertEquals(2, gbk.words)
    }

    @Test
    @Uat("WD-14g")
    fun pastAHundredThousandWordsIsNoImportForTheUser() {
        val words = 100_001
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "user-100k.txt").apply {
            bufferedWriter().use { w -> repeat(words) { w.write("${sixChars(it)} ${sixReadings(it)}\n") } }
        }
        try {
            val counted = prepare("user-100k.txt", file).use { p -> wordsForUser(p.analysis) { error("not read again") } }
            assertEquals(words, counted)
            assertTrue(tooLargeForUser(counted, 0))
            // 这个词库太大，请并入基础词库
            assertEquals(R.string.dict_too_large_for_user, userCapMessage(counted))
        } finally {
            file.delete()
        }
    }

    @Test
    @Uat("WD-14o")
    fun theUsersCapCountsWhatWasImportedBefore() {
        val big = fixture("big-words.txt")
        val imported = prepare("big-words.txt", big).use { p -> wordsForUser(p.analysis) { error("not read again") } }
        // 1001 words and the blocked line, as the user dictionary takes them
        assertEquals(1002, imported)
        // 99,500 more with 1001 imported: past the cap, 用户词库里导入的词已近上限
        assertTrue(tooLargeForUser(99_500, imported))
        assertEquals(R.string.dict_user_imports_full, userCapMessage(99_500))
        // that batch dropped: in
        assertFalse(tooLargeForUser(99_500, 0))
        assertTrue(99_500 < USER_MAX_WORDS)
    }

    @Test
    @Uat("WD-14i")
    fun aCancelledImportChangesNothing() {
        import("rime-words.dict.yaml").getOrThrow()
        val before = files()
        val cache = RuntimeEnvironment.getApplication().cacheDir
        val cancel = AtomicBoolean()
        val progress = ArrayList<Float>()
        val result = prepare("big-words.txt").use {
            PinyinDictManager.importPrepared(it, "big-words", cancel) { at -> progress += at; cancel.set(true) }
        }
        assertTrue(result.isFailure)
        assertEquals(before, files())
        assertTrue(dir.list()!!.none { it.endsWith(".tmp") || it.endsWith(".build") })
        assertTrue(cache.list()!!.toList().toString(), cache.list()!!.none { it.startsWith("import") })
        assertEquals(mapOf("rime-words" to true), listed())
        assertTrue(typed("氪喵星港"))
        assertFalse(typed("鲸喵屿"))
    }

    @Test
    @Uat("WD-14j")
    fun turnedOffOnAndDeleted() {
        val dictionary = import("rime-words.dict.yaml").getOrThrow()
        assertTrue(PinyinDictManager.turn(dictionary, false))
        made()
        assertEquals(listOf("rime-words.txt.gz.disable"), files())
        assertFalse(typed("氪喵星港"))
        assertTrue(PinyinDictManager.turn(PinyinDictManager.listDictionaries().single(), true))
        made()
        assertEquals(listOf("rime-words.txt.gz"), files())
        assertTrue(typed("氪喵星港"))
        assertTrue(PinyinDictManager.delete(PinyinDictManager.listDictionaries().single()))
        made()
        assertEquals(emptyList<String>(), files())
        assertFalse(typed("氪喵星港"))
        assertNull(MergedDictionary.work.value)
    }

    @Test
    @Uat("WD-14k")
    fun theThirdColumnRanksAsItIsRead() {
        val d = detected("log-words.txt")
        assertEquals(DictionaryFormat.Weight.LOG, d.weight)
        assertEquals(4, d.words)
        import("log-words.txt").getOrThrow()
        val m = merged()!!
        fun score(text: String) = m.placesOf(text).single().let(m::score)
        // the larger first, not the file's order
        assertTrue(score("氪喵堂果") > score("氪喵糖果"))
    }

    @Test
    @Uat("WD-14l")
    fun aBinaryFileRefusedPinyinFirstRunTogetherRead() {
        val binary = prepare("binary-words.bin").use { it.analysis }
        assertTrue("$binary", binary is DictionaryFormat.Refused && binary.reason == DictionaryFormat.Reason.BINARY)
        assertTrue(listed().isEmpty())
        val d = detected("pinyin-first.txt")
        assertEquals(DictionaryFormat.Order.PINYIN_FIRST, d.order)
        assertEquals(DictionaryFormat.Reading.RUN_TOGETHER, d.reading)
        assertEquals(3, d.words)
        import("pinyin-first.txt").getOrThrow()
        assertEquals(mapOf("pinyin-first" to true), listed())
        assertTrue(typed("氪喵屿"))
    }

    @Test
    @Uat("WD-14m")
    fun aNameInTheBaseAlreadyAsksForTheUser() {
        import("dict-words.txt").getOrThrow()
        val other = fixture("same-name/dict-words.txt")
        // asked before it is read: 基础词库里已有名为「dict-words」的词库。改为并入用户词库吗？
        assertTrue(Incoming("dict-words.txt").namedForBase && ImportedDictionaries.hasDictionary(dir, "dict-words"))
        // as the user dictionary takes it: its five words
        assertEquals(5, prepare("dict-words.txt", other).use { p -> wordsForUser(p.analysis) { error("not read again") } })
        // never into the base over the one there
        assertTrue(import("dict-words.txt", other).isFailure)
        assertEquals(listOf("dict-words"), PinyinDictManager.listDictionaries().map { it.name })
    }

    @Test
    @Uat("WD-14n")
    fun rimesPercentWeightsImported() {
        val d = detected("rime-percent.dict.yaml")
        assertEquals(DictionaryFormat.Source.RIME, d.source)
        assertEquals(3, d.words)
        assertEquals(0, d.unread)
        assertNull(d.refusedForBase)
        import("rime-percent.dict.yaml").getOrThrow()
        assertEquals(mapOf("rime-percent" to true), listed())
        assertTrue(typed("氪喵灯港"))
    }

    @Test
    @Uat("WD-14p")
    fun utf8TraditionalInBig5Not() {
        import("trad-words.txt").getOrThrow()
        assertEquals(mapOf("trad-words" to true), listed())
        assertTrue(typed("氪喵燈塔"))
        // what the base knows of these characters' readings (the app's from its assets, none in a test): the UTF-8 file's
        val known = fixture("trad-words.txt").readLines().flatMap { line ->
            val parts = line.trim().split(" ")
            parts.first().map(Char::toString).zip(parts.drop(1).map(Syllables::id))
        }.toSet()
        MergedDictionary.base = { MergedDictionary.Base("uat") { { text, syllables -> (text to syllables.single()) in known } } }
        assertEquals(PinyinDictManager.refusal(DictionaryFormat.Reason.MISMATCH), refused("trad-big5.txt"))
        assertEquals(mapOf("trad-words" to true), listed())
    }

    @Test
    @Uat("WD-14q")
    fun wordsWithoutPinyinApartTooManyNotForTheBase() {
        val some = detected("no-pinyin-some.txt")
        assertEquals(60, some.words)
        assertEquals(1, some.textOnly)
        assertEquals(PinyinDictManager.refusal(DictionaryFormat.Reason.NO_PINYIN), refused("no-pinyin-most.txt"))
        assertTrue(listed().isEmpty())
    }

    // ------------------------------------------------------------------ an older build's, upgraded

    // a dictionary as 0.3.1 kept it, text in libime's format: rime-frost's stand-in, a word of it and big-words' thousand
    private fun oldText(name: String, extra: String = "") = File(dir, "$name.txt").apply {
        writeText("阿坝州 a'ba'zhou 0.0\n" + extra + fixture("big-words.txt").readLines().filter { !it.startsWith("!") }
            .joinToString("") { line -> line.split(" ").let { "${it[0]} ${it.drop(1).joinToString("'")} 0.0\n" } })
    }

    private fun small(name: String = "small") = File(dir, "$name.txt").apply { writeText("氪喵星港 ke'miao'xing'gang 0.0\n") }

    // the keyboard shown: the engine's start compiles what an older build left; what each compile was for, told
    private fun started(): Set<MergedDictionary.Kind> {
        val kinds = Collections.synchronizedSet(HashSet<MergedDictionary.Kind>())
        MergedDictionary.told = { kinds += it.kind }
        MergedDictionary.refresh(dir)?.get(1, TimeUnit.MINUTES)
        return kinds.toSet()
    }

    // the app force-stopped as a compile runs, at [at] of it
    private fun killedAt(at: (MergedDictionary.Work) -> Boolean) {
        val cancel = AtomicBoolean()
        MergedDictionary.told = { if (at(it)) cancel.set(true) }
        assertTrue(runCatching { MergedDictionary.refreshNow(dir, cancel) }.exceptionOrNull() is CancellationException)
        // the next process: nothing of this one's object left
        MergedDictionary.reset()
        MergedDictionary.made = {}
        MergedDictionary.base = { null }
    }

    private fun all() = dir.list()!!.sorted()

    @Test
    @Uat("WD-14s")
    fun anUpgradeKilledAsItConvertsIsFinishedAtTheNextStart() {
        oldText("frost")
        // what a force stop halfway through the conversion leaves (no cancel reaches into a conversion):
        // the text, and its gzip half written, from the process before
        File(dir, "frost${SourceNames.CONVERT_TMP}").apply { writeText("half"); setLastModified(System.currentTimeMillis() - 3_600_000) }
        assertEquals(setOf(MergedDictionary.Kind.UPGRADE), started())
        assertEquals(listOf("frost.txt.gz"), files())
        assertTrue(all().toString(), all().none { it.endsWith(".tmp") })
        assertTrue(typed("阿坝州"))
        assertEquals(listOf("frost"), PinyinDictManager.listDictionaries().map { it.name })
    }

    @Test
    @Uat("WD-14s2")
    fun anUpgradeKilledAfterItsConversionIsStillAnUpgrade() {
        oldText("frost")
        killedAt { it.stage == MergedDictionary.Step.READ }
        assertEquals(listOf("frost.txt.gz"), files())
        assertTrue(all().toString(), ".merged.upgrading" in all() && MergedDictionary.FILE !in all())
        assertEquals(setOf(MergedDictionary.Kind.UPGRADE), started())
        assertFalse(".merged.upgrading" in all())
        assertTrue(MergedDictionary.FILE in all())
        assertTrue(typed("阿坝州"))
    }

    @Test
    @Uat("WD-14t")
    fun turnedOffAndDeletedAsTheyConvert() {
        oldText("frost")
        small()
        val once = AtomicBoolean()
        // the page's answers: asserted on this thread, a failure on the compile's would show as a failed compile
        val answered = Collections.synchronizedList(ArrayList<Boolean>())
        val kinds = Collections.synchronizedSet(HashSet<MergedDictionary.Kind>())
        MergedDictionary.told = {
            kinds += it.kind
            if (it.stage == MergedDictionary.Step.CONVERT && it.percent > 0 && once.compareAndSet(false, true)) {
                val listed = PinyinDictManager.listDictionaries().associateBy { d -> d.name }
                answered += PinyinDictManager.delete(listed.getValue("small"))
                answered += PinyinDictManager.turn(listed.getValue("frost"), false)
            }
        }
        MergedDictionary.refresh(dir)!!.get(1, TimeUnit.MINUTES)
        made()
        assertTrue(once.get())
        assertEquals(listOf(true, true), answered.toList())
        assertEquals(listOf("frost.txt.gz.disable"), files())
        assertEquals(mapOf("frost" to false), listed())
        assertFalse(typed("氪喵星港"))
        // turned on again: an update, never told an upgrade
        kinds.clear()
        assertTrue(PinyinDictManager.turn(PinyinDictManager.listDictionaries().single(), true))
        made()
        assertEquals(setOf(MergedDictionary.Kind.UPDATE), kinds.toSet())
        assertTrue(typed("阿坝州"))
    }

    @Test
    @Uat("WD-14t2")
    fun aTextLeftAfterAnUpgradeIsDeletedNotConvertedAgain() {
        oldText("frost")
        assertEquals(setOf(MergedDictionary.Kind.UPGRADE), started())
        fun leave() {
            val left = small("frost")
            File(dir, ".merged.left").writeText(LegacyTextConversion.leftKey(left) + "\n")
        }
        leave()
        // the page opened: its retry deletes the text, never converts it
        val kinds = Collections.synchronizedSet(HashSet<MergedDictionary.Kind>())
        MergedDictionary.told = { kinds += it.kind }
        MergedDictionary.retry(dir)?.get(1, TimeUnit.MINUTES)
        assertFalse(all().toString(), "frost.txt" in all() || ".merged.left" in all())
        leave()
        assertTrue(PinyinDictManager.turn(PinyinDictManager.listDictionaries().single(), false))
        made()
        assertTrue(PinyinDictManager.turn(PinyinDictManager.listDictionaries().single(), true))
        made()
        assertTrue("$kinds", MergedDictionary.Kind.UPGRADE !in kinds)
        assertFalse(all().toString(), "frost.txt" in all() || ".merged.left" in all())
        assertTrue("frost.txt.gz" in all())
        assertEquals(mapOf("frost" to true), listed())
        assertTrue(typed("阿坝州"))
        assertFalse(typed("氪喵星港"))
    }

    @Test(timeout = 60_000)
    @Uat("WD-14w")
    fun anImportWhileAnUpgradeConverts() {
        oldText("frost")
        val converting = CountDownLatch(1)
        val go = CountDownLatch(1)
        MergedDictionary.told = {
            if (it.stage == MergedDictionary.Step.CONVERT && converting.count > 0) {
                converting.countDown()
                go.await()
            }
        }
        val upgrade = MergedDictionary.refresh(dir)!!
        converting.await()
        var imported: Result<*>? = null
        val importing = thread { imported = import("dict-words.txt") }
        go.countDown()
        upgrade.get(1, TimeUnit.MINUTES)
        importing.join()
        imported!!.getOrThrow()
        made()
        assertEquals(listOf("dict-words.txt.gz", "frost.txt.gz"), files())
        assertEquals(mapOf("dict-words" to true, "frost" to true), listed())
        assertTrue(typed("氪喵糖铺"))
        assertTrue(typed("阿坝州"))
        assertTrue(all().toString(), all().none { it.endsWith(".tmp") || it.endsWith(".txt") })
    }

    @Test
    @Uat("WD-14x")
    fun aDamagedFileIsSkippedTheRestMerged() {
        import("dict-words.txt").getOrThrow()
        import("rime-words.dict.yaml").getOrThrow()
        val gz = File(dir, "dict-words.txt.gz")
        gz.writeBytes(gz.readBytes().copyOf(30))
        MergedDictionary.reset()
        started()
        assertEquals(setOf("dict-words"), MergedDictionary.unreadable(dir))
        assertEquals(0, PinyinDictManager.wordCount(PinyinDictManager.listDictionaries().single { it.name == "dict-words" }))
        assertTrue(typed("氪喵星港"))
        assertFalse(typed("氪喵糖铺"))
        assertFalse(File(dir, ".merged.failed").exists())
        assertTrue(PinyinDictManager.delete(PinyinDictManager.listDictionaries().single { it.name == "dict-words" }))
        made()
        import("dict-words.txt").getOrThrow()
        assertTrue(typed("氪喵糖铺"))
        assertEquals(emptySet<String>(), MergedDictionary.unreadable(dir))
        assertEquals(listOf("dict-words", "rime-words"), PinyinDictManager.listDictionaries().map { it.name }.sorted())
    }

    @Test
    @Uat("WD-14x2")
    fun aFileDamagedHalfwayGivesWhatWasReadBefore() {
        import("big-words.txt").getOrThrow()
        val gz = File(dir, "big-words.txt.gz")
        gz.writeBytes(gz.readBytes().let { it.copyOf(it.size * 2 / 3) })
        MergedDictionary.reset()
        started()
        assertEquals(setOf("big-words"), MergedDictionary.unreadable(dir))
        val words = PinyinDictManager.wordCount(PinyinDictManager.listDictionaries().single())
        // 文件损坏，并入了 N 个词，可能不全
        assertTrue("$words", words in 1 until 1001)
        assertTrue(typed("鲸喵屿"))
        assertFalse(typed("橘星屿"))
    }

    @Test
    @Uat("WD-14y")
    fun aTextNotConvertedForSpaceIsKeptTillThereIsRoom() {
        small()
        File(dir, "small${SourceNames.CONVERT_TMP}/full").mkdirs()
        File(dir, "small${SourceNames.CONVERT_TMP}/full/x").createNewFile()
        started()
        assertEquals(setOf("small"), MergedDictionary.unconverted(dir))
        assertEquals(emptySet<String>(), MergedDictionary.unreadable(dir))
        assertFalse(typed("氪喵星港"))
        assertTrue(File(dir, "small.txt").exists())
        File(dir, "small${SourceNames.CONVERT_TMP}").deleteRecursively()
        // the page opened again
        MergedDictionary.retry(dir)!!.get(1, TimeUnit.MINUTES)
        assertEquals(listOf("small.txt.gz"), files())
        assertEquals(emptySet<String>(), MergedDictionary.unconverted(dir))
        assertEquals(1, PinyinDictManager.wordCount(PinyinDictManager.listDictionaries().single()))
        assertTrue(typed("氪喵星港"))
    }

    @Test(timeout = 60_000)
    @Uat("WD-14j2")
    fun anotherTurnedOffWhileABigOneCompiles() {
        import("rime-words.dict.yaml").getOrThrow()
        val big = import("big-words.txt").getOrThrow()
        assertTrue(PinyinDictManager.turn(big, false))
        made()
        val reading = CountDownLatch(1)
        val go = CountDownLatch(1)
        MergedDictionary.told = {
            if (it.stage == MergedDictionary.Step.READ && reading.count > 0) {
                reading.countDown()
                go.await()
            }
        }
        val failed = Collections.synchronizedList(ArrayList<Boolean>())
        val listening = CoroutineScope(Dispatchers.Unconfined).launch { MergedDictionary.done.collect { if (!it.second) failed += true } }
        try {
            assertTrue(PinyinDictManager.turn(PinyinDictManager.listDictionaries().single { it.name == "big-words" }, true))
            reading.await()
            // 正在更新 N%: the other turned off then
            assertTrue(PinyinDictManager.turn(PinyinDictManager.listDictionaries().single { it.name == "rime-words" }, false))
            go.countDown()
            made()
        } finally {
            go.countDown()
            listening.cancel()
        }
        assertTrue("词库没能更新 $failed", failed.isEmpty())
        assertEquals(mapOf("big-words" to true, "rime-words" to false), listed())
        assertEquals(1001, PinyinDictManager.wordCount(PinyinDictManager.listDictionaries().single { it.name == "big-words" }))
        assertFalse(typed("氪喵星港"))
    }

    companion object {
        private const val CHARS = "啊吧擦大饿发嘎哈鸡卡"
        private val READINGS = "a ba ca da e fa ga ha ji ka".split(" ")

        // WD-14g's words: the n-th six-character word of those ten characters, and its reading
        private fun digits(n: Int) = (5 downTo 0).map { n / pow(it) % 10 }
        private fun pow(e: Int) = (0 until e).fold(1) { a, _ -> a * 10 }
        fun sixChars(n: Int) = digits(n).joinToString("") { CHARS[it].toString() }
        fun sixReadings(n: Int) = digits(n).joinToString("'") { READINGS[it] }
    }
}
