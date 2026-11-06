/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The debug build's clip microphone, for the device UAT (uat.py Device.say): clips queued from the
 * shell (VoiceClipProvider, the voiceDebug source set) are what [VoiceListener] reads in place of
 * the AudioRecord's samples, at the microphone's pace, then a faint noise till the talk ends. The
 * AudioRecord is still opened, started and released as ever. Reached only under BuildConfig.DEBUG,
 * so R8 drops it from a release build.
 */
object VoiceClipMic {

    sealed interface Part {
        class Clip(val name: String, val samples: FloatArray) : Part
        class Pause(val ms: Int) : Part
    }

    private val queue = ArrayDeque<Part>()

    /** on from the first [queue] till [clear]: every talk meanwhile hears the clips, none the microphone */
    @Volatile
    var on = false
        private set

    /** a [Feed] is playing a clip or a pause now */
    @Volatile
    internal var playing = false

    @Synchronized
    fun queue(parts: List<Part>) {
        queue.addAll(parts)
        on = true
    }

    @Synchronized
    fun clear() {
        queue.clear()
        on = false
    }

    @Synchronized
    fun state(): String = "on=$on playing=$playing queued=${queue.joinToString(",") { it.label() }}"

    @Synchronized
    internal fun take(): Part? = queue.removeFirstOrNull()

    /** what this talk reads in place of the microphone; null: the microphone */
    fun feed(): Feed? = if (on) Feed(VoiceEngine.SAMPLE_RATE, ::take) { playing = it } else null

    private fun Part.label() = when (this) {
        is Part.Clip -> name
        is Part.Pause -> "${ms}ms"
    }

    /**
     * A WAV (16-bit PCM, mono, 16 kHz) or raw 16-bit little-endian PCM as samples, -1 to 1.
     * Throws IllegalArgumentException on another format.
     */
    fun decode(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var data = b
        if (bytes.size >= WAV_HEADER && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
            b.position(WAV_HEADER)
            var found: ByteBuffer? = null
            while (b.remaining() >= CHUNK_HEADER && found == null) {
                val id = String(bytes, b.position(), 4, Charsets.US_ASCII)
                b.position(b.position() + 4)
                // a streamed WAV's data size may be unset (-1): to the end
                val size = b.int.let { if (it < 0 || it > b.remaining()) b.remaining() else it }
                val end = b.position() + size
                when (id) {
                    "fmt " -> {
                        val format = b.short.toInt()
                        val channels = b.short.toInt()
                        val rate = b.int
                        b.position(b.position() + 6)
                        val bits = b.short.toInt()
                        require(format == 1 && channels == 1 && rate == VoiceEngine.SAMPLE_RATE && bits == 16) {
                            "want 16-bit PCM mono ${VoiceEngine.SAMPLE_RATE} Hz, got format=$format channels=$channels rate=$rate bits=$bits"
                        }
                    }
                    "data" -> found = b.slice().order(ByteOrder.LITTLE_ENDIAN).also { it.limit(end - b.position()) }
                }
                b.position(minOf(end + (size and 1), b.limit()))
            }
            data = requireNotNull(found) { "no data chunk" }
        }
        val shorts = data.asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / PCM_16BIT_SCALE }
    }

    /**
     * One talk's reading of the queue: [read] fills a window at the pace a microphone would (it
     * sleeps till the window's last sample is due since the first read), the clips in order, a
     * pause or the queue run dry as a faint noise (the onset and a silent microphone's 2 s are
     * for exact zeroes, which no real microphone gives).
     */
    class Feed(
        private val rate: Int,
        private val take: () -> Part?,
        private val clock: () -> Long = System::nanoTime,
        private val sleep: (ms: Long) -> Unit = Thread::sleep,
        private val busy: (Boolean) -> Unit = {},
    ) {
        private var start = 0L
        private var fed = 0L
        private var clip: FloatArray? = null
        private var at = 0
        private var pause = 0
        private var noise = 1

        fun read(buffer: FloatArray): Int {
            if (fed == 0L) start = clock()
            val due = (clock() - start) * rate / NS_PER_S
            val ahead = fed + buffer.size - due
            if (ahead > 0) sleep(ahead * MS_PER_S / rate)
            for (i in buffer.indices) buffer[i] = next()
            fed += buffer.size
            busy(clip != null || pause > 0)
            return buffer.size
        }

        private fun next(): Float {
            while (true) {
                val c = clip
                if (c != null) {
                    if (at < c.size) return c[at++]
                    clip = null
                }
                if (pause > 0) {
                    pause--
                    return hiss()
                }
                when (val p = take()) {
                    is Part.Clip -> {
                        clip = p.samples
                        at = 0
                    }
                    is Part.Pause -> pause = (p.ms.toLong() * rate / MS_PER_S).toInt()
                    null -> return hiss()
                }
            }
        }

        // ±2 of 16 bits, some -84 dBFS: never exact zero
        private fun hiss(): Float {
            noise = noise * LCG_A + LCG_C
            return ((noise ushr NOISE_SHIFT) % NOISE_SPAN - 2).let { if (it >= 0) it + 1 else it } / PCM_16BIT_SCALE
        }
    }

    private const val WAV_HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val PCM_16BIT_SCALE = 32768f
    private const val NS_PER_S = 1_000_000_000L
    private const val MS_PER_S = 1000L
    private const val LCG_A = 1103515245
    private const val LCG_C = 12345
    private const val NOISE_SHIFT = 16
    private const val NOISE_SPAN = 4
}
