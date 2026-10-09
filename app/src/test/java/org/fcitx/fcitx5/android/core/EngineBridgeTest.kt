/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.UnreadableInputMethod
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

/**
 * The engine's custom phrases saved from the keyboard: never over what the editor saved since it
 * read them. And an input method whose data cannot load, and only that: not called again till the
 * engine is reloaded. And the engine's files a user-data import killed midway left aside: put back
 * before it opens them.
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
        File(file.path + ".new").mkdir()
        saves.save(pinned)
        assertEquals(listOf(phone, email), CustomPhraseManager.load(file))
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
        // worded as an UnreadableInputMethod, not one: the type latches, not the words
        val worded = IllegalArgumentException("cannot read input method ${Engines.PINYIN}")
        assertThrows(IllegalArgumentException::class.java) { unloadable.callThrowing(Engines.PINYIN, worded) }
        assertThrows(OutOfMemoryError::class.java) { unloadable.callThrowing(Engines.PINYIN, OutOfMemoryError()) }
        // the data mapped with no memory left to map it in
        assertThrows(IOException::class.java) { unloadable.callThrowing(Engines.PINYIN, IOException("Map failed", OutOfMemoryError())) }
        // the same for a table the user added, wrapped as the engine throws for one it cannot read
        val added = UnreadableInputMethod("mine", IOException("Map failed", OutOfMemoryError()))
        assertThrows(UnreadableInputMethod::class.java) { unloadable.callThrowing("mine", added) }
        assertFalse(Engines.PINYIN in unloadable)
        assertFalse("mine" in unloadable)
        assertEquals(emptyList<String>(), latched)
        assertEquals(1, unloadable.call(Engines.PINYIN) { 1 })
    }

    @Test
    fun causesThatLoopBackAreEachReadOnce() {
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
        // no data in the app, and a table the user imported gone
        Engines({ throw FileNotFoundException(it) }, null).use { engines ->
            assertThrows(FileNotFoundException::class.java) { engines.reset(Engines.PINYIN) }
            assertThrows(UnreadableInputMethod::class.java) { engines.reset("mine") }
        }
        // data that is not the engine's
        Engines({ ByteBuffer.allocate(0) }, null).use { engines ->
            assertThrows(DataFormatException::class.java) { engines.reset(Engines.T9) }
        }
        assertEquals(listOf(Engines.PINYIN, "mine", Engines.T9), latched)
    }

    // the app for its files directory; the engine, once made, stays made for the rest of the run
    @RunWith(RobolectricTestRunner::class)
    @Config(application = FcitxApplication::class)
    class Made {
        @Test
        fun whatAnImportKilledBetweenItsRenamesLeftAsideIsPutBackBeforeTheEngineOpensIt() {
            val dir = File(appContext.filesDir, "engine")
            val aside = File(dir.path + ".old")
            File(aside.apply { mkdirs() }, "log").writeText("learned")
            assertNotNull(EngineBridge.engines)
            assertEquals("learned", File(dir, "log").readText())
            assertFalse(aside.exists())
        }
    }
}
