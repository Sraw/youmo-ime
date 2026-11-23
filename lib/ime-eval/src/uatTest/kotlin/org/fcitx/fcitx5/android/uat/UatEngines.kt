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
import org.fcitx.fcitx5.android.engine.runtime.ModelLevel
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

/** One [Engines] over the app's own data (`uat.assets`), shared by every case: read once, each case starting from [fresh] as from `pm clear`. */
object UatEngines {
    /** Learning's clock: the system's, moved on by [days]; back to now on [fresh]. */
    object Clock : EngineClock {
        @Volatile private var shift = 0L

        override fun now() = System.currentTimeMillis() + shift
        override fun offset(at: Long) = JvmClock.offset(at)

        // moved on by a replay's user away ([away]), so engine timing (a table's space after a commit) sees time pass
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

    /** The app's pinyin data, for what a case reads off it directly (a character's tones). */
    val pinyinData: PinyinData by lazy { PinyinData.load(data(InputMethodCatalog.PINYIN_DATA)) }

    fun fixture(name: String): File =
        File(checkNotNull(System.getProperty("uat.fixtures")) { "no uat.fixtures given: run by Gradle (:lib:ime-eval:uatTest)" }, name)

    private val userDir: File = Files.createTempDirectory("uat-user").toFile().also { dir ->
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    }

    /** The custom phrases file (`dz,1=北京市海淀区` a line), read on [Engines.reload]. */
    var phrases: String = ""

    /** The common words file (an export's text), read on [Engines.reload]. */
    var commonWords: String = ""

    /** 生僻字屏蔽's file (`pinyin/rarechars.txt`'s text), read on [Engines.reload]. */
    var rareChars: String = ""

    // what a case's user dir errors were: a case fails on one, as the app would log and go on
    private val errors = ArrayList<IOException>()

    /** Where the tables keep their index of [dictionary]'s words; null for none. */
    var indexes: File? = null

    /** The merged dictionary (the app's merged.ymd), null for none: read at [Engines.reload]. */
    var dictionary: (() -> DictionaryFile?) = { null }

    // each data file mapped once, as the app maps it once: a new Engines reads the same buffers
    private val mapped = HashMap<String, ByteBuffer>()

    private fun mappedOnce(path: String): ByteBuffer = synchronized(mapped) { mapped.getOrPut(path) { load(path) } }.duplicate()

    fun data(path: String): ByteBuffer = mappedOnce(path)

    // the install id beside the user directory, kept over a restart; removed whole at exit, as deleteOnExit skips a non-empty directory
    private val installFile: File = File(tempDir("uat-install"), "install-id")

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
                commonWords = commonWords,
                saveCommonWords = { commonWords = it.format() },
                rareChars = rareChars,
            )
        },
        // on the caller's thread: what a case sees does not depend on a warm-up's timing
        warmUps = Executor(Runnable::run),
        clock = Clock,
    )

    var engines: Engines = make().also { it.settings = BASELINE }
        private set

    /** The engines as `pm clear` leaves them, the mapped data kept; fails if the case before left an error of the user's files. */
    fun fresh(): Engines {
        // the case before's background work (compaction, backup, a table's coding) done first, or it lands in the next
        settle()
        engines.close()
        settle()
        userDir.listFiles()?.forEach { it.deleteRecursively() }
        installFile.delete()
        Clock.reset()
        phrases = ""
        commonWords = ""
        rareChars = ""
        dictionary = { null }
        indexes = null
        val left = synchronized(errors) { errors.toList().also { errors.clear() } }
        check(left.isEmpty()) { "user files: ${left.joinToString { it.toString() }}" }
        engines = make().also { it.settings = BASELINE }
        return engines
    }

    /**
     * B0's settings, read from the host's fresh options so the replays cannot drift from the device's, at the high
     * level (not 自动, which is lower on some phones, the 6T's medium among them: `DeviceProfile.of`); 双拼 is the
     * baselines' 自然码.
     */
    val BASELINE: EngineSettings
        get() = EngineOptions().toSettings(ModelLevel.HIGH).copy(shuangpin = EngineSettings.ZIRANMA)

    fun restart(): Engines {
        val settings = engines.settings
        settle()
        engines.close()
        settle()
        engines = make().also { it.settings = settings }
        return engines
    }

    /** A glide across [keys]' centres on [layout] as the engine read it (what the GLIDE key carries): both replays' one rule, as [ink]. */
    fun glide(engines: Engines, im: String, keys: String, layout: GlideLayout, shift: Boolean = false) =
        checkNotNull(engines.glide(im, Glide(keys.flatMap { layout.centre(it).toList() }.toFloatArray(), layout, shift = shift))) { "$im does not glide" }

    /** The recognizer's [readings] for a new ink piece after [last], over [im]'s keys: both replays' one rule. */
    fun ink(engines: Engines, im: String, readings: List<String>, last: Int): Action.Ink {
        val piece = maxOf(last + 1, engines.inkEnded + 1)
        return Action.Ink(piece, InkReading(readings, DoubleArray(readings.size) { -it.toDouble() })).also { engines.ink(it, shown = true, im = im) }
    }

    /** The learning record ([LearnLog.FILE]) after background work; empty for none yet. */
    fun learningRecord(): ByteArray {
        settle()
        return File(userDir, LearnLog.FILE).takeIf { it.isFile }?.readBytes() ?: ByteArray(0)
    }

    fun settle() {
        val until = System.nanoTime() + SETTLE_NANOS
        while (BackgroundWork.busy > 0) {
            check(System.nanoTime() < until) { "background work still running after 30 s" }
            Thread.sleep(5)
        }
    }

    private const val SETTLE_NANOS = 30_000_000_000L
}
