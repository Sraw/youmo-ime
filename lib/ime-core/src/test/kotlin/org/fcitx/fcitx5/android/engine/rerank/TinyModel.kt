/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A chinese-ime-lm model small enough to write out in a test, with random weights, and the same
 * forward pass written plainly over the dense weights to check [SentenceModel] against.
 */
class TinyModel(
    val vocab: List<String> = listOf("<pad>", "<unk>", "<bos>", "我", "你", "好", "的", "天", "ab"),
    val layers: Int = 2,
    val heads: Int = 2,
    val width: Int = 8,
    val window: Int = 8,
    seed: Long = 1,
) {
    private val random = Random(seed)

    /** Each tensor as floats, with its shape; matrices named in [quantized] are written as I8. */
    val tensors = LinkedHashMap<String, Pair<IntArray, FloatArray>>()
    val quantized = HashSet<String>()

    private fun put(name: String, vararg shape: Int, scale: Float = 0.5f, around: Float = 0f) {
        tensors[name] = shape to FloatArray(shape.fold(1) { a, d -> a * d }) { around + (random.nextGaussian() * scale).toFloat() }
    }

    init {
        val w = width
        put("tok.weight", vocab.size, w)
        quantized += "tok.weight"
        put("pos.weight", window, w, scale = 0.1f)
        for (i in 0 until layers) {
            val p = "blocks.$i."
            put(p + "ln1.weight", w, scale = 0.1f, around = 1f)
            put(p + "ln1.bias", w, scale = 0.1f)
            put(p + "qkv.weight", 3 * w, w)
            put(p + "qkv.bias", 3 * w, scale = 0.1f)
            put(p + "proj.weight", w, w)
            put(p + "proj.bias", w, scale = 0.1f)
            put(p + "ln2.weight", w, scale = 0.1f, around = 1f)
            put(p + "ln2.bias", w, scale = 0.1f)
            put(p + "fc.weight", 4 * w, w)
            put(p + "fc.bias", 4 * w, scale = 0.1f)
            put(p + "out.weight", w, 4 * w)
            put(p + "out.bias", w, scale = 0.1f)
            quantized += listOf(p + "qkv.weight", p + "fc.weight", p + "out.weight")
        }
        put("ln_f.weight", w, scale = 0.1f, around = 1f)
        put("ln_f.bias", w, scale = 0.1f)
        // what an int8 matrix will read back as, so the plain pass sees the same weights
        for (name in quantized) {
            val (shape, f) = tensors.getValue(name)
            val columns = shape[1]
            for (r in 0 until shape[0]) {
                val scale = scaleOf(f, r, columns)
                for (c in 0 until columns) f[r * columns + c] = Math.round(f[r * columns + c] / scale) * scale
            }
        }
    }

    private fun scaleOf(f: FloatArray, r: Int, columns: Int): Float {
        var max = 0f
        for (c in 0 until columns) max = maxOf(max, Math.abs(f[r * columns + c]))
        return if (max == 0f) 1f else max / 127
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The model as a safetensors file, with [metadata] replaced where given. */
    fun bytes(metadata: Map<String, String> = emptyMap()): ByteArray {
        val data = ByteArrayOutputStream()
        val entries = ArrayList<String>()
        fun write(name: String, dtype: String, shape: IntArray, bytes: ByteArray) {
            entries += "${quote(name)}:{\"dtype\":\"$dtype\",\"shape\":[${shape.joinToString(",")}]," +
                "\"data_offsets\":[${data.size()},${data.size() + bytes.size}]}"
            data.write(bytes)
        }
        fun floats(f: FloatArray) = ByteBuffer.allocate(4 * f.size).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(f) }.array()
        for ((name, t) in tensors) {
            val (shape, f) = t
            if (name !in quantized) {
                write(name, "F32", shape, floats(f))
                continue
            }
            val columns = shape[1]
            val scales = FloatArray(shape[0]) { scaleOf(f, it, columns) }
            val q = ByteArray(f.size) { Math.round(f[it] / scales[it / columns]).toByte() }
            write(name, "I8", shape, q)
            write("$name.scale", "F32", intArrayOf(shape[0]), floats(scales))
        }
        val meta = linkedMapOf(
            "format" to "chinese-ime-lm",
            "version" to "1",
            "config" to "{\"vocab\":${vocab.size},\"n_layer\":$layers,\"n_head\":$heads,\"n_embd\":$width,\"context\":$window}",
            "vocab" to vocab.joinToString(",", "[", "]") { quote(it) },
            "license" to "Apache-2.0",
            "attribution" to "made up in a test",
        )
        meta.putAll(metadata)
        entries.add(0, "\"__metadata__\":{" + meta.entries.joinToString(",") { quote(it.key) + ":" + quote(it.value) } + "}")
        val header = "{" + entries.joinToString(",") + "}"
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(8 + headerBytes.size + data.size()).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(headerBytes.size.toLong()).put(headerBytes).put(data.toByteArray()).array()
    }

    fun load(unpack: Boolean = false): SentenceModel = SentenceModel.load(ByteBuffer.wrap(bytes()), unpack)

    private fun t(name: String) = tensors.getValue(name).second

    private fun norm(x: FloatArray, name: String): FloatArray {
        val mean = x.average()
        val variance = x.map { (it - mean) * (it - mean) }.average()
        val w = t("$name.weight")
        val b = t("$name.bias")
        return FloatArray(x.size) { ((x[it] - mean) / sqrt(variance + 1e-5) * w[it] + b[it]).toFloat() }
    }

    private fun linear(x: FloatArray, name: String): FloatArray {
        val w = t("$name.weight")
        val b = t("$name.bias")
        return FloatArray(b.size) { o -> b[o] + x.indices.sumOf { (x[it] * w[o * x.size + it]).toDouble() }.toFloat() }
    }

    /** log P(each next token) after each of [tokens], by the textbook pass over the whole run. */
    fun logProbs(tokens: IntArray): List<DoubleArray> {
        val w = width
        val d = w / heads
        var x = tokens.mapIndexed { p, token -> FloatArray(w) { t("tok.weight")[token * w + it] + t("pos.weight")[p * w + it] } }
        for (l in 0 until layers) {
            val p = "blocks.$l."
            val qkv = x.map { linear(norm(it, p + "ln1"), p + "qkv") }
            x = x.indices.map { i ->
                val attended = FloatArray(w)
                for (h in 0 until heads) {
                    val scores = (0..i).map { s -> (0 until d).sumOf { (qkv[i][h * d + it] * qkv[s][w + h * d + it]).toDouble() } / sqrt(d.toDouble()) }
                    val max = scores.max()
                    val e = scores.map { exp(it - max) }
                    for (s in 0..i) for (k in 0 until d) attended[h * d + k] += (e[s] / e.sum() * qkv[s][2 * w + h * d + k]).toFloat()
                }
                val mid = FloatArray(w) { x[i][it] + linear(attended, p + "proj")[it] }
                val hidden = linear(norm(mid, p + "ln2"), p + "fc").map { v -> 0.5 * v * (1 + SentenceModel.erf(v / sqrt(2.0))) }
                val out = linear(FloatArray(hidden.size) { hidden[it].toFloat() }, p + "out")
                FloatArray(w) { mid[it] + out[it] }
            }
        }
        return x.map { h ->
            val n = norm(h, "ln_f")
            val logits = DoubleArray(vocab.size) { v -> (0 until w).sumOf { (n[it] * t("tok.weight")[v * w + it]).toDouble() } }
            val max = logits.max()
            val log = max + Math.log(logits.sumOf { exp(it - max) })
            DoubleArray(logits.size) { logits[it] - log }
        }
    }
}
