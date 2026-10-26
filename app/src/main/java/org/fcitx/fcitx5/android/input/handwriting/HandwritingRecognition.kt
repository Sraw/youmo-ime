/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.EngineNotices
import org.fcitx.fcitx5.android.engine.handwriting.InkNet
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.handwriting.InkRecognizer
import org.fcitx.fcitx5.android.handwriting.OrtModel
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The handwriting recognizer, run on a thread of its own ([worker]), its readings handed on on
 * the main thread ([main]). [shared] is the app's: its two models (the character's image and its
 * path), mapped from the APK, are opened as the keyboard is first shown ([warmUp]), and kept. Of a
 * piece's reads, a newer one makes those still waiting stale: they are skipped. An earlier piece's
 * last read is not: it is what that piece shows, and commits. A read of a piece the engine ended
 * ([ended], the latest) is dropped.
 */
class HandwritingRecognition internal constructor(
    private val worker: Executor,
    private val main: Executor,
    private val ended: () -> Int,
    private val reader: Reader,
) {

    /** What reads the strokes, on the worker. */
    internal interface Reader {
        /** False once it could not be loaded, for good: nothing is asked of it then. Read on the main thread. */
        val loadable: Boolean

        /** Loaded, if it is not yet. */
        fun load()

        /** [strokes] read, loaded first if need be; null if they cannot be. */
        fun read(strokes: List<FloatArray>): InkReading?
    }

    // each piece's newest read asked for: an older one of the piece still waiting is not run
    private val newest = ConcurrentHashMap<Int, Int>()

    // on the main thread: reads asked for whose reading is not handed on yet, and actions held behind them
    private var pending = 0
    private var held = 0

    /**
     * Reads [read] on the worker and hands the reading to [done] on the main thread; no strokes are
     * read as nothing. A read no longer its piece's newest when its turn comes is dropped, as is
     * one of a piece the engine ended, and one that fails. Asked on the main thread.
     */
    fun read(read: InkPad.Read, done: (InkReading) -> Unit) {
        if (!reader.loadable) return
        newest[read.piece] = read.id
        pending++
        worker.execute {
            var reading: InkReading? = null
            try {
                reading = recognize(read)
            } finally {
                // handed on, or not, but counted off whatever was thrown: else keys would wait on it for good
                val r = reading
                main.execute {
                    r?.let(done)
                    pending--
                }
            }
        }
    }

    /** Whether a reading asked for is not handed on yet, or an action held behind one not run; on the main thread. */
    val busy: Boolean get() = pending > 0 || held > 0

    /** [action] on the main thread once the readings asked for so far are handed on; asked on the main thread. */
    fun afterReads(action: () -> Unit) {
        held++
        worker.execute {
            main.execute {
                try {
                    action()
                } finally {
                    held--
                }
            }
        }
    }

    /** The recognizer loaded before a read needs it (the keyboard shown); no read, it holds no key behind it. */
    fun warmUp() {
        if (reader.loadable) worker.execute { reader.load() }
    }

    private fun recognize(read: InkPad.Read): InkReading? {
        // its piece's newest: run, and forgotten unless a newer came meanwhile
        if (!newest.remove(read.piece, read.id) || read.piece <= ended()) return null
        return if (read.strokes.isEmpty()) InkReading.NONE else reader.read(read.strokes)
    }

    companion object {
        /** The app's, reading with the recognizer of the app's assets. */
        val shared: HandwritingRecognition by lazy {
            val main = Handler(Looper.getMainLooper())
            HandwritingRecognition(
                Executors.newSingleThreadExecutor { Thread(it, "handwriting").apply { isDaemon = true } },
                Executor { main.post(it) },
                { EngineBridge.handwritingEnded.value },
                LoadingReader(AssetRecognizer::open, AssetRecognizer::told),
            )
        }
    }
}

/**
 * The recognizer [open] makes, on the worker only: loaded at its first load, and kept. What cannot be
 * loaded (its library, its files) is not tried again at every stroke: the pad draws on with nothing
 * read, and the user is [told] once. [open] adds what it opens to the list it is given, closed if
 * the recognizer is of no use.
 */
internal class LoadingReader(
    private val open: (opened: MutableList<AutoCloseable>) -> InkRecognizer,
    private val told: () -> Unit,
) : HandwritingRecognition.Reader {

    private val models = ArrayList<AutoCloseable>()
    private var recognizer: InkRecognizer? = null

    @Volatile
    private var broken = false

    override val loadable: Boolean get() = !broken

    override fun load() {
        loaded()
    }

    override fun read(strokes: List<FloatArray>): InkReading? {
        val recognizer = loaded() ?: return null
        return try {
            recognizer.character(strokes)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // onnxruntime's failures come as IllegalStateException: this read's alone, the next one run
            Timber.w(e, "handwriting recognition")
            null
        } catch (e: LinkageError) {
            cannotLoad(e)
        }
    }

    private fun loaded(): InkRecognizer? {
        if (broken) return null
        recognizer?.let { return it }
        return try {
            open(models).also { recognizer = it }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // a file missing (IOException) or not the recognizer's (IllegalArgumentException), a model this
            // onnxruntime refuses (IllegalStateException: one a newer one wrote); one opened before is of no use
            cannotLoad(e)
        } catch (e: LinkageError) {
            // the native library not loaded (ExceptionInInitializerError is one too): the IME lives on without handwriting
            cannotLoad(e)
        }
    }

    private fun cannotLoad(e: Throwable): Nothing? {
        Timber.e(e, "handwriting cannot load: not tried again till the app restarts")
        broken = true
        // of no use from now on, whether it failed loading or reading (a LinkageError): not held open for the process
        recognizer = null
        // closing is native too, and may throw after a LinkageError: the rest closed and the user told all the same
        models.forEach { model -> runCatching { model.close() }.onFailure { Timber.w(it, "handwriting model close") } }
        models.clear()
        told()
        return null
    }
}

/** The recognizer of the app's assets: its models opened and its files read as it loads. */
private object AssetRecognizer {

    fun open(opened: MutableList<AutoCloseable>): InkRecognizer = InkRecognizer(
        net("char_image", opened), net("char_path", opened),
        chars = appContext.assets.open("$DIR/chars.txt").use { it.readBytes().toString(Charsets.UTF_8) },
        prior = prior(),
    )

    fun told() {
        EngineNotices.failed(R.string.engine_unloadable)
    }

    // opened at once, not at its first run: one onnxruntime refuses is found as the recognizer loads
    private fun net(name: String, opened: MutableList<AutoCloseable>): InkNet {
        val model = OrtModel.open(appContext.assets, "$DIR/$name.ort", THREADS).also(opened::add)
        return InkNet { x, shape -> model.run(x, shape).values }
    }

    // each character's log frequency, float32 little-endian
    private fun prior(): FloatArray {
        val b = ByteBuffer.wrap(appContext.assets.open("$DIR/prior.data").use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.remaining() / Float.SIZE_BYTES) { b.getFloat() }
    }

    private const val DIR = "handwriting"

    // a character's two models are some 20 ms a run on one desktop core: two of the phone's
    private const val THREADS = 2
}
