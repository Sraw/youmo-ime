/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.handwriting.InkNet
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.handwriting.InkRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Of a piece's waiting reads only the newest runs; a read of an ended piece is dropped; an action behind reads
 * runs after their readings; a recognizer that fails loading or reading is given up once, the user told once.
 */
class HandwritingRecognitionTest {

    class Queue : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks += command
        }

        fun run() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }

        val size get() = tasks.size
    }

    class Reader(override var loadable: Boolean = true) : HandwritingRecognition.Reader {
        var loads = 0
        val asked = ArrayList<Int>()
        override fun load() {
            loads++
        }

        override fun read(strokes: List<FloatArray>): InkReading {
            asked += strokes.size
            return InkReading(listOf("${strokes.size}"), doubleArrayOf(0.0))
        }
    }

    private val worker = Queue()
    private val main = Queue()
    private var ended = -1
    private val reader = Reader()
    private val recognition = HandwritingRecognition(worker, main, { ended }, reader)

    private fun strokes(n: Int) = List(n) { floatArrayOf(0f, 0f, 1f, 1f) }

    private fun runAll() {
        worker.run()
        main.run()
    }

    @Test
    fun ofAPiecesReadsStillWaitingOnlyTheNewestIsRun() {
        val got = ArrayList<String>()
        recognition.read(InkPad.Read(strokes(1), 7, 1)) { got += it.texts }
        recognition.read(InkPad.Read(strokes(2), 7, 2)) { got += it.texts }
        // an earlier piece's last read is what that piece shows: not stale
        recognition.read(InkPad.Read(strokes(3), 6, 3)) { got += it.texts }
        runAll()
        assertEquals(listOf(2, 3), reader.asked)
        assertEquals(listOf("2", "3"), got)
    }

    @Test
    fun aReadOfAPieceTheEngineEndedIsDropped() {
        val got = ArrayList<String>()
        ended = 7
        recognition.read(InkPad.Read(strokes(1), 7, 1)) { got += it.texts }
        recognition.read(InkPad.Read(strokes(1), 8, 2)) { got += it.texts }
        runAll()
        assertEquals(listOf("1"), got)
        assertEquals(listOf(1), reader.asked)
    }

    @Test
    fun anActionHeldBehindReadsRunsAfterTheirReadingsAndKeysWaitTillThen() {
        val order = ArrayList<String>()
        recognition.read(InkPad.Read(strokes(1), 1, 1)) { order += "reading" }
        assertTrue(recognition.busy)
        recognition.afterReads { order += "key" }
        worker.run()
        assertTrue(recognition.busy)
        main.run()
        assertEquals(listOf("reading", "key"), order)
        assertFalse(recognition.busy)
    }

    @Test
    fun noStrokesAreReadAsNothingWithoutAskingTheRecognizer() {
        val got = ArrayList<InkReading>()
        recognition.read(InkPad.Read(emptyList(), 1, 1)) { got += it }
        runAll()
        assertEquals(listOf(InkReading.NONE), got)
        assertEquals(emptyList<Int>(), reader.asked)
    }

    @Test
    fun aRecognizerThatCannotLoadIsAskedNothingAndHoldsNoKey() {
        reader.loadable = false
        recognition.warmUp()
        recognition.read(InkPad.Read(strokes(1), 1, 1)) { error("no reading") }
        assertFalse(recognition.busy)
        assertEquals(0, worker.size)
        reader.loadable = true
        recognition.warmUp()
        runAll()
        assertEquals(1, reader.loads)
        // loading ahead is no read: no key waits on it
        assertFalse(recognition.busy)
    }

    private class Model : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun aRecognizerThatFailsIsGivenUpOnceAndWhatItOpenedClosed() {
        val broken = InkNet { _, _ -> throw UnsatisfiedLinkError("onnxruntime") }
        fun givenUp(loads: Boolean) = {
            val model = Model()
            var opens = 0
            var told = 0
            val loading = LoadingReader({ opened ->
                opens++
                opened += model
                if (!loads) throw IOException("chars.txt missing")
                InkRecognizer(broken, broken, "一", floatArrayOf(0f))
            }, { told++ })
            loading.load()
            assertEquals(loads, loading.loadable)
            assertNull(loading.read(strokes(1)))
            if (loads) assertNull(loading.read(strokes(1))) else loading.load()
            assertEquals(1, opens)
            assertEquals(1, told)
            assertFalse(loading.loadable)
            assertTrue(model.closed)
        }
        assertRows("its file missing" to givenUp(loads = false), "its library failing in a read" to givenUp(loads = true))
    }

    @Test
    fun aRecognizerGivenUpIsLoadedAgainOnceAPackIsImported() {
        var generation = 0
        var opens = 0
        var told = 0
        val model = Model()
        val net = InkNet { _, _ -> floatArrayOf(0f) }
        val loading = LoadingReader({ opened ->
            opens++
            if (generation == 0) throw IOException("chars.txt missing")
            opened += model
            InkRecognizer(net, net, "一", floatArrayOf(0f))
        }, { told++ }) { generation }
        loading.load()
        assertFalse(loading.loadable)
        generation = 1
        assertTrue(loading.loadable)
        assertEquals(listOf("一"), loading.read(strokes(1))?.texts)
        assertEquals(2, opens)
        assertEquals(1, told)
        generation = 2
        assertEquals(listOf("一"), loading.read(strokes(1))?.texts)
        assertTrue(model.closed)
        assertEquals(3, opens)
    }

    @Test
    fun aModelThatThrowsAsItClosesStillLeavesTheRestClosed() {
        val model = Model()
        var told = 0
        val broken = InkNet { _, _ -> throw UnsatisfiedLinkError("onnxruntime") }
        val loading = LoadingReader({ opened ->
            opened += AutoCloseable { throw UnsatisfiedLinkError("nativeClose") }
            opened += model
            InkRecognizer(broken, broken, "一", floatArrayOf(0f))
        }, { told++ })
        loading.load()
        assertNull(loading.read(strokes(1)))
        assertEquals(1, told)
        assertFalse(loading.loadable)
        assertTrue(model.closed)
    }
}
