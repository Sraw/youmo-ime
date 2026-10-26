/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.host.BackgroundWork
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.TableSettings
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.concurrent.Executor

/**
 * One [Engines] over the app's own data (what `:app` compiles into its assets, given by the build
 * as `uat.assets`), shared by every case: the data is read once, a few seconds, and each case
 * starts from [fresh], as a device's case starts from `pm clear`.
 */
object UatEngines {
    /** Learning's clock: the system's, moved on by [days] (a case's typing days, U-42's expiry); back to now on [fresh]. */
    object Clock : LearnClock {
        @Volatile private var shift = 0L

        override fun now() = System.currentTimeMillis() + shift
        override fun offset(at: Long) = LearnClock.System.offset(at)

        fun days(n: Int) {
            shift += n * LearnClock.DAY
        }

        fun reset() {
            shift = 0L
        }
    }


    private val roots: List<File> by lazy {
        val given = checkNotNull(System.getProperty("uat.assets")) { "no uat.assets given: run by Gradle (:lib:ime-eval:uatTest)" }
        given.split(File.pathSeparator).map(::File)
    }

    // a data file by its path in the assets, mapped as the app's openFd maps it
    private fun load(path: String): ByteBuffer {
        val file = roots.map { File(it, path) }.firstOrNull { it.isFile } ?: throw FileNotFoundException(path)
        return RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
    }

    /** A file of uat/fixtures, as a device's case pushes it. */
    fun fixture(name: String): File =
        File(checkNotNull(System.getProperty("uat.fixtures")) { "no uat.fixtures given: run by Gradle (:lib:ime-eval:uatTest)" }, name)

    private val userDir: File = Files.createTempDirectory("uat-user").toFile().also { dir ->
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    }

    /** The custom phrases file, as the app keeps it (`dz,1=北京市海淀区` a line): read by the engine on [Engines.reload]. */
    var phrases: String = ""

    // what a case's user dir errors were: a case fails on one, as the app would log and go on
    private val errors = ArrayList<IOException>()

    /** Where the tables keep their index of [dictionary]'s words, beside it as the app keeps them; null for none. */
    var indexes: File? = null

    /** The merged dictionary the base takes in (the app's merged.ymd), null for none imported: read at [Engines.reload]. */
    var dictionary: (() -> DictionaryFile?) = { null }

    // each data file mapped once, as the app maps it once: a new Engines reads the same buffers
    private val mapped = HashMap<String, ByteBuffer>()

    private fun mappedOnce(path: String): ByteBuffer = synchronized(mapped) { mapped.getOrPut(path) { load(path) } }.duplicate()

    // where the install's id is kept, beside the user directory as the app's no-backup directory is: a restart keeps it, a fresh install not;
    // removed whole at exit, as deleteOnExit leaves a directory that is not empty
    private val installFile: File = File(tempDir("uat-install"), "install-id")

    /** A temporary directory removed with all in it when the JVM exits. */
    fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { dir ->
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    }

    private fun make() = Engines(
        load = ::mappedOnce,
        userDir = userDir,
        installFile = installFile,
        onError = { synchronized(errors) { errors += it } },
        additions = {
            Engines.Additions(
                phrases = phrases,
                dictionaries = "",
                savePhrases = { phrases = CustomPhrases.format(it.all) },
                dictionary = { dictionary() },
                indexes = indexes,
            )
        },
        // on the caller's thread: what a case sees does not depend on a warm-up's timing
        warmUps = Executor(Runnable::run),
        learnClock = Clock,
    )

    /** The engines of the case running: [fresh]'s. */
    var engines: Engines = make().also { it.settings = NEW_INSTALL }
        private set

    /**
     * The engines as a fresh install has them, as `pm clear` leaves the app: a new [Engines] over
     * an empty user directory (one kept would keep its words lists and tables in memory), the
     * data mapped before kept. Fails if the case before left an error of the user's files behind.
     */
    fun fresh(): Engines {
        // what the case before left running (a record's compaction, an automatic backup, a table's
        // coding) done first: written after the wipe, it would be the next case's
        settle()
        engines.close()
        settle()
        userDir.listFiles()?.forEach { it.deleteRecursively() }
        installFile.delete()
        Clock.reset()
        phrases = ""
        dictionary = { null }
        indexes = null
        val left = synchronized(errors) { errors.toList().also { errors.clear() } }
        check(left.isEmpty()) { "user files: ${left.joinToString { it.toString() }}" }
        engines = make().also { it.settings = NEW_INSTALL }
        return engines
    }

    /**
     * What the addon pushes on a new install: its config's defaults, which for the tables are not
     * the presets' (TableOptions): 调频 off for every table (U-28 D7; androidengine.h's
     * EngineTableConfig), 自动上屏 on.
     */
    val NEW_INSTALL: EngineSettings get() = EngineSettings(
        tables = Engines.TABLES.values.associate { it.group to TableSettings(autoSelect = true, orderByUse = false) },
    )

    /** The app started again (a force stop): the engines read the user's files afresh, nothing wiped. */
    fun restart(): Engines {
        val settings = engines.settings
        settle()
        engines.close()
        settle()
        engines = make().also { it.settings = settings }
        return engines
    }

    /**
     * The recognizer's [readings] (best first, scored as it ranks them) offered to [engines] for a new ink
     * piece, after [last] (the replay's last offered) and after what the pad ended: the piece's number.
     * EngineReplay's and HostReplay's one rule, so their handwriting cannot drift apart (F4299).
     */
    fun offerInk(engines: Engines, readings: List<String>, last: Int): Int {
        val piece = maxOf(last + 1, engines.inkEnded + 1)
        engines.offerInk(InkReading(readings, DoubleArray(readings.size) { -it.toDouble() }), piece)
        return piece
    }

    /** The learning record as the engines wrote it ([LearnLog.FILE]), its background work done first; empty for none yet. */
    fun learningRecord(): ByteArray {
        settle()
        return File(userDir, LearnLog.FILE).takeIf { it.isFile }?.readBytes() ?: ByteArray(0)
    }

    /** Until the engines' background work is done, as the device's idle signal waits for it. */
    fun settle() {
        val until = System.nanoTime() + SETTLE_NANOS
        while (BackgroundWork.busy > 0) {
            check(System.nanoTime() < until) { "background work still running after 30 s" }
            Thread.sleep(5)
        }
    }

    private const val SETTLE_NANOS = 30_000_000_000L
}
