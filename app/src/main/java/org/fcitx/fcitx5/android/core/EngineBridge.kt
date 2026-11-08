/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.text.TextPaint
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.pack.AppData
import org.fcitx.fcitx5.android.data.pack.PackUnavailableException
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
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.FieldFlags
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.host.EngineHost
import org.fcitx.fcitx5.android.input.address.AddressStore
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.appContext
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
 * Where the Kotlin host ([host]) reaches ime-core's input methods: one per host, its [KotlinHost]'s.
 * Called only on the host's thread (`fcitx-main`): the sessions are that thread's alone, so nothing
 * here locks. What the keyboards watch of it is [EngineState]'s. Only the
 * custom phrases the keyboard changes are saved on a thread of their own ([PhraseFile]), which
 * [additions] waits for before it reads them.
 */
class EngineBridge {

    /**
     * What the handwriting pad read, or a piece of it finished ([piece]), for the Ink key to carry,
     * [send] sending it, then [Engines.inkSent] holds it if the key never reached the session; with
     * handwriting not [current], the input method, no key, the piece only held. Whether the key was
     * sent. On the fcitx thread.
     */
    fun sendInk(piece: Action.Ink, current: String, send: () -> Unit): Boolean {
        if (current != InputMethodCatalog.HANDWRITING) {
            handwriting(current) { engines.ink(piece, shown = false) }
            return false
        }
        // only the session's making caught as handwriting's: a slip of the key sent after it is the key's,
        // not a read untaken (else the pad would type it again as its character)
        if (!handwriting(current) { engines.ink(piece, shown = true) }) return false
        send()
        engines.inkSent(piece)
        return true
    }

