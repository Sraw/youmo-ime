/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.engine.handwriting.InkNet
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.handwriting.InkRecognizer
import org.fcitx.fcitx5.android.handwriting.OrtModel
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The handwriting recognizer, run on a thread of their own: its two models (the character's image
 * and its path) are opened, mapped from the APK, when a read first needs them, and kept. Of a piece's reads, a newer one makes those still waiting stale: they are skipped. An
 * earlier piece's last read is not: it is what that piece shows, and commits.
 */
object HandwritingRecognition {

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "handwriting").apply { isDaemon = true } }

    // each piece's newest read asked for: an older one of the piece still waiting is not run
    private val newest = ConcurrentHashMap<Int, Int>()

    // on the worker only
    private val models = HashMap<String, OrtModel>()
    private var recognizer: InkRecognizer? = null

    // the recognizer could not be loaded (its library, its files): not tried again at every stroke
    @Volatile
    private var broken = false

    private val main = Handler(Looper.getMainLooper())

    // on the main thread: reads asked for whose reading is not handed on yet, and actions held behind them
    private var pending = 0
    private var held = 0

    /**
     * Reads [read] on the worker and hands the reading to [done] on the main thread; no strokes are read as nothing. A read no longer its
     * piece's newest when its turn comes is dropped, as is one of a piece the engine ended, and one
     * that fails. Asked on the main thread.
     */
    fun read(read: InkPad.Read, done: (InkReading) -> Unit) {
        if (broken) return
        newest[read.piece] = read.id
        pending++
        worker.execute {
            var reading: InkReading? = null
            try {
                reading = recognize(read)
            } finally {
                // handed on, or not, but counted off whatever was thrown: else keys would wait on it for good
                val r = reading
                main.post {
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
            main.post {
                try {
                    action()
                } finally {
                    held--
                }
            }
        }
    }

    private fun recognize(read: InkPad.Read): InkReading? {
        // its piece's newest: run, and forgotten unless a newer came meanwhile
        if (!newest.remove(read.piece, read.id) || read.piece <= EngineBridge.handwritingEnded.value) return null
        val strokes = read.strokes
        return try {
            if (strokes.isEmpty()) InkReading.NONE else recognizer().character(strokes)
        } catch (e: IOException) {
            Timber.w(e, "handwriting models")
            null
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // onnxruntime's failures come as IllegalStateException
            Timber.w(e, "handwriting recognition")
            null
        } catch (e: LinkageError) {
            // the native library not loaded (ExceptionInInitializerError is one too): the IME lives on without handwriting
            Timber.e(e, "handwriting library")
            broken = true
            null
        }
    }

    private fun recognizer(): InkRecognizer = recognizer ?: InkRecognizer(
        net("char_image"), net("char_path"),
        chars = appContext.assets.open("$DIR/chars.txt").use { it.readBytes().toString(Charsets.UTF_8) },
        prior = prior(),
    ).also { recognizer = it }

    private fun net(name: String) = InkNet { x, shape ->
        models.getOrPut(name) { OrtModel.open(appContext.assets, "$DIR/$name.ort", THREADS) }.run(x, shape).values
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
