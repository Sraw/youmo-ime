/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
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
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.write
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [Engines.learning] (impl/streams/E-design.md): a long press's menu and acts, each taken back by
 * the chip; U-93 off as installed but for the blocks; the clears; the lexicon page's lists.
 */
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

    // 拟 picked under ni, twice after nothing: first there (U-02)
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
        // nothing learned of it: nothing to forget
        assertEquals(WordKind.DICTIONARY, menu.kind)
        assertEquals(listOf(MenuItem.BLOCK, MenuItem.PIN, MenuItem.WHY), menu.items)
        assertEquals(BlockScope.EVERYWHERE, menu.scope)
        // the floating list's index, from the page shown: its first here
        assertEquals(menu, engines.op(LearningOp.OnPage(LearningOp.Menu(0, "你"))))
        // the list changed since: another word at the index
        assertEquals(LearningResult.Unavailable, engines.op(LearningOp.Menu(0, "拟")))
        assertEquals(LearningResult.Unavailable, engines.learning(InputMethodCatalog.SHUANGPIN, LearningOp.Menu(0, "你")))
        engines.learnNi()
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        val learned = engines.op(LearningOp.Menu(engines.texts().indexOf("拟"), "拟")) as LearningResult.Menu
        assertEquals(MenuItem.DELETE, learned.items.first())
        engines.close()
    }

    // review-int-runtime M2: a candidate the habits ranked says its tier, uses and code (U-81); WHY
    // only where there is something to say
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

    // verify-int-step1a V1: a 联想's 为什么排在这 says it follows the word before, through the session's own why
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

    // review-int N3: a word added by hand, deleted and taken back, is where it was among them (their order, 我的词's)
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
        // the keyboard reads its input anew for the host to show
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
        // not offered: done nothing
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

    // a pin made under a 双拼 scheme 0.5 dropped (sp:Ziguang, as 0.4 wrote it) is left as it is: listed
    // under its own kind with 小鹤 set now, and taken off the list as any other (SchemeMigration)
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

    // U-82 (review-int M4): 固定首位 over another pin taken back at once: the pin there before is back
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
        // the report has the candidates, never the word before
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

    // review-E-engine's open question (verify-E F2): 你好 put in, the decoder's 你 好 made the user's
    // word, 联想 goes on from it, the record's last word, not from 好: what was put in after it is
    // offered there, and turned off there. 你 after it is only there for being typed there, three
    // times (Predictor.PAIRS)
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

    // U-03 for a 联想 picked: deleted at once, it is taken back as a commit is, and moves nothing up
    @Test
    fun aPredictionDeletedAtOnceIsTakenBackAndOneTurnedOffOrClearedIsNotPutBack() {
        val engines = engines()
        fun afterNihao(): List<String> {
            engines.context(InputMethodCatalog.PINYIN, "", cleared = true)
            engines.type(InputMethodCatalog.PINYIN, "nihao")
            assertEquals("你好", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, '1'.code + engines.texts().indexOf("你好")).commit)
            return engines.texts()
        }
        // 你 and 好 typed after 你好 enough to be offered there
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
        // a character alone goes first picked twice running (U-10): deleted each time, it stays where it was
        fun pickSecond(deleted: Boolean) {
            assertEquals(second, engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1).commit)
            if (deleted) repeat(second.length) { assertFalse(engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.BACKSPACE, 0).handled) }
            engines.type(InputMethodCatalog.PINYIN, "ni")
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.ESCAPE, 0)
            afterNihao()
        }
        repeat(2) { pickSecond(deleted = true) }
        assertEquals(shown, engines.texts())
        // kept, it goes first
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

    // review-E-engine S3 (verify-E F3): libime's own files, an older build's yet, go with a clear as the rest
    @Test
    fun libimesOwnFilesGoWithAClear() {
        val libime = MemoryFiles()
        val files = listOf(libime.file("user.dict"), libime.file("user.history")).onEach { it.write("x".toByteArray()) }
        val engines = Engines(::load, root, warmUps = inPlace, legacyFiles = files)
        engines.learnNi()
        assertTrue(engines.op(LearningOp.ClearLearned(all = false)) is LearningResult.Cleared)
        assertTrue(files.none { it.exists() || it.beside(".cleared").exists() })
        engines.close()
    }

    // F2 review 1: a table's log of 0.4 and the mark beside it, read by nothing now, go with a clear
    // too: a 0.4 put back would offer the phrases cleared again
    @Test
    fun aTablesOldLogGoesWithAClear() {
        val files = listOf("wubi.user", "wubi.user.told", "cangjie.user").map { root.file(it).apply { write("x".toByteArray()) } }
        val engines = Engines(::load, root, warmUps = inPlace)
        engines.learnNi()
        assertTrue(engines.op(LearningOp.ClearLearned(all = true)) is LearningResult.Cleared)
        assertTrue(files.none { it.exists() || it.beside(".cleared").exists() })
        engines.close()
    }

    // F2 review 7: 0.4 stopped mid-conversion (its mark left): told at the open, the mark left for a 0.4 put back
    @Test
    fun aConversionOf04LeftUnfinishedIsTold() {
        val mark = root.file("learn.converting").apply { write("".toByteArray()) }
        val errors = ArrayList<String>()
        repeat(2) {
            val engines = Engines(::load, root, warmUps = inPlace, onError = { errors += it.message.orEmpty() })
            engines.learnNi()
            engines.close()
        }
        // once, not at every start (F2 review B6)
        assertEquals(1, errors.count { "learn.converting" in it })
        assertTrue(mark.exists())
    }

    // F2 review B2: a clear of today leaves a table's log not read into the record yet (its phrases the user's still:
    // here 仓颉's, whose table the fixture has not), and takes it once read
    @Test
    fun aClearOfTodayTakesATablesLogOnlyOnceRead() {
        val log = root.file("cangjie.user").apply { write(File("src/test/resources/upgrade/v0.4.0-converted/engine/wubi.user").readBytes()) }
        val pinyin = root.file("pinyin.user").apply { write("x".toByteArray()) }
        Engines(::load, root, warmUps = inPlace).use { engines ->
            engines.learnNi()
            assertTrue(engines.op(LearningOp.ClearRecent(LearningOp.Span.TODAY)) is LearningResult.Cleared)
        }
        assertTrue(log.exists())
        assertFalse(pinyin.exists())
        root.file(Engines.TABLE_LOGS_READ).createNew()
        Engines(::load, root, warmUps = inPlace).use { engines ->
            engines.learnNi()
            assertTrue(engines.op(LearningOp.ClearRecent(LearningOp.Span.TODAY)) is LearningResult.Cleared)
        }
        assertFalse(log.exists())
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
        // an English word and a handwritten pick while off are not learned either (review R11 F2684)
        engines.type(InputMethodCatalog.ENGLISH, "the")
        engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code)
        engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.RESET, 0)
        engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(0, InkReading(listOf("拟"), doubleArrayOf(0.0))))
        engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.PICK, 0)
        engines.onEvent(InputMethodCatalog.HANDWRITING, EngineEvent.RESET, 0)
        // nothing written while off
        assertEquals(logged, root.file(LearnLog.FILE).length())
        engines.op(LearningOp.UseHabits(true))
        engines.afresh()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        assertEquals("拟", engines.texts().first())
        engines.close()
    }

    // review R11 F2728: an act on a candidate of the page shown is on that page's word, not the first page's
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

    // review R11 F2728: a delete on the page shown deletes that page's word, not the first page's
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

    // U-93 off in a table: the user's words none, as installed (review-E-engine M2)
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

    // C25: no backup of this install's still holds what a clear forgot; the one saved after holds what is left
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
        // what the user did stays
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
        // nor anything of a candidate where no input method is in use
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
        // what a table learned is in the record (stream C): its delete is taken back as pinyin's
        val done = engines.learning(InputMethodCatalog.WUBI, LearningOp.Delete(at, "妤")) as LearningResult.Done
        assertTrue(done.undo != LearningResult.NO_UNDO)
        assertEquals(LearningResult.Done(), engines.learning(InputMethodCatalog.WUBI, LearningOp.Undo(done.undo)))
        engines.close()
    }

    // U-91, D19: a search field shows no 联想, and AGAIN brings none
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

    // design M4: on the 14 keys a long press acts on their session, what they show; the report spells their keys
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

    // review M1: a 14 keys' commit deleted at once is the 14 keys' session's to take back (U-03), not learned
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
}
