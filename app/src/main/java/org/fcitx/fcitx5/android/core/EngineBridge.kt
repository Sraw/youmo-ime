/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.restoreDirectory
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.UnreadableInputMethod
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Where the androidengine addon (native) reaches ime-core's input methods. Called only on the
 * fcitx thread, through JNI: the sessions are that thread's alone, so nothing here locks.
 */
object EngineBridge {

    /**
     * A snapshot as the addon reads it, field by field. [candidates] run from the first of all,
     * through the page shown ([shown] of them from [first]), so the list the addon builds indexes
     * as the session does. [actionable]: whether a long press on one may offer something, asked
     * of [offers] then. [labels]: the keys picking those shown, empty for the digits. [refines]:
     * whether the addon is to send [EngineEvent.REFINE] while the user pauses.
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
    )

    /**
     * What the nine-key keyboard shows beside its keys, as the last event of [Engines.T9] left
     * it: whether anything is typed, what its first digits not yet taken may be, and which list
     * that is (see Snapshot.syllables, syllablesId). Set on the fcitx thread, read on the main one.
     */
    data class T9State(val composing: Boolean = false, val syllables: List<String> = emptyList(), val id: Int = 0)

    private val t9State = MutableStateFlow(T9State())
    val t9: StateFlow<T9State> = t9State

    private val made = lazy(LazyThreadSafetyMode.NONE) {
        val userDir = File(appContext.filesDir, "engine")
        // before the engine makes it anew: a user-data import killed between its two renames left
        // the engine's files only in engine.old, which the next import would delete
        try {
            restoreDirectory(userDir)
        } catch (e: IOException) {
            Timber.w(e, "engine user data")
        }
        Engines(
            ::asset, userDir, { Timber.w(it, "engine user data") }, ::legacy, ::additions, ::userTable, NativeMatrixKernel,
        )
    }
    /** Made when first used; on the fcitx thread only, as the addon uses it there. */
    val engines by made

    // what cannot load is not loaded again on every key: the keys go to the app, the user told once
    private val unloadable = Unloadable { im, e ->
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

        // an asset or table that cannot be opened or is none, or a table the user imported that cannot be
        // read; not memory run out mapping one, FileChannel.map's IOException "Map failed", even as the
        // cause of an UnreadableInputMethod
        private fun cannotLoad(e: Throwable) =
            (e is IOException || e is DataFormatException || e is UnreadableInputMethod) && chain(e).none { it is VirtualMachineError }

        // [e] and its causes, each once: initCause refuses only a throwable itself, so a chain may loop back
        private fun chain(e: Throwable): Sequence<Throwable> {
            val seen = HashSet<Throwable>()
            return generateSequence(e) { it.cause }.takeWhile(seen::add)
        }
    }

