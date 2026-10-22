/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.text.TextPaint
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.restoreDirectory
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.host.ConversionNote
import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.learn.Simplifier
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the androidengine addon (native) reaches ime-core's input methods. Called only on the
 * fcitx thread, through JNI: the sessions are that thread's alone, so nothing here locks. Only the
 * custom phrases the keyboard changes are saved on a thread of their own ([PhraseFile]), which
 * [additions] waits for before it reads them.
 */
object EngineBridge {

    /**
     * One event's outcome, which the addon reads field by field. [candidates] run from [first]:
     * the page shown ([shown] of them), then more. The addon numbers them from [first], as the
     * session does. [actionable]: whether a long press on a candidate may offer actions ([offers]).
     * [labels]: the keys that pick the shown candidates, empty for the digits. [refines]: whether
     * the addon should send [EngineEvent.REFINE] while the user pauses.
     */
    class Result(
        @JvmField val handled: Boolean,
        @JvmField val commit: String,
        @JvmField val preedit: String,
        @JvmField val candidates: Array<String>,
        @JvmField val hints: Array<String>,
        @JvmField val first: Int,
        @JvmField val shown: Int,
        @JvmField val total: Int,
        @JvmField val actionable: Boolean,
        @JvmField val labels: String,
        @JvmField val refines: Boolean,
        @JvmField val deleteBefore: Int = 0,
        @JvmField val cursor: Int = -1,
    )

    /**
     * What the nine-key keyboard shows beside its keys, as the last event of [Engines.T9] left
     * it: whether anything is typed, what its first digits not yet taken may be, and which list
     * that is (see Snapshot.syllables, syllablesId). Set on the fcitx thread, read on the main one.
     */
    data class T9State(val composing: Boolean = false, val syllables: List<String> = emptyList(), val id: Int = 0)

    private val t9State = MutableStateFlow(T9State())
    val t9: StateFlow<T9State> = t9State

    private val englishTypingState = MutableStateFlow(false)

    /**
     * Whether English has a word typed (not a prediction offered), as its last event left it: on
     * the nine keys, 1 is then an apostrophe in the word. Set on the fcitx thread, read on the main one.
     */
    val englishTyping: StateFlow<Boolean> = englishTypingState

    /** Whether English suggests words, as the addon's config last said ([EngineSettings.englishSuggest]). */
    @Volatile
    var englishSuggests = true
        private set

    private val inkEnded = MutableStateFlow(HandwritingSession.NO_PIECE)

    /**
     * The latest piece of handwriting the engine ended (Engines.inkEnded): the pad wipes the piece
     * it is writing once it is among them. Set on the fcitx thread, read on the main one.
     */
    val handwritingEnded: StateFlow<Int> = inkEnded

    /** What the handwriting pad read of its [piece], for the key sent next to show: Engines.offerInk. On the fcitx thread, [current] its input method. */
    fun offerInk(reading: InkReading, piece: Int, current: String) = ink(current) { engines.offerInk(reading, piece) }

    /** The handwriting pad's [piece] finished, for the key sent next to tell: Engines.inkFinished. On the fcitx thread, [current] its input method. */
    fun inkFinished(piece: Int, current: String) = ink(current) { engines.inkFinished(piece) }

    /** A glide across the letters, for the key sent next to commit its word: Engines.offerGlide. On the fcitx thread, [current] its input method. */
    fun offerGlide(glide: Glide, current: String): Boolean {
        if (current in unloadable) return false
        return try {
            unloadable.call(current) { engines.offerGlide(current, glide) }
        } catch (e: IOException) {
            Timber.w(e, "glide in $current")
            false
        } catch (e: DataFormatException) {
            Timber.w(e, "glide in $current")
            false
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // the decoders read any path drawn: a slip of theirs loses the glide, not the keyboard
            Timber.e(e, "glide in $current")
            false
        }
    }

