/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineNotices
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.pack.AppData
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.pack.PackUnavailableException
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.handwriting.InkNet
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.handwriting.InkRecognizer
import org.fcitx.fcitx5.android.handwriting.OrtModel
import org.fcitx.fcitx5.android.pack.PackKind
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Runs on its own [worker] thread, readings handed on on [main]. [shared]'s two models, mapped from the
 * text pack, open as the keyboard is first shown ([warmUp]). A newer read of a piece makes its waiting
 * ones stale; an earlier piece's last read is not (it is what that piece commits). Reads of a piece the
 * engine [ended] are dropped.
 */
class HandwritingRecognition internal constructor(
    private val worker: Executor,
    private val main: Executor,
    private val ended: () -> Int,
    // a JVM test's reader of fixed readings in place of the native model
    @get:VisibleForTesting internal var reader: Reader,
) {

    /** On the worker. */
    internal interface Reader {
        /** False once it could not be loaded, till another text pack is imported. Read on the main thread. */
        val loadable: Boolean

        fun load()

        /** Null if they cannot be read. */
        fun read(strokes: List<FloatArray>): InkReading?
    }

    // an older read of the piece still waiting is not run
    private val newest = ConcurrentHashMap<Int, Int>()

    // main thread: reads not handed on yet, and actions held behind them
    private var pending = 0
    private var held = 0

    /** A read no longer its piece's newest when its turn comes is dropped, as is a failure. Asked on the main thread. */
    fun read(read: InkPad.Read, done: (InkReading) -> Unit) {
        if (!reader.loadable) return
        newest[read.piece] = read.id
        pending++
        worker.execute {
            var reading: InkReading? = null
            try {
                reading = recognize(read)
            } finally {
                // counted off whatever was thrown: else keys would wait on it for good
                val r = reading
                main.execute {
                    r?.let(done)
                    pending--
                }
            }
        }
    }

    /** Main thread. */
    val loadable: Boolean get() = reader.loadable

    /** Main thread. */
    val busy: Boolean get() = pending > 0 || held > 0

    /** Asked on the main thread. */
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

    /** No read, so it holds no key behind it. */
    fun warmUp() {
        if (reader.loadable) worker.execute { reader.load() }
    }

    private fun recognize(read: InkPad.Read): InkReading? {
        // run, and forgotten unless a newer came meanwhile
        if (!newest.remove(read.piece, read.id) || read.piece <= ended()) return null
        return if (read.strokes.isEmpty()) InkReading.NONE else reader.read(read.strokes)
    }

    companion object {
        val shared: HandwritingRecognition by lazy {
            val main = Handler(Looper.getMainLooper())
            HandwritingRecognition(
                Executors.newSingleThreadExecutor { Thread(it, "handwriting").apply { isDaemon = true } },
                Executor { main.post(it) },
                { EngineState.handwritingEnded.value },
                LoadingReader(AssetRecognizer::open, AssetRecognizer::told, ::textPack),
            ).also { made = true }
        }

        // a JVM test's wait for its reads does not make it
        @Volatile
        private var made = false

        /** A JVM test's main looper not idle till its reads are in; main thread. */
        @VisibleForTesting
        internal val sharedBusy: Boolean get() = made && shared.busy

        /** Not PackStore's generation, which a voice pack import counts up too (reloading for nothing). */
        internal fun textPack(): Any? = PackStore.dir(PackKind.TEXT)
    }
}

/**
 * Loaded at its first load and kept. What cannot load is not retried at every stroke: the pad draws on
 * and the user is [told] once. Without the text pack it is neither loaded nor given up: the gate says
 * what is missing. Once [pack] changes it is let go and loaded anew at the next read.
 */
internal class LoadingReader(
    private val open: (opened: MutableList<AutoCloseable>) -> InkRecognizer,
    private val told: () -> Unit,
    private val pack: () -> Any? = { null },
) : HandwritingRecognition.Reader {

    private val models = ArrayList<AutoCloseable>()
    private var recognizer: InkRecognizer? = null

    @Volatile
    private var broken = false

    // NotLoaded till the first load
    @Volatile
    private var of: Any? = NotLoaded

    override val loadable: Boolean get() = !broken || pack() != of

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
        val now = pack()
        if (now != of) {
            // a pack imported since: what was loaded maps the old one's files, what was given up may load now
            if (of !== NotLoaded) letGo()
            broken = false
            of = now
        }
        if (broken) return null
        recognizer?.let { return it }
        return try {
            open(models).also { recognizer = it }
        } catch (e: PackUnavailableException) {
            // no text pack (yet): nothing to tell, the keyboard's gate says it; tried again at the next read
            Timber.i("handwriting: %s", e.message)
            letGo()
            null
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // a file missing or not the recognizer's, or a model this onnxruntime refuses (written by a newer one)
            cannotLoad(e)
        } catch (e: LinkageError) {
            // the native library not loaded (ExceptionInInitializerError is one too): the IME lives on without handwriting
            cannotLoad(e)
        }
    }

    private fun cannotLoad(e: Throwable): Nothing? {
        Timber.e(e, "handwriting cannot load: not tried again till the app restarts or a text pack is imported")
        broken = true
        // whether it failed loading or reading (a LinkageError): not held open for the process
        letGo()
        told()
        return null
    }

    private fun letGo() {
        recognizer = null
        // closing is native too, and may throw after a LinkageError: the rest closed all the same
        models.forEach { model -> runCatching { model.close() }.onFailure { Timber.w(it, "handwriting model close") } }
        models.clear()
    }
}

/** Not left on drawing ink that reads nothing. Any thread: the switch's listeners hear it on the main one. */
internal fun handwritingCannotLoad() = AppPrefs.getInstance().internal.handwritingOpen.setValue(false)

/** Internal: HandwritingUatTest's failing reader tells the user as this one does ([told]). */
internal object AssetRecognizer {

    fun open(opened: MutableList<AutoCloseable>): InkRecognizer = InkRecognizer(
        net("char_image", opened), net("char_path", opened),
        chars = AppData.open("$DIR/chars.txt").use { it.readBytes().toString(Charsets.UTF_8) },
        prior = prior(),
    )

    fun told() {
        EngineNotices.failed(R.string.engine_unloadable)
        handwritingCannotLoad()
    }

    // opened at once: one onnxruntime refuses is found as the recognizer loads
    private fun net(name: String, opened: MutableList<AutoCloseable>): InkNet {
        val model = OrtModel.open(AppData.map("$DIR/$name.ort"), THREADS).also(opened::add)
        return InkNet { x, shape -> model.run(x, shape).values }
    }

    // float32 little-endian
    private fun prior(): FloatArray {
        val b = ByteBuffer.wrap(AppData.open("$DIR/prior.data").use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.remaining() / Float.SIZE_BYTES) { b.getFloat() }
    }

    private const val DIR = "handwriting"

    // some 20 ms a run on one desktop core
    private const val THREADS = 2
}

private object NotLoaded
