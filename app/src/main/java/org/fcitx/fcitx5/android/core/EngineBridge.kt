/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.text.TextPaint
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.pack.AppData
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.pack.PackUnavailableException
import org.fcitx.fcitx5.android.data.pinyin.CommonWordsManager
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.restoreDirectory
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.learn.Simplifier
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.phrase.CommonWords
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.FieldFlags
import org.fcitx.fcitx5.android.engine.runtime.OwnKeysTurn
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.store.RecordStore
import org.fcitx.fcitx5.android.engine.store.Unsaved
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.host.EngineHost
import org.fcitx.fcitx5.android.input.address.AddressStore
import org.fcitx.fcitx5.android.input.handwriting.handwritingCannotLoad
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.causes
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * ime-core's input methods for one [KotlinHost]. Called only on `fcitx-main`, so nothing here locks; only the
 * custom phrases are saved on a thread of their own ([PhraseFile]), which [additions] waits for.
 */
class EngineBridge {

    /**
     * Ink written on [current]'s letter keys for the Ink key to carry; [Engines.inkSent] holds it if the key never
     * reached the session. Returns false where [current] writes on no keys or cannot load. On the fcitx thread.
     */
    fun sendInk(piece: Action.Ink, current: String, send: () -> Unit): Boolean {
        // an input method that cannot load writes on no keys: its failure is not handwriting's
        if (!InputMethodCatalog.writesOnKeys(current) || current in unloadable) return false
        // made first under its own latch, so a broken code table fails as itself, not as handwriting
        if (!madeOnKeys(current)) return false
        if (!handwriting { engines.ink(piece, shown = true, im = current) }) return false
        send()
        engines.inkSent(piece, current)
        return true
    }

    /** A test's may throw. */
    @VisibleForTesting
    internal var makeOnKeys: (String) -> Unit = { engines.make(it) }

    private fun madeOnKeys(current: String): Boolean = try {
        unloadable.call(current) { makeOnKeys(current) }
        true
    } catch (e: IOException) {
        Timber.w(e, "session of $current")
        false
    } catch (e: DataFormatException) {
        Timber.w(e, "session of $current")
        false
    }

    /** Null where no key is to be sent. On the fcitx thread. */
    fun glide(glide: Glide, current: String): Action.Glide? {
        if (current in unloadable) return null
        return try {
            unloadable.call(current) { engines.glide(current, glide) }
        } catch (e: IOException) {
            Timber.w(e, "glide in $current")
            null
        } catch (e: DataFormatException) {
            Timber.w(e, "glide in $current")
            null
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // a decoder's slip loses the glide, not the keyboard
            Timber.e(e, "glide in $current")
            null
        }
    }

    // handwriting's pinyin data may not load: latched once ([HANDWRITING]) rather than retried every character
    internal fun handwriting(call: () -> Unit): Boolean {
        if (HANDWRITING in unloadable) return false
        try {
            unloadable.call(HANDWRITING, call)
            return true
        } catch (e: IOException) {
            Timber.w(e, "handwriting engine")
        } catch (e: DataFormatException) {
            Timber.w(e, "handwriting engine")
        }
        return false
    }

    private var made: Engines? = null

    // what the field's last context left out of learning, kept for engines made after it
    private val fieldFlags = FieldFlags()

