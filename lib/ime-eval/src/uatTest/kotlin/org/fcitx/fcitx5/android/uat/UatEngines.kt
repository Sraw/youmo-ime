/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.platform.Clock as EngineClock
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.runtime.BackgroundWork
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.host.EngineOptions
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
    object Clock : EngineClock {
        @Volatile private var shift = 0L

        override fun now() = System.currentTimeMillis() + shift
        override fun offset(at: Long) = JvmClock.offset(at)

        // the system's, as the engines' own would be, moved on by a replay's user away ([away]): the
        // engines' timing (a table's space taken after a commit) sees the time pass as the host does
        @Volatile private var ahead = 0L

        override fun uptimeMillis() = JvmClock.uptimeMillis() + ahead

        fun away(millis: Long) {
            ahead += millis
        }

        fun days(n: Int) {
            shift += n * LearnClock.DAY
        }

        fun reset() {
            shift = 0L
            ahead = 0L
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

    /** The app's pinyin data, as the engines map it: what a case reads off it directly (a character's tones). */
    val pinyinData: PinyinData by lazy { PinyinData.load(load(InputMethodCatalog.PINYIN_DATA)) }

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

    /** The app's data file at [path] (`engine/pinyin.data`), as the engines map it. */
    fun data(path: String): ByteBuffer = mappedOnce(path)

    // where the install's id is kept, beside the user directory as the app's no-backup directory is: a restart keeps it, a fresh install not;
    // removed whole at exit, as deleteOnExit leaves a directory that is not empty
    private val installFile: File = File(tempDir("uat-install"), "install-id")

    /** A temporary directory removed with all in it when the JVM exits. */
    fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { dir ->
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    }

    private fun make() = Engines(
        blobs = ::mappedOnce,
        userDir = JvmFiles(userDir),
        installFile = JvmFile(installFile),
        onError = { synchronized(errors) { errors += it } },
        additions = {
            Engines.Additions(
                phrases = phrases,
                dictionaries = "",
                savePhrases = { phrases = CustomPhrases.format(it.all) },
                dictionary = { dictionary() },
                indexes = indexes?.let(::JvmFiles),
            )
        },
        // on the caller's thread: what a case sees does not depend on a warm-up's timing
        warmUps = Executor(Runnable::run),
        clock = Clock,
    )

    /** The engines of the case running: [fresh]'s. */
    var engines: Engines = make().also { it.settings = BASELINE }
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
        engines = make().also { it.settings = BASELINE }
        return engines
    }

    /**
     * The baselines' settings, B0's: what the app pushes on a new install, its host's fresh options (KotlinHost's EngineOptions, as
     * EngineSettings), read from them so the replays' defaults cannot drift from the device's (for
     * the tables 调频 off, 自动上屏 on, 编码提示 the preset's, not TableOptions' presets). That the app
     * pushes these and adds nothing at their default is EngineBridgeTest's. 双拼's scheme as the
     * baselines that add 双拼 set it (B0-双拼 chooses 自然码, the cases written for its keys), not a new
     * install's 小鹤 (SchemeMigration; KotlinHostSchemeTest.aNewInstallsShuangpinIsXiaohe).
     */
    val BASELINE: EngineSettings
        get() = EngineOptions().toSettings().copy(shuangpin = EngineSettings.ZIRANMA)

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
     * A glide across the centres of [keys] on [layout] ([shift]: Shift on, as TextKeyboard hands one
     * over where it is on), as the engine read it: what the GLIDE key carries. EngineReplay's and
     * HostReplay's one rule, as [ink] is.
     */
    fun glide(engines: Engines, im: String, keys: String, layout: GlideLayout, shift: Boolean = false) =
        checkNotNull(engines.glide(im, Glide(keys.flatMap { layout.centre(it).toList() }.toFloatArray(), layout, shift = shift))) { "$im does not glide" }

    /**
     * The recognizer's [readings] (best first, scored as it ranks them) for a new ink piece, after
     * [last] (the replay's last written) and after what the pad ended: what the Ink key carries.
     * EngineReplay's and HostReplay's one rule, so their handwriting cannot drift apart (F4299).
     */
    fun ink(engines: Engines, readings: List<String>, last: Int): Action.Ink {
        val piece = maxOf(last + 1, engines.inkEnded + 1)
        return Action.Ink(piece, InkReading(readings, DoubleArray(readings.size) { -it.toDouble() })).also { engines.ink(it, shown = true) }
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
