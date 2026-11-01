/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.replaceDirectory
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.host.EngineOptions
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    private val bridge = EngineBridge()

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
        val broken = { throw FileNotFoundException(InputMethodCatalog.PINYIN_DATA) }
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
        assertThrows(IllegalStateException::class.java) { unloadable.callThrowing(InputMethodCatalog.PINYIN, IllegalStateException("a bug")) }
        assertThrows(IllegalArgumentException::class.java) { unloadable.callThrowing(InputMethodCatalog.PINYIN, IllegalArgumentException("a bug")) }
        assertThrows(OutOfMemoryError::class.java) { unloadable.callThrowing(InputMethodCatalog.PINYIN, OutOfMemoryError()) }
        // the data mapped with no memory left to map it in
        assertThrows(IOException::class.java) { unloadable.callThrowing(InputMethodCatalog.PINYIN, IOException("Map failed", OutOfMemoryError())) }
        assertFalse(InputMethodCatalog.PINYIN in unloadable)
        assertEquals(emptyList<String>(), latched)
        assertEquals(1, unloadable.call(InputMethodCatalog.PINYIN) { 1 })
    }

    // a walk that does not end would hang the test, not fail it
    @Test(timeout = 5_000)
    fun anIOExceptionWhoseCausesLoopBackStillLatches() {
        val latched = ArrayList<String>()
        val unloadable = EngineBridge.Unloadable { im, _ -> latched += im }
        val first = IOException("cannot read")
        first.initCause(IOException("cannot read", first))
        assertThrows(IOException::class.java) { unloadable.callThrowing(InputMethodCatalog.PINYIN, first) }
        assertEquals(listOf(InputMethodCatalog.PINYIN), latched)
    }

    @Test
    fun whatTheEngineThrowsForDataItCannotReadLatchesIt() {
        val latched = ArrayList<String>()
        val unloadable = EngineBridge.Unloadable { im, _ -> latched += im }
        // the first event, as the addon sends it on activate
        fun Engines.reset(im: String) = unloadable.call(im) { onEvent(im, EngineEvent.RESET, 0) }
        // no data in the app
        Engines({ throw FileNotFoundException(it) }, null).use { engines ->
            assertThrows(FileNotFoundException::class.java) { engines.reset(InputMethodCatalog.PINYIN) }
        }
        // data that is not the engine's
        Engines({ ByteBuffer.allocate(0) }, null).use { engines ->
            assertThrows(DataFormatException::class.java) { engines.reset(InputMethodCatalog.SHUANGPIN) }
        }
        assertEquals(listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN), latched)
    }

    @Test
    fun whatAnImportKilledBetweenItsRenamesLeftAsideIsPutBack() {
        val dir = File(folder.root, "engine")
        val aside = File(dir.path + ".old")
        File(aside.apply { mkdirs() }, "log").writeText("learned")
        assertEquals(dir, bridge.restored(dir))
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
        assertNull(bridge.restored(dir))
        assertThrows(IOException::class.java) { replaceDirectory(dir, File(dir.path + ".import")) {} }
        assertEquals("learned", File(aside, "log").readText())
    }

    // a phrase file not read: the keyboard's pin is refused, with why, and the file left as it was
    // (its phrases would be lost to the pin)
    @Test
    @Uat("PR-42")
    fun theKeyboardsPinIsRefusedWhereTheFileWouldBeLost() {
        // as root, every file reads: checked once, a skip (F4987), not a test that passes having checked nothing
        val probe = File(folder.root, "probe").apply { writeText("") }
        assumeTrue("an unreadable file (not as root)", probe.setReadable(false) && !probe.canRead())
        probe.delete()
        val file = phraseFile()
        file.setReadable(false)
        var told = 0
        val added = bridge.additions(folder.root, unreadable = { told++ })
        file.setReadable(true)
        assertEquals(1, told)
        assertEquals("", added.phrases)
        val refused = assertThrows(IOException::class.java) { added.savePhrases(CustomPhrases.parse("").pinned("yx", "信箱")) }
        assertEquals("custom phrases were not read", refused.message)
        assertEquals(listOf(phone, email), CustomPhraseManager.load(file))
    }

    // data not settled (external storage away on an upgraded phone): the pin is refused and nothing is
    // written that the next settle would not look for, the dictionaries neither converted nor compiled.
    // Changes no permission bits: runs as root too (F5004)
    @Test
    @Uat("PR-42")
    fun theKeyboardsPinIsRefusedWhereDataIsNotSettled() {
        val file = phraseFile()
        val before = file.readText()
        val dictionaries = File(folder.root, "dictionaries").apply { mkdirs() }
        File(dictionaries, "mine.txt").writeText("你好 ni'hao 0.0\n")
        val list = File(dictionaries, ImportedDictionaries.INTO_NEW).apply { writeText("mine\n") }
        MergedDictionary.reset()
        var made = 0
        MergedDictionary.made = { made++ }
        MergedDictionary.base = { null }
        try {
            val added = bridge.additions(folder.root, writable = false)
            assertEquals(before, added.phrases)
            val refused = assertThrows(IOException::class.java) { added.savePhrases(CustomPhrases.parse(added.phrases).pinned("yx", "信箱")) }
            assertEquals("where fcitx's data is, is not settled", refused.message)
            assertEquals(before, file.readText())
            // 0.3.1's text and the older build's list as they were, nothing compiled (the text still
            // owes its words, so it stays pending for the start that settles the data)
            assertTrue(list.exists())
            assertTrue(File(dictionaries, "mine.txt").exists())
            assertFalse(File(dictionaries, "mine.txt.gz").exists())
            assertFalse(File(dictionaries, MergedDictionary.FILE).exists())
            assertEquals(0, made)
        } finally {
            MergedDictionary.reset()
            MergedDictionary.made = {}
        }
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
            val added = bridge.additions(folder.root)
            assertFalse(list.exists())
            assertNull(added.dictionary())
            MergedDictionary.refreshNow(dictionaries)
            val merged = MergedDictionary.current(dictionaries).file!!
            assertEquals(2, merged.size)
            assertTrue(merged.hasText("搭子"))
            assertTrue(made > 0)
            assertEquals(mapOf("mine" to 1, "theirs" to 1), MergedDictionary.sourceWords(dictionaries))
            // the engine is told of another file, as it reads them again for it
            assertNotEquals(added.dictionaries, bridge.additions(folder.root).dictionaries)
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
    fun aRefineReadsAsRefiningWhileEachOfItsSlicesRuns() {
        fun refines(refines: Boolean) = Snapshot(
            commit = "", preedit = "n", candidates = listOf("你"), page = 0, hasPreviousPage = false,
            hasNextPage = false, handled = true, predicting = false, refines = refines,
        )
        val during = ArrayList<Boolean>()
        // a key, then the slices the host sends for it, the last with nothing left to refine
        bridge.answer(InputMethodCatalog.PINYIN, EngineEvent.CHAR) { refines(true) }
        assertTrue(EngineState.refining)
        bridge.answer(InputMethodCatalog.PINYIN, EngineEvent.REFINE) { during += EngineState.refining; refines(true) }
        bridge.answer(InputMethodCatalog.PINYIN, EngineEvent.REFINE) { during += EngineState.refining; refines(false) }
        // as EngineEvalRunner reads it off the host thread while a slice runs
        assertEquals(listOf(true, true), during)
        assertFalse(EngineState.refining)
        // the host sends no refine after a failure, and the failure is thrown on for its router
        bridge.answer(InputMethodCatalog.PINYIN, EngineEvent.CHAR) { refines(true) }
        EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.PINYIN, true, listOf("ni"), 1)
        assertThrows(IllegalStateException::class.java) {
            bridge.answer(InputMethodCatalog.PINYIN, EngineEvent.REFINE) { error("a bug") }
        }
        assertFalse(EngineState.refining)
        // nor syllables to take after it: a tap would send an id the engine drops
        assertEquals(EngineState.SyllableState(InputMethodCatalog.PINYIN), EngineState.syllables.value)
        EngineState.syllablesState.value = EngineState.SyllableState()
    }

    // written on each answer, and a hand-back writes the empty state once
    @Test
    fun eachInputMethodsAnswerTellsItsSyllablesAndAHandBackClearsThem() {
        fun typed(preedit: String, syllables: List<String>, id: Int = 0, handled: Boolean = true) = Snapshot(
            commit = "", preedit = preedit, candidates = emptyList(), page = 0, hasPreviousPage = false,
            hasNextPage = false, handled = handled, predicting = false, syllables = syllables, syllablesId = id,
        )
        try {
            // 双拼's
            bridge.answer(InputMethodCatalog.SHUANGPIN, EngineEvent.CHAR) { typed("ni", listOf("ni", "mi"), 1) }
            assertEquals(EngineState.SyllableState(InputMethodCatalog.SHUANGPIN, true, listOf("ni", "mi"), 1), EngineState.syllables.value)
            // the 14 or nine keys, which are pinyin's: no gate on which keys typed
            bridge.answer(InputMethodCatalog.PINYIN, EngineEvent.CHAR) { typed("bn", listOf("bu", "nu"), 2) }
            assertEquals(EngineState.SyllableState(InputMethodCatalog.PINYIN, true, listOf("bu", "nu"), 2), EngineState.syllables.value)
            // a refine that changed nothing keeps them
            bridge.answer(InputMethodCatalog.PINYIN, EngineEvent.REFINE) { typed("", emptyList(), handled = false) }
            assertEquals(listOf("bu", "nu"), EngineState.syllables.value.syllables)
            // one that did, tells
            bridge.answer(InputMethodCatalog.PINYIN, EngineEvent.REFINE) { typed("bn", listOf("nu"), 3) }
            assertEquals(EngineState.SyllableState(InputMethodCatalog.PINYIN, true, listOf("nu"), 3), EngineState.syllables.value)
            // handed back to 全拼, which offers none: cleared
            bridge.answer(InputMethodCatalog.PINYIN, EngineEvent.CHAR) { typed("n", emptyList()) }
            assertEquals(EngineState.SyllableState(InputMethodCatalog.PINYIN, true), EngineState.syllables.value)
            // another input method after: its own, empty
            bridge.answer(InputMethodCatalog.WUBI, EngineEvent.RESET) { typed("", emptyList()) }
            assertEquals(EngineState.SyllableState(InputMethodCatalog.WUBI), EngineState.syllables.value)
        } finally {
            EngineState.syllablesState.value = EngineState.SyllableState()
        }
    }

    // the app for its files directory
    @RunWith(RobolectricTestRunner::class)
    @Config(application = FcitxApplication::class)
    class Made {

        private val bridge = EngineBridge()
        /** Taken, it says so: an Ink key goes out for what is shown. */
        @Test
        fun handwritingThatLoadsTakesTheInk() {
            bridge.unloadable.clear()
            var calls = 0
            try {
                assertTrue(bridge.handwriting(InputMethodCatalog.HANDWRITING) { calls++ })
                assertEquals(1, calls)
                assertFalse(InputMethodCatalog.HANDWRITING in bridge.unloadable)
            } finally {
                bridge.unloadable.clear()
            }
        }

        /** A piece finished as the pad leaves for English: the engine is told, but no Ink key goes into keyboard-us. */
        @Test
        fun inkUnderAnotherInputMethodReachesTheEngineButIsNotTaken() {
            bridge.unloadable.clear()
            var calls = 0
            try {
                assertFalse(bridge.handwriting(InputMethodCatalog.ENGLISH) { calls++ })
                assertEquals(1, calls)
                assertFalse(InputMethodCatalog.HANDWRITING in bridge.unloadable)
            } finally {
                bridge.unloadable.clear()
            }
        }

        // Robolectric: a latch is said on the keyboard (EngineNotices)
        @Test
        fun anInputMethodThatCannotLoadAnswersNothingAndItsSyllablesGo() {
            bridge.unloadable.clear()
            try {
                assertThrows(FileNotFoundException::class.java) { bridge.unloadable.call("mine") { throw FileNotFoundException("mine") } }
                EngineState.syllablesState.value = EngineState.SyllableState("mine", true, listOf("ni", "mi"), 4)
                assertEquals(null, bridge.answer("mine", EngineEvent.CHAR) { error("not called") })
                assertEquals(EngineState.SyllableState("mine"), EngineState.syllables.value)
            } finally {
                bridge.unloadable.clear()
                EngineState.syllablesState.value = EngineState.SyllableState()
            }
        }

        @Test
        fun handwritingWhosePinyinDataCannotLoadIsTriedOnceThenSkippedThrowingNothing() {
            bridge.unloadable.clear()
            var calls = 0
            try {
                // each a character written: offerInk, inkFinished; none taken, so no Ink key is sent for it
                repeat(3) {
                    assertFalse(
                        bridge.handwriting(InputMethodCatalog.HANDWRITING) {
                            calls++
                            throw FileNotFoundException("pinyin")
                        }
                    )
                }
                assertEquals(1, calls)
                assertTrue(InputMethodCatalog.HANDWRITING in bridge.unloadable)
            } finally {
                bridge.unloadable.clear()
            }
        }

        @Test
        fun whatAnImportKilledBetweenItsRenamesLeftAsideIsPutBackBeforeTheEngineOpensIt() {
            val dir = File(appContext.filesDir, "engine")
            val aside = File(dir.path + ".old")
            File(aside.apply { mkdirs() }, "log").writeText("learned")
            assertNotNull(bridge.engines)
            assertEquals("learned", File(dir, "log").readText())
            assertFalse(aside.exists())
            bridge.close()
        }

        // a view made before the next engine speaks would show this one's syllables or word; the
        // config's (the scheme, suggesting) stays, read again at the next start (F4908: close dropped them too)
        @Test
        fun closeLeavesNoSessionStateButKeepsTheConfigs() {
            val scheme = EngineState.shuangpinScheme
            val suggests = EngineState.englishSuggests
            try {
                EngineState.refining = true
                EngineState.syllablesState.value = EngineState.SyllableState(im = "pinyin", composing = true, syllables = listOf("ni"), id = 3)
                EngineState.englishTypingState.value = true
                EngineState.inkEnded.value = 7
                EngineState.shuangpinScheme = if (scheme === ShuangpinScheme.XIAOHE) ShuangpinScheme.ZIRANMA else ShuangpinScheme.XIAOHE
                EngineState.englishSuggests = !suggests
                bridge.close()
                assertFalse(EngineState.refining)
                assertEquals(EngineState.SyllableState(), EngineState.syllables.value)
                assertFalse(EngineState.englishTyping.value)
                assertEquals(HandwritingSession.NO_PIECE, EngineState.handwritingEnded.value)
                assertNotEquals(scheme, EngineState.shuangpinScheme)
                assertEquals(!suggests, EngineState.englishSuggests)
            } finally {
                EngineState.shuangpinScheme = scheme
                EngineState.englishSuggests = suggests
            }
        }

        @Test
        fun aClosedEngineIsMadeAnewAtItsNextUse() {
            val closed = bridge.engines
            bridge.close()
            assertNotSame(closed, bridge.engines)
            bridge.close()
        }

        @Test
        fun anInputMethodThatCouldNotLoadIsTriedAgainOnTheEngineMadeAnew() {
            val broken = { throw FileNotFoundException("mine") }
            assertThrows(FileNotFoundException::class.java) { bridge.unloadable.call("mine", broken) }
            assertTrue("mine" in bridge.unloadable)
            bridge.close()
            assertFalse("mine" in bridge.unloadable)
        }

        // UAT decision 6 (dev/design/f1-uat-classification.md): KotlinHostTest holds a first run's host to
        // pushing EngineOptions().toSettings(); this is the bridge's half: those settings, with the AppPrefs
        // the engine also reads (the nine keys' letters) left at their defaults, reach the engine unchanged
        @Test
        fun aNewInstallsEngineSettingsAreTheHostOptionsDefaults() {
            val defaults = EngineOptions().toSettings()
            try {
                bridge.configure(defaults)
                assertEquals(defaults, bridge.engines.settings)
            } finally {
                bridge.close()
            }
        }

        // the keyboard draws the user's letters: the engine must read the keys by them
        @Test
        fun theEngineTakesTheNineKeysLettersOnConfigureAndAgainOnReload() {
            val pref = AppPrefs.getInstance().keyboard.t9Letters
            val moved = NineKeyLetters.of(listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyzl"))
            val again = NineKeyLetters.of(listOf("abc", "def", "gi", "jkl", "mnoh", "pqrs", "tuv", "wxyz"))
            try {
                pref.setValue(moved.serialize())
                bridge.configure(EngineSettings())
                assertEquals(moved, bridge.engines.settings.nineKeys)
                // changed on the settings page, then fcitx reloads the engine, its config as it was
                pref.setValue(again.serialize())
                bridge.reload()
                assertEquals(again, bridge.engines.settings.nineKeys)
            } finally {
                pref.setValue("")
                bridge.close()
            }
        }

        // the keyboard reads it to tell 微软双拼's ; from text (CommonKeyActionListener.typedAsIs)
        @Test
        fun theShuangpinSchemeIsTheOneConfigured() {
            try {
                bridge.configure(EngineSettings(shuangpin = "MS"))
                assertEquals(ShuangpinScheme.MICROSOFT, EngineState.shuangpinScheme)
                // the default again once the config no longer names one, not the last set
                bridge.configure(EngineSettings())
                assertEquals(ShuangpinScheme.ZIRANMA, EngineState.shuangpinScheme)
            } finally {
                bridge.close()
            }
        }

        // the keyboard waits on it (CommonKeyActionListener.typeAsIsOrSend): no table has a session yet, nor is the engine made to answer
        @Test
        fun noTableHasKeysWhileTheEngineIsNotMade() {
            bridge.close()
            val dir = File(appContext.filesDir, "engine").apply { deleteRecursively() }
            val aside = File(dir.path + ".old")
            File(aside.apply { mkdirs() }, "log").writeText("learned")
            try {
                assertEquals(emptySet<Char>(), bridge.tableKeys("engine-erbi"))
                // made, it would have put engine.old back
                assertTrue(aside.exists())
                assertFalse(dir.exists())
            } finally {
                // made after all, it is not left to the tests after
                bridge.close()
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
            assertTrue(op.toString(), bridge.shownAnew(op, pinyin, done))
        }
        // what only reads, or the addon shows itself
        for (op in listOf(LearningOp.Overview, LearningOp.Menu(0, "你"), LearningOp.Delete(0, "你"), LearningOp.ResetTableOrder("wbx"),
            BackupOp.Kept, BackupOp.Copy, BackupOp.Peek(ByteArray(0)), BackupOp.Delete("a"), BackupOp.SetAuto(true))) {
            assertFalse(op.toString(), bridge.shownAnew(op, pinyin, done))
        }
        // nothing done, or an input method not the engine's: nothing shown anew
        assertFalse(bridge.shownAnew(LearningOp.Undo(1), pinyin, LearningResult.Unavailable))
        assertFalse(bridge.shownAnew(LearningOp.Undo(1), "rime", done))
        assertTrue(bridge.shownAnew(LearningOp.Undo(1), InputMethodCatalog.ENGLISH, done))
    }

    @Test
    fun recentsAreClearedFromTheMinuteLearningWas() {
        assertEquals(0L, bridge.recentsSince(LearningResult.ALL))
        assertEquals(30_000_000L * 60_000L, bridge.recentsSince(30_000_000))
    }
}