    /** Made when first used, and again after [close]; fcitx thread only. */
    val engines: Engines
        get() = made ?: Engines(
            blobs = ::asset,
            userDir = userDir()?.let(::restored)?.let(::JvmFiles),
            onError = ::userDataFailed,
            additions = { data().let { additions(pinyinDir(it), it.writable) } },
            kernel = NativeMatrixKernel.loaded,
            emojiShows = EmojiModifier.validator(),
            // a paint of its own: this is the fcitx thread's
            textShows = TextPaint().let { paint -> paint::hasGlyph },
            simplifier = ::simplifier,
            // not backed up: a copied backup is another install's
            installFile = JvmFile(File(appContext.noBackupFilesDir, "learn.install")),
            appVersion = Const.versionName,
            legacyFiles = listOf(File(pinyinDir(), LIBIME_DICTIONARY), File(pinyinDir(), LIBIME_HISTORY)).map(::JvmFile),
            fieldFlags = fieldFlags,
            // goes with the learning backups (LearnBackup.ADDRESSES)
            addresses = AddressStore::data,
            putAddresses = AddressStore::put,
            // logged, not thrown: a debug build is also someone's daily keyboard
            offHostThread = if (BuildConfig.DEBUG) ({ Timber.e(IllegalStateException(it)) }) else null,
            unkept = ::learningUnkept,
        ).also {
            it.useHabits = AppPrefs.getInstance().internal.useHabits.getValue()
            it.learning(null, BackupOp.SetAuto(AppPrefs.getInstance().internal.learningAutoBackup.getValue()))
            made = it
        }

    // read from the APK's assets, never a copy on disk that could go stale or missing
    private fun simplifier(): Simplifier = try {
        val assets = appContext.assets
        Simplifier.read(assets.open(TRADITIONAL_TABLE).use { it.readBytes() }, assets.open(SIMPLIFIED_TABLE).use { it.readBytes() })
    } catch (e: IOException) {
        Timber.w(e, "no traditional-to-simplified table: text read as it is")
        Simplifier.NONE
    }

    @VisibleForTesting
    internal fun userDataFailed(e: IOException) {
        Timber.w(e, "engine user data")
        if (e is RecordStore.KeptAside) EngineNotices.failedOnce(R.string.learning_kept_aside)
        // a word added or blocked by hand not written: in memory alone
        if (e is Unsaved) learningUnkept()
    }

    /** Told once a process, whatever the cause: learning silently lost with the process is what this is for. */
    private fun learningUnkept() {
        Timber.w("what is learned is not kept on the disk this run")
        sayUnkept(FcitxApplication.getInstance().isDirectBootMode)
    }

    /** Direct boot is expected till the unlock restart, so that is news, not a failure. */
    @VisibleForTesting
    internal fun sayUnkept(directBoot: Boolean) =
        if (directBoot) EngineNotices.toldOnce(R.string.learning_unkept_locked) else EngineNotices.failedOnce(R.string.learning_unkept)

    // credential-encrypted: what the user typed is not readable before the first unlock; till then the engine learns in memory
    fun userDir() = if (FcitxApplication.getInstance().isDirectBootMode) null else File(appContext.filesDir, "engine")

    /**
     * Puts [userDir] back from engine.old if an import was killed between its two renames. Null if it cannot (the
     * engine then learns in memory): a fresh engine/ would let the next import delete engine.old, the only copy. Made
     * if missing, else the learning record cannot open (ENOENT).
     */
    internal fun restored(userDir: File): File? = try {
        restoreDirectory(userDir)
        if (!userDir.isDirectory && !userDir.mkdirs()) throw IOException("cannot make $userDir")
        userDir
    } catch (e: IOException) {
        Timber.e(e, "engine user data cannot be put back: what is learned is kept in memory only")
        null
    }

    /** Once fcitx has exited, on its thread; an input method that could not load is tried again on the next engine. */
    fun close() {
        made?.close()
        made = null
        unloadable.clear()
        // a view made before the next engine speaks would show this one's syllables or word
        EngineState.reset()
    }

    // the text pack read through here, keys queued behind it: logged so a slow device's wait shows
    private fun verifyTimed(e: Throwable): Boolean {
        val start = System.nanoTime()
        return PackStore.verifyAfterFailure(PackKind.TEXT, e).also {
            Timber.i("text pack checked after a failure in %d ms: %s", (System.nanoTime() - start) / 1_000_000, if (it) "damaged" else "not found damaged")
        }
    }