    // fcitx's data home, as Fcitx starts it: where libime kept its files, and where the app's
    // editors still keep what the user adds
    private fun fcitxHome(): File {
        val context = FcitxApplication.getInstance().directBootAwareContext
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    private fun dataDir() = File(fcitxHome(), "data")

    private fun pinyinDir() = File(dataDir(), "pinyin")

    /**
     * The table input method [im] the user imported (TableManager), as fcitx's table addon had it:
     * `inputmethod/<im>.conf`, naming its table (libime's, or text), and what the user set of it,
     * kept in fcitx's config home as `table/<im>.conf`.
     */
    private fun userTable(im: String): Engines.UserTable? {
        val data = dataDir()
        val conf = File(data, "inputmethod/$im.conf").takeIf { it.isFile } ?: return null
        return Engines.UserTable(
            conf.readText(),
            { File(data, it).let { dict -> "${dict.length()} ${dict.lastModified()}" } },
            { tableText(File(data, it)) },
            File(fcitxHome(), "config/table/$im.conf").takeIf { it.isFile }?.readText().orEmpty(),
        )
    }

    /** A table the user imported: libime's, as saved before the engine, or its text. */
    private fun tableText(file: File): BufferedReader {
        val bytes = file.readBytes()
        return if (LibimeFiles.isTable(bytes)) LibimeFiles.table(bytes).reader().buffered() else bytes.inputStream().bufferedReader()
    }

    /** What libime's pinyin learned, where fcitx kept it (FCITX_DATA_HOME), as text. */
    private fun legacy(): LibimeImport.Legacy? {
        val dir = pinyinDir()
        val dictionary = File(dir, "user.dict")
        val history = File(dir, "user.history")
        if (!dictionary.exists() && !history.exists()) return null
        return LibimeImport.Legacy(
            libime(dictionary, LibimeFiles::pinyinDictionary),
            libime(history, LibimeFiles::history),
            { LibimeFiles.spell(it).orEmpty() },
        ).also { Timber.i("libime's pinyin: %d words, %d sentences", it.dictionary.size, it.history.size) }
    }

    /**
     * The custom phrases and the dictionaries turned on, as the editors keep them; a file that
     * cannot be read is left out, the rest still read. The dictionaries are read only when the
     * engine asks, and again only when one of them changed; libime's are turned into text first.
     */
    private fun additions(): Engines.Additions {
        val dir = pinyinDir()
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
        val packs = dictionaryDir.listFiles { f -> f.name.endsWith(".words") }.orEmpty().sortedBy { it.name }
        val intoNew = ImportedDictionaries.intoNew(dictionaryDir)
        fun File.intoNew() = PinyinDictionary.nameOf(name) in intoNew
        // a dictionary moved to another layer is a change too
        val seen = (dictionaries + packs).joinToString("\n") { "${it.name} ${it.length()} ${it.lastModified()} ${it.intoNew()}" }
        val save = PhraseFile(phraseFile, phrases, read, phraseSaves)::save
        val readPacks = {
            packs.mapNotNull { file ->
                try {
                    Engines.Pack(file.name, file.readLines().asSequence(), file.intoNew())
                } catch (e: IOException) {
                    Timber.w(e, "word pack %s", file.name)
                    null
                }
            }
        }
        fun read(files: List<File>) = files.flatMap { file ->
            try {
                file.readLines()
            } catch (e: IOException) {
                Timber.w(e, "pinyin dictionary %s", file.name)
                emptyList()
            }
        }
        val (newDictionaries, baseDictionaries) = dictionaries.partition { it.intoNew() }
        return Engines.Additions(phrases, seen, save, readPacks, { read(newDictionaries) }) { read(baseDictionaries) }
    }

    /**
     * Saves [phrases], the engine's, as what it changed since [base] (those it read or last saved)
     * applied to [file] as it is now: the editor may have saved since, its reload of the engine
     * not run, and what it saved is kept.
     */
    internal fun savePhrases(base: CustomPhrases, phrases: CustomPhrases, file: File) {
        fun CustomPhrases.items() = all.map { PinyinCustomPhrase(it.key, it.order, it.value) }
        CustomPhraseManager.saveKeysOver(base.items(), phrases.items(), file)
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

    /** [learning] is false in a password or other sensitive field: nothing typed there is kept. */
    @JvmStatic
    fun onEvent(im: String, event: Int, arg: Int, learning: Boolean): Result {
        if (im in unloadable) return unhandled
        val s = unloadable.call(im) { engines.onEvent(im, event, arg, learning) }
        if (im == Engines.T9 && (event != EngineEvent.REFINE || s.handled)) t9State.value = T9State(s.preedit.isNotEmpty(), s.syllables, s.syllablesId)
        // the page shown and at least a chunk: the list rarely has to come back for more; none
        // for a slice of refining that changed nothing, which the addon does not show
        val unshown = s.candidates.isEmpty() || (event == EngineEvent.REFINE && !s.handled)
        val all = if (unshown) emptyList() else engines.candidates(im, 0, maxOf(CHUNK, s.first + s.candidates.size))
        return Result(
            s.handled, s.commit, s.preedit,
            all.map { it.text }.toTypedArray(), all.map { it.hint }.toTypedArray(),
            s.first, s.candidates.size, s.total, s.actionable, s.labels, s.refines,
        )
    }

    /** The addon's config, flattened as [EngineSettings.parse] reads it; on the fcitx thread too. */
    @JvmStatic
    fun configure(settings: String) {
        engines.settings = EngineSettings.parse(settings)
    }

    /**
     * Reads [additions] and the tables the user imported again, the sessions made anew; nothing to
     * do before the engine is first used. An input method that could not load is tried again.
     */
    @JvmStatic
    fun reload() {
        unloadable.clear()
        if (made.isInitialized()) engines.reload()
    }

    /** See [Engines.context]; nothing to tell before the engine is first used. */
    @JvmStatic
    fun context(before: String) {
        if (made.isInitialized()) engines.context(before)
    }

    /** Text and hint of each candidate in [from, from + count), one after the other. */
    @JvmStatic
    fun candidates(im: String, from: Int, count: Int): Array<String> =
        engines.candidates(im, from, count).flatMap { listOf(it.text, it.hint) }.toTypedArray()

    /** What a long press on the [index]th of [im]'s candidates offers: a bit for each [Offer], by its order. */
    @JvmStatic
    fun offers(im: String, index: Int): Int = engines.offers(im, index).sumOf { 1 shl it.ordinal }

    // as when the engine fails: the key is the app's, the panel cleared
    private val unhandled = Result(false, "", "", emptyArray(), emptyArray(), 0, 0, 0, false, "", false)

    private const val CHUNK = 32
}
