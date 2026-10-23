/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.replaceDirectory
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * The engine's custom phrases saved from the keyboard: never over what the editor saved since it
 * read them, nor over a file it could not read. And an input method whose data cannot load, and
 * only that: not called again till the engine is reloaded. And the engine's files a user-data
 * import killed midway left aside: put back before it opens them, or left there. And what the
 * engine is handed of the user's dictionaries, and what it hands the addon. And the nine keys'
 * letters the user set, which the engine reads as it is configured and again as it is reloaded,
 * and the 双拼 scheme configured, which the keyboard reads.
 */
class EngineBridgeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val email = PinyinCustomPhrase("yx", 1, "邮箱")
    private val phone = PinyinCustomPhrase("dh", 1, "电话")

    private fun phraseFile() = File(folder.root, "customphrase").also { CustomPhraseManager.save(listOf(email, phone), it) }

    @Test
    fun aPhrasePinnedFromTheKeyboardIsSavedAsTheEngineHasIt() {
        val file = phraseFile()
        val read = CustomPhrases.parse(file.readText())
        EngineBridge.savePhrases(read, read.pinned("yx", "信箱"), file)
        assertEquals(listOf(phone, PinyinCustomPhrase("yx", 1, "信箱"), email), CustomPhraseManager.load(file))
    }

    @Test
    fun whatTheEditorSavedSinceTheEngineReadTheFileIsKept() {
        val file = phraseFile()
        val read = CustomPhrases.parse(file.readText())
        // the editor adds a phrase, and leaves before the engine is reloaded
        val mobile = PinyinCustomPhrase("sj", 1, "手机")
        CustomPhraseManager.saveOver(CustomPhraseManager.load(file), listOf(phone, email, mobile), file)
        val once = read.without("yx", "邮箱")
        EngineBridge.savePhrases(read, once, file)
        assertEquals(listOf(phone, mobile), CustomPhraseManager.load(file))
        EngineBridge.savePhrases(once, once.without("dh", "电话"), file)
        assertEquals(listOf(mobile), CustomPhraseManager.load(file))
    }

    @Test
    fun whatTheEditorDeletedSinceAKeyboardSaveStaysDeleted() {
        val file = phraseFile()
        val queued = ArrayList<Runnable>()
        val saves = EngineBridge.PhraseFile(file, file.readText(), true) { queued += it }
        val pinned = CustomPhrases.parse(file.readText()).pinned("yx", "信箱")
        saves.save(pinned)
        // on the executor, not on the thread that takes the keys
        assertEquals(listOf(phone, email), CustomPhraseManager.load(file))
        queued.removeAt(0).run()
        assertEquals(listOf(phone, PinyinCustomPhrase("yx", 1, "信箱"), email), CustomPhraseManager.load(file))
        // the editor deletes the phrase pinned, and leaves before the engine is reloaded
        CustomPhraseManager.saveOver(CustomPhraseManager.load(file), listOf(phone, email), file)
        saves.save(pinned.without("dh", "电话"))
        queued.removeAt(0).run()
        assertEquals(listOf(email), CustomPhraseManager.load(file))
    }

    @Test
    fun aKeyboardChangeNotSavedIsSavedWithTheNext() {
        val file = phraseFile()
        val saves = EngineBridge.PhraseFile(file, file.readText(), true) { it.run() }
        val pinned = CustomPhrases.parse(file.readText()).pinned("yx", "信箱")
        // where the new file is written first: the save fails
        val blocker = File(file.path + ".new").apply { mkdir() }
        saves.save(pinned)
        assertEquals(listOf(phone, email), CustomPhraseManager.load(file))
        // taken away here, not by the failed save: the next save then depends on the base alone
        blocker.delete()
        saves.save(pinned.without("dh", "电话"))
        assertEquals(listOf(PinyinCustomPhrase("yx", 1, "信箱"), email), CustomPhraseManager.load(file))
    }

    @Test
    fun anInputMethodThatCannotLoadIsNotCalledAgainTillReloaded() {
        val latched = ArrayList<String>()
        val unloadable = EngineBridge.Unloadable { im, _ -> latched += im }
        val broken = { throw FileNotFoundException(Engines.PINYIN_DATA) }
        assertThrows(FileNotFoundException::class.java) { unloadable.call("engine-pinyin", broken) }
        assertTrue("engine-pinyin" in unloadable)
        assertThrows(FileNotFoundException::class.java) { unloadable.call("engine-pinyin", broken) }
        assertEquals(listOf("engine-pinyin"), latched)
        // one that answered once fails for that event alone
        assertEquals(1, unloadable.call("engine-t9") { 1 })
        assertThrows(FileNotFoundException::class.java) { unloadable.call("engine-t9", broken) }
        assertFalse("engine-t9" in unloadable)
        unloadable.clear()
        assertFalse("engine-pinyin" in unloadable)
        assertThrows(FileNotFoundException::class.java) { unloadable.call("engine-pinyin", broken) }
        assertEquals(listOf("engine-pinyin", "engine-pinyin"), latched)
    }

    // [t] thrown by a call of [im]'s engine
    private fun EngineBridge.Unloadable.callThrowing(im: String, t: Throwable) {
        call(im) { throw t }
    }

    @Test
    fun aBugOrMemoryRunOutBeforeTheFirstAnswerIsThatEventsAlone() {
        val latched = ArrayList<String>()
        val unloadable = EngineBridge.Unloadable { im, _ -> latched += im }
        assertThrows(IllegalStateException::class.java) { unloadable.callThrowing(Engines.PINYIN, IllegalStateException("a bug")) }
        assertThrows(IllegalArgumentException::class.java) { unloadable.callThrowing(Engines.PINYIN, IllegalArgumentException("a bug")) }
        assertThrows(OutOfMemoryError::class.java) { unloadable.callThrowing(Engines.PINYIN, OutOfMemoryError()) }
        // the data mapped with no memory left to map it in
        assertThrows(IOException::class.java) { unloadable.callThrowing(Engines.PINYIN, IOException("Map failed", OutOfMemoryError())) }
        assertFalse(Engines.PINYIN in unloadable)
        assertEquals(emptyList<String>(), latched)
        assertEquals(1, unloadable.call(Engines.PINYIN) { 1 })
    }

    // a walk that does not end would hang the test, not fail it
    @Test(timeout = 5_000)
    fun anIOExceptionWhoseCausesLoopBackStillLatches() {
        val latched = ArrayList<String>()
        val unloadable = EngineBridge.Unloadable { im, _ -> latched += im }
        val first = IOException("cannot read")
        first.initCause(IOException("cannot read", first))
        assertThrows(IOException::class.java) { unloadable.callThrowing(Engines.PINYIN, first) }
        assertEquals(listOf(Engines.PINYIN), latched)
    }

    @Test
    fun whatTheEngineThrowsForDataItCannotReadLatchesIt() {
        val latched = ArrayList<String>()
        val unloadable = EngineBridge.Unloadable { im, _ -> latched += im }
        // the first event, as the addon sends it on activate
        fun Engines.reset(im: String) = unloadable.call(im) { onEvent(im, EngineEvent.RESET, 0) }
        // no data in the app
        Engines({ throw FileNotFoundException(it) }, null).use { engines ->
            assertThrows(FileNotFoundException::class.java) { engines.reset(Engines.PINYIN) }
        }
        // data that is not the engine's
        Engines({ ByteBuffer.allocate(0) }, null).use { engines ->
            assertThrows(DataFormatException::class.java) { engines.reset(Engines.T9) }
        }
        assertEquals(listOf(Engines.PINYIN, Engines.T9), latched)
    }

    @Test
    fun whatAnImportKilledBetweenItsRenamesLeftAsideIsPutBack() {
        val dir = File(folder.root, "engine")
        val aside = File(dir.path + ".old")
        File(aside.apply { mkdirs() }, "log").writeText("learned")
        assertEquals(dir, EngineBridge.restored(dir))
        assertEquals("learned", File(dir, "log").readText())
        assertFalse(aside.exists())
    }

    @Test
    fun whatCannotBePutBackIsLeftAsideAndTheNextImportDoesNotDeleteIt() {
        val dir = File(folder.root, "engine")
        val aside = File(dir.path + ".old")
        File(aside.apply { mkdirs() }, "log").writeText("learned")
        // a directory is not renamed over a file: here a link to nothing, so engine/ is not there
        assumeTrue(runCatching { Files.createSymbolicLink(dir.toPath(), File(folder.root, "none").toPath()) }.isSuccess)
        assertNull(EngineBridge.restored(dir))
        assertThrows(IOException::class.java) { replaceDirectory(dir, File(dir.path + ".import")) {} }
        assertEquals("learned", File(aside, "log").readText())
    }

    @Test
    fun customPhrasesThatCannotBeReadAreNotSavedOver() {
        val file = phraseFile()
        file.setReadable(false)
        // as root, every file reads
        assumeFalse(file.canRead())
        val added = EngineBridge.additions(folder.root)
        file.setReadable(true)
        assertEquals("", added.phrases)
        assertThrows(IOException::class.java) { added.savePhrases(CustomPhrases.parse("").pinned("yx", "信箱")) }
        assertEquals(listOf(phone, email), CustomPhraseManager.load(file))
    }

    // one an older build merged into the new words is merged into the base, its list gone; and 0.3.1's
    // texts are never read here (the fcitx thread), but converted and compiled once off it, the
    // engine told of the file then
    @Test
    fun theDictionariesAreCompiledOffTheEnginesThreadNotReadByIt() {
        val dictionaries = File(folder.root, "dictionaries").apply { mkdirs() }
        File(dictionaries, "mine.txt").writeText("你好 ni'hao 0.0\n")
        File(dictionaries, "theirs.txt").writeText("搭子 da'zi 0.0\n")
        val list = File(dictionaries, ImportedDictionaries.INTO_NEW).apply { writeText("mine\n") }
        MergedDictionary.reset()
        var made = 0
        MergedDictionary.made = { made++ }
        MergedDictionary.base = { null }
        try {
            val added = EngineBridge.additions(folder.root)
            assertFalse(list.exists())
            assertNull(added.dictionary())
            MergedDictionary.refreshNow(dictionaries)
            val merged = MergedDictionary.current(dictionaries).file!!
            assertEquals(2, merged.size)
            assertTrue(merged.hasText("搭子"))
            assertTrue(made > 0)
            assertEquals(mapOf("mine" to 1, "theirs" to 1), MergedDictionary.sourceWords(dictionaries))
            // the engine is told of another file, as it reads them again for it
            assertNotEquals(added.dictionaries, EngineBridge.additions(folder.root).dictionaries)
            // kept gzip'd, the texts gone
            assertEquals(listOf("mine.txt.gz", "theirs.txt.gz"), dictionaries.list()!!.filter { it.endsWith(".gz") }.sorted())
            assertFalse(File(dictionaries, "mine.txt").exists())
            // a change of a dictionary is compiled again: a text of its name (a 0.3.2 backup restored) wins
            File(dictionaries, "theirs.txt").writeText("搭子 da'zi 0.0\n妮 ni 0.0\n")
            MergedDictionary.refreshNow(dictionaries)
            assertEquals(3, MergedDictionary.current(dictionaries).file!!.size)
            // none on: no file
            File(dictionaries, "mine.txt.gz").delete()
            File(dictionaries, "theirs.txt.gz").delete()
            MergedDictionary.refreshNow(dictionaries)
            assertNull(MergedDictionary.current(dictionaries).file)
            assertFalse(File(dictionaries, MergedDictionary.FILE).exists())
        } finally {
            MergedDictionary.refreshNow(dictionaries)
            MergedDictionary.reset()
            MergedDictionary.made = {}
        }
    }

    @Test
    fun theAddonIsSentThePageShownAndAtLeastAChunk() {
        fun shown(first: Int, count: Int, handled: Boolean = true) = Snapshot(
            commit = "", preedit = "n", candidates = List(count) { "$it" }, page = 0,
            hasPreviousPage = first > 0, hasNextPage = true, handled = handled, predicting = false, first = first,
        )
        assertEquals(EngineBridge.CHUNK, EngineBridge.candidatesSent(EngineEvent.CHAR, shown(0, 5)))
        // from the page's first: those before it are not sent again
        assertEquals(EngineBridge.CHUNK, EngineBridge.candidatesSent(EngineEvent.PAGE_DOWN, shown(40, 5)))
        val longPage = EngineBridge.CHUNK + 8
        assertEquals(longPage, EngineBridge.candidatesSent(EngineEvent.PAGE_DOWN, shown(40, longPage)))
        assertEquals(EngineBridge.CHUNK, EngineBridge.candidatesSent(EngineEvent.REFINE, shown(0, 5)))
        // nothing to show, or a slice of refining that changed nothing: the addon shows none
        assertEquals(0, EngineBridge.candidatesSent(EngineEvent.CHAR, shown(0, 0)))
        assertEquals(0, EngineBridge.candidatesSent(EngineEvent.REFINE, shown(0, 5, handled = false)))
    }

    @Test
    fun theCandidatesSentRunFromThePagesFirstAsTheAddonNumbersThem() {
        val s = Snapshot(
            commit = "", preedit = "n", candidates = listOf("40", "41"), page = 8, hasPreviousPage = true,
            hasNextPage = true, handled = true, predicting = false, total = 50, first = 40,
        )
        val asked = ArrayList<Pair<Int, Int>>()
        val sent = EngineBridge.result(EngineEvent.PAGE_DOWN, s) { from, count ->
            asked += from to count
            (from until minOf(from + count, s.total)).map { Choice("$it") }
        }
        assertEquals(listOf(40 to EngineBridge.CHUNK), asked)
        // EngineCandidateList's offset_: the first sent is the page's first, index 40 of all
        assertEquals(40, sent.first)
        assertEquals(listOf("40", "41"), sent.candidates.take(2))
        assertEquals(10, sent.candidates.size)
        assertEquals(2, sent.shown)
    }

    @Test
    fun theCursorGoesToTheAddonInCodePointsNotUtf16Units() {
        fun cursor(preedit: String, at: Int) = EngineBridge.result(
            EngineEvent.CHAR,
            Snapshot(
                commit = "", preedit = preedit, candidates = emptyList(), page = 0, hasPreviousPage = false,
                hasNextPage = false, handled = true, predicting = false, cursor = at,
            ),
        ) { _, _ -> emptyList() }.cursor
        // 𠀀 (U+20000) is two UTF-16 units, one code point to the addon
        assertEquals(2, cursor("𠀀ab", 3))
        assertEquals(3, cursor("😀😀c", 5))
        // none past the BMP: as it was
        assertEquals(1, cursor("ab", 1))
        // no cursor stays none; past the end, at the end
        assertEquals(-1, cursor("𠀀a", -1))
        assertEquals(2, cursor("𠀀a", 10))
    }

    @Test
    fun aRefineReadsAsRefiningWhileEachOfItsSlicesRuns() {
        fun refines(refines: Boolean) = Snapshot(
            commit = "", preedit = "n", candidates = listOf("你"), page = 0, hasPreviousPage = false,
            hasNextPage = false, handled = true, predicting = false, refines = refines,
        )
        val fetch = { _: Int, _: Int -> listOf(Choice("你")) }
        val during = ArrayList<Boolean>()
        // a key, then the slices the addon sends for it, the last with nothing left to refine
        EngineBridge.answer(Engines.PINYIN, EngineEvent.CHAR, { refines(true) }, fetch)
        assertTrue(EngineBridge.refining)
        EngineBridge.answer(Engines.PINYIN, EngineEvent.REFINE, { during += EngineBridge.refining; refines(true) }, fetch)
        EngineBridge.answer(Engines.PINYIN, EngineEvent.REFINE, { during += EngineBridge.refining; refines(false) }, fetch)
        // as EngineEvalRunner reads it off the fcitx thread while a slice runs
        assertEquals(listOf(true, true), during)
        assertFalse(EngineBridge.refining)
        // the addon sends no refine after a failure
        EngineBridge.answer(Engines.PINYIN, EngineEvent.CHAR, { refines(true) }, fetch)
        assertThrows(IllegalStateException::class.java) {
            EngineBridge.answer(Engines.PINYIN, EngineEvent.REFINE, { error("a bug") }, fetch)
        }
        assertFalse(EngineBridge.refining)
    }

    @Test
    fun eachOfferIsTheBitTheAddonReadsForIt() {
        // androidengine_public.h's EngineOffer
        assertEquals(1, EngineBridge.offerBits(setOf(Offer.FORGET)))
        assertEquals(2, EngineBridge.offerBits(setOf(Offer.PIN)))
        assertEquals(4, EngineBridge.offerBits(setOf(Offer.UNPIN)))
        assertEquals(8, EngineBridge.offerBits(setOf(Offer.BLOCK)))
        assertEquals(2 or 8, EngineBridge.offerBits(setOf(Offer.PIN, Offer.BLOCK)))
    }

    // the app for its files directory
    @RunWith(RobolectricTestRunner::class)
    @Config(application = FcitxApplication::class)
    class Made {
        /** Taken, it says so: an Ink key goes out for what is shown. */
        @Test
        fun handwritingThatLoadsTakesTheInk() {
            EngineBridge.unloadable.clear()
            var calls = 0
            try {
                assertTrue(EngineBridge.ink(Engines.HANDWRITING) { calls++ })
                assertEquals(1, calls)
                assertFalse(Engines.HANDWRITING in EngineBridge.unloadable)
            } finally {
                EngineBridge.unloadable.clear()
            }
        }

        /** A piece finished as the pad leaves for English: the engine is told, but no Ink key goes into keyboard-us. */
        @Test
        fun inkUnderAnotherInputMethodReachesTheEngineButIsNotTaken() {
            EngineBridge.unloadable.clear()
            var calls = 0
            try {
                assertFalse(EngineBridge.ink(Engines.ENGLISH) { calls++ })
                assertEquals(1, calls)
                assertFalse(Engines.HANDWRITING in EngineBridge.unloadable)
            } finally {
                EngineBridge.unloadable.clear()
            }
        }

        @Test
        fun handwritingWhosePinyinDataCannotLoadIsTriedOnceThenSkippedThrowingNothing() {
            EngineBridge.unloadable.clear()
            var calls = 0
            try {
                // each a character written: offerInk, inkFinished; none taken, so no Ink key is sent for it
                repeat(3) {
                    assertFalse(
                        EngineBridge.ink(Engines.HANDWRITING) {
                            calls++
                            throw FileNotFoundException("pinyin")
                        }
                    )
                }
                assertEquals(1, calls)
                assertTrue(Engines.HANDWRITING in EngineBridge.unloadable)
            } finally {
                EngineBridge.unloadable.clear()
            }
        }

        @Test
        fun whatAnImportKilledBetweenItsRenamesLeftAsideIsPutBackBeforeTheEngineOpensIt() {
            // made here, whichever test of this sandbox made it before
            EngineBridge.close()
            val dir = File(appContext.filesDir, "engine")
            val aside = File(dir.path + ".old")
            File(aside.apply { mkdirs() }, "log").writeText("learned")
            assertNotNull(EngineBridge.engines)
            assertEquals("learned", File(dir, "log").readText())
            assertFalse(aside.exists())
            EngineBridge.close()
        }

        @Test
        fun aClosedEngineIsMadeAnewAtItsNextUse() {
            val closed = EngineBridge.engines
            EngineBridge.close()
            assertNotSame(closed, EngineBridge.engines)
            EngineBridge.close()
        }

        @Test
        fun anInputMethodThatCouldNotLoadIsTriedAgainOnTheEngineMadeAnew() {
            val broken = { throw FileNotFoundException("mine") }
            assertThrows(FileNotFoundException::class.java) { EngineBridge.unloadable.call("mine", broken) }
            assertTrue("mine" in EngineBridge.unloadable)
            EngineBridge.close()
            assertFalse("mine" in EngineBridge.unloadable)
        }

        // the keyboard draws the user's letters: the engine must read the keys by them
        @Test
        fun theEngineTakesTheNineKeysLettersOnConfigureAndAgainOnReload() {
            val pref = AppPrefs.getInstance().keyboard.t9Letters
            val moved = NineKeyLetters.of(listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyzl"))
            val again = NineKeyLetters.of(listOf("abc", "def", "gi", "jkl", "mnoh", "pqrs", "tuv", "wxyz"))
            try {
                pref.setValue(moved.serialize())
                EngineBridge.configure("")
                assertEquals(moved, EngineBridge.engines.settings.nineKeys)
                // changed on the settings page, then fcitx reloads the engine, its config as it was
                pref.setValue(again.serialize())
                EngineBridge.reload()
                assertEquals(again, EngineBridge.engines.settings.nineKeys)
            } finally {
                pref.setValue("")
                EngineBridge.close()
            }
        }

        // the keyboard reads it to tell 微软双拼's ; from text (CommonKeyActionListener.typedAsIs)
        @Test
        fun theShuangpinSchemeIsTheOneConfigured() {
            try {
                EngineBridge.configure("ShuangpinProfile=MS")
                assertEquals(ShuangpinScheme.MICROSOFT, EngineBridge.shuangpinScheme)
                // the default again once the config no longer names one, not the last set
                EngineBridge.configure("")
                assertEquals(ShuangpinScheme.ZIRANMA, EngineBridge.shuangpinScheme)
            } finally {
                EngineBridge.close()
            }
        }

        // the keyboard waits on it (CommonKeyActionListener.typeAsIsOrSend): no table has a session yet, nor is the engine made to answer
        @Test
        fun noTableHasKeysWhileTheEngineIsNotMade() {
            EngineBridge.close()
            val dir = File(appContext.filesDir, "engine").apply { deleteRecursively() }
            val aside = File(dir.path + ".old")
            File(aside.apply { mkdirs() }, "log").writeText("learned")
            try {
                assertEquals(emptySet<Char>(), EngineBridge.tableKeys("engine-erbi"))
                // made, it would have put engine.old back
                assertTrue(aside.exists())
                assertFalse(dir.exists())
            } finally {
                // made after all, it is not left to the tests after
                EngineBridge.close()
                dir.deleteRecursively()
                aside.deleteRecursively()
            }
        }
    }

    @Test
    fun whatAnActOnLearningShowsAnew() {
        val pinyin = "engine-pinyin"
        val done = LearningResult.Done()
        // what changed what the list shows: shown anew, on a page too
        for (op in listOf(LearningOp.Undo(1), LearningOp.UseHabits(false), LearningOp.ClearLearned(true),
            LearningOp.NoPredict(0, "你"), LearningOp.OnPage(LearningOp.NoPredict(0, "你")), LearningOp.RemoveWords(emptyList()),
            // a restore or 退回恢复前 changes what was learned (F2962)
            BackupOp.Restore(ByteArray(0)), BackupOp.RestoreKept("a"), BackupOp.Revert)) {
            assertTrue(op.toString(), EngineBridge.shownAnew(op, pinyin, done))
        }
        // what only reads, or the addon shows itself
        for (op in listOf(LearningOp.Overview, LearningOp.Menu(0, "你"), LearningOp.Delete(0, "你"), LearningOp.ResetTableOrder("wbx"),
            BackupOp.Kept, BackupOp.Copy, BackupOp.Peek(ByteArray(0)), BackupOp.Delete("a"), BackupOp.SetAuto(true))) {
            assertFalse(op.toString(), EngineBridge.shownAnew(op, pinyin, done))
        }
        // nothing done, or an input method not the engine's: nothing shown anew
        assertFalse(EngineBridge.shownAnew(LearningOp.Undo(1), pinyin, LearningResult.Unavailable))
        assertFalse(EngineBridge.shownAnew(LearningOp.Undo(1), "rime", done))
        assertTrue(EngineBridge.shownAnew(LearningOp.Undo(1), Engines.ENGLISH, done))
    }

    @Test
    fun recentsAreClearedFromTheMinuteLearningWas() {
        assertEquals(0L, EngineBridge.recentsSince(LearningResult.ALL))
        assertEquals(30_000_000L * 60_000L, EngineBridge.recentsSince(30_000_000))
    }
}