    // these make the handwriting session, whose pinyin data may not load: as its keys, latched once
    // and skipped after, the pad drawing on with nothing taken (not a load tried at every character).
    // Whether it was taken: not, no Ink key is sent, which unanswered would be typed as its character.
    // Handwriting's only: a piece finished as the pad leaves for English ([current]) is told the engine,
    // but is not a key typed into keyboard-us
    internal fun ink(current: String, call: () -> Unit): Boolean {
        if (Engines.HANDWRITING in unloadable) return false
        try {
            unloadable.call(Engines.HANDWRITING, call)
            return current == Engines.HANDWRITING
        } catch (e: IOException) {
            Timber.w(e, "handwriting engine")
        } catch (e: DataFormatException) {
            Timber.w(e, "handwriting engine")
        }
        return false
    }

    /** The 双拼 scheme the user set, for the keyboard to tell its keys from text (Engines.readsAsKey): set on the fcitx thread, read on the main one. */
    @Volatile
    var shuangpinScheme: ShuangpinScheme = ShuangpinScheme.ZIRANMA
        private set

    private var made: Engines? = null

    // what the field's last context said learning leaves out, kept for engines made after it
    private var fieldFlags = 0

    /** Made when first used, and again after [close]; on the fcitx thread only, as the addon uses it there. */
    val engines: Engines
        get() = made ?: Engines(
            load = ::asset,
            userDir = userDir()?.let(::restored),
            onError = { Timber.w(it, "engine user data") },
            legacy = ::legacy,
            additions = { additions(pinyinDir()) },
            kernel = NativeMatrixKernel,
            emojiShows = EmojiModifier.validator(),
            // a paint of its own: this is the fcitx thread's, not the main one's
            textShows = TextPaint().let { paint -> paint::hasGlyph },
            simplifier = ::simplifier,
            // where the app's files do not go with a phone moved: a copied backup is another install's
            installFile = File(appContext.noBackupFilesDir, "learn.install"),
            appVersion = Const.versionName,
            // libime's own, which [legacy] reads: gone with what the clears clear (review-E-engine S3)
            legacyFiles = listOf(File(pinyinDir(), LIBIME_DICTIONARY), File(pinyinDir(), LIBIME_HISTORY)),
            onConversion = ::conversion,
        ).also {
            it.useHabits = AppPrefs.getInstance().internal.useHabits.getValue()
            // made anew in a field (after a close): its flags as the field's context told them (review-int N1)
            it.fieldFlags = fieldFlags
            it.learning(null, BackupOp.SetAuto(AppPrefs.getInstance().internal.learningAutoBackup.getValue()))
            made = it
        }

    /** See [Engines.paused]; nothing waits while the engine is not made. */
    fun paused(idle: Boolean): Boolean = made?.paused(idle) == true

    // the older builds' learning's conversion: logged, counts alone (a release's logcat reads it, the
    // upgrade's device check); made, put in place once typing pauses a second (uat-0.3.1 recheck)
    private fun conversion(note: ConversionNote) {
        if (note != ConversionNote.Made) {
            Log.i(LOG_TAG, note.toString())
            return
        }
        // on the warm-up thread: the IME may disconnect after the connection was got, the
        // conversion still made and put in place at the next start's pause
        try {
            FcitxDaemon.getFirstConnectionOrNull()?.runIfReady {
                // a stale merge made again (up to three times) reports Made each time: one wait does for all
                if (!puttingInPlace.compareAndSet(false, true)) return@runIfReady
                do {
                    try {
                        do delay(Engines.IDLE_MILLIS) while (enginePaused(idle = true))
                    } finally {
                        puttingInPlace.set(false)
                    }
                    // a Made between the last pause and the flag's clearing found it set and left: asked
                    // once more, and waited for again unless another Made took the wait (F3171)
                } while (enginePaused(idle = true) && puttingInPlace.compareAndSet(false, true))
            }
        } catch (e: IllegalStateException) {
            Timber.w(e, "learning converted: put in place once the engine pauses next")
        }
    }