    /**
     * A glide across the letters as [current], the input method, reads it, for the Glide key to
     * carry and commit its word: Engines.glide. Null where no key is to be sent. On the fcitx thread.
     */
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
            // the decoders read any path drawn: a slip of theirs loses the glide, not the keyboard
            Timber.e(e, "glide in $current")
            null
        }
    }

    // these make the handwriting session, whose pinyin data may not load: as its keys, latched once
    // and skipped after, the pad drawing on with nothing taken (not a load tried at every character).
    // Whether it was taken: not, no Ink key is sent, which unanswered would be typed as its character.
    // Handwriting's only: a piece finished as the pad leaves for English ([current]) is told the engine,
    // but is not a key typed into keyboard-us
    internal fun handwriting(current: String, call: () -> Unit): Boolean {
        if (InputMethodCatalog.HANDWRITING in unloadable) return false
        try {
            unloadable.call(InputMethodCatalog.HANDWRITING, call)
            return current == InputMethodCatalog.HANDWRITING
        } catch (e: IOException) {
            Timber.w(e, "handwriting engine")
        } catch (e: DataFormatException) {
            Timber.w(e, "handwriting engine")
        }
        return false
    }

    private var made: Engines? = null

    // what the field's last context said learning leaves out, kept for engines made after it: the
    // one copy, the engines' own (FieldFlags)
    private val fieldFlags = FieldFlags()

    /** Made when first used, and again after [close]; on the fcitx thread only, as the host uses it there. */
    val engines: Engines
        get() = made ?: Engines(
            blobs = ::asset,
            userDir = userDir()?.let(::restored)?.let(::JvmFiles),
            onError = { Timber.w(it, "engine user data") },
            additions = { data().let { additions(pinyinDir(it), it.writable) } },
            kernel = NativeMatrixKernel.loaded,
            emojiShows = EmojiModifier.validator(),
            // a paint of its own: this is the fcitx thread's, not the main one's
            textShows = TextPaint().let { paint -> paint::hasGlyph },
            simplifier = ::simplifier,
            // where the app's files do not go with a phone moved: a copied backup is another install's
            installFile = JvmFile(File(appContext.noBackupFilesDir, "learn.install")),
            appVersion = Const.versionName,
            // libime's own, an older build's: gone with what the clears clear (review-E-engine S3)
            legacyFiles = listOf(File(pinyinDir(), LIBIME_DICTIONARY), File(pinyinDir(), LIBIME_HISTORY)).map(::JvmFile),
            // made anew in a field (after a close): its flags as the field's context told them (review-int N1)
            fieldFlags = fieldFlags,
            // what the address bar learned goes with the learning backups (LearnBackup.ADDRESSES)
            addresses = AddressStore::data,
            putAddresses = AddressStore::put,
            // logged rather than thrown, as EditingSession's: a debug build is also someone's daily keyboard
            offHostThread = if (BuildConfig.DEBUG) ({ Timber.e(IllegalStateException(it)) }) else null,
        ).also {
            it.useHabits = AppPrefs.getInstance().internal.useHabits.getValue()
            it.learning(null, BackupOp.SetAuto(AppPrefs.getInstance().internal.learningAutoBackup.getValue()))
            made = it
        }

    // OpenCC's character tables, as the app ships them: the app's traditional text read simplified by
    // learning. Read from the APK's assets, never from the data directory DataManager syncs: no
    // copy there to go stale or missing
    private fun simplifier(): Simplifier = try {
        val assets = appContext.assets
        Simplifier.read(assets.open(TRADITIONAL_TABLE).use { it.readBytes() }, assets.open(SIMPLIFIED_TABLE).use { it.readBytes() })
    } catch (e: IOException) {
        Timber.w(e, "no traditional-to-simplified table: text read as it is")
        Simplifier.NONE
    }

    // credential-encrypted, unlike fcitx's home: what the user typed is not kept where it can be read
    // before the phone is first unlocked. Till then the engine learns in memory; the app restarts at unlock.
    fun userDir() = if (FcitxApplication.getInstance().isDirectBootMode) null else File(appContext.filesDir, "engine")

    /**
     * [userDir], put back before the engine makes it anew: a user-data import killed between its
     * two renames left the engine's files only in engine.old. Null if it cannot be, the engine then
     * learning in memory: an engine/ made anew would have the next import delete engine.old, the
     * only copy of them.
     */
    internal fun restored(userDir: File): File? = try {
        restoreDirectory(userDir)
        userDir
    } catch (e: IOException) {
        Timber.e(e, "engine user data cannot be put back: what is learned is kept in memory only")
        null
    }

    /**
     * Closes the engine, made anew at its next use: once fcitx has exited, on its thread. An input
     * method that could not load on this one is tried again on that one.
     */
    fun close() {
        made?.close()
        made = null
        unloadable.clear()
        // a view made before the next engine speaks (a restart) would show this one's syllables or word
        EngineState.reset()
    }

    // what cannot load is not loaded again on every key: the keys go to the app, the user told once
    internal val unloadable = Unloadable { im, e ->
        Timber.e("%s cannot load (%s): not called again till the engine is reloaded", im, e.javaClass.name)
        // said on the keyboard (§3: not a toast, which Android 13 drops); a pack missing is told as such
        val pack = generateSequence(e) { it.cause }.take(MAX_CAUSES).filterIsInstance<PackUnavailableException>().firstOrNull()
        EngineNotices.failed(if (pack != null) R.string.pack_text_needed_short else R.string.engine_unloadable)
    }

    /**
     * The input methods whose engine could not be made (its data unreadable, say), not to be
     * called again till [clear]: what one throws before it first answered is taken for that if it
     * is what [Engines] throws for data it cannot read, with no memory run out (a [VirtualMachineError])
     * among its causes, and [latched] is told, once. Anything else it throws, before or after (a bug,
     * memory run out, however wrapped), is that event's alone; it is called again.
     */
    internal class Unloadable(private val latched: (im: String, e: Throwable) -> Unit) {
        private val answered = HashSet<String>()
        private val failed = HashSet<String>()

        operator fun contains(im: String) = im in failed

        /** [block], a call of [im]'s engine; what it throws is thrown on, for the host to log. */
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

        // an asset or table that cannot be opened or is none; not memory run out mapping one,
        // FileChannel.map's IOException "Map failed", even as a cause
        private fun cannotLoad(e: Throwable) =
            (e is IOException || e is DataFormatException) && chain(e).none { it is VirtualMachineError }

        // [e] and its causes, each once: initCause refuses only a throwable itself, so a chain may loop back
        private fun chain(e: Throwable): Sequence<Throwable> {
            val seen = HashSet<Throwable>()
            return generateSequence(e) { it.cause }.takeWhile(seen::add)
        }
    }

    /**
     * fcitx's data this run, where libime kept its files and the app's editors keep what the user
     * adds: the app's own `files/host-data`, an older build's `data/` on the external storage moved
     * in at the first start that can ([HostConfigDir.settle], A4).
     */
    internal fun data(): HostConfig = HostConfigDir.settle(ConfigHome.DATA)

    // with none settled (before the unlock; the external storage away, nothing moved yet): a directory
    // with nothing in it, as fcitx's home was before the unlock, and nothing written
    private fun pinyinDir(data: HostConfig = data()) = File(data.dir ?: HostConfigDir.unsettled(ConfigHome.DATA), "pinyin")

    /**
     * The custom phrases and the dictionaries turned on in [dir], fcitx's pinyin data directory, as
     * the editors keep them; the phrases not read are left out. The dictionaries are the file
     * compiled from them ([MergedDictionary]); libime's are turned into text first.
     */
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
        val dictionaryDir = File(dir, "dictionaries")
        // where the data is not settled, nothing written: no libime file converted, no file compiled,
        // what is there read as it is (where that is, the next start may not read)
        if (writable) ImportedDictionaries.migrate(dictionaryDir)
        val refused = when {
            !read -> "custom phrases were not read"
            !writable -> "where fcitx's data is, is not settled"
            else -> null
        }
        val save = PhraseFile(phraseFile, phrases, refused == null, refused.orEmpty(), phraseSaves)::save
        // the compiled file, never the texts (0.3.1 read them whole here and ran the heap out at
        // every focus): one not made yet, or stale, is compiled off this thread, the engine reloaded then
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
        )
    }

    // the engine's phrases saved off the fcitx thread, which takes the keys too; one at a time, in order.
    // Its thread ends when idle, as the host's config thread does: a bridge is a host's, and a JVM
    // test makes many, none closing it
    private val phraseSaves = ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) {
        Thread(it, "engine-phrases")
    }.apply { allowCoreThreadTimeOut(true) }

    /**
     * The custom phrases the engine read from [file] as [text], saved there as the keyboard changes
     * them ([savePhrases]), on [saves], if [read] (false: it could not be, or the data is not settled,
     * [refused] saying which): what the engine has as last saved, the base of the next, is that thread's.
     */
    internal class PhraseFile(
        private val file: File,
        private val text: String,
        private val read: Boolean,
        private val refused: String = "custom phrases were not read",
        private val saves: Executor,
    ) {
        private var base: CustomPhrases? = null

        fun save(phrases: CustomPhrases) {
            // a file not read is not written: the phrases it has would be lost to the one pinned
            if (!read) throw IOException(refused)
            saves.execute {
                try {
                    savePhrases(base ?: CustomPhrases.parse(text), phrases, file)
                    // only once saved: else the next save takes what this one changed as in the file
                    base = phrases
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // on that thread, it would end the process: kept in memory, as the engine does
                    Timber.w(e, "custom phrases")
                }
            }
        }
    }

    // mapped where it lies (AppData: a resource pack's file, or the APK's asset stored uncompressed):
    // nothing copied, and pages the OS may drop. Checked as it was imported (or signed with the APK),
    // so the checksums need not be read through.
    internal fun asset(path: String): ByteBuffer = AppData.map(path)

    /**
     * The Kotlin host's port ([EngineHost]): [answer] of the engine's snapshot. What the engine
     * throws is thrown on, for the host's router to log and recover from as native-lib's
     * engineFailed did. [learning] is false in a password or other sensitive field: nothing typed
     * there is kept.
     */
    val host: EngineHost = object : EngineHost {
        override fun event(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot? =
            answer(im, event) { engines.onEvent(im, event, arg, learning, payload) }

        override fun candidates(im: String, from: Int, count: Int): List<Choice> = engines.candidates(im, from, count)

        override fun offers(im: String, index: Int): Set<Offer> = engines.offers(im, index)

        override fun settings(settings: EngineSettings) = configure(settings)
    }

    /**
     * [im]'s engine's answer to [event], [call], with its side effects (the syllables'
     * and English's state, the unloadable latch, [EngineState.refining]); null for an input method that cannot
     * load, whose key the host gives back.
     */
    internal fun answer(im: String, event: Int, call: () -> Snapshot): Snapshot? {
        if (im in unloadable) {
            EngineState.refining = false
            // nothing typed there now: syllables shown before it became unloadable go
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

    // what the app keeps of an event's snapshot [s]: every input method's syllables, English's word being spelled
    private fun follow(im: String, event: Int, s: Snapshot) {
        // every input method's answer, not only while keys are typed: the 14 keys handing back to
        // 全拼 answer with none, which clears what they offered (a gate on them would keep it shown)
        if (event != EngineEvent.REFINE || s.handled) EngineState.syllablesState.value = EngineState.SyllableState(im, s.preedit.isNotEmpty(), s.syllables, s.syllablesId)
        if (im == InputMethodCatalog.ENGLISH) EngineState.englishTypingState.value = s.preedit.isNotEmpty() && !s.predicting
        // the engines that answered: none made, nothing of theirs to tell (an answer not theirs is no reason to make them)
        made?.let { e -> followed(im) { e } }
    }

    private fun failedEvent(im: String) {
        // the host refines no more after a failure (native-lib's engineFailed)
        EngineState.refining = false
        // nor is a word being spelled: 1 left an apostrophe with nothing typed
        if (im == InputMethodCatalog.ENGLISH) EngineState.englishTypingState.value = false
        // nor any syllables to take: a tap would send an id the engine drops
        EngineState.syllablesState.value = EngineState.SyllableState(im)
    }

    /**
     * The host's config ([org.fcitx.fcitx5.android.host.EngineOptions.toSettings]), with the nine keys'
     * letters the keyboard draws (the app's preference, read again by [reload]); on the fcitx thread too.
     */
    fun configure(settings: EngineSettings) {
        engines.settings = settings.copy(nineKeys = nineKeys())
        EngineState.shuangpinScheme = engines.settings.scheme
        EngineState.englishSuggests = engines.settings.englishSuggest
        EngineState.candidateReadingState.value = engines.settings.candidateReading
        // 双拼's 14 keys under a scheme they cannot type (微软): stored as the 18 keys, which the
        // input view is made anew for (a keyboard preference), the settings page and ⋯ showing it so
        // Written here, on the fcitx thread: its listeners (the service's, which makes the input view anew) still run on
        // the main thread, as SharedPreferences hands its change listeners there; a change told another way must post
        val keyboards = AppPrefs.getInstance().keyboard
        ImKeyboards.fallback(InputMethodCatalog.SHUANGPIN, keyboards.shuangpinKeyboard.getValue().keyboard, engines.settings.scheme)
            ?.let { keyboards.setImKeyboard(InputMethodCatalog.SHUANGPIN, it) }
    }

    /**
     * [Engines.learning] of [op] in [im], the input method in use, [act] sending a long press's
     * event the way the menu does; on the fcitx thread. With whether what an engine's input method
     * shows is to be shown anew: what was taken back, cleared or turned off may be on it.
     */
    fun learning(op: LearningOp, im: String, act: (event: Int) -> Boolean): Pair<LearningResult, Boolean> {
        // the switch's preference is the app's to set, on the main thread: read when the engine is made
        val result = engines.learning(im, op) { event, _ -> act(event) }
        if (result is LearningResult.Cleared) RecentlyUsed.clear(recentsSince(result.fromMinute))
        return result to shownAnew(op, im, result)
    }

    // the pickers' recents go with what was learned (U-67): from the same minute on, or all (0)
    internal fun recentsSince(fromMinute: Int): Long =
        if (fromMinute == LearningResult.ALL) 0L else fromMinute * MS_PER_MINUTE

    // only an engine's input method that loaded shows its list anew, and only after an op that did something
    internal fun shownAnew(op: LearningOp, im: String, result: LearningResult) =
        isEngines(im) && im !in unloadable && result != LearningResult.Unavailable && showsAnew(op)

    // the acts on a candidate the host shows itself, as it shows a long press's; what only reads
    // shows nothing new. Every op named: a new one must say whether it changes what is shown
    private fun showsAnew(op: LearningOp): Boolean = when (op) {
        is LearningOp.OnPage -> showsAnew(op.op)
        is LearningOp.Undo, is LearningOp.UseHabits, is LearningOp.ClearRecent, is LearningOp.ClearLearned,
        is LearningOp.NoPredict, is LearningOp.RemoveItems, is LearningOp.RemoveWords -> true
        is LearningOp.Menu, is LearningOp.Delete, is LearningOp.Block, is LearningOp.Pin, is LearningOp.Unpin,
        is LearningOp.Report, LearningOp.Overview, is LearningOp.ListItems, is LearningOp.RestoreCorrection,
        is LearningOp.ResetTableOrder -> false
        // a restore or 退回恢复前 changes what was learned; the rest only read or keep files
        is BackupOp.Restore, is BackupOp.RestoreKept, BackupOp.Revert -> true
        BackupOp.Kept, is BackupOp.Delete, is BackupOp.Peek, BackupOp.Copy, is BackupOp.SetAuto -> false
    }

    private fun nineKeys() = NineKeyLetters.parse(AppPrefs.getInstance().keyboard.t9Letters.getValue())

    /**
     * Reads [additions] and the nine keys' letters again, the sessions made anew; nothing to do
     * while the engine is not made. An input method that could not load is tried again.
     */
    fun reload() {
        unloadable.clear()
        made?.let { it.settings = it.settings.copy(nineKeys = nineKeys()) }
        made?.reload()
    }

    /** See [Engines.context]; nothing to tell while the engine is not made. True if [im] offers words now, for a RECALL key (Engines.context) to show. */
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
        // the engines keep them as they hear the context; none made, kept here for those made next
        val engines = made ?: run {
            fieldFlags.value = learnFlags
            return false
        }
        val offers = engines.context(
            // named: eight flags in a row, two swapped would still compile (review R11 F3080)
            im, before, learning, cleared = cleared, learnFlags = learnFlags, started = started,
            after = after, selected = selected, said = said,
        )
        followed(im) { engines }
        return offers
    }

    // what the app keeps of an input method's session, after any call of it: one place as input methods come.
    // [engines] asked only for one followed: a call answered elsewhere (a test's) makes none
    private inline fun followed(im: String, engines: () -> Engines) {
        if (im == InputMethodCatalog.HANDWRITING) EngineState.inkEnded.value = engines().inkEnded
        // a table's keyboard: its 拼 key turned, its settings changed (a field's context comes as it
        // shows); none of one that could not be read, whose keys say nothing
        if (im in InputMethodCatalog.TABLES && im !in unloadable) EngineState.tableKeyboardState.value = engines().tableKeyboard(im)
    }

    /** See [Engines.tableKeys]; none while the engine is not made, no session yet, nor is it made to answer: the keyboard waits. */
    fun tableKeys(im: String): Set<Char> = made?.tableKeys(im).orEmpty()

    companion object {
        /**
         * Saves [phrases], the engine's, as what it changed since [base] (those it read or last saved)
         * applied to [file] as it is now: the editor may have saved since, its reload of the engine
         * not run, and what it saved is kept.
         */
        internal fun savePhrases(base: CustomPhrases, phrases: CustomPhrases, file: File) {
            CustomPhraseManager.saveKeysOver(CustomPhraseManager.items(base), CustomPhraseManager.items(phrases), file)
        }

        /** Whether [im] is one of the engine's input methods, whose long press the app words (U-82). */
        fun isEngines(im: String) = im.startsWith(ENGINE) || im == InputMethodCatalog.ENGLISH

        // the host's input methods (the native androidengine's), but English, which is keyboard-us's
        private const val ENGINE = "engine-"
        // a chain of causes looked down this far for a pack missing (initCause may loop one back)
        private const val MAX_CAUSES = 16
        private const val MS_PER_MINUTE = 60_000L
        private const val IDLE_SECONDS = 30L
        private const val LIBIME_DICTIONARY = "user.dict"
        private const val LIBIME_HISTORY = "user.history"
        // OpenCC 1.2.0's character tables, in the APK's assets: see simplifier()
        private const val TRADITIONAL_TABLE = "learn/TSCharacters.txt"
        private const val SIMPLIFIED_TABLE = "learn/STCharacters.txt"
    }
}
