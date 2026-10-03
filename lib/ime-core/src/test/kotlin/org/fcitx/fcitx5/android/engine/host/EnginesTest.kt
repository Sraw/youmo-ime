/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.remote.RemoteModel
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.rerank.TinyModel
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.UserLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

class EnginesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("拟", -3f, 0f)
        .unigram("好", -2.5f, 0f)
        .bigram("好", "拟", -0.5f, 0f)
        .entry("你", syl("ni"))
        .entry("拟", syl("ni"))
        .entry("好", syl("hao"))
        .build()
        .toByteArray()

    private val wubi = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .entry("wqiy", "你")
        .entry("vbg", "好")
        .entry("vbgf", "妤")
        .apply { "一二三四五六七".forEachIndexed { i, c -> entry("va" + ('a' + i) + "a", c.toString()) } }
        .build()
        .toByteArray()

    private var model: ByteArray? = TinyModel().bytes()
    private var refining: ByteArray? = TinyModel().bytes()

    private val loaded = ArrayList<String>()

    private fun load(path: String): ByteBuffer {
        loaded += path
        val bytes = when (path) {
            Engines.PINYIN_DATA -> pinyin
            "${Engines.TABLE_DIR}/wbx.data" -> wubi
            Engines.SENTENCE_MODEL -> model
            Engines.REFINING_MODEL -> refining
            else -> throw IllegalArgumentException(path)
        }
        // as the app's assets do of a file it has not got
        return ByteBuffer.wrap(bytes ?: throw FileNotFoundException(path))
    }

    private fun Engines.type(im: String, keys: String) = keys.map { onEvent(im, EngineEvent.CHAR, it.code) }.last()

    /** What the addon does while the user pauses after [typed]: refines till there is no more to do. */
    private fun Engines.pause(im: String, typed: Snapshot): List<Snapshot> {
        val slices = ArrayList<Snapshot>()
        var s = typed
        while (s.refines) {
            s = onEvent(im, EngineEvent.REFINE, 0)
            slices += s
            assertTrue("never done", slices.size < MAX_SLICES)
        }
        return slices
    }

    @Test
    fun pinyinIsTypedAndPicked() {
        val engines = Engines(::load, null)
        assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
        assertEquals("你", engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code).commit)
    }

    @Test
    fun theRestOfTheCandidatesAreFetchedFromTheSession() {
        val engines = Engines(::load, null)
        assertTrue(engines.candidates(Engines.PINYIN, 0, 5).isEmpty())
        val shown = engines.type(Engines.PINYIN, "ni")
        assertEquals(2, shown.total)
        assertEquals(listOf(Choice("拟")), engines.candidates(Engines.PINYIN, 1, 5))
        assertEquals("拟", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1).commit)
    }

    @Test
    fun aKernelGivenMultipliesTheSentenceModelsAsTheKotlinOneDoes() {
        var calls = 0
        val kernel = MatrixKernel { q, scales, rows, columns, x, y -> calls++; MatrixKernel.JVM.times(q, scales, rows, columns, x, y) }
        val expected = Engines(::load, null).type(Engines.PINYIN, "nihao").candidates
        assertEquals(0, calls)
        assertEquals(expected, Engines(::load, null, kernel = kernel).type(Engines.PINYIN, "nihao").candidates)
        assertTrue(calls > 0)
    }

    @Test
    fun aTableIsLoadedOnlyWhenItsInputMethodIsUsed() {
        val engines = Engines(::load, null)
        engines.type(Engines.PINYIN, "ni")
        // the larger model only once the user pauses
        assertEquals(listOf(Engines.PINYIN_DATA, Engines.SENTENCE_MODEL), loaded)
        loaded.clear()
        val s = engines.type("engine-wubi", "vbg")
        assertEquals(listOf("好", "妤"), s.candidates)
        assertEquals(listOf("", "f"), s.hints)
        // wubi's pinyin lookup shares the pinyin data
        assertEquals(listOf("${Engines.TABLE_DIR}/wbx.data"), loaded)
    }

    @Test
    fun theTextBeforeTheCursorIsTheContextOfWhatIsTypedNext() {
        val engines = Engines(::load, null)
        // no keyboard yet: nothing to tell
        engines.context("好")
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        engines.context("好")
        assertEquals(listOf("拟", "你"), engines.type(Engines.PINYIN, "ni").candidates)
    }

    @Test
    fun eachInputMethodKeepsItsOwnInput() {
        val engines = Engines(::load, null)
        engines.type(Engines.PINYIN, "ni")
        engines.type("engine-wubi", "vb")
        assertEquals("ni", engines.type(Engines.PINYIN, "h").preedit.replace(" ", "").take(2))
    }

    @Test
    fun anUnknownInputMethodIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { Engines(::load, null).onEvent("nope", EngineEvent.CHAR, 'a'.code) }
    }

    @Test
    fun whatIsPickedIsKeptUnderTheUserDirectory() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
            // picked until it leads
            repeat(MAX_PICKS) {
                if (engines.type(Engines.PINYIN, "ni").candidates.first() == "拟") return@repeat
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1)
            }
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        }
        assertTrue(dir.resolve(Engines.USER_PINYIN).length() > 0)
        // and read back: 拟 still comes first
        Engines(::load, dir).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
    }

    @Test
    fun afterAPageTurnAPickIsOfTheCandidateShown() {
        val engines = Engines(::load, null)
        engines.settings = EngineSettings(pageSize = 3)
        engines.type("engine-wubi", "va")
        val page = engines.onEvent("engine-wubi", EngineEvent.PAGE_DOWN, 0)
        assertEquals(3, page.first)
        assertEquals(page.candidates, engines.candidates("engine-wubi", page.first, 3).map { it.text })
        assertEquals("五", engines.onEvent("engine-wubi", EngineEvent.PICK, page.first + 1).commit)
    }

    @Test
    fun nothingIsLearnedWhereTheAppSaysNotTo() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
            repeat(MAX_PICKS) {
                engines.type(Engines.PINYIN, "ni")
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1, learning = false)
            }
            assertEquals("你", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
    }

    private var dictionaryReads = 0

    private fun added(phrases: String, dictionaries: String, vararg lines: String) =
        Engines.Additions(phrases, dictionaries) { dictionaryReads++; lines.toList() }

    private fun Engines.pickUntilFirst(text: String) {
        repeat(MAX_PICKS) {
            onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            val shown = type(Engines.PINYIN, "ni").candidates
            if (shown.first() != text) onEvent(Engines.PINYIN, EngineEvent.PICK, shown.indexOf(text))
        }
        onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        assertEquals(text, type(Engines.PINYIN, "ni").candidates.first())
        onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
    }

    @Test
    fun aWordPackIsTypedScoredAsItSaysAndItsLayerLearned() {
        val errors = ArrayList<IOException>()
        val pack = "# youmo words 1\n# layer: 2026q1\n泥 ni -2.5\n妮 ni -9\n"
        val additions = Engines.Additions("", "p", packs = { listOf("new.words" to pack.lineSequence(), "old.words" to "你 ni 0".lineSequence()) }) { emptyList() }
        Engines(::load, folder.newFolder("engine"), { errors += it }, additions = { additions }).use { engines ->
            // 泥 at -2.5 sits between 你 (-2) and 拟 (-3); 妮 under the unknown word's -7
            assertEquals(listOf("你", "泥", "拟", "妮"), engines.type(Engines.PINYIN, "ni").candidates)
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1)
            // the file that is no pack is reported, the pack still read
            assertEquals(1, errors.size)
            assertEquals("old.words:1: not a word pack: expected \"# youmo words 1\"", errors[0].message)
        }
        // the prior learned is kept with the user's words, and the pack's words come back scored
        Engines(::load, File(folder.root, "engine"), { errors += it }, additions = { additions }).use { engines ->
            assertEquals(listOf("你", "泥", "拟", "妮"), engines.type(Engines.PINYIN, "ni").candidates)
            repeat(10) {
                engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
                engines.type(Engines.PINYIN, "ni")
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1)
            }
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            // 11 picks of 0.05 on its layer, and its own counts: 泥 over 你
            assertEquals("泥", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
    }

    @Test
    fun whatTheUserAddedIsReadAtFirstNeedAndAgainOnReload() {
        var additions = added("ni,1=呢\n", "a", "泥 ni 0", "nonsense")
        var reads = 0
        Engines(::load, folder.newFolder("engine"), additions = { reads++; additions }).use { engines ->
            assertEquals(0, reads)
            assertEquals(listOf("呢", "你", "拟", "泥"), engines.type(Engines.PINYIN, "ni").candidates)
            engines.type(Engines.SHUANGPIN, "ni")
            assertEquals(1, reads)
            assertEquals(1, dictionaryReads)
            // what was typed is dropped, and what changed is read: the phrases only
            additions = added("", "a", "泥 ni 0")
            engines.reload()
            assertEquals(2, reads)
            assertEquals(listOf("你", "拟", "泥"), engines.type(Engines.PINYIN, "ni").candidates)
            assertEquals(1, dictionaryReads)
            // the dictionaries too
            additions = added("", "")
            engines.reload()
            assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
            assertEquals(2, dictionaryReads)
        }
    }

    @Test
    fun whatWasLearnedOutlivesADictionaryChange() {
        var additions = added("", "a", "泥 ni 0")
        Engines(::load, folder.newFolder("engine"), additions = { additions }).use { engines ->
            engines.pickUntilFirst("拟")
            additions = added("", "b", "泥 ni 0", "尼 ni 0")
            engines.reload()
            val shown = engines.type(Engines.PINYIN, "ni").candidates
            assertEquals("拟", shown.first())
            assertTrue("尼" in shown)
        }
    }

    @Test
    fun withNoLogADictionaryChangeAddsToWhatIsInMemory() {
        var additions = added("", "a", "泥 ni 0")
        val engines = Engines(::load, null, additions = { additions })
        engines.pickUntilFirst("拟")
        additions = added("", "b", "尼 ni 0")
        engines.reload()
        val shown = engines.type(Engines.PINYIN, "ni").candidates
        assertEquals("拟", shown.first())
        assertTrue("尼" in shown && "泥" in shown)
    }

    @Test
    fun aCandidatePinnedFromEitherPinyinIsSavedAndOfferedInBoth() {
        val saved = ArrayList<String>()
        val errors = ArrayList<IOException>()
        var fail = false
        val additions = Engines.Additions("", "", { if (fail) throw IOException("full") else saved += it.all.joinToString(" ") }) { emptyList() }
        val engines = Engines(::load, null, { errors += it }, additions = { additions })
        assertEquals(emptySet<Offer>(), engines.offers(Engines.PINYIN, 0))
        val shown = engines.type(Engines.PINYIN, "ni")
        assertTrue(shown.actionable)
        assertEquals(setOf(Offer.FORGET, Offer.PIN), engines.offers(Engines.PINYIN, 1))
        assertEquals(listOf("拟", "你"), engines.onEvent(Engines.PINYIN, EngineEvent.PIN, 1).candidates)
        assertEquals(listOf("ni,1=拟"), saved)
        assertEquals(listOf("拟", "你"), engines.type(Engines.SHUANGPIN, "ni").candidates)
        // the decoder's 拟 moved first: still a word to forget
        assertEquals(setOf(Offer.FORGET, Offer.UNPIN), engines.offers(Engines.SHUANGPIN, 0))
        // not saved: kept till the next start all the same
        fail = true
        assertEquals(listOf("你", "拟"), engines.onEvent(Engines.SHUANGPIN, EngineEvent.UNPIN, 0).candidates)
        assertEquals(1, errors.size)
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
    }

    @Test
    fun additionsThatCannotBeReadLeavePinyinWorking() {
        val errors = ArrayList<IOException>()
        val engines = Engines(::load, null, { errors += it }, additions = { throw IOException("no") })
        assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
        assertEquals(1, errors.size)
        val dictionaries = Engines(::load, null, { errors += it }, additions = {
            Engines.Additions("ni,1=呢", "a") { throw IOException("no") }
        })
        assertEquals(listOf("呢", "你", "拟"), dictionaries.type(Engines.PINYIN, "ni").candidates)
        assertEquals(2, errors.size)
    }

    @Test
    fun theSentenceModelsAreLoadedOnceForBothPinyinsUnlessTurnedOff() {
        val models = listOf(Engines.SENTENCE_MODEL, Engines.REFINING_MODEL)
        val engines = Engines(::load, null)
        engines.pause(Engines.PINYIN, engines.type(Engines.PINYIN, "ni"))
        engines.pause(Engines.SHUANGPIN, engines.type(Engines.SHUANGPIN, "ni"))
        assertEquals(models, loaded.filter { it in models })
        loaded.clear()
        val off = Engines(::load, null)
        off.settings = EngineSettings(sentenceModel = false)
        assertEquals(listOf("你", "拟"), off.type(Engines.PINYIN, "ni").candidates)
        assertTrue(loaded.none { it in models })
        // dropped when turned off, and read again when turned back on
        engines.settings = EngineSettings(sentenceModel = false)
        engines.settings = EngineSettings()
        engines.pause(Engines.PINYIN, engines.type(Engines.PINYIN, "ni"))
        assertEquals(models, loaded.filter { it in models })
    }

    /** Puts the last of what it is asked about first, and offers 好久不见; counts the questions. */
    private class Server : RemoteModel {
        var scored = 0
        override fun score(context: String, candidates: List<String>): Future<FloatArray> {
            scored++
            return CompletableFuture.completedFuture(FloatArray(candidates.size) { if (it == candidates.lastIndex) 0f else -100f })
        }
    }

    @Test
    fun theUsersServerIsAskedAsTheyPauseWhileItIsOn() {
        val server = Server()
        var on = true
        val engines = Engines(::load, null, remote = { server.takeIf { on } })
        val typed = engines.type(Engines.PINYIN, "ni")
        val refined = engines.pause(Engines.PINYIN, typed).last()
        assertEquals(1, server.scored)
        assertNotEquals(typed.candidates.first(), refined.candidates.first())
        assertEquals(refined.candidates.first(), engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 0).commit)
        // after a commit the predictions are the phone's alone: the server is not asked
        engines.type(Engines.PINYIN, "hao")
        assertFalse(engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 0).refines)
        assertEquals(1, server.scored)
        // turned off: asked no more
        on = false
        engines.pause(Engines.PINYIN, engines.type(Engines.PINYIN, "ni"))
        assertEquals(1, server.scored)
    }

    @Test
    fun whileTheUserPausesTheLargerModelWeighsTheReadingsTillItHasNoMoreToDo() {
        val engines = Engines(::load, null)
        val typed = engines.type(Engines.PINYIN, "nihao")
        assertTrue(typed.refines)
        val slices = engines.pause(Engines.PINYIN, typed)
        assertTrue(slices.isNotEmpty())
        assertTrue(slices.all { it.commit.isEmpty() })
        // shown only if it changed the order, and then once
        assertTrue(slices.count { it.handled } <= 1)
        assertEquals(typed.candidates.toSet(), slices.last().candidates.toSet())
        assertFalse(engines.onEvent(Engines.PINYIN, EngineEvent.REFINE, 0).handled)
    }

    @Test
    fun withNoSentenceModelsPinyinWorksAndNothingIsReported() {
        model = null
        refining = null
        val errors = ArrayList<IOException>()
        val engines = Engines(::load, null, onError = { errors += it })
        val typed = engines.type(Engines.PINYIN, "ni")
        assertEquals(listOf("你", "拟"), typed.candidates)
        assertTrue(engines.pause(Engines.PINYIN, typed).none { it.handled })
        assertEquals("拟", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1).commit)
        assertEquals(listOf(Engines.SENTENCE_MODEL, Engines.REFINING_MODEL), loaded.filter { it.endsWith(".safetensors") })
        assertTrue(errors.isEmpty())
    }

    @Test
    fun aSentenceModelThatCannotBeReadLeavesPinyinWorking() {
        val (small, large) = model!! to refining!!
        for (broken in listOf(Engines.SENTENCE_MODEL, Engines.REFINING_MODEL)) {
            model = if (broken == Engines.SENTENCE_MODEL) small.copyOf(100) else small
            refining = if (broken == Engines.REFINING_MODEL) large.copyOf(100) else large
            val errors = ArrayList<IOException>()
            val engines = Engines(::load, null, onError = { errors += it })
            val typed = engines.type(Engines.PINYIN, "ni")
            assertEquals(broken, listOf("你", "拟"), typed.candidates)
            assertTrue(engines.pause(Engines.PINYIN, typed).none { it.handled })
            assertEquals("拟", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1).commit)
            assertEquals(broken, 1, errors.size)
            assertTrue(errors[0].cause is IllegalArgumentException)
        }
    }

    @Test
    fun aUserLogThatCannotBeReadLeavesPinyinWorking() {
        val dir = folder.newFolder("engine")
        // a directory where the log should be
        dir.resolve(Engines.USER_PINYIN).mkdir()
        val errors = ArrayList<IOException>()
        Engines(::load, dir, onError = { errors += it }).use { engines ->
            assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
            assertEquals("拟", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1).commit)
        }
        assertEquals(1, errors.size)
    }

    @Test
    fun whatATableLearnsOutlivesASettingChangedAndARestart() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
            // codes longer than what is typed go by use
            engines.type("engine-wubi", "va")
            assertEquals("二", engines.onEvent("engine-wubi", EngineEvent.PICK, 1).commit)
            engines.settings = EngineSettings(pageSize = 3)
            assertEquals(listOf("二", "一", "三"), engines.type("engine-wubi", "va").candidates)
        }
        assertTrue(dir.resolve(Engines.userTable("engine-wubi")).length() > 0)
        Engines(::load, dir).use { engines ->
            assertEquals("二", engines.type("engine-wubi", "va").candidates.first())
        }
    }

    @Test
    fun aTablesOwnSettingsApplyToItAlone() {
        Engines(::load, null).use { engines ->
            engines.settings = EngineSettings(pageSize = 3, tables = mapOf("Wubi" to TableSettings(orderByUse = false)))
            engines.type("engine-wubi", "va")
            assertEquals("二", engines.onEvent("engine-wubi", EngineEvent.PICK, 1).commit)
            assertEquals(listOf("一", "二", "三"), engines.type("engine-wubi", "va").candidates)
            engines.settings = EngineSettings(pageSize = 3, tables = mapOf("Cangjie" to TableSettings(orderByUse = false)))
            assertEquals(listOf("二", "一", "三"), engines.type("engine-wubi", "va").candidates)
        }
    }

    @Test
    fun aTableLogThatCannotBeReadLeavesTheTableWorking() {
        val dir = folder.newFolder("engine")
        dir.resolve(Engines.userTable("engine-wubi")).mkdir()
        val errors = ArrayList<IOException>()
        Engines(::load, dir, onError = { errors += it }).use { engines ->
            assertEquals(listOf("好", "妤"), engines.type("engine-wubi", "vbg").candidates)
        }
        assertEquals(1, errors.size)
    }

    @Test
    fun aKeySlippedIsReadAsMeantUnlessSlipsAreOff() {
        val engines = Engines(::load, null)
        // p is next to o
        assertEquals("好", engines.type(Engines.PINYIN, "hap").candidates.first())
        engines.settings = EngineSettings(typos = false)
        assertEquals(listOf("hap"), engines.type(Engines.PINYIN, "hap").candidates)
    }

    @Test
    fun settingsShapeTheSessionsMadeAfterThem() {
        val engines = Engines(::load, null)
        assertEquals(EngineSettings.DEFAULT_PAGE_SIZE, engines.type("engine-wubi", "va").candidates.size)
        // only the letters as typed
        assertEquals(listOf("li"), engines.type(Engines.PINYIN, "li").candidates)
        engines.settings = EngineSettings(pageSize = 3, fuzzy = setOf(Fuzzy.L_N))
        // made again: what was typed is gone
        assertEquals(listOf("一", "二", "三"), engines.type("engine-wubi", "va").candidates)
        assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "li").candidates)
        // the same settings again leave the input as it is
        engines.settings = EngineSettings(pageSize = 3, fuzzy = setOf(Fuzzy.L_N))
        assertEquals("你", engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code).commit)
    }

    @Test
    fun whatLibimeLearnedIsReadIntoAFreshLogOnce() {
        val dir = folder.newFolder("engine")
        var asked = 0
        val legacy = {
            asked++
            LibimeImport.Legacy(listOf("拟 ni 0"), List(MAX_PICKS) { "拟\tni" })
        }
        Engines(::load, dir, legacy = legacy).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
        Engines(::load, dir, legacy = legacy).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
        assertEquals(1, asked)
    }

    @Test
    fun aLogKilledBeforeAnythingWasLearnedIsStillFilled() {
        val dir = folder.newFolder("engine")
        dir.resolve(Engines.USER_PINYIN).writeBytes(UserLog.header())
        val legacy = { LibimeImport.Legacy(emptyList(), List(MAX_PICKS) { "拟\tni" }) }
        Engines(::load, dir, legacy = legacy).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
    }

    @Test
    fun libimesFilesThatCannotBeReadAreTriedAgainNextTime() {
        val dir = folder.newFolder("engine")
        val errors = ArrayList<IOException>()
        val failures = ArrayDeque(listOf<Throwable>(IOException("unreadable"), DataFormatException("corrupt")))
        val legacy: () -> LibimeImport.Legacy? = {
            failures.removeFirstOrNull()?.let { throw it }
            LibimeImport.Legacy(emptyList(), List(MAX_PICKS) { "拟\tni" })
        }
        repeat(2) {
            Engines(::load, dir, onError = { errors += it }, legacy = legacy).use { engines ->
                assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
            }
        }
        assertEquals(listOf("unreadable", "corrupt"), errors.map { it.cause?.message ?: it.message })
        Engines(::load, dir, legacy = legacy).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
        // with none, nothing is asked of the log
        Engines(::load, folder.newFolder("other"), legacy = { null }).use { engines ->
            assertFalse(engines.type(Engines.PINYIN, "ni").candidates.isEmpty())
        }
    }

    private fun userTable(
        conf: String = "",
        stamp: String = "1",
        reads: () -> Unit = {},
        text: String = MY_TABLE,
        settings: String = "",
    ) = Engines.UserTable("[Table]\nFile=table/my.main.dict\n$conf", { "$it $stamp" }, {
        assertEquals("table/my.main.dict", it)
        reads()
        text.reader().buffered()
    }, settings)

    @Test
    fun whatTheUserSetOfAnAddedTableIsReadAgainWithoutBuildingIt() {
        var reads = 0
        var settings = ""
        Engines(::load, folder.newFolder("engine"), userTables = { userTable(reads = { reads++ }, settings = settings) }).use { engines ->
            engines.type("my", "ab")
            engines.onEvent("my", EngineEvent.PICK, 1)
            assertEquals("工", engines.type("my", "ab").candidates.first())
            settings = "[Table]\nOrderPolicy=Freq\n"
            engines.reload()
            // the pick kept all along, now what orders them
            assertEquals("式", engines.type("my", "ab").candidates.first())
        }
        assertEquals(1, reads)
    }

    @Test
    fun anAddedTableThatCannotBeKeptIsTypedAllTheSame() {
        val dir = folder.newFolder("engine")
        // where the tables would go, a file
        dir.resolve(Engines.USER_TABLES).writeText("")
        val errors = ArrayList<IOException>()
        Engines(::load, dir, { errors += it }, userTables = { userTable() }).use { engines ->
            assertEquals(listOf("工", "式", "工作"), engines.type("my", "ab").candidates)
        }
        // the table not kept, nor what it learned
        assertEquals(2, errors.size)
    }

    @Test
    fun aTableTheUserAddedIsBuiltOnceAndAgainOnlyWhenItChanges() {
        val dir = folder.newFolder("engine")
        var reads = 0
        var stamp = "1"
        val tables = { im: String -> if (im == "my") userTable(stamp = stamp, reads = { reads++ }) else null }
        repeat(2) {
            Engines(::load, dir, userTables = tables).use { engines ->
                assertEquals(listOf("工", "式", "工作"), engines.type("my", "ab").candidates)
                engines.onEvent("my", EngineEvent.RESET, 0)
                // coded by the rules from its 词组
                assertEquals(listOf("工作"), engines.type("my", "abcd").candidates)
            }
        }
        assertEquals(1, reads)
        stamp = "2"
        Engines(::load, dir, userTables = tables).use { engines ->
            engines.reload()
            assertEquals(listOf("工", "式", "工作"), engines.type("my", "ab").candidates)
        }
        assertEquals(2, reads)
        assertThrows(IllegalArgumentException::class.java) { Engines(::load, dir, userTables = tables).type("nope", "a") }
    }

    @Test
    fun aTableTheUserAddedLearnsAsItsConfSays() {
        val dir = folder.newFolder("engine")
        for ((conf, first) in listOf("OrderPolicy=Freq\nLearning=False" to "工", "OrderPolicy=Freq" to "式")) {
            val tables = { _: String -> userTable(conf) }
            Engines(::load, dir, userTables = tables).use { engines ->
                engines.type("my", "ab")
                assertEquals("式", engines.onEvent("my", EngineEvent.PICK, 1).commit)
                // not even for now, if it does not learn
                assertEquals(first, engines.type("my", "ab").candidates.first())
                engines.onEvent("my", EngineEvent.RESET, 0)
            }
            Engines(::load, dir, userTables = tables).use { engines ->
                assertEquals(first, engines.type("my", "ab").candidates.first())
            }
        }
        // the conf's own options, not the built-in tables'
        Engines(::load, null, userTables = { userTable() }).use { engines ->
            engines.type("my", "ab")
            engines.onEvent("my", EngineEvent.PICK, 1)
            assertEquals("工", engines.type("my", "ab").candidates.first())
        }
    }

    @Test
    fun aTableTheUserAddedThatCannotBeReadIsReportedOnceUntilReload() {
        val errors = ArrayList<IOException>()
        var asked = 0
        var text = "键码=ab\n"
        val engines = Engines(::load, folder.newFolder("engine"), { errors += it }, userTables = {
            asked++
            userTable(text = text)
        })
        repeat(2) { assertThrows(IllegalArgumentException::class.java) { engines.type("my", "a") } }
        assertEquals(1, asked)
        assertEquals(1, errors.size)
        text = MY_TABLE
        engines.reload()
        assertEquals(listOf("工", "式", "工作"), engines.type("my", "ab").candidates)
        assertEquals(2, asked)
        // nor a .conf naming no table
        val noFile = Engines(::load, null, { errors += it }, userTables = { Engines.UserTable("", { "1" }, { MY_TABLE.reader().buffered() }) })
        assertThrows(IllegalArgumentException::class.java) { noFile.type("my", "a") }
        // nor one the app cannot read
        val gone = Engines(::load, null, { errors += it }, userTables = { throw IOException("gone") })
        assertThrows(IllegalArgumentException::class.java) { gone.type("my", "a") }
        assertEquals(listOf("gone"), errors.drop(2).map { it.message })
        assertEquals(3, errors.size)
    }

    @Test
    fun aBuiltTableCutShortIsBuiltAgain() {
        val dir = folder.newFolder("engine")
        val errors = ArrayList<IOException>()
        var reads = 0
        val tables = { _: String -> userTable(reads = { reads++ }) }
        Engines(::load, dir, userTables = tables).use { it.type("my", "ab") }
        val built = dir.resolve("${Engines.USER_TABLES}/my.table")
        built.writeBytes(built.readBytes().copyOf(built.length().toInt() / 2))
        Engines(::load, dir, { errors += it }, userTables = tables).use { engines ->
            assertEquals(listOf("工", "式", "工作"), engines.type("my", "ab").candidates)
        }
        assertEquals(2, reads)
        assertEquals(1, errors.size)
        Engines(::load, dir, userTables = tables).use { it.type("my", "ab") }
        assertEquals(2, reads)
    }

    private companion object {
        const val MAX_PICKS = 10
        const val MAX_SLICES = 10_000

        val MY_TABLE = """
            键码=abcd
            码长=4
            [组词规则]
            e2=p11+p12+p21+p22
            [数据]
            ab 工
            ab 式
            cd 作
            [词组]
            工作
        """.trimIndent()
    }
}
