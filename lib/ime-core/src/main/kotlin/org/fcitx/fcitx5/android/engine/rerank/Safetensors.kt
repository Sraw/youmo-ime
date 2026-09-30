/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A safetensors file: an 8-byte little-endian header size, a JSON header naming each tensor's
 * dtype, shape and byte range, then the data. Only F32 and I8 are read, all this project needs.
 * The file is untrusted: every size is checked against the file before anything is allocated,
 * and [IllegalArgumentException] says what did not fit.
 */
internal class Safetensors(buffer: ByteBuffer) {

    private val bytes = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val header: Map<*, *>
    private val dataStart: Int
    private val dataSize: Int

    /** `__metadata__`: string keys to string values, as the format has it. */
    val metadata: Map<String, String>

    init {
        val start = bytes.position()
        require(bytes.remaining() >= HEADER_SIZE_BYTES) { "truncated" }
        val size = bytes.getLong(start)
        require(size in 2..MAX_HEADER && size <= bytes.remaining() - HEADER_SIZE_BYTES) { "bad header size $size" }
        val text = ByteArray(size.toInt())
        at(start + HEADER_SIZE_BYTES).get(text)
        header = requireNotNull(Json.parse(String(text, Charsets.UTF_8)) as? Map<*, *>) { "header is no object" }
        require(header.size <= MAX_TENSORS) { "too many tensors" }
        metadata = (header["__metadata__"] as? Map<*, *>).orEmpty().entries
            .mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }.toMap()
        dataStart = start + HEADER_SIZE_BYTES + size.toInt()
        dataSize = bytes.limit() - dataStart
    }

    /** A view of the file from [offset], in its byte order. */
    private fun at(offset: Int): ByteBuffer {
        val view = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        // through Buffer: ByteBuffer.position(int) returning ByteBuffer is Java 9, not Android's
        (view as Buffer).position(offset)
        return view
    }

    private class Entry(val dtype: String, val shape: List<Int>, val from: Int, val to: Int)

    private fun entry(name: String): Entry {
        val e = requireNotNull(header[name] as? Map<*, *>) { "no tensor $name" }
        val dtype = requireNotNull(e["dtype"] as? String) { "$name: no dtype" }
        val shape = requireNotNull(e["shape"] as? List<*>) { "$name: no shape" }.map {
            requireNotNull((it as? Double)?.takeIf { d -> d >= 1 && d <= MAX_DIM && d == Math.floor(d) }) { "$name: bad shape" }.toInt()
        }
        val offsets = (e["data_offsets"] as? List<*>).orEmpty().map { (it as? Double)?.takeIf { d -> d == Math.floor(d) } ?: -1.0 }
        require(offsets.size == 2 && offsets[0] >= 0 && offsets[0] <= offsets[1] && offsets[1] <= dataSize) { "$name: bad offsets" }
        val itemSize = ITEM_SIZES[dtype] ?: throw IllegalArgumentException("$name: dtype $dtype")
        val count = shape.fold(1L) { a, d -> a * d }
        require(count * itemSize == (offsets[1] - offsets[0]).toLong()) { "$name: size does not match shape" }
        return Entry(dtype, shape, offsets[0].toInt(), offsets[1].toInt())
    }

    /** Tensor [name], F32 of [shape]. */
    fun floats(name: String, vararg shape: Int): FloatArray {
        val e = entry(name)
        require(e.dtype == "F32" && e.shape == shape.toList()) { "$name: expected F32 ${shape.toList()}, got ${e.dtype} ${e.shape}" }
        val out = FloatArray((e.to - e.from) / Float.SIZE_BYTES)
        at(dataStart + e.from).asFloatBuffer().get(out)
        return out
    }

    /** Tensor [name] of `[rows, columns]`: I8 with an F32 `.scale` per row, multiplied by [kernel], or F32. */
    fun matrix(name: String, rows: Int, columns: Int, kernel: MatrixKernel = MatrixKernel.JVM): Matrix {
        val e = entry(name)
        require(e.shape == listOf(rows, columns)) { "$name: expected [$rows, $columns], got ${e.shape}" }
        if (e.dtype == "F32") return Matrix.of(rows, columns, floats(name, rows, columns))
        val q = ByteArray(e.to - e.from)
        at(dataStart + e.from).get(q)
        return Matrix.quantized(rows, columns, q, floats("$name.scale", rows), kernel)
    }

    private companion object {
        const val HEADER_SIZE_BYTES = 8
        const val MAX_HEADER = 1L shl 24
        const val MAX_TENSORS = 1024
        const val MAX_DIM = 1 shl 16
        val ITEM_SIZES = mapOf("F32" to Float.SIZE_BYTES, "I8" to 1)
    }
}
