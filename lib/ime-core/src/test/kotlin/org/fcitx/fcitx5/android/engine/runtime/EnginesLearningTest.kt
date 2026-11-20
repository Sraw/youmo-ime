/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.english.EnglishBlocks
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.backup.Backups
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.ListedItem
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.learn.control.WordKind
import org.fcitx.fcitx5.android.engine.learn.simplifierOf
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.write
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Engines.learning]: a long press's menu and acts, the clears, the lexicon page's lists. */
class EnginesLearningTest : EnginesFixture() {

    private val root = MemoryFiles()

    private fun engines() = Engines(::load, root, warmUps = inPlace)

    private fun Engines.texts() = candidates(InputMethodCatalog.PINYIN, 0, 10).map { it.text }

    private fun Engines.op(op: LearningOp) = learning(InputMethodCatalog.PINYIN, op)

    // nothing typed, nothing before
    private fun Engines.afresh() {
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        context(InputMethodCatalog.PINYIN, "")
    }

    // 拟 picked under ni, twice after nothing: first there
    private fun Engines.learnNi() = repeat(2) {
        afresh()
        type(InputMethodCatalog.PINYIN, "ni")
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, texts().indexOf("拟"))
    }

    private fun log() = LearnLog.replay(root.file(LearnLog.FILE).read())

    @Test
    fun theMenuSaysWhatTheWordIsAndWhatMayBeDone() {
        val engines = engines()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        val menu = engines.op(LearningOp.Menu(0, "你")) as LearningResult.Menu
        assertEquals(WordKind.DICTIONARY, menu.kind)
        assertEquals(listOf(MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), menu.items)
        assertEquals(BlockScope.EVERYWHERE, menu.scope)
        // the floating list's index, from the page shown: its first here
        assertEquals(menu, engines.op(LearningOp.OnPage(LearningOp.Menu(0, "你"))))
        assertEquals(LearningResult.Unavailable, engines.op(LearningOp.Menu(0, "拟")))
        assertEquals(LearningResult.Unavailable, engines.learning(InputMethodCatalog.SHUANGPIN, LearningOp.Menu(0, "你")))
        engines.learnNi()
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        val learned = engines.op(LearningOp.Menu(engines.texts().indexOf("拟"), "拟")) as LearningResult.Menu
        assertEquals(MenuItem.DELETE, learned.items.first())
        engines.close()
    }

    // a candidate the habits ranked says its tier, uses and code; WHY only where there is something to say
    @Test
    fun theMenuSaysWhyACandidateIsWhereItIs() {
        val engines = engines()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        val plain = (engines.op(LearningOp.Menu(0, "你")) as LearningResult.Menu).why!!
        assertEquals(HabitRanker.Tier.DICTIONARY, plain.tier)
        assertNotNull(plain.code)
        engines.learnNi()
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        val learned = engines.op(LearningOp.Menu(0, "拟")) as LearningResult.Menu
        assertEquals(HabitRanker.Tier.FIRST_HERE, learned.why!!.tier)
        assertEquals(2, learned.why!!.uses)
        assertEquals(plain.code, learned.why!!.code)
        assertTrue(MenuItem.WHY in learned.items)
        // a table's: under the code typed; a longer code's word after them is ranked by none
        engines.settings = wubiShown
        engines.type(InputMethodCatalog.WUBI, "vbg")
        val shown = engines.candidates(InputMethodCatalog.WUBI, 0, 10).map { it.text }
        assertEquals(listOf("好", "妤"), shown)
        val ranked = engines.learning(InputMethodCatalog.WUBI, LearningOp.Menu(0, "好")) as LearningResult.Menu
        assertEquals("vbg", ranked.why!!.code!!.keys)
        assertTrue(MenuItem.WHY in ranked.items)
        val longer = engines.learning(InputMethodCatalog.WUBI, LearningOp.Menu(1, "妤")) as LearningResult.Menu
        assertNull(longer.why)
        assertFalse(MenuItem.WHY in longer.items)
        engines.close()
    }

    @Test
    fun aPredictionsWhyIsThatItFollowsTheWordBefore() {
        val engines = engines()
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, "hao")
        val offered = engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.texts().indexOf("好")).candidates
        val at = offered.indexOf("拟")
        assertTrue("$offered", at >= 0)
        val menu = engines.op(LearningOp.Menu(at, "拟")) as LearningResult.Menu
        assertEquals(Kinds.PREDICTION, menu.why!!.code!!.kind)
        assertEquals(LearningMenu.Reason.PREDICTION, LearningMenu.reason(menu.why))
        engines.close()
    }

    @Test
    fun aWordAddedByHandIsDeletedAndComesBackWithTheChip() {
        val engines = engines()
        engines.addWord("拟好", "ni hao")
        engines.type(InputMethodCatalog.PINYIN, "nihao")
        val at = engines.texts().indexOf("拟好")
        assertEquals(WordKind.ADDED, (engines.op(LearningOp.Menu(at, "拟好")) as LearningResult.Menu).kind)
        val done = engines.op(LearningOp.Delete(at, "拟好")) as LearningResult.Done
        assertTrue(engines.userWords(UserWord.Kind.ADDED).isEmpty())
        assertEquals(LearningResult.Done(), engines.op(LearningOp.Undo(done.undo)))
        assertEquals(listOf("拟好"), engines.userWords(UserWord.Kind.ADDED).map { it.text })
        engines.close()
    }

    @Test
    fun aWordAddedByHandTakenBackIsWhereItWas() {
        val engines = engines()
        engines.addWord("你们", "ni men")
        engines.addWord("拟好", "ni hao")
        engines.addWord("好的", "hao de")
        engines.type(InputMethodCatalog.PINYIN, "nihao")
        val done = engines.op(LearningOp.Delete(engines.texts().indexOf("拟好"), "拟好")) as LearningResult.Done
        assertEquals(LearningResult.Done(), engines.op(LearningOp.Undo(done.undo)))
        assertEquals(listOf("你们", "拟好", "好的"), engines.userWords(UserWord.Kind.ADDED).map { it.text })
        engines.close()
    }

    @Test
    fun aForgetTakenBackLeavesWhatWasLearned() {
        val engines = engines()
        engines.learnNi()
        val was = log().dump()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        val done = engines.op(LearningOp.Delete(engines.texts().indexOf("拟"), "拟")) as LearningResult.Done
        assertFalse(log().dump() == was)
        assertEquals(LearningResult.Done(), engines.op(LearningOp.Undo(done.undo)))
        assertEquals(was, log().dump())
        val shown = engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN)
        assertTrue(shown.handled)
        assertTrue("拟" in shown.candidates)
        engines.close()
    }

    @Test
    fun aBlockTakenBackShowsTheWordAgain() {
        val engines = engines()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        val done = engines.op(LearningOp.Block(1, "拟")) as LearningResult.Done
        assertEquals(listOf("拟"), engines.userWords(UserWord.Kind.BLOCKED).map { it.text })
        assertEquals(LearningResult.Done(), engines.op(LearningOp.Undo(done.undo)))
        assertTrue(engines.userWords(UserWord.Kind.BLOCKED).isEmpty())
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN)
        assertTrue("拟" in engines.texts())
        assertEquals(LearningResult.Unavailable, engines.op(LearningOp.Unpin(1, "拟")))
        engines.close()
    }

    @Test
    fun aPressForAKeyboardNeverMadeIsNotSent() {
        // a pair made its keyboard on the press, typed nothing, and the act read as done
        val engines = Engines(::load, null, warmUps = inPlace)
        val before = loaded.toList()
        assertFalse(engines.pressShown(InputMethodCatalog.SHUANGPIN, EngineEvent.UNPIN, 0))
        // nor was the keyboard made for it: no data read in for a session
        assertEquals(before, loaded)
        engines.close()
    }

    @Test
    fun aPinIsListedAndTakenOffTheList() {
        val engines = engines()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        engines.op(LearningOp.Pin(1, "拟"))
        assertEquals(listOf("拟", "你"), engines.texts().take(2))
        val pins = (engines.op(LearningOp.ListItems(LearningOp.Listed.PINS)) as LearningResult.Listed).items
        assertEquals(listOf(ListedItem(Code(Kinds.FULL, "ni"), "", "拟", "ni")), pins)
        assertEquals(1, (engines.op(LearningOp.Overview) as LearningResult.Overview).pinned)
        engines.op(LearningOp.RemoveItems(pins))
        assertTrue(log().pins.isEmpty())
        engines.close()
    }

    @Test
    fun aPinUnderADroppedSchemeIsListedAndTakenOff() {
        val engines = engines()
        // a name no scheme has types as 自然码 does, its records kept under the name
        engines.settings = EngineSettings(shuangpin = "Ziguang")
        engines.type(InputMethodCatalog.SHUANGPIN, "ni")
        val second = engines.candidates(InputMethodCatalog.SHUANGPIN, 0, 10)[1].text
        engines.learning(InputMethodCatalog.SHUANGPIN, LearningOp.Pin(1, second))
        engines.settings = EngineSettings(shuangpin = EngineSettings.XIAOHE)
        val pins = (engines.op(LearningOp.ListItems(LearningOp.Listed.PINS)) as LearningResult.Listed).items
        assertEquals(listOf(Code(Kinds.shuangpin("Ziguang"), "ni")), pins.map { it.code })
        engines.op(LearningOp.RemoveItems(pins))
        assertTrue(log().pins.isEmpty())
        engines.close()
    }

    @Test
    fun aPinOverAnotherTakenBackPutsTheOtherBack() {
        val engines = engines()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        engines.op(LearningOp.Pin(1, "拟"))
        assertEquals(listOf("拟", "你"), engines.texts().take(2))
        val done = engines.op(LearningOp.Pin(1, "你")) as LearningResult.Done
        assertEquals("你", log().pins[Code(Kinds.FULL, "ni")]?.text)
        assertEquals(LearningResult.Done(), engines.op(LearningOp.Undo(done.undo)))
        assertEquals("拟", log().pins[Code(Kinds.FULL, "ni")]?.text)
        assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN).candidates.first())
        engines.close()
    }

    @Test
    fun aPredictionTurnedOffIsNotOfferedAfterThatWord() {
        val engines = engines()
        engines.type(InputMethodCatalog.PINYIN, "hao")
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code)
        val at = engines.texts().indexOf("拟")
        assertTrue(at >= 0)
        val menu = engines.op(LearningOp.Menu(at, "拟")) as LearningResult.Menu
        assertTrue(MenuItem.NO_PREDICT in menu.items)
        val report = (engines.op(LearningOp.Report(at, "拟")) as LearningResult.Text).text
        assertTrue("拟" in report && "好" !in report)
        val done = engines.op(LearningOp.NoPredict(at, "拟")) as LearningResult.Done
        assertTrue(done.undo != LearningResult.NO_UNDO)
        assertFalse("拟" in engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN).candidates)
        val off = (engines.op(LearningOp.ListItems(LearningOp.Listed.NO_PREDICT)) as LearningResult.Listed).items
        assertEquals(listOf(ListedItem(null, "好", "拟")), off)
        engines.op(LearningOp.RemoveItems(off))
        engines.type(InputMethodCatalog.PINYIN, "hao")
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code)
        assertTrue("拟" in engines.texts())
        engines.close()
    }

    // 联想 goes on from 你好, the record's last word, not from 好
    @Test
    fun aLearnedPredictionTurnedOffAfterALongerCommitIsOffToo() {
        val engines = engines()
        fun afterNihao(): List<String> {
            engines.context(InputMethodCatalog.PINYIN, "", cleared = true)
            engines.type(InputMethodCatalog.PINYIN, "nihao")
            // picked by its place: what the user picks after it moves 拟好 up
            assertEquals("你好", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, '1'.code + engines.texts().indexOf("你好")).commit)
            return engines.texts()
        }
        assertFalse("你" in afterNihao())
        repeat(4) {
            afterNihao()
            engines.type(InputMethodCatalog.PINYIN, "ni")
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, '1'.code + engines.texts().indexOf("你"))
        }
        val predicted = afterNihao()
        val at = predicted.indexOf("你")
        assertTrue(predicted.toString(), at >= 0)
        val done = engines.op(LearningOp.NoPredict(at, "你")) as LearningResult.Done
        assertFalse("你" in engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN).candidates)
        // undone, it is back; turned off again, listed, and put back from the list
        assertEquals(LearningResult.Done(), engines.op(LearningOp.Undo(done.undo)))
        assertTrue("你" in engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN).candidates)
        assertTrue(engines.op(LearningOp.NoPredict(at, "你")) is LearningResult.Done)
        assertFalse("你" in afterNihao())
        val off = (engines.op(LearningOp.ListItems(LearningOp.Listed.NO_PREDICT)) as LearningResult.Listed).items
        assertEquals(listOf(ListedItem(null, "你好", "你")), off)
        engines.op(LearningOp.RemoveItems(off))
        assertTrue("你" in afterNihao())
        engines.close()
    }

    @Test
    fun aPredictionDeletedAtOnceIsTakenBackAndOneTurnedOffOrClearedIsNotPutBack() {
        val engines = engines()
        fun afterNihao(): List<String> {
            engines.context(InputMethodCatalog.PINYIN, "", cleared = true)
            engines.type(InputMethodCatalog.PINYIN, "nihao")
            assertEquals("你好", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, '1'.code + engines.texts().indexOf("你好")).commit)
            return engines.texts()
        }
        afterNihao()
        repeat(4) {
            for (w in listOf("ni" to "你", "hao" to "好")) {
                engines.type(InputMethodCatalog.PINYIN, w.first)
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, '1'.code + engines.texts().indexOf(w.second))
                afterNihao()
            }
        }
        val shown = afterNihao()
        assertTrue(shown.toString(), shown.size >= 2)
        val second = shown[1]
        // a character alone goes first picked twice running: deleted each time, it stays where it was
        fun pickSecond(deleted: Boolean) {
            assertEquals(second, engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1).commit)
            if (deleted) repeat(second.length) { assertFalse(engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.BACKSPACE, 0).handled) }
            engines.type(InputMethodCatalog.PINYIN, "ni")
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.ESCAPE, 0)
            afterNihao()
        }
        repeat(2) { pickSecond(deleted = true) }
        assertEquals(shown, engines.texts())
        repeat(2) { pickSecond(deleted = false) }
        assertEquals(second, engines.texts().first())
        // turned off there, the pick does not put it back among them (HabitRanker's own, PredictionOrder's back)
        val off = engines.op(LearningOp.NoPredict(0, second)) as LearningResult.Done
        assertFalse(second in engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN).candidates)
        assertEquals(LearningResult.Done(), engines.op(LearningOp.Undo(off.undo)))
        assertEquals(second, engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN).candidates.first())
        // the last hour cleared: the picks, and the pairs typed, gone with it
        assertTrue(engines.op(LearningOp.ClearRecent(LearningOp.Span.HOUR)) is LearningResult.Cleared)
        assertEquals(listOf("拟"), afterNihao())
        engines.close()
    }

    // a copy cannot lose a time alone, so it goes with either clear; an older build's file (whether left: the row's
    // third) only with a clear of today or more
    @Uat("WD-47")
    @Test
    fun aClearTakesTheCopiesOfTheRecordKeptAside() = assertRows(
        listOf(
            Triple("全部清空: the record and all beside it", LearningOp.ClearLearned(true), false),
            Triple("清空学到的, 全部清空 not ticked: the copies are of what was learned", LearningOp.ClearLearned(false), false),
            Triple("a clear of today: a copy cannot lose a time alone, so the whole copy", LearningOp.ClearRecent(LearningOp.Span.TODAY), false),
            Triple("a clear of 10 minutes: the copies as today's, the older builds' files left", LearningOp.ClearRecent(LearningOp.Span.TEN_MINUTES), true),
            Triple("a clear of an hour: the copies as today's, the older builds' files left", LearningOp.ClearRecent(LearningOp.Span.HOUR), true),
        ),
        { it.first },
    ) { (why, op, olderLeft) ->
        val files = MemoryFiles()
        val copies = listOf("learn.log.unreadable", "learn.log.unreadable.1").map { files.file(it).apply { write("x".toByteArray()) } }
        val other = files.file("learn.log.unreadable.x").apply { write("x".toByteArray()) }
        val older = files.file("pinyin.user").apply { write("x".toByteArray()) }
        Engines(::load, files, warmUps = inPlace).use { engines ->
            engines.learnNi()
            assertTrue(engines.op(op) is LearningResult.Cleared)
        }
        copies.forEach { assertFalse("$it", it.exists()) }
        assertTrue(other.exists())
        assertEquals(why, olderLeft, older.exists())
    }

    // found by the name it was renamed from, though no file of that name is listed
    @Test
    fun aCopyKeptAsideThatAKilledClearLeftRenamedIsGoneAtTheNextStart() {
        val left = listOf("learn.log.unreadable.cleared", "learn.log.unreadable.1.cleared").map { root.file(it).apply { write("x".toByteArray()) } }
        val other = root.file("learn.log.unreadable.x.cleared").apply { write("x".toByteArray()) }
        // the copies alone aside may be a clear of 10 minutes, which takes no older build's file
        val older = root.file("pinyin.user").apply { write("x".toByteArray()) }
        engines().use { it.learnNi() }
        left.forEach { assertFalse("$it", it.exists()) }
        assertTrue(other.exists())
        assertTrue(older.exists())
    }

    @Test
    @Uat("WD-25")
    fun aClearOfEverythingEmptiesTheWordListsOfTheLinesThisBuildDoesNotRead() {
        val lists = mapOf(
            WordLists.ADDED to "拟好 ni'hao\n幽默 you'mo 2 later\n",
            WordLists.BLOCKED to "你好 ni'hao 1.5 later\n",
            EnglishBlocks.FILE to "the\n!later form\n",
        )
        lists.forEach { (name, text) -> root.file(name).write(text.toByteArray()) }
        engines().use { engines ->
            engines.learnNi()
            assertTrue(engines.op(LearningOp.ClearLearned(true)) is LearningResult.Cleared)
        }
        assertEquals(lists.mapValues { "" }, lists.mapValues { root.file(it.key).read().decodeToString() })
    }

    @Test
    fun withHabitsOffItIsAsInstalledButForTheBlocks() {
        val engines = engines()
        engines.learnNi()
        engines.addWord("拟好", "ni hao")
        engines.blockWord("好", "hao")
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertEquals("拟", engines.texts().first())
        val logged = root.file(LearnLog.FILE).length()
        engines.op(LearningOp.UseHabits(false))
        assertFalse(engines.useHabits)
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertEquals("你", engines.texts().first())
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.texts().indexOf("拟"))
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, "nihao")
        assertFalse("拟好" in engines.texts())
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, "hao")
        assertFalse("好" in engines.texts())
        // an English word and a handwritten pick while off are not learned either
        engines.type(InputMethodCatalog.ENGLISH, "the")
        engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code)
        engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.RESET, 0)
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(0, InkReading(listOf("拟"), doubleArrayOf(0.0))))
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0)
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        assertEquals(logged, root.file(LearnLog.FILE).length())
        engines.op(LearningOp.UseHabits(true))
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertEquals("拟", engines.texts().first())
        engines.close()
    }

    @Test
    fun anActOnTheSecondPageIsOnItsWord() {
        val engines = engines()
        engines.settings = EngineSettings(pageSize = 2)
        engines.type(InputMethodCatalog.PINYIN, "ni")
        val first = engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PAGE_DOWN, 0).candidates.first()
        assertEquals(LearningResult.Done::class, engines.op(LearningOp.OnPage(LearningOp.Block(0, first)))::class)
        engines.afresh()
        engines.settings = EngineSettings()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertFalse(first in engines.texts())
        engines.close()
    }

    @Test
    fun aDeleteOnTheSecondPageDeletesItsWord() {
        val engines = engines()
        assertTrue(engines.addWord("泥", "ni"))
        assertTrue(engines.addWord("逆", "ni"))
        engines.settings = EngineSettings(pageSize = 1)
        val all = engines.type(InputMethodCatalog.PINYIN, "ni").let { engines.texts() }
        val second = engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PAGE_DOWN, 0).candidates.single()
        assertEquals(all[1], second)
        assertTrue(second in listOf("泥", "逆"))
        assertEquals(LearningResult.Done::class, engines.op(LearningOp.OnPage(LearningOp.Delete(0, second)))::class)
        engines.afresh()
        engines.settings = EngineSettings()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertFalse(second in engines.texts())
        assertTrue(all[0] in engines.texts())
        engines.close()
    }

    @Test
    fun withHabitsOffATableOffersNoneOfTheUsersWords() {
        val engines = engines()
        engines.type(InputMethodCatalog.WUBI, "vbg")
        engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.RESET, 0)
        assertTrue(engines.addWord("好你", "hao ni"))
        assertTrue("好你" in engines.type(InputMethodCatalog.WUBI, "vbw").candidates)
        engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.RESET, 0)
        engines.op(LearningOp.UseHabits(false))
        assertFalse("好你" in engines.type(InputMethodCatalog.WUBI, "vbw").candidates)
        engines.close()
    }

    // no backup of this install's still holds what a clear forgot; the one saved after holds what is left
    @Test
    fun aClearLeavesNoBackupOfWhatItCleared() {
        // the fresh one is written off the thread on the phone: here as it is handed over
        val engines = Engines(::load, root, warmUps = inPlace, installFile = root.file("learn.install"), background = inPlace)
        engines.learning(null, BackupOp.SetAuto(true))
        engines.learnNi()
        assertTrue(engines.op(LearningOp.ClearLearned(all = false)) is LearningResult.Cleared)
        val dir = root.dir(Backups.DIR)
        assertEquals(1, (engines.op(BackupOp.Kept) as BackupResult).backups.size)
        engines.close()
        val files = dir.files().map(dir::file)
        assertEquals(1, files.size)
        files.forEach { assertTrue(it.name, LearnBackup.read(it.read()).state.stats.isEmpty()) }
    }

    @Test
    fun theClearsForgetWhatWasLearned() {
        val engines = engines()
        engines.learnNi()
        engines.addWord("拟好", "ni hao")
        engines.blockWord("好", "hao")
        assertTrue(engines.op(LearningOp.ClearRecent(LearningOp.Span.HOUR)) is LearningResult.Cleared)
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertEquals("你", engines.texts().first())
        assertTrue(log().stats.isEmpty())
        engines.learnNi()
        assertEquals(LearningResult.Cleared(LearningResult.ALL), engines.op(LearningOp.ClearLearned(all = false)))
        assertTrue(log().stats.isEmpty())
        assertEquals(1, (engines.op(LearningOp.Overview) as LearningResult.Overview).added)
        engines.op(LearningOp.ClearLearned(all = true))
        val overview = engines.op(LearningOp.Overview) as LearningResult.Overview
        assertEquals(listOf(0, 0, 0, 0, 0), listOf(overview.mine, overview.added, overview.pinned, overview.blocked, overview.noPredict))
        assertTrue(overview.habits)
        engines.close()
    }

    @Test
    fun wordsRemovedTogetherComeBackTogether() {
        val engines = engines()
        engines.addWord("拟好", "ni hao")
        engines.blockWord("好", "hao")
        val words = engines.userWords(UserWord.Kind.ADDED) + engines.userWords(UserWord.Kind.BLOCKED)
        val done = engines.learning(null, LearningOp.RemoveWords(words)) as LearningResult.Done
        assertTrue(engines.userWords(UserWord.Kind.ADDED).isEmpty() && engines.userWords(UserWord.Kind.BLOCKED).isEmpty())
        assertEquals(LearningResult.Done(), engines.learning(null, LearningOp.Undo(done.undo)))
        assertEquals(listOf("拟好"), engines.userWords(UserWord.Kind.ADDED).map { it.text })
        assertEquals(listOf("好"), engines.userWords(UserWord.Kind.BLOCKED).map { it.text })
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, "nihao")
        assertTrue("拟好" in engines.texts())
        engines.close()
    }

    @Test
    fun theWordsListedSayWhenTheyWereLastUsed() {
        val engines = engines()
        engines.addWord("拟好", "ni hao")
        engines.type(InputMethodCatalog.PINYIN, "nihao")
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.texts().indexOf("拟好"))
        val word = engines.userWords(UserWord.Kind.ADDED).single()
        assertTrue(word.lastUsed != UserWord.NEVER)
        engines.close()
    }

    @Test
    fun withNoUserDirectoryOnlyTheTimelessClearsAreDone() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.learnNi()
        assertEquals(LearningResult.Unavailable, engines.op(LearningOp.ClearRecent(LearningOp.Span.TODAY)))
        assertEquals(LearningResult.Cleared(LearningResult.ALL), engines.op(LearningOp.ClearLearned(all = true)))
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertEquals("你", engines.texts().first())
        assertEquals(LearningResult.Unavailable, engines.op(LearningOp.Undo(1)))
        assertEquals(LearningResult.Unavailable, engines.learning(null, LearningOp.Menu(0, "你")))
        engines.close()
    }

    @Test
    fun aTablesDeleteIsTakenBackByTheChip() {
        val engines = engines()
        engines.settings = wubiShown
        repeat(2) {
            engines.type(InputMethodCatalog.WUBI, "vbg")
            engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.PICK, engines.candidates(InputMethodCatalog.WUBI, 0, 10).map { it.text }.indexOf("妤"))
        }
        engines.type(InputMethodCatalog.WUBI, "vbg")
        val at = engines.candidates(InputMethodCatalog.WUBI, 0, 10).map { it.text }.indexOf("妤")
        val menu = engines.learning(InputMethodCatalog.WUBI, LearningOp.Menu(at, "妤")) as LearningResult.Menu
        assertEquals(BlockScope.UNDER_CODE, menu.scope)
        assertTrue(MenuItem.DELETE in menu.items)
        // what a table learned is in the record: its delete is taken back as pinyin's
        val done = engines.learning(InputMethodCatalog.WUBI, LearningOp.Delete(at, "妤")) as LearningResult.Done
        assertTrue(done.undo != LearningResult.NO_UNDO)
        assertEquals(LearningResult.Done(), engines.learning(InputMethodCatalog.WUBI, LearningOp.Undo(done.undo)))
        engines.close()
    }

    @Test
    fun aSearchFieldShowsNoPrediction() {
        val engines = engines()
        engines.context(InputMethodCatalog.PINYIN, "", learnFlags = Commit.NO_PREDICT)
        engines.type(InputMethodCatalog.PINYIN, "hao")
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code)
        assertEquals(emptyList<String>(), engines.texts())
        assertEquals(emptyList<String>(), engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN).candidates)
        engines.context(InputMethodCatalog.PINYIN, "", learnFlags = 0)
        engines.type(InputMethodCatalog.PINYIN, "hao")
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code)
        assertTrue("拟" in engines.texts())
        engines.close()
    }

    @Test
    fun onTheFourteenKeysALongPressActsOnWhatTheyShow() {
        val engines = engines()
        val ni = NineKeyLetters.G14.typings("ni").single()
        repeat(2) {
            engines.afresh()
            engines.type(InputMethodCatalog.PINYIN, ni)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.texts().indexOf("拟"))
        }
        // the full keyboard's pinyin learned nothing of it (qk: apart from qp)
        engines.afresh()
        assertEquals(0, engines.type(InputMethodCatalog.PINYIN, "ni").candidates.indexOf("你"))
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, ni)
        val at = engines.texts().indexOf("拟")
        val menu = engines.op(LearningOp.Menu(at, "拟")) as LearningResult.Menu
        assertEquals(Kinds.keys(NineKeyLetters.G14.tag()), menu.why!!.code!!.kind)
        val report = (engines.op(LearningOp.Report(at, "拟")) as LearningResult.Text).text
        assertTrue(report, " bn ui\n" in report)
        assertTrue(report, report.none(NineKeyLetters.G14::isCode))
        assertTrue(engines.op(LearningOp.Delete(at, "拟")) is LearningResult.Done)
        assertTrue("拟" !in engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN).candidates.take(at + 1).drop(at))
        engines.close()
    }

    @Test
    fun onTheFourteenKeysACommitDeletedAtOnceIsNotLearned() {
        val engines = engines()
        val ni = NineKeyLetters.G14.typings("ni").single()
        repeat(2) {
            // a field whose text is known: what is deleted of a commit can be taken back
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            engines.context(InputMethodCatalog.PINYIN, "给", learnFlags = 0)
            engines.type(InputMethodCatalog.PINYIN, ni)
            assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.texts().indexOf("拟")).commit)
            assertFalse(engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.BACKSPACE, 0).handled)
        }
        engines.afresh()
        assertEquals("你", engines.type(InputMethodCatalog.PINYIN, ni).candidates.first())
        engines.close()
    }


    // each commit in the learning record, read back as it ranks
    @Test
    fun aCommitIsRecordedInTheLearningLog() {
        val engines = Engines(::load, root, simplifier = { simplifierOf('妳' to '你') })
        // the keyboard made by its first key, then told of the app's text
        repeat(2) {
            engines.context(InputMethodCatalog.PINYIN, "妳")
            engines.type(InputMethodCatalog.PINYIN, "ni")
            assertEquals("你", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code).commit)
        }
        engines.close()
        val state = LearnLog.replay(root.file(LearnLog.FILE).read())
        val key = state.stats.keys.single()
        assertEquals("qp:ni" to "你", key.code.toString() to key.text)
        assertEquals(1, state.pairs.keys.count { it.prev == "你" && it.next == "你" })
        val again = Engines(::load, root)
        again.removeWords(listOf(UserWord("你", "ni", UserWord.Kind.LEARNED)))
        again.type(InputMethodCatalog.PINYIN, "ni")
        again.close()
        assertTrue(LearnLog.replay(root.file(LearnLog.FILE).read()).stats.isEmpty())
    }

    @Test
    fun withNoUserDirectoryItLearnsInMemory() {
        Engines(::load, null).use { engines ->
            engines.type(InputMethodCatalog.PINYIN, "ni")
            assertEquals("你", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code).commit)
            // learned there, in a record on no disk: 拟 picked twice counted beside 你
            repeat(2) {
                val at = engines.type(InputMethodCatalog.PINYIN, "ni").candidates.indexOf("拟")
                assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, at).commit)
            }
            val log = engines.learnLog()
            assertFalse(log.onDisk)
            assertEquals(setOf("你", "拟"), log.state.stats.keys.map { it.text }.toSet())
        }
    }

    // 自然码 js is jiong whole, and j and s alone: 角色 typed in full as jtse
    @Test
    @Uat("SP-30")
    fun theMenuSaysAWordTypedIsReadByItsKeysAlone() {
        val engines = engines()
        val sp = InputMethodCatalog.SHUANGPIN
        fun shown() = engines.candidates(sp, 0, 10).map { it.text }
        engines.type(sp, "jtse")
        engines.onEvent(sp, EngineEvent.PICK, shown().indexOf("角色"))
        engines.onEvent(sp, EngineEvent.RESET, 0)
        engines.context(sp, "")
        engines.type(sp, "js")
        val at = shown().indexOf("角色")
        assertTrue("${shown()}", at >= 0)
        val menu = engines.learning(sp, LearningOp.Menu(at, "角色")) as LearningResult.Menu
        assertEquals(HabitRanker.Tier.ALONE, menu.why!!.tier)
        assertEquals(LearningMenu.Reason.ALONE, LearningMenu.reason(menu.why))
        engines.close()
    }
}
