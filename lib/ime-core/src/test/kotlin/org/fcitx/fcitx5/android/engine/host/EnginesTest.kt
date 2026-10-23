/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.table.TableUser.TableLog
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.UserLog
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

class EnginesTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

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

    // in the learning record under the user directory (PLAN §2.2), read again as English is next typed;
    // the older builds' log no longer written
    @Test
    fun theEnglishWordsLearnedAreKeptAcrossAStart() {
        val first = Engines(::load, folder.root)
        first.type(Engines.ENGLISH, "youmo")
        assertEquals("youmo ", first.onEvent(Engines.ENGLISH, EngineEvent.CHAR, ' '.code).commit)
        first.close()
        assertFalse(File(folder.root, Engines.USER_ENGLISH).exists())
        assertTrue(File(folder.root, LearnLog.FILE).length() > 0)
        val again = Engines(::load, folder.root)
        assertTrue("youmo" in again.type(Engines.ENGLISH, "you").candidates)
        again.close()
    }

    // as the English page's word list has them: added as typed twice, forgotten for good
    @Test
    fun theEnglishWordsAreListedAddedAndForgotten() {
        val engines = Engines(::load, folder.root)
        engines.type(Engines.ENGLISH, "the")
        engines.onEvent(Engines.ENGLISH, EngineEvent.CHAR, ' '.code)
        assertTrue(engines.addEnglishWord("Youmo"))
        assertFalse(engines.addEnglishWord("you mo"))
        assertFalse(engines.addEnglishWord("'em"))
        assertEquals(listOf("Youmo" to true, "the" to false), engines.englishWords().map { it.text to it.neverCorrected })
        assertEquals(listOf(2f, 1f), engines.englishWords().map { it.count })
        // the user's: not corrected, offered by its keys
        assertTrue("Youmo" in engines.type(Engines.ENGLISH, "you").candidates)
        engines.onEvent(Engines.ENGLISH, EngineEvent.CHAR, ' '.code)
        engines.forgetEnglishWords(listOf("Youmo"))
        engines.close()
        assertEquals(listOf("the", "you"), Engines(::load, folder.root).use { again -> again.englishWords().map { it.text } })
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
    fun aTableIsLoadedOnlyWhenItsInputMethodIsUsed() {
        val engines = Engines(::load, null)
        engines.type(Engines.PINYIN, "ni")
        // the larger model only once the user pauses; the emoji as the first candidates are listed
        assertEquals(listOf(Engines.PINYIN_DATA, Engines.SENTENCE_MODEL, Engines.EMOJI_DATA), loaded)
        loaded.clear()
        val s = engines.type("engine-wubi", "vbg")
        assertEquals(listOf("好", "妤"), s.candidates)
        assertEquals(listOf("", "f"), s.hints)
        // wubi's pinyin lookup shares the pinyin data
        assertEquals(listOf("${Engines.TABLE_DIR}/wbx.data"), loaded)
    }

    @Test
    fun theTextBeforeTheCursorIsTheContextOfWhatIsTypedNext() {
        val engines = Engines(::load, null, warmUps = inPlace)
        // no keyboard yet: nothing to tell
        engines.context(Engines.PINYIN, "好")
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        engines.context(Engines.PINYIN, "好")
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
            engines.pickUntilFirst("拟")
        }
        assertTrue(dir.resolve(LearnLog.FILE).length() > 0)
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
        // nor in the learning record
        val kept = logged(dir)
        assertTrue(kept.wordUses.isEmpty() && kept.stats.isEmpty())
    }

    // the learning record under [dir], read back as a start reads it
    private fun logged(dir: File): LearnState = LearnLog(dir.resolve(LearnLog.FILE)).run {
        open()
        state.also { close() }
    }

    private var dictionaryReads = 0

    private fun added(phrases: String, dictionaries: String, vararg lines: String) =
        Engines.Additions(phrases, dictionaries, indexes = indexDir(), dictionary = compiled(::load) { dictionaryReads++; lines.asSequence() })

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
    fun aDictionaryWordIsMergedIntoTheBase() {
        // no scores in a dictionary: 妮 and 泥, merged into the base, at the unknown word's until picked
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("妮 ni 0", "泥 ni 0") })
        Engines(::load, folder.newFolder("engine"), { throw it }, additions = { additions }).use { engines ->
            val candidates = engines.type(Engines.PINYIN, "ni").candidates
            assertEquals(listOf("你", "拟"), candidates.take(2))
            assertEquals(setOf("妮", "泥"), candidates.drop(2).toSet())
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
    fun withNoLogADictionaryChangeKeepsWhatWasLearned() {
        var additions = added("", "a", "泥 ni 0")
        val engines = Engines(::load, null, additions = { additions })
        engines.pickUntilFirst("拟")
        additions = added("", "b", "尼 ni 0")
        engines.reload()
        val shown = engines.type(Engines.PINYIN, "ni").candidates
        assertEquals("拟", shown.first())
        // the model made again of the dictionaries now
        assertTrue("尼" in shown && "泥" !in shown)
    }

    @Test
    fun withNoLogADictionaryChangeIsCodedInTheTablesLoaded() {
        val wubi = CodeTable.Builder()
            .header("键码", "abcdefghijklmnopqrstuvwxy")
            .header("码长", "4")
            .rule("e2", "p11+p12+p21+p22")
            .entry("wqiy", "你")
            .entry("vbg", "好")
            .build()
            .toByteArray()
        var additions = added("", "a", "你好 ni'hao 0")
        val engines = Engines({ if (it == "${Engines.TABLE_DIR}/wbx.data") ByteBuffer.wrap(wubi) else load(it) }, null, warmUps = inPlace, additions = { additions })
        assertEquals(listOf("你好"), engines.type("engine-wubi", "wqv").candidates)
        // listed into the same user model, not by add(): 五笔, loaded still, codes them all again
        additions = added("", "b", "你好 ni'hao 0", "好你 hao'ni 0")
        engines.reload()
        assertEquals(listOf("好你"), engines.type("engine-wubi", "vbw").candidates)
    }

    /**
     * What one pinyin learned leaves another's short input as the model has it (D32: 全拼 does not
     * change 双拼's order, 简拼's first is the model's), though that one read the same keys in the
     * same context before; forgotten, each is as it was, past the sentences too.
     */
    @Test
    fun aPickInOnePinyinLeavesAnothersShortInputAndIsForgottenInBoth() {
        val chars = "你拟泥尼逆腻昵倪妮霓"
        val data = PinyinDataBuilder().unigram("<unk>", -7f, 0f)
            .apply { chars.forEachIndexed { i, c -> unigram(c.toString(), -2f - i * 0.4f, 0f).entry(c.toString(), syl("ni")) } }
            .build().toByteArray()
        val load = { path: String -> if (path == Engines.PINYIN_DATA) ByteBuffer.wrap(data) else load(path) }
        Engines(load, null).use { engines ->
            engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
            val fresh = engines.type(Engines.SHUANGPIN, "n").candidates
            engines.onEvent(Engines.SHUANGPIN, EngineEvent.RESET, 0)
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.type(Engines.PINYIN, "ni").candidates.indexOf("昵"))
            assertEquals(fresh, engines.type(Engines.SHUANGPIN, "n").candidates)
            engines.onEvent(Engines.SHUANGPIN, EngineEvent.RESET, 0)
            engines.onEvent(Engines.PINYIN, EngineEvent.FORGET, engines.type(Engines.PINYIN, "ni").candidates.indexOf("昵"))
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            val forgotten = listOf("你", "拟", "泥", "尼", "逆", "腻", "昵")
            assertEquals(forgotten, engines.type(Engines.PINYIN, "n").candidates)
            // 昵 at its own score again past the sentences too, not at the one the last "n" gave it
            assertEquals(forgotten, engines.type(Engines.SHUANGPIN, "n").candidates)
        }
    }

    @Test
    fun aCandidatePinnedIsTheLearningRecordsUnderItsOwnCodeThePhrasesUntouched() {
        val saved = ArrayList<String>()
        val errors = ArrayList<IOException>()
        val additions = Engines.Additions("", "", { saved += it.all.joinToString(" ") }, dictionary = { null })
        val engines = Engines(::load, null, { errors += it }, additions = { additions })
        assertEquals(emptySet<Offer>(), engines.offers(Engines.PINYIN, 0))
        val shown = engines.type(Engines.PINYIN, "ni")
        assertTrue(shown.actionable)
        assertEquals(setOf(Offer.PIN, Offer.BLOCK), engines.offers(Engines.PINYIN, 1))
        assertEquals(listOf("拟", "你"), engines.onEvent(Engines.PINYIN, EngineEvent.PIN, 1).candidates)
        assertTrue(saved.isEmpty())
        // 双拼 pins under its own codes (impl/design-1b.md §3)
        assertEquals(listOf("你", "拟"), engines.type(Engines.SHUANGPIN, "ni").candidates)
        engines.onEvent(Engines.SHUANGPIN, EngineEvent.RESET, 0)
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        engines.type(Engines.PINYIN, "ni")
        assertTrue(Offer.UNPIN in engines.offers(Engines.PINYIN, 0))
        assertEquals(listOf("你", "拟"), engines.onEvent(Engines.PINYIN, EngineEvent.UNPIN, 0).candidates)
        assertEquals(emptyList<IOException>(), errors)
    }

    // design-1a Open 3: a phrase a pin of full pinyin was converted from goes, once, where the pin shows its word
    @Test
    fun pinsConvertedFromPhrasesTakeThemOutOnce() {
        val saved = ArrayList<String>()
        val additions = { Engines.Additions("ni,1=拟\nhao,1=呢\n", "", { saved += it.all.joinToString(" ") }, dictionary = { null }) }
        val dir = folder.newFolder("engine")
        Engines(::load, dir, additions = additions).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
        // 拟 the learning record's pin now; 呢 no word of the model's, which a pin could not show
        assertEquals(listOf("hao,1=呢"), saved)
        assertTrue(dir.resolve(Engines.PINS_MOVED).exists())
        // once: a phrase put back since is the user's
        Engines(::load, dir, additions = additions).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
        assertEquals(1, saved.size)
    }

    // review-1b M2: a pin the input reads though not as the likeliest spelling (你好 on 简拼) takes its phrase out too, shown once
    @Test
    fun aPinOfAnAbbreviationTakesItsPhraseOut() {
        val saved = ArrayList<String>()
        val additions = { Engines.Additions("nh,1=你好\n", "", { saved += it.all.joinToString(" ") }, dictionary = { null }) }
        Engines(::load, folder.newFolder("engine"), additions = additions).use { engines ->
            val shown = engines.type(Engines.PINYIN, "nh").candidates
            assertEquals("你好", shown.first())
            assertEquals(1, shown.count { it == "你好" })
        }
        assertEquals(listOf(""), saved)
    }

    // review-1b M2: a phrase of the pin's text put back since (the move is done once) is shown once, the pin first
    @Test
    fun aPhraseOfThePinsTextIsShownOnce() {
        val dir = folder.newFolder("engine")
        val additions = { Engines.Additions("nh,1=你好\n", "", { }, dictionary = { null }) }
        Engines(::load, dir, additions = additions).use { it.type(Engines.PINYIN, "nh") }
        // the phrase again, the pin kept: both of 你好
        Engines(::load, dir, additions = additions).use { engines ->
            val shown = engines.type(Engines.PINYIN, "nh").candidates
            assertEquals("你好", shown.first())
            assertEquals(1, shown.count { it == "你好" })
        }
    }

    @Test
    fun additionsThatCannotBeReadLeavePinyinWorking() {
        val errors = ArrayList<IOException>()
        val engines = Engines(::load, null, { errors += it }, additions = { throw IOException("no") })
        assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
        assertEquals(1, errors.size)
        val dictionaries = Engines(::load, null, { errors += it }, additions = {
            Engines.Additions("ni,1=呢", "a", dictionary = { throw IOException("no") })
        })
        assertEquals(listOf("呢", "你", "拟"), dictionaries.type(Engines.PINYIN, "ni").candidates)
        assertEquals(2, errors.size)
    }

    @Test
    fun aUserLogThatCannotBeReadLeavesPinyinWorking() {
        val dir = folder.newFolder("engine")
        // a directory where the log should be
        dir.resolve(LearnLog.FILE).mkdir()
        val errors = ArrayList<IOException>()
        Engines(::load, dir, onError = { errors += it }).use { engines ->
            assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
            assertTrue(errors.isNotEmpty())
            // told once: the record left closed, not tried again on each key and commit
            val told = errors.size
            assertEquals("拟", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1).commit)
            engines.type(Engines.PINYIN, "ni")
            assertEquals(told, errors.size)
        }
    }

    @Test
    fun whatATableLearnsOutlivesASettingChangedAndARestart() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
            // pinned first under the code typed, ordered by use or not (U-28)
            engines.type("engine-wubi", "va")
            assertTrue(Offer.PIN in engines.offers("engine-wubi", 1))
            assertEquals(listOf("二", "一", "三"), engines.onEvent("engine-wubi", EngineEvent.PIN, 1).candidates.take(3))
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            engines.settings = EngineSettings(pageSize = 3, tables = mapOf("Wubi" to TableSettings(orderByUse = false)))
            assertEquals(listOf("二", "一", "三"), engines.type("engine-wubi", "va").candidates)
            // past the 简码 kept in order (TableHabitOrder's reach): 员 picked twice under 调频 is first there, kept with 调频 off
            byUse(engines, true)
            repeat(2) { engines.onEvent("engine-wubi", EngineEvent.PICK, engines.type("engine-wubi", "kkkk").candidates.indexOf("员")) }
            assertEquals(listOf("叫", "员"), byUse(engines, false))
        }
        Engines(::load, dir).use { engines ->
            assertEquals(listOf("员", "叫"), byUse(engines, true))
            // 恢复码表顺序 forgets the order learned by use, not what was pinned
            // the one entry the app has (U-28's 恢复码表顺序 in a table's settings)
            assertEquals(LearningResult.Done(), engines.learning(null, LearningOp.ResetTableOrder("engine-wubi")))
            assertEquals("二", engines.type("engine-wubi", "va").candidates.first())
            assertEquals(setOf(Offer.UNPIN, Offer.BLOCK), engines.offers("engine-wubi", 0))
        }
    }

    @Test
    fun aTablesOwnSettingsApplyToItAlone() {
        Engines(::load, null).use { engines ->
            engines.settings = EngineSettings(pageSize = 3, tables = mapOf("Wubi" to TableSettings(orderByUse = false)))
            engines.type("engine-wubi", "va")
            assertEquals("二", engines.onEvent("engine-wubi", EngineEvent.PICK, 1).commit)
            // 调频 off: the pick is kept but not used, so nothing offered to forget (U-28)
            assertEquals(listOf("一", "二", "三"), engines.type("engine-wubi", "va").candidates)
            assertFalse(Offer.FORGET in engines.offers("engine-wubi", 1))
            engines.onEvent("engine-wubi", EngineEvent.RESET, 0)
            engines.settings = EngineSettings(pageSize = 3, tables = mapOf("Cangjie" to TableSettings(orderByUse = false)))
            engines.type("engine-wubi", "va")
            assertTrue(Offer.FORGET in engines.offers("engine-wubi", 1))
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
        // its counts the words' uses (impl/design-1b.md §4), what sentences go by (MULTI); its first under ni no habit
        repeat(2) {
            Engines(::load, dir, legacy = legacy).use { engines -> engines.type(Engines.PINYIN, "ni") }
            assertTrue("拟" in logged(dir).wordUses)
        }
        assertEquals(1, asked)
    }

    @Test
    fun aLogKilledBeforeAnythingWasLearnedIsStillFilled() {
        val dir = folder.newFolder("engine")
        dir.resolve(Engines.USER_PINYIN).writeBytes(UserLog.header())
        val legacy = { LibimeImport.Legacy(emptyList(), List(MAX_PICKS) { "拟\tni" }) }
        // converted on the warm-ups, merged in at the next key: run in place here, so by the next
        Engines(::load, dir, legacy = legacy, warmUps = inPlace).use { engines -> engines.type(Engines.PINYIN, "ni") }
        assertTrue("拟" in logged(dir).wordUses)
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
        Engines(::load, dir, legacy = legacy).use { engines -> engines.type(Engines.PINYIN, "ni") }
        assertTrue("拟" in logged(dir).wordUses)
        // with none, nothing is asked of the log
        Engines(::load, folder.newFolder("other"), legacy = { null }).use { engines ->
            assertFalse(engines.type(Engines.PINYIN, "ni").candidates.isEmpty())
        }
    }

    @Test
    fun aWordAddedAndPickedIsNotTypeableOnceRemoved() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
            assertTrue(engines.addWord("妮浩", "nihao"))
            engines.type(Engines.PINYIN, "nihao")
            val at = engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "妮浩" }
            assertEquals("妮浩", engines.onEvent(Engines.PINYIN, EngineEvent.PICK, at).commit)
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            engines.removeWord(Engines.UserWord("妮浩", "ni hao", Engines.UserWord.Kind.ADDED))
            // its pick in the log does not make it a word of the user's own when read back
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
            assertFalse("妮浩" in engines.type(Engines.PINYIN, "nihao").candidates)
        }
        Engines(::load, dir).use { engines ->
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
        }
    }

    @Test
    fun uLooksACharacterUpByItsStrokesShowingItsPinyin() {
        val engines = Engines(::load, null)
        val s = engines.type(Engines.PINYIN, "uz")
        assertEquals("u乛", s.preedit)
        assertEquals(listOf("好"), s.candidates)
        assertEquals("hao", engines.candidates(Engines.PINYIN, 0, 1).single().hint)
        assertEquals("好", engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code).commit)
        // an app without the data: u is pinyin's, and that is reported
        strokes = null
        val errors = ArrayList<IOException>()
        assertEquals("u", Engines(::load, null, { errors += it }).type(Engines.PINYIN, "u").preedit.replace(" ", ""))
        assertEquals(1, errors.size)
        assertTrue(Engines.STROKE_DATA in errors[0].message.orEmpty())
    }

    @Test
    fun aCharacterLookedUpByItsStrokesIsReadAfterTheTextBeforeIt() {
        // 你 after 你好, 拟 after 好 alone
        val data = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("你", -2f, 0f)
            .unigram("拟", -3f, 0f)
            .unigram("好", -2.5f, 0f)
            .bigram("你", "好", -1f, -2f)
            .bigram("好", "拟", -0.5f, 0f)
            .trigram("你", "好", "你", -0.2f)
            .entry("你", syl("ni"))
            .entry("拟", syl("ni"))
            .entry("好", syl("hao"))
            .build()
            .toByteArray()
        val engines = Engines({ if (it == Engines.PINYIN_DATA) ByteBuffer.wrap(data) else load(it) }, null, warmUps = inPlace)
        // the decoder's reading alone
        engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
        engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
        engines.context(Engines.PINYIN, "你")
        engines.type(Engines.PINYIN, "uz")
        assertEquals("好", engines.onEvent(Engines.PINYIN, EngineEvent.CHAR, ' '.code).commit)
        assertEquals("你", engines.type(Engines.PINYIN, "ni").candidates.first())
    }

    @Test
    fun aWordTheDictionaryHasTakenOffTheAddedKeepsWhatWasLearnedOfIt() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir).use { engines ->
            assertTrue(engines.addWord("拟", "ni"))
            engines.pickUntilFirst("拟")
            engines.removeWord(Engines.UserWord("拟", "ni", Engines.UserWord.Kind.ADDED))
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
        // nor forgotten in the log
        Engines(::load, dir).use { engines ->
            assertEquals("拟", engines.type(Engines.PINYIN, "ni").candidates.first())
        }
    }

    /** A coarse word (小穴) the user added waited past the first page: listed, it has no count to say it is theirs. */
    @Test
    fun aCoarseWordTheUserAddsComesOnTheFirstPage() {
        val data = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("小穴", -1f, 0f)
            .unigram("小学", -3f, 0f)
            .unigram("小雪", -3.5f, 0f)
            .entry("小穴", syl("xiao", "xue"))
            .entry("小学", syl("xiao", "xue"))
            .entry("小雪", syl("xiao", "xue"))
            .build()
            .toByteArray()
        val engines = Engines({ if (it == Engines.PINYIN_DATA) ByteBuffer.wrap(data) else load(it) }, null)
        // the decoder's reading alone, on a page of two
        engines.settings = EngineSettings(pageSize = 2, modelLevel = ModelLevel.LOW, typos = false)
        assertFalse("小穴" in engines.type(Engines.PINYIN, "xiaoxue").candidates)
        assertTrue(engines.addWord("小穴", "xiao xue"))
        // the session made again knows it the user's
        assertTrue("小穴" in engines.type(Engines.PINYIN, "xiaoxue").candidates)
    }

    @Test
    fun oneLearnedWordRemovedIsARecordAppendedNotTheLogRewritten() {
        val dir = folder.newFolder("engine")
        val log = dir.resolve(LearnLog.FILE)
        Engines(::load, dir).use { engines ->
            // 拟 then 好, over the decoder's 你好, twice: the user's word 拟好
            repeat(2) {
                engines.type(Engines.PINYIN, "nihao")
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "拟" })
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 0)
                engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            }
            val learned = engines.userWords().single()
            val before = log.readBytes()
            // and one whose pinyin does not read: one word forgotten all the same
            engines.removeWords(listOf(learned, Engines.UserWord("拟好", "zz", Engines.UserWord.Kind.LEARNED)))
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
            val after = log.readBytes()
            assertTrue(after.size > before.size)
            assertArrayEquals(before, after.copyOf(before.size))
        }
        // and forgotten still when the log is read back
        assertEquals(emptyList<Engines.UserWord>(), Engines(::load, dir).use { it.userWords() })
    }

    @Test
    fun aCandidateForgottenInAnyPinyinIsForgottenInTheTablesToo() {
        val wubi = "engine-wubi"
        // a phrase, as a table saves no single character (TableRegistry.forgetInTables loads none for one)
        val log = TableLog.FORMAT.header() + TableLog.saved("abcd", "你好")
        // the log forgets nothing by itself: 你好 is 五笔's by abcd
        val kept = folder.newFolder("kept")
        kept.resolve(Engines.userTable(wubi)).writeBytes(log)
        Engines(::load, kept).use {
            it.settings = wubiShown
            assertEquals(listOf("你好"), it.type(wubi, "abcd").candidates)
        }
        for ((im, keys) in listOf(Engines.PINYIN to "nihao", Engines.T9 to "64426", Engines.SHUANGPIN to "nihk")) {
            val dir = folder.newFolder(im)
            // a phrase 五笔 saved, its table not loaded: loaded to forget it
            dir.resolve(Engines.userTable(wubi)).writeBytes(log)
            Engines(::load, dir).use { engines ->
                // learned here first, to be forgotten
                engines.type(im, keys)
                val picked = engines.candidates(im, 0, 20).indexOfFirst { it.text == "你好" }
                assertEquals(im, "你好", engines.onEvent(im, EngineEvent.PICK, picked).commit)
                engines.onEvent(im, EngineEvent.RESET, 0)
                engines.type(im, keys)
                val at = engines.candidates(im, 0, 20).indexOfFirst { it.text == "你好" }
                assertTrue(im, Offer.FORGET in engines.offers(im, at))
                engines.onEvent(im, EngineEvent.FORGET, at)
            }
            Engines(::load, dir).use {
                it.settings = wubiShown
                assertFalse(im, "你好" in it.type(wubi, "abcd").candidates)
            }
        }
    }

    @Test
    fun aWordAddedAfterATableCodedTheListedOnesIsCodedThereAloneAndOfferedOnce() {
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("你好 ni'hao 0") })
        val wubi = "engine-wubi"
        // the listed words coded at once: in the background, a slow machine typed before they were
        Engines(::load, null, additions = { additions }, warmUps = { it.run() }).use { engines ->
            // a dictionary's word, coded by the table's rule: its code's one candidate, it commits itself
            assertEquals("你好", engines.type(wubi, "wqvb").commit)
            assertTrue(engines.addWord("好你", "hao ni"))
            assertEquals("好你", engines.type(wubi, "vbwq").commit)
            // listed before in another reading: coded already, offered once
            assertTrue(engines.addWord("你好", "li hao"))
            assertEquals("你好", engines.type(wubi, "wqvb").commit)
        }
    }

    // the nine keys' 重输 with nothing typed: not the app's
    @Test
    fun nineKeysStartOverWithNothingTypedIsTheirs() {
        assertTrue(Engines(::load, null).onEvent(Engines.T9, EngineEvent.ESCAPE, 0).handled)
    }

    @Test
    fun nineKeysAndWubiAreOfferedFirstToBeAdded() {
        val offered = listOf("engine-erbi", "engine-cangjie", Engines.WUBI, Engines.T9, "engine-ziranma", Engines.SHUANGPIN)
        assertEquals(
            listOf(Engines.T9, Engines.WUBI, "engine-erbi", "engine-cangjie", "engine-ziranma", Engines.SHUANGPIN),
            Engines.addOrder(offered) { it },
        )
    }

    /**
     * In an address field a character goes in as it is unless the input method reads it as a key
     * ([Engines.readsAsKey], told whether something is typed): the pinyins' `'` only while
     * something is, as their sessions read it. Taken as text, it put xi'an in as xi' and an.
     */
    @Test
    fun aPinyinsSeparatorIsAKeyWhereItsSessionReadsIt() {
        val separator = '\''.code
        val scheme = EngineSettings().scheme
        for ((im, keys) in listOf(Engines.PINYIN to "ni", Engines.T9 to "64", Engines.SHUANGPIN to "ni")) {
            Engines(::load, null).use { engines ->
                assertFalse(im, engines.onEvent(im, EngineEvent.CHAR, separator).handled)
                assertFalse(im, Engines.readsAsKey(im, scheme, separator, composing = false))
                assertTrue(im, engines.type(im, keys).preedit.isNotEmpty())
                assertTrue(im, engines.onEvent(im, EngineEvent.CHAR, separator).handled)
                assertTrue(im, Engines.readsAsKey(im, scheme, separator, composing = true))
            }
        }
    }

    private companion object {
        const val MAX_PICKS = 10
        const val MAX_SLICES = 10_000
    }
}