    // keys go to the app, the user told once
    internal val unloadable = Unloadable { im, e ->
        Timber.e("%s cannot load (%s): not called again till the engine is reloaded", im, e.javaClass.name)
        // said on the keyboard, not a toast (Android 13 drops them); a missing or damaged pack is named as such
        val pack = e.causes().filterIsInstance<PackUnavailableException>().firstOrNull()
        if (im == HANDWRITING) handwritingCannotLoad()
        EngineNotices.failed(
            when {
                pack != null -> R.string.pack_text_needed_short
                verifyTimed(e) -> R.string.pack_text_damaged
                else -> R.string.engine_unloadable
            },
        )
    }

    /**
     * Input methods whose engine could not be made, not called again till [clear]. Only data errors [Engines] throws
     * before the first answer latch, never with a [VirtualMachineError] among the causes; anything else is that call's alone.
     */
    internal class Unloadable(private val latched: (im: String, e: Throwable) -> Unit) {
        private val answered = HashSet<String>()
        private val failed = HashSet<String>()

        operator fun contains(im: String) = im in failed

        /** What [block] throws is thrown on, for the host to log. */
        fun <T> call(im: String, block: () -> T): T {
            val result = try {
                block()
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                if (im !in answered && cannotLoad(e) && failed.add(im)) latched(im, e)
                throw e
            }
            answered += im
            return result
        }

        fun clear() {
            answered.clear()
            failed.clear()
        }

        // FileChannel.map's IOException "Map failed" is memory, not a missing asset, even as a cause
        private fun cannotLoad(e: Throwable) =
            (e is IOException || e is DataFormatException) && e.causes().none { it is VirtualMachineError }
    }

    /** fcitx's data (`files/host-data`, an older build's external `data/` moved in by [HostConfigDir.settle]). */
    internal fun data(): HostConfig = HostConfigDir.settle(ConfigHome.DATA)

    // with none settled: an empty directory, nothing written
    private fun pinyinDir(data: HostConfig = data()) = File(data.dir ?: HostConfigDir.unsettled(ConfigHome.DATA), "pinyin")

    /** The dictionaries are read from their compiled file ([MergedDictionary]); libime's are turned into text first. */
    internal fun additions(
        dir: File,
        writable: Boolean = true,
        unreadable: () -> Unit = { EngineNotices.failedOnce(R.string.user_data_unreadable) },
    ): Engines.Additions {
        val phraseFile = File(dir, "customphrase")
        // what the keyboard changed just before is in the file as it is read again
        phraseSaves.submit(Runnable {}).get()
        var read = true
        val phrases = try {
            phraseFile.takeIf { it.isFile }?.readText().orEmpty()
        } catch (e: IOException) {
            Timber.e(e, "custom phrases: kept as they are, not written this run")
            unreadable()
            read = false
            ""
        }
        val wordsFile = File(dir, CommonWordsManager.FILE.substringAfter('/'))
        var wordsRead = true
        val commonWords = try {
            wordsFile.takeIf { it.isFile }?.readText().orEmpty()
        } catch (e: IOException) {
            Timber.e(e, "common words: kept as they are, not written this run")
            unreadable()
            wordsRead = false
            ""
        }
        // one not read is off, never the default written over it
        val rareChars = try {
            File(dir, RareCharsManager.FILE.substringAfter('/')).takeIf { it.isFile }?.readText().orEmpty()
        } catch (e: IOException) {
            Timber.e(e, "rare characters: off this run")
            unreadable()
            ""
        }
        val dictionaryDir = File(dir, "dictionaries")
        // not settled: nothing converted or compiled, read as it is
        if (writable) ImportedDictionaries.migrate(dictionaryDir)
        val refused = when {
            !read -> "custom phrases were not read"
            !writable -> "where fcitx's data is, is not settled"
            else -> null
        }
        val save = PhraseFile(phraseFile, phrases, refused == null, refused.orEmpty(), saves = phraseSaves)::save
        // the compiled file, never the texts (read whole, they ran the heap out at every focus); stale ones compile off this thread
        val seen = MergedDictionary.current(dictionaryDir, compiles = writable)
        val merged = seen.file
        Timber.d("merged dictionaries: %s", merged?.let { "${it.size} words" } ?: "none yet")
        return Engines.Additions(
            phrases = phrases,
            dictionaries = seen.token,
            savePhrases = save,
            dictionary = { merged },
            indexes = JvmFiles(dictionaryDir),
            dictionaryStale = seen.stale,
            dictionaryPending = { MergedDictionary.pending(dictionaryDir) },
            commonWords = commonWords,
            saveCommonWords = WordsFile(wordsFile, commonWords, wordsRead && writable, saves = phraseSaves)::save,
            rareChars = rareChars,
        )
    }