    private val puttingInPlace = AtomicBoolean(false)

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
    }

    // what cannot load is not loaded again on every key: the keys go to the app, the user told once
    internal val unloadable = Unloadable { im, e ->
        Timber.e("%s cannot load (%s): not called again till the engine is reloaded", im, e.javaClass.name)
        ContextCompat.getMainExecutor(appContext).execute { appContext.toast(R.string.engine_unloadable, Toast.LENGTH_LONG) }
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

        /** [block], a call of [im]'s engine; what it throws is thrown on, for the addon to log. */
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

    // fcitx's data home, as Fcitx starts it: where libime kept its files, and where the app's
    // editors still keep what the user adds
    internal fun fcitxHome(): File {
        val context = FcitxApplication.getInstance().directBootAwareContext
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    private fun dataDir() = File(fcitxHome(), "data")

    private fun pinyinDir() = File(dataDir(), "pinyin")

    /** What libime's pinyin learned, where fcitx kept it (FCITX_DATA_HOME), as text. */
    private fun legacy(): LibimeImport.Legacy? {
        val dir = pinyinDir()
        val dictionary = File(dir, LIBIME_DICTIONARY)
        val history = File(dir, LIBIME_HISTORY)
        if (!dictionary.exists() && !history.exists()) return null
        return LibimeImport.Legacy(
            libime(dictionary, LibimeFiles::pinyinDictionary),
            libime(history, LibimeFiles::history),
            { LibimeFiles.spell(it).orEmpty() },
        ).also { Timber.i("libime's pinyin: %d words, %d sentences", it.dictionary.size, it.history.size) }
    }

    /**
     * The custom phrases and the dictionaries turned on in [dir], fcitx's pinyin data directory, as
     * the editors keep them; a file that cannot be read is left out, the rest still read. The
     * dictionaries are read only when the engine asks, and again only when one of them changed;
     * libime's are turned into text first.
     */
    internal fun additions(dir: File): Engines.Additions {
        val phraseFile = File(dir, "customphrase")
        // what the keyboard changed just before is in the file as it is read again
        phraseSaves.submit(Runnable {}).get()
        var read = true
        val phrases = try {
            phraseFile.takeIf { it.isFile }?.readText().orEmpty()
        } catch (e: IOException) {
            Timber.w(e, "custom phrases")
            read = false
            ""
        }
        val dictionaryDir = File(dir, "dictionaries")
        ImportedDictionaries.migrate(dictionaryDir)
        val dictionaries = dictionaryDir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
        val seen = dictionaries.joinToString("\n") { "${it.name} ${it.length()} ${it.lastModified()}" }
        val save = PhraseFile(phraseFile, phrases, read, phraseSaves)::save
        // one that cannot be read is left out, the rest still read; then the first failure is
        // thrown, for the engine to know a dictionary is missing: removing a word it may list
        // forgets nothing then (UserLexicon.selfMade)
        fun read() = sequence {
            var failure: IOException? = null
            for (file in dictionaries) {
                val lines = try {
                    file.readLines()
                } catch (e: IOException) {
                    Timber.w(e, "pinyin dictionary %s", file.name)
                    if (failure == null) failure = e
                    emptyList()
                }
                yieldAll(lines)
            }
            failure?.let { throw it }
        }
        return Engines.Additions(
            phrases = phrases,
            dictionaries = seen,
            savePhrases = save,
            dictionary = { read() },
        )
    }

    /**
     * Saves [phrases], the engine's, as what it changed since [base] (those it read or last saved)
     * applied to [file] as it is now: the editor may have saved since, its reload of the engine
     * not run, and what it saved is kept.
     */
    internal fun savePhrases(base: CustomPhrases, phrases: CustomPhrases, file: File) {
        CustomPhraseManager.saveKeysOver(CustomPhraseManager.items(base), CustomPhraseManager.items(phrases), file)
    }

    // the engine's phrases saved off the fcitx thread, which takes the keys too; one at a time, in order
    private val phraseSaves = Executors.newSingleThreadExecutor { Thread(it, "engine-phrases") }

    /**
     * The custom phrases the engine read from [file] as [text] ([read] false if it could not),
     * saved there as the keyboard changes them ([savePhrases]), on [saves]: what the engine has
     * as last saved, the base of the next, is that thread's.
     */
    internal class PhraseFile(private val file: File, private val text: String, private val read: Boolean, private val saves: Executor) {
        private var base: CustomPhrases? = null

        fun save(phrases: CustomPhrases) {
            // a file not read is not written: the phrases it has would be lost to the one pinned
            if (!read) throw IOException("custom phrases were not read")
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

    /**
     * One of libime's files, as [read] gives it back. One that cannot be read fails the whole
     * import, which is tried again next time: a reader fixed in an update still gets what the
     * user's pinyin learned.
     */
    private fun libime(file: File, read: (ByteArray) -> List<String>): List<String> =
        if (file.exists()) read(file.readBytes()) else emptyList()

    // mapped where it lies in the APK (stored uncompressed): nothing copied, and pages the OS may
    // drop. Signed with the APK, so the checksums need not be read through.
    private fun asset(path: String): ByteBuffer = appContext.assets.openFd(path).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length) }
    }

    /**
     * Whether the input method of the last [onEvent] has refining left, its snapshot's
     * [refines][Snapshot.refines]: the addon then sends it [EngineEvent.REFINE] while the user
     * pauses. Written on the fcitx thread once an event is answered, and left as it was while one
     * runs: read off that thread during a refining step (EngineEvalRunner), it is still true.
     */
    @Volatile
    var refining = false
        private set

    /** [learning] is false in a password or other sensitive field: nothing typed there is kept. */
    @JvmStatic
    fun onEvent(im: String, event: Int, arg: Int, learning: Boolean): Result =
        answer(im, event, { engines.onEvent(im, event, arg, learning) }) { from, count -> engines.candidates(im, from, count) }

    /** [onEvent] of [im], whose engine [call] answers [event], [fetch] giving its candidates. */
    internal fun answer(im: String, event: Int, call: () -> Snapshot, fetch: (from: Int, count: Int) -> List<Choice>): Result {
        if (im in unloadable) {
            refining = false
            return unhandled
        }
        val answered = try {
            val s = unloadable.call(im, call)
            if (im == Engines.T9 && (event != EngineEvent.REFINE || s.handled)) t9State.value = T9State(s.preedit.isNotEmpty(), s.syllables, s.syllablesId)
            if (im == Engines.ENGLISH) englishTypingState.value = s.preedit.isNotEmpty() && !s.predicting
            followed(im) { engines }
            result(event, s, fetch)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // the addon refines no more after a failure (native-lib's engineFailed)
            refining = false
            // nor is a word being spelled: 1 left an apostrophe with nothing typed
            if (im == Engines.ENGLISH) englishTypingState.value = false
            throw e
        }
        refining = answered.refines
        return answered
    }

    /**
     * [s] as the addon reads it after [event]: [candidatesSent] of its candidates, from the page's
     * first, as [fetch] gives them; the addon numbers them from [Result.first] (EngineCandidateList).
     */
    internal fun result(event: Int, s: Snapshot, fetch: (from: Int, count: Int) -> List<Choice>): Result {
        val count = candidatesSent(event, s)
        val all = if (count == 0) emptyList() else fetch(s.first, count)
        return Result(
            s.handled, s.commit, s.preedit,
            all.map { it.text }.toTypedArray(), all.map { it.hint }.toTypedArray(),
            s.first, s.candidates.size, s.total, s.actionable, s.labels, s.refines, s.deleteBefore,
            // the addon counts code points: a character past the BMP in the pieces picked is one
            if (s.cursor < 0) -1 else s.preedit.codePointCount(0, minOf(s.cursor, s.preedit.length)),
        )
    }

    /**
     * How many of [s]'s candidates, from the page's first, go to the addon after [event]: the page
     * shown and at least a chunk, so the addon seldom asks for more; none when a refining step
     * changed nothing, which the addon does not show.
     */
    internal fun candidatesSent(event: Int, s: Snapshot): Int =
        if (s.candidates.isEmpty() || (event == EngineEvent.REFINE && !s.handled)) 0 else maxOf(CHUNK, s.candidates.size)

    /**
     * The addon's config, flattened as [EngineSettings.parse] reads it, with the nine keys' letters
     * the keyboard draws (the app's preference, read again by [reload]); on the fcitx thread too.
     */
    @JvmStatic
    fun configure(settings: String) {
        engines.settings = EngineSettings.parse(settings).copy(nineKeys = nineKeys())
        shuangpinScheme = engines.settings.scheme
        englishSuggests = engines.settings.englishSuggest
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

    // the acts on a candidate the addon shows itself, as it shows a long press's; what only reads
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

    /** Whether [im] is one of the engine's input methods, whose long press the app words (U-82). */
    fun isEngines(im: String) = im.startsWith(ENGINE) || im == Engines.ENGLISH

    private fun nineKeys() = NineKeyLetters.parse(AppPrefs.getInstance().keyboard.t9Letters.getValue())

    /**
     * Reads [additions] and the nine keys' letters again, the sessions made anew; nothing to do
     * while the engine is not made. An input method that could not load is tried again.
     */
    @JvmStatic
    fun reload() {
        unloadable.clear()
        made?.let { it.settings = it.settings.copy(nineKeys = nineKeys()) }
        made?.reload()
    }

    /** See [Engines.context]; nothing to tell while the engine is not made. True if [im] offers words now, for a RECALL key (Engines.context) to show. */
    @JvmStatic
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
        fieldFlags = learnFlags
        val engines = made ?: return false
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
        if (im == Engines.HANDWRITING) inkEnded.value = engines().inkEnded
    }

    /** See [Engines.tableKeys]; none while the engine is not made, no session yet, nor is it made to answer: the keyboard waits. */
    fun tableKeys(im: String): Set<Char> = made?.tableKeys(im).orEmpty()

    /** Text and hint of each candidate in [from, from + count), one after the other. */
    @JvmStatic
    fun candidates(im: String, from: Int, count: Int): Array<String> =
        engines.candidates(im, from, count).flatMap { listOf(it.text, it.hint) }.toTypedArray()

    /** What a long press on the [index]th of [im]'s candidates offers: a bit for each [Offer]. */
    @JvmStatic
    fun offers(im: String, index: Int): Int = offerBits(engines.offers(im, index))

    /** [offers] as the addon reads them (androidengine_public.h's EngineOffer): a bit for each. */
    internal fun offerBits(offers: Set<Offer>): Int = offers.sumOf { it.bit }

    // as when the engine fails: the key is the app's, the panel cleared
    private val unhandled = Result(false, "", "", emptyArray(), emptyArray(), 0, 0, 0, false, "", false)

    internal const val CHUNK = 32

    // the addon's input methods (androidengine), but English, which is keyboard-us's
    private const val ENGINE = "engine-"
    private const val LOG_TAG = "youmo-learn"
    private const val MS_PER_MINUTE = 60_000L
    private const val LIBIME_DICTIONARY = "user.dict"
    private const val LIBIME_HISTORY = "user.history"
    // OpenCC 1.2.0's character tables, in the APK's assets: see simplifier()
    private const val TRADITIONAL_TABLE = "learn/TSCharacters.txt"
    private const val SIMPLIFIED_TABLE = "learn/STCharacters.txt"
}
