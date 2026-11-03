/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.ByteBuffer

class EnginesTest : EnginesFixture() {
    // a directory where a file should be: on the disk too
    @get:Rule
    val folder = TemporaryFolder()

    private val root = MemoryFiles()

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
        assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
        assertEquals("你", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code).commit)
    }

    // a key that picks says so (the router's log of what to type again ends at it: review2 M2);
    // a key that types does not
    @Test
    fun aKeysPickIsToldAsOne() {
        val engines = Engines(::load, null)
        assertFalse(engines.type(InputMethodCatalog.PINYIN, "ni").picked)
        assertTrue("space", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code).picked)
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertTrue("a digit", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, '2'.code).picked)
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertTrue("the keypad's", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.KEYPAD, '1'.code).picked)
        assertFalse("nothing typed: the app's digit", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, '1'.code).picked)
    }

    // in the learning record under the user directory (PLAN §2.2), read again as English is next typed
    @Test
    fun theEnglishWordsLearnedAreKeptAcrossAStart() {
        val first = Engines(::load, root)
        first.type(InputMethodCatalog.ENGLISH, "youmo")
        assertEquals("youmo ", first.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code).commit)
        first.close()
        assertTrue(root.file(LearnLog.FILE).length() > 0)
        val again = Engines(::load, root)
        assertTrue("youmo" in again.type(InputMethodCatalog.ENGLISH, "you").candidates)
        again.close()
    }

    // as the English page's word list has them: added as typed twice, forgotten for good
    @Test
    fun theEnglishWordsAreListedAddedAndForgotten() {
        val engines = Engines(::load, root)
        engines.type(InputMethodCatalog.ENGLISH, "the")
        engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code)
        assertTrue(engines.addEnglishWord("Youmo"))
        assertFalse(engines.addEnglishWord("you mo"))
        assertFalse(engines.addEnglishWord("'em"))
        assertEquals(listOf("Youmo" to true, "the" to false), engines.englishWords().map { it.text to it.neverCorrected })
        assertEquals(listOf(2f, 1f), engines.englishWords().map { it.count })
        // the user's: not corrected, offered by its keys
        assertTrue("Youmo" in engines.type(InputMethodCatalog.ENGLISH, "you").candidates)
        engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code)
        engines.forgetEnglishWords(listOf("Youmo"))
        engines.close()
        assertEquals(listOf("the", "you"), Engines(::load, root).use { again -> again.englishWords().map { it.text } })
    }

    @Test
    fun theRestOfTheCandidatesAreFetchedFromTheSession() {
        val engines = Engines(::load, null)
        assertTrue(engines.candidates(InputMethodCatalog.PINYIN, 0, 5).isEmpty())
        val shown = engines.type(InputMethodCatalog.PINYIN, "ni")
        assertEquals(2, shown.total)
        assertEquals(listOf(Choice("拟")), engines.candidates(InputMethodCatalog.PINYIN, 1, 5))
        assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1).commit)
    }

    @Test
    fun aTableIsLoadedOnlyWhenItsInputMethodIsUsed() {
        val engines = Engines(::load, null)
        engines.type(InputMethodCatalog.PINYIN, "ni")
        // the larger model only once the user pauses; the emoji as the first candidates are listed
        assertEquals(listOf(InputMethodCatalog.PINYIN_DATA, InputMethodCatalog.SENTENCE_MODEL, InputMethodCatalog.EMOJI_DATA), loaded)
        loaded.clear()
        val s = engines.type(InputMethodCatalog.WUBI, "vbg")
        assertEquals(listOf("好", "妤"), s.candidates)
        assertEquals(listOf("", "f"), s.hints)
        // wubi's pinyin lookup shares the pinyin data
        assertEquals(listOf("${InputMethodCatalog.TABLE_DIR}/wbx.data"), loaded)
    }

    @Test
    fun theTextBeforeTheCursorIsTheContextOfWhatIsTypedNext() {
        val engines = Engines(::load, null, warmUps = inPlace)
        // no keyboard yet: nothing to tell
        engines.context(InputMethodCatalog.PINYIN, "好")
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        engines.context(InputMethodCatalog.PINYIN, "好")
        assertEquals(listOf("拟", "你"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
    }

    @Test
    fun eachInputMethodKeepsItsOwnInput() {
        val engines = Engines(::load, null)
        engines.type(InputMethodCatalog.PINYIN, "ni")
        engines.type(InputMethodCatalog.WUBI, "vb")
        assertEquals("ni", engines.type(InputMethodCatalog.PINYIN, "h").preedit.replace(" ", "").take(2))
    }

    @Test
    fun anUnknownInputMethodIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { Engines(::load, null).onEvent("nope", EngineEvent.CHAR, 'a'.code) }
    }

    @Test
    fun whatIsPickedIsKeptUnderTheUserDirectory() {
        val dir = MemoryFiles()
        Engines(::load, dir).use { engines ->
            // picked until it leads
            engines.pickUntilFirst("拟")
        }
        assertTrue(dir.file(LearnLog.FILE).length() > 0)
        // and read back: 拟 still comes first
        Engines(::load, dir).use { engines ->
            assertEquals("拟", engines.type(InputMethodCatalog.PINYIN, "ni").candidates.first())
        }
    }

    @Test
    fun afterAPageTurnAPickIsOfTheCandidateShown() {
        val engines = Engines(::load, null)
        engines.settings = EngineSettings(pageSize = 3)
        engines.type(InputMethodCatalog.WUBI, "va")
        val page = engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.PAGE_DOWN, 0)
        assertEquals(3, page.first)
        assertEquals(page.candidates, engines.candidates(InputMethodCatalog.WUBI, page.first, 3).map { it.text })
        assertEquals("五", engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.PICK, page.first + 1).commit)
    }

    @Test
    fun nothingIsLearnedWhereTheAppSaysNotTo() {
        val dir = MemoryFiles()
        Engines(::load, dir).use { engines ->
            repeat(MAX_PICKS) {
                engines.type(InputMethodCatalog.PINYIN, "ni")
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1, learning = false)
            }
            assertEquals("你", engines.type(InputMethodCatalog.PINYIN, "ni").candidates.first())
        }
        // nor in the learning record
        val kept = logged(dir)
        assertTrue(kept.wordUses.isEmpty() && kept.stats.isEmpty())
    }

    // the learning record under [dir], read back as a start reads it
    private fun logged(dir: UserFiles): LearnState = LearnLog(dir.file(LearnLog.FILE)).run {
        open()
        state.also { close() }
    }

    private var dictionaryReads = 0

    private fun added(phrases: String, dictionaries: String, vararg lines: String) =
        Engines.Additions(phrases, dictionaries, indexes = indexDir(), dictionary = compiled(::load) { dictionaryReads++; lines.asSequence() })

    private fun Engines.pickUntilFirst(text: String) {
        repeat(MAX_PICKS) {
            onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            val shown = type(InputMethodCatalog.PINYIN, "ni").candidates
            if (shown.first() != text) onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, shown.indexOf(text))
        }
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        assertEquals(text, type(InputMethodCatalog.PINYIN, "ni").candidates.first())
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
    }

    @Test
    fun aDictionaryWordIsMergedIntoTheBase() {
        // no scores in a dictionary: 妮 and 泥, merged into the base, at the unknown word's until picked
        val additions = Engines.Additions("", "d", indexes = indexDir(), dictionary = compiled(::load) { sequenceOf("妮 ni 0", "泥 ni 0") })
        Engines(::load, MemoryFiles(), { throw it }, additions = { additions }).use { engines ->
            val candidates = engines.type(InputMethodCatalog.PINYIN, "ni").candidates
            assertEquals(listOf("你", "拟"), candidates.take(2))
            assertEquals(setOf("妮", "泥"), candidates.drop(2).toSet())
        }
    }

    @Test
    fun whatTheUserAddedIsReadAtFirstNeedAndAgainOnReload() {
        var additions = added("ni,1=呢\n", "a", "泥 ni 0", "nonsense")
        var reads = 0
        Engines(::load, MemoryFiles(), additions = { reads++; additions }).use { engines ->
            assertEquals(0, reads)
            assertEquals(listOf("呢", "你", "拟", "泥"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            engines.type(InputMethodCatalog.SHUANGPIN, "ni")
            assertEquals(1, reads)
            assertEquals(1, dictionaryReads)
            // what was typed is dropped, and what changed is read: the phrases only
            additions = added("", "a", "泥 ni 0")
            engines.reload()
            assertEquals(2, reads)
            assertEquals(listOf("你", "拟", "泥"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            assertEquals(1, dictionaryReads)
            // the dictionaries too
            additions = added("", "")
            engines.reload()
            assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            assertEquals(2, dictionaryReads)
        }
    }

    @Test
    fun whatWasLearnedOutlivesADictionaryChange() {
        var additions = added("", "a", "泥 ni 0")
        Engines(::load, MemoryFiles(), additions = { additions }).use { engines ->
            engines.pickUntilFirst("拟")
            additions = added("", "b", "泥 ni 0", "尼 ni 0")
            engines.reload()
            val shown = engines.type(InputMethodCatalog.PINYIN, "ni").candidates
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
        val shown = engines.type(InputMethodCatalog.PINYIN, "ni").candidates
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
        val engines = Engines({ if (it == "${InputMethodCatalog.TABLE_DIR}/wbx.data") ByteBuffer.wrap(wubi) else load(it) }, null, warmUps = inPlace, additions = { additions })
        assertEquals(listOf("你好"), engines.type(InputMethodCatalog.WUBI, "wqv").candidates)
        // listed into the same user model, not by add(): 五笔, loaded still, codes them all again
        additions = added("", "b", "你好 ni'hao 0", "好你 hao'ni 0")
        engines.reload()
        assertEquals(listOf("好你"), engines.type(InputMethodCatalog.WUBI, "vbw").candidates)
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
        val load = { path: String -> if (path == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(data) else load(path) }
        Engines(load, null).use { engines ->
            engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
            val fresh = engines.type(InputMethodCatalog.SHUANGPIN, "n").candidates
            engines.onEvent(InputMethodCatalog.SHUANGPIN, EngineEvent.RESET, 0)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.type(InputMethodCatalog.PINYIN, "ni").candidates.indexOf("昵"))
            assertEquals(fresh, engines.type(InputMethodCatalog.SHUANGPIN, "n").candidates)
            engines.onEvent(InputMethodCatalog.SHUANGPIN, EngineEvent.RESET, 0)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.FORGET, engines.type(InputMethodCatalog.PINYIN, "ni").candidates.indexOf("昵"))
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            val forgotten = listOf("你", "拟", "泥", "尼", "逆", "腻", "昵")
            assertEquals(forgotten, engines.type(InputMethodCatalog.PINYIN, "n").candidates)
            // 昵 at its own score again past the sentences too, not at the one the last "n" gave it
            assertEquals(forgotten, engines.type(InputMethodCatalog.SHUANGPIN, "n").candidates)
        }
    }

    @Test
    fun aCandidatePinnedIsTheLearningRecordsUnderItsOwnCodeThePhrasesUntouched() {
        val saved = ArrayList<String>()
        val errors = ArrayList<IOException>()
        val additions = Engines.Additions("", "", { saved += it.all.joinToString(" ") }, dictionary = { null })
        val engines = Engines(::load, null, { errors += it }, additions = { additions })
        assertEquals(emptySet<Offer>(), engines.offers(InputMethodCatalog.PINYIN, 0))
        val shown = engines.type(InputMethodCatalog.PINYIN, "ni")
        assertTrue(shown.actionable)
        assertEquals(setOf(Offer.PIN, Offer.BLOCK), engines.offers(InputMethodCatalog.PINYIN, 1))
        assertEquals(listOf("拟", "你"), engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PIN, 1).candidates)
        assertTrue(saved.isEmpty())
        // 双拼 pins under its own codes (impl/design-1b.md §3)
        assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.SHUANGPIN, "ni").candidates)
        engines.onEvent(InputMethodCatalog.SHUANGPIN, EngineEvent.RESET, 0)
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertTrue(Offer.UNPIN in engines.offers(InputMethodCatalog.PINYIN, 0))
        assertEquals(listOf("你", "拟"), engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.UNPIN, 0).candidates)
        assertEquals(emptyList<IOException>(), errors)
    }

    // a pin and a custom phrase of the same text under the code (a 0.4.0 install moved its order-1 phrases
    // to pins, and the user may add the phrase back): shown once, the pin first, ahead of the phrase before it
    @Test
    fun aPhraseOfThePinsTextIsShownOnce() {
        val dir = MemoryFiles()
        LearnLog(dir.file(LearnLog.FILE)).apply {
            open()
            pin(Code(Kinds.FULL, "nh"), "你好", "", on = true)
            close()
        }
        val additions = { Engines.Additions("nh,1=你会\nnh,2=你好\n", "", { }, dictionary = { null }) }
        // the phrase there beside the pin: both of 你好
        Engines(::load, dir, additions = additions).use { engines ->
            val shown = engines.type(InputMethodCatalog.PINYIN, "nh").candidates
            assertEquals("你好", shown.first())
            assertEquals(1, shown.count { it == "你好" })
        }
    }

    @Test
    fun additionsThatCannotBeReadLeavePinyinWorking() {
        val errors = ArrayList<IOException>()
        val engines = Engines(::load, null, { errors += it }, additions = { throw IOException("no") })
        assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
        assertEquals(1, errors.size)
        val dictionaries = Engines(::load, null, { errors += it }, additions = {
            Engines.Additions("ni,1=呢", "a", dictionary = { throw IOException("no") })
        })
        assertEquals(listOf("呢", "你", "拟"), dictionaries.type(InputMethodCatalog.PINYIN, "ni").candidates)
        assertEquals(2, errors.size)
    }

    // in memory and on the disk alike: a directory there fails to open on both
    @Test
    fun aUserLogThatCannotBeReadLeavesPinyinWorking() = assertRows(
        listOf<Pair<String, () -> UserFiles>>("in memory" to { MemoryFiles() }, "on disk" to { JvmFiles(folder.newFolder()) }),
        { it.first },
    ) { userLogThatCannotBeRead(it.second()) }

    private fun userLogThatCannotBeRead(dir: UserFiles) {
        // a directory where the log should be
        dir.dir(LearnLog.FILE).mkdirs()
        val errors = ArrayList<IOException>()
        Engines(::load, dir, onError = { errors += it }).use { engines ->
            assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            assertTrue(errors.isNotEmpty())
            // told once: the record left closed, not tried again on each key and commit
            val told = errors.size
            assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1).commit)
            engines.type(InputMethodCatalog.PINYIN, "ni")
            assertEquals(told, errors.size)
        }
    }

    @Test
    fun whatATableLearnsOutlivesASettingChangedAndARestart() {
        val dir = MemoryFiles()
        Engines(::load, dir).use { engines ->
            // pinned first under the code typed, ordered by use or not (U-28)
            engines.type(InputMethodCatalog.WUBI, "va")
            assertTrue(Offer.PIN in engines.offers(InputMethodCatalog.WUBI, 1))
            assertEquals(listOf("二", "一", "三"), engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.PIN, 1).candidates.take(3))
            engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.RESET, 0)
            engines.settings = EngineSettings(pageSize = 3, tables = mapOf("Wubi" to TableSettings(orderByUse = false)))
            assertEquals(listOf("二", "一", "三"), engines.type(InputMethodCatalog.WUBI, "va").candidates)
            // past the 简码 kept in order (TableHabitOrder's reach): 员 picked twice under 调频 is first there, kept with 调频 off
            byUse(engines, true)
            repeat(2) { engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.PICK, engines.type(InputMethodCatalog.WUBI, "kkkk").candidates.indexOf("员")) }
            assertEquals(listOf("叫", "员"), byUse(engines, false))
        }
        Engines(::load, dir).use { engines ->
            assertEquals(listOf("员", "叫"), byUse(engines, true))
            // 恢复码表顺序 forgets the order learned by use, not what was pinned
            // the one entry the app has (U-28's 恢复码表顺序 in a table's settings)
            assertEquals(LearningResult.Done(), engines.learning(null, LearningOp.ResetTableOrder(InputMethodCatalog.WUBI)))
            assertEquals("二", engines.type(InputMethodCatalog.WUBI, "va").candidates.first())
            assertEquals(setOf(Offer.UNPIN, Offer.BLOCK), engines.offers(InputMethodCatalog.WUBI, 0))
        }
    }

    @Test
    fun aTablesOwnSettingsApplyToItAlone() {
        Engines(::load, null).use { engines ->
            engines.settings = EngineSettings(pageSize = 3, tables = mapOf("Wubi" to TableSettings(orderByUse = false)))
            engines.type(InputMethodCatalog.WUBI, "va")
            assertEquals("二", engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.PICK, 1).commit)
            // 调频 off: the pick is kept but not used, so nothing offered to forget (U-28)
            assertEquals(listOf("一", "二", "三"), engines.type(InputMethodCatalog.WUBI, "va").candidates)
            assertFalse(Offer.FORGET in engines.offers(InputMethodCatalog.WUBI, 1))
            engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.RESET, 0)
            engines.settings = EngineSettings(pageSize = 3, tables = mapOf("Cangjie" to TableSettings(orderByUse = false)))
            engines.type(InputMethodCatalog.WUBI, "va")
            assertTrue(Offer.FORGET in engines.offers(InputMethodCatalog.WUBI, 1))
        }
    }

    @Test
    fun aKeySlippedIsReadAsMeantUnlessSlipsAreOff() {
        val engines = Engines(::load, null)
        // p is next to o
        assertEquals("好", engines.type(InputMethodCatalog.PINYIN, "hap").candidates.first())
        engines.settings = EngineSettings(typos = false)
        assertEquals(listOf("hap"), engines.type(InputMethodCatalog.PINYIN, "hap").candidates)
    }

    @Test
    fun settingsShapeTheSessionsMadeAfterThem() {
        val engines = Engines(::load, null)
        assertEquals(EngineSettings.DEFAULT_PAGE_SIZE, engines.type(InputMethodCatalog.WUBI, "va").candidates.size)
        // only the letters as typed
        assertEquals(listOf("li"), engines.type(InputMethodCatalog.PINYIN, "li").candidates)
        engines.settings = EngineSettings(pageSize = 3, fuzzy = setOf(Fuzzy.L_N))
        // made again: what was typed is gone
        assertEquals(listOf("一", "二", "三"), engines.type(InputMethodCatalog.WUBI, "va").candidates)
        assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "li").candidates)
        // the same settings again leave the input as it is
        engines.settings = EngineSettings(pageSize = 3, fuzzy = setOf(Fuzzy.L_N))
        assertEquals("你", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code).commit)
    }

    @Test
    fun aWordAddedAndPickedIsNotTypeableOnceRemoved() {
        val dir = MemoryFiles()
        Engines(::load, dir).use { engines ->
            assertTrue(engines.addWord("妮浩", "nihao"))
            engines.type(InputMethodCatalog.PINYIN, "nihao")
            val at = engines.candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == "妮浩" }
            assertEquals("妮浩", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, at).commit)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            engines.removeWord(UserWord("妮浩", "ni hao", UserWord.Kind.ADDED))
            // its pick in the log does not make it a word of the user's own when read back
            assertEquals(emptyList<UserWord>(), engines.userWords())
            assertFalse("妮浩" in engines.type(InputMethodCatalog.PINYIN, "nihao").candidates)
        }
        Engines(::load, dir).use { engines ->
            assertEquals(emptyList<UserWord>(), engines.userWords())
        }
    }

    @Test
    fun uLooksACharacterUpByItsStrokesShowingItsPinyin() {
        val engines = Engines(::load, null)
        val s = engines.type(InputMethodCatalog.PINYIN, "uz")
        assertEquals("u乛", s.preedit)
        assertEquals(listOf("好"), s.candidates)
        // its pinyin above it, with its tone (候选词注音 off: a hint's reading, EnginesReadingsTest)
        assertEquals(listOf("hǎo"), engines.candidates(InputMethodCatalog.PINYIN, 0, 1).single().readings)
        assertEquals("好", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code).commit)
        // an app without the data: u is pinyin's, and that is reported
        strokes = null
        val errors = ArrayList<IOException>()
        assertEquals("u", Engines(::load, null, { errors += it }).type(InputMethodCatalog.PINYIN, "u").preedit.replace(" ", ""))
        assertEquals(1, errors.size)
        assertTrue(InputMethodCatalog.STROKE_DATA in errors[0].message.orEmpty())
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
        val engines = Engines({ if (it == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(data) else load(it) }, null, warmUps = inPlace)
        // the decoder's reading alone
        engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        engines.context(InputMethodCatalog.PINYIN, "你")
        engines.type(InputMethodCatalog.PINYIN, "uz")
        assertEquals("好", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code).commit)
        assertEquals("你", engines.type(InputMethodCatalog.PINYIN, "ni").candidates.first())
    }

    @Test
    fun aWordTheDictionaryHasTakenOffTheAddedKeepsWhatWasLearnedOfIt() {
        val dir = MemoryFiles()
        Engines(::load, dir).use { engines ->
            assertTrue(engines.addWord("拟", "ni"))
            engines.pickUntilFirst("拟")
            engines.removeWord(UserWord("拟", "ni", UserWord.Kind.ADDED))
            assertEquals("拟", engines.type(InputMethodCatalog.PINYIN, "ni").candidates.first())
        }
        // nor forgotten in the log
        Engines(::load, dir).use { engines ->
            assertEquals("拟", engines.type(InputMethodCatalog.PINYIN, "ni").candidates.first())
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
        val engines = Engines({ if (it == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(data) else load(it) }, null)
        // the decoder's reading alone, on a page of two
        engines.settings = EngineSettings(pageSize = 2, modelLevel = ModelLevel.LOW, typos = false)
        assertFalse("小穴" in engines.type(InputMethodCatalog.PINYIN, "xiaoxue").candidates)
        assertTrue(engines.addWord("小穴", "xiao xue"))
        // the session made again knows it the user's
        assertTrue("小穴" in engines.type(InputMethodCatalog.PINYIN, "xiaoxue").candidates)
    }

    @Test
    fun oneLearnedWordRemovedIsARecordAppendedNotTheLogRewritten() {
        val dir = MemoryFiles()
        val log = dir.file(LearnLog.FILE)
        Engines(::load, dir).use { engines ->
            // 拟 then 好, over the decoder's 你好, twice: the user's word 拟好
            repeat(2) {
                engines.type(InputMethodCatalog.PINYIN, "nihao")
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == "拟" })
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0)
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            }
            val learned = engines.userWords().single()
            val before = log.read()
            // and one whose pinyin does not read: one word forgotten all the same
            engines.removeWords(listOf(learned, UserWord("拟好", "zz", UserWord.Kind.LEARNED)))
            assertEquals(emptyList<UserWord>(), engines.userWords())
            val after = log.read()
            assertTrue(after.size > before.size)
            assertArrayEquals(before, after.copyOf(before.size))
        }
        // and forgotten still when the log is read back
        assertEquals(emptyList<UserWord>(), Engines(::load, dir).use { it.userWords() })
    }

    @Test
    fun aCandidateForgottenInAnyPinyinIsForgottenInTheTablesToo() {
        val wubi = InputMethodCatalog.WUBI
        // a phrase, as a table saves no single character (TableRegistry.forgetInTables loads none for one)
        // the log forgets nothing by itself: 你好 is 五笔's by abcd
        val kept = MemoryFiles()
        olderPhrases(kept, "abcd" to "你好")
        Engines(::load, kept).use {
            it.settings = wubiShown
            assertEquals(listOf("你好"), it.type(wubi, "abcd").candidates)
        }
        for ((im, keys) in listOf(InputMethodCatalog.PINYIN to "nihao", InputMethodCatalog.T9 to "64426", InputMethodCatalog.SHUANGPIN to "nihk")) {
            val dir = MemoryFiles()
            // a phrase 五笔 saved, its table not loaded: loaded to forget it
            olderPhrases(dir, "abcd" to "你好")
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
        val wubi = InputMethodCatalog.WUBI
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
        assertTrue(Engines(::load, null).onEvent(InputMethodCatalog.T9, EngineEvent.ESCAPE, 0).handled)
    }

    @Test
    fun wubiIsOfferedFirstToBeAdded() {
        // the nine keys a keyboard of pinyin now (I1), no input method to add
        val offered = listOf("engine-erbi", "engine-cangjie", InputMethodCatalog.WUBI, "engine-ziranma", InputMethodCatalog.SHUANGPIN)
        assertEquals(
            listOf(InputMethodCatalog.WUBI, "engine-erbi", "engine-cangjie", "engine-ziranma", InputMethodCatalog.SHUANGPIN),
            InputMethodCatalog.addOrder(offered) { it },
        )
    }

    /**
     * In an address field a character goes in as it is unless the input method reads it as a key
     * ([InputMethodCatalog.readsAsKey], told whether something is typed): the pinyins' `'` only while
     * something is, as their sessions read it. Taken as text, it put xi'an in as xi' and an.
     */
    @Test
    fun aPinyinsSeparatorIsAKeyWhereItsSessionReadsIt() {
        val separator = '\''.code
        val scheme = EngineSettings().scheme
        for ((im, keys) in listOf(InputMethodCatalog.PINYIN to "ni", InputMethodCatalog.SHUANGPIN to "ni")) {
            Engines(::load, null).use { engines ->
                assertFalse(im, engines.onEvent(im, EngineEvent.CHAR, separator).handled)
                assertFalse(im, InputMethodCatalog.readsAsKey(im, scheme, separator, composing = false))
                assertTrue(im, engines.type(im, keys).preedit.isNotEmpty())
                assertTrue(im, engines.onEvent(im, EngineEvent.CHAR, separator).handled)
                assertTrue(im, InputMethodCatalog.readsAsKey(im, scheme, separator, composing = true))
            }
        }
    }

    private companion object {
        const val MAX_PICKS = 10
        const val MAX_SLICES = 10_000
    }
}