    /** Saved as the keyboard deletes a common word, as [PhraseFile] saves phrases; not where unread or unsettled ([writable]). */
    internal class WordsFile(
        private val file: File,
        private val text: String,
        private val writable: Boolean,
        private val unsaved: () -> Unit = ::toldUnsaved,
        private val saves: Executor,
    ) {
        private var base: CommonWords? = null

        fun save(words: CommonWords) {
            if (!writable) throw IOException("common words were not read, or where fcitx's data is is not settled")
            saves.execute {
                try {
                    CommonWordsManager.saveOver(base ?: CommonWords.parse(text).words, words, file)
                    // only once saved, as the phrases'
                    base = words
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Timber.w(e, "common words")
                    unsaved()
                }
            }
        }
    }

    // one at a time, in order, off the fcitx thread; the thread ends when idle since a JVM test makes many bridges and closes none
    private val phraseSaves = ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) {
        Thread(it, "engine-phrases")
    }.apply { allowCoreThreadTimeOut(true) }

    /** Saved on [saves] only if [read] (else [refused] says why); the last saved base is that thread's. */
    internal class PhraseFile(
        private val file: File,
        private val text: String,
        private val read: Boolean,
        private val refused: String = "custom phrases were not read",
        private val unsaved: () -> Unit = ::toldUnsaved,
        private val saves: Executor,
    ) {
        private var base: CustomPhrases? = null

        fun save(phrases: CustomPhrases) {
            // a file not read is not written: its phrases would be lost to the one pinned
            if (!read) throw IOException(refused)
            saves.execute {
                try {
                    savePhrases(base ?: CustomPhrases.parse(text), phrases, file)
                    // only once saved: else the next save takes this one's change as in the file
                    base = phrases
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // thrown on that thread it would end the process
                    Timber.w(e, "custom phrases")
                    unsaved()
                }
            }
        }
    }

    // mapped in place (a pack's file, or an uncompressed APK asset); checked at import or by the APK's signature, so not read through
    internal fun asset(path: String): ByteBuffer = AppData.map(path)

    /** What the engine throws is thrown on, for the host's router to recover from. [learning] is false in sensitive fields. */
    val host: EngineHost = object : EngineHost {
        override fun event(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot? =
            answer(im, event) { engines.onEvent(im, event, arg, learning, payload) }

        override fun candidates(im: String, from: Int, count: Int): List<Choice> = engines.candidates(im, from, count)

        override fun offers(im: String, index: Int): Set<Offer> = engines.offers(im, index)

        override fun settings(settings: EngineSettings) = configure(settings)
    }

    /** Null for an input method that cannot load, whose key the host gives back. */
    internal fun answer(im: String, event: Int, call: () -> Snapshot): Snapshot? {
        if (im in unloadable) {
            EngineState.refining = false
            EngineState.syllablesState.value = EngineState.SyllableState(im)
            return null
        }
        val s = try {
            unloadable.call(im, call).also { follow(im, event, it) }
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            failedEvent(im)
            throw e
        }
        EngineState.refining = s.refines
        return s
    }

    private fun follow(im: String, event: Int, s: Snapshot) {
        // every answer, not only while typing: the 14 keys handing back to 全拼 answer with none, which clears what they offered
        if (event != EngineEvent.REFINE || s.handled) EngineState.syllablesState.value = EngineState.SyllableState(im, s.preedit.isNotEmpty(), s.syllables, s.syllablesId)
        if (im == InputMethodCatalog.ENGLISH) EngineState.englishTypingState.value = s.preedit.isNotEmpty() && !s.predicting
        // an answer not theirs is no reason to make them
        made?.let { e -> followed(im) { e } }
    }

    private fun failedEvent(im: String) {
        // the host refines no more after a failure
        EngineState.refining = false
        // nor is a word being spelled: 1 left an apostrophe with nothing typed
        if (im == InputMethodCatalog.ENGLISH) EngineState.englishTypingState.value = false
        // a tap would send an id the engine drops
        EngineState.syllablesState.value = EngineState.SyllableState(im)
    }

    /**
     * Adds the nine keys' letters and the own-key groups ([syncOwnKeys]'s, else the keyboard's). Not the keyboard's
     * while others are typed on ([turnAt] waiting): their remade session would drop what was typed. On the fcitx thread too.
     */
    fun configure(settings: EngineSettings) {
        engines.settings = settings.copy(nineKeys = nineKeys(), ownKeys = inUseOf ?: EngineState.ownKeys)
        EngineState.shuangpinScheme = engines.settings.scheme
        EngineState.englishSuggests = engines.settings.englishSuggest
        EngineState.candidateReadingState.value = engines.settings.candidateReading
        // 双拼's 14 keys cannot type 微软: stored as the 18 keys. Written on the fcitx thread, but SharedPreferences still
        // runs its listeners (the service's, which remakes the input view) on main; a change told another way must post.
        val keyboards = AppPrefs.getInstance().keyboard
        // a user's layout of the 14 keys' groups alike; one not drawn is left chosen
        val chosen = keyboards.imKeyboards()
        ImKeyboards.fallback(InputMethodCatalog.SHUANGPIN, chosen.shuangpin, engines.settings.scheme, chosen.customOf(InputMethodCatalog.SHUANGPIN))
            ?.let { keyboards.setImKeyboard(InputMethodCatalog.SHUANGPIN, it) }
    }

    /** On the fcitx thread; also returns whether the input method's list is to be shown anew. */
    fun learning(op: LearningOp, im: String, shown: (String) -> String = { it }, act: (event: Int, index: Int, text: String) -> Boolean): Pair<LearningResult, Boolean> {
        // the switch's preference is the app's to set, on main: read when the engine is made
        val result = engines.learning(im, op, shown, act)
        if (result is LearningResult.Cleared) RecentlyUsed.clear(recentsSince(result.fromMinute))
        return result to shownAnew(op, im, result)
    }

    // the pickers' recents go with what was learned: from the same minute on, or all (0)
    internal fun recentsSince(fromMinute: Int): Long =
        if (fromMinute == LearningResult.ALL) 0L else fromMinute * MS_PER_MINUTE

    internal fun shownAnew(op: LearningOp, im: String, result: LearningResult) =
        isEngines(im) && im !in unloadable && result != LearningResult.Unavailable && showsAnew(op)

    // every op named: a new one must say whether it changes what is shown
    private fun showsAnew(op: LearningOp): Boolean = when (op) {
        is LearningOp.OnPage -> showsAnew(op.op)
        is LearningOp.Undo, is LearningOp.UseHabits, is LearningOp.ClearRecent, is LearningOp.ClearLearned,
        is LearningOp.NoPredict, is LearningOp.RemoveItems, is LearningOp.RemoveWords -> true
        is LearningOp.Menu, is LearningOp.Delete, is LearningOp.Block, is LearningOp.Pin, is LearningOp.Unpin,
        is LearningOp.Report, LearningOp.Overview, LearningOp.Saving, is LearningOp.ListItems, is LearningOp.RestoreCorrection,
        is LearningOp.ResetTableOrder -> false
        is BackupOp.Restore, is BackupOp.RestoreKept, BackupOp.Revert -> true
        BackupOp.Kept, is BackupOp.Delete, is BackupOp.Peek, BackupOp.Copy, is BackupOp.SetAuto -> false
    }

    private fun nineKeys() = NineKeyLetters.parse(AppPrefs.getInstance().keyboard.t9Letters.getValue())

    private var inUseOf: List<String>? = null

    /** Other own groups than [syncOwnKeys]'s take over at their first code, committing what was typed; none told before is no change. */
    fun turnAt(own: List<String>?, codePoint: Int): OwnKeysTurn.Step = OwnKeysTurn.at(inUseOf, own, codePoint)

    /**
     * Tells the keyboard's own-layout groups where they changed: [NineKeyLetters.inUse] and the engine's settings,
     * remaking its sessions. Before each key, on the fcitx thread; the same list does nothing.
     */
    fun syncOwnKeys(own: List<String>? = EngineState.ownKeys) {
        if (own != inUseOf) {
            inUseOf = own
            own?.let(NineKeyLetters::ownOf)?.let(NineKeyLetters::inUse) ?: NineKeyLetters.clearInUse()
        }
        made?.let { if (it.settings.ownKeys != own) it.settings = it.settings.copy(ownKeys = own) }
    }

    /** An input method that could not load is tried again. */
    fun reload() {
        unloadable.clear()
        made?.let { it.settings = it.settings.copy(nineKeys = nineKeys()) }
        made?.reload()
    }

    /** See [Engines.context]; true if [im] offers words now, for a RECALL key to show. */
    fun context(
        before: String,
        learning: Boolean,
        im: String,
        cleared: Boolean = false,
        learnFlags: Int = 0,
        started: Boolean = false,
        after: String? = null,
        selected: Boolean = false,
        said: Boolean = false,
    ): Boolean {
        // none made: kept for those made next
        val engines = made ?: run {
            fieldFlags.value = learnFlags
            return false
        }
        val offers = engines.context(
            // named: eight flags in a row, two swapped would still compile
            im, before, learning, cleared = cleared, learnFlags = learnFlags, started = started,
            after = after, selected = selected, said = said,
        )
        followed(im) { engines }
        return offers
    }

    // [engines] asked only when followed: a call answered elsewhere (a test's) makes none
    private inline fun followed(im: String, engines: () -> Engines) {
        if (InputMethodCatalog.writesOnKeys(im) && im !in unloadable) EngineState.inkEnded.value = engines().inkEnded
        // none for a table that could not be read, whose keys say nothing
        if (im in InputMethodCatalog.TABLES && im !in unloadable) EngineState.tableKeyboardState.value = engines().tableKeyboard(im)
    }

    /** None while the engine is not made; it is not made to answer: the keyboard waits. */
    fun tableKeys(im: String): Set<Char> = made?.tableKeys(im).orEmpty()

    companion object {
        // one latch whichever input method is written on: the recognizer's readings are pinyin's data
        private const val HANDWRITING = "handwriting"

        // its own words, not learning_unkept: that says learned words are unsaved, and its once-a-process would be spent here
        private fun toldUnsaved() = EngineNotices.failedOnce(R.string.user_lists_unkept)

        /** Applies [phrases]' changes since [base] to [file] as it is now, so an editor's save in between is kept. */
        internal fun savePhrases(base: CustomPhrases, phrases: CustomPhrases, file: File) {
            CustomPhraseManager.saveKeysOver(CustomPhraseManager.items(base), CustomPhraseManager.items(phrases), file)
        }

        fun isEngines(im: String) = InputMethodCatalog.listed(im) != null

        private const val MS_PER_MINUTE = 60_000L
        private const val IDLE_SECONDS = 30L
        private const val LIBIME_DICTIONARY = "user.dict"
        private const val LIBIME_HISTORY = "user.history"
        // OpenCC 1.2.0's character tables: see simplifier()
        private const val TRADITIONAL_TABLE = "learn/TSCharacters.txt"
        private const val SIMPLIFIED_TABLE = "learn/STCharacters.txt"
    }
}
