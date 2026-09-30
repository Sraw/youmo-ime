/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Where the androidengine addon (native) reaches ime-core's input methods. Called only on the
 * fcitx thread, through JNI: the sessions are that thread's alone, so nothing here locks.
 */
object EngineBridge {

    /**
     * A snapshot as the addon reads it, field by field. [candidates] run from the first of all,
     * through the page shown ([shown] of them from [first]), so the list the addon builds indexes
     * as the session does. [forgets]: whether "Forget word" is offered on them. [labels]: the
     * keys picking those shown, empty for the digits.
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
        @JvmField val forgets: Boolean,
        @JvmField val labels: String,
    )

    private val made = lazy(LazyThreadSafetyMode.NONE) {
        Engines(::asset, File(appContext.filesDir, "engine"), { Timber.w(it, "engine user data") }, ::legacy, ::additions, ::userTable)
    }
    private val engines by made

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
     * cannot be read is left out, the rest still read. The dictionaries are converted only when
     * the engine asks, and again only when one of them changed.
     */
    private fun additions(): Engines.Additions {
        val dir = pinyinDir()
        val phrases = try {
            File(dir, "customphrase").takeIf { it.isFile }?.readText().orEmpty()
        } catch (e: IOException) {
            Timber.w(e, "custom phrases")
            ""
        }
        val dictionaries = File(dir, "dictionaries").listFiles { f -> f.name.endsWith(".dict") }.orEmpty()
            .sortedBy { it.name }
        val seen = dictionaries.joinToString("\n") { "${it.name} ${it.length()} ${it.lastModified()}" }
        return Engines.Additions(phrases, seen) {
            dictionaries.flatMap { file ->
                try {
                    LibimeFiles.pinyinDictionary(file.readBytes())
                } catch (e: IOException) {
                    Timber.w(e, "pinyin dictionary")
                    emptyList()
                } catch (e: DataFormatException) {
                    Timber.w(e, "pinyin dictionary %s", file.name)
                    emptyList()
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
        val s = engines.onEvent(im, event, arg, learning)
        // the page shown and at least a chunk: the list rarely has to come back for more
        val all = if (s.candidates.isEmpty()) emptyList() else engines.candidates(im, 0, maxOf(CHUNK, s.first + s.candidates.size))
        return Result(
            s.handled, s.commit, s.preedit,
            all.map { it.text }.toTypedArray(), all.map { it.hint }.toTypedArray(),
            s.first, s.candidates.size, s.total, s.forgets, s.labels,
        )
    }

    /** The addon's config, flattened as [EngineSettings.parse] reads it; on the fcitx thread too. */
    @JvmStatic
    fun configure(settings: String) {
        engines.settings = EngineSettings.parse(settings)
    }

    /**
     * Reads [additions] and the tables the user imported again, the sessions made anew; nothing to
     * do before the engine is first used.
     */
    @JvmStatic
    fun reload() {
        if (made.isInitialized()) engines.reload()
    }

    /** Text and hint of each candidate in [from, from + count), one after the other. */
    @JvmStatic
    fun candidates(im: String, from: Int, count: Int): Array<String> =
        engines.candidates(im, from, count).flatMap { listOf(it.text, it.hint) }.toTypedArray()

    private const val CHUNK = 32
}
