/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A character-level transformer language model in the chinese-ime-lm safetensors format
 * (metasequoiaime/pinyin-ime-reranker-4M: 6 layers, width 192, a window of 64 characters, int8
 * weights with a scale per output row, the token table tied as the output projection).
 *
 * Text is run a character at a time into a tree of [Node]s, each holding its position's keys and
 * values for later positions to attend to: readings of one input share most of their characters,
 * and each shared character is run once.
 */
class SentenceModel private constructor(
    private val config: Config,
    private val vocab: List<String>,
    private val tok: Matrix,
    private val pos: FloatArray,
    private val blocks: List<Block>,
    private val lnF: Norm,
    /** The weights' licence, as the file states it. */
    val license: String,
    /** Whose text the weights were trained on: to travel with them. */
    val attribution: String,
) {
    /** The model's dimensions, as its file states them. */
    class Config(val vocab: Int, val layers: Int, val heads: Int, val width: Int, val window: Int)

    internal class Norm(val weight: FloatArray, val bias: FloatArray)

    internal class Block(
        val ln1: Norm,
        val qkv: Matrix,
        val qkvBias: FloatArray,
        val proj: Matrix,
        val projBias: FloatArray,
        val ln2: Norm,
        val fc: Matrix,
        val fcBias: FloatArray,
        val out: Matrix,
        val outBias: FloatArray,
    )

    /**
     * [token] at [position] after [parent]: that position's keys and values in each layer, and
     * its output, which predicts the next token.
     */
    class Node internal constructor(
        val parent: Node?,
        val token: Int,
        val position: Int,
        internal val keys: Array<FloatArray>,
        internal val values: Array<FloatArray>,
        internal val out: FloatArray,
    ) {
        // the final norm of [out], and the log of its softmax's denominator: the costliest part,
        // done once however many next tokens are asked about
        internal var normed: FloatArray? = null
        internal var logSum = 0f
        internal var summed = false
    }

    private val index: Map<Char, Int> = HashMap<Char, Int>().apply {
        vocab.forEachIndexed { id, s -> if (s.length == 1 && id > BOS) put(s[0], id) }
    }

    val window: Int get() = config.window

    /** Token ids of [text]'s characters; unknown ones (and any beyond the BMP) as `<unk>`. */
    fun encode(text: String): IntArray {
        val out = IntArray(text.length)
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val pair = Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])
            out[n++] = if (pair) UNK else index[c] ?: UNK
            i += if (pair) 2 else 1
        }
        return out.copyOf(n)
    }

    /** Runs [token] after [parent] (or first of all). */
    fun next(parent: Node?, token: Int): Node {
        val position = (parent?.position ?: -1) + 1
        require(position < config.window) { "past the model's window" }
        require(token in 0 until config.vocab) { "no token $token" }
        val width = config.width
        val chain = arrayOfNulls<Node>(position)
        var n = parent
        while (n != null) {
            chain[n.position] = n
            n = n.parent
        }
        val x = FloatArray(width)
        tok.row(token, x)
        for (i in 0 until width) x[i] += pos[position * width + i]
        val keys = Array(config.layers) { FloatArray(width) }
        val values = Array(config.layers) { FloatArray(width) }
        val y = FloatArray(width)
        val qkv = FloatArray(3 * width)
        val hidden = FloatArray(4 * width)
        val att = FloatArray(position + 1)
        val add = FloatArray(width)
        for ((l, b) in blocks.withIndex()) {
            layerNorm(x, b.ln1, y)
            b.qkv.apply(y, b.qkvBias, qkv)
            System.arraycopy(qkv, width, keys[l], 0, width)
            System.arraycopy(qkv, 2 * width, values[l], 0, width)
            attend(qkv, chain, l, keys[l], values[l], att, y)
            b.proj.times(y, add)
            for (o in 0 until width) x[o] += add[o] + b.projBias[o]
            layerNorm(x, b.ln2, y)
            b.fc.apply(y, b.fcBias, hidden)
            for (i in hidden.indices) hidden[i] = gelu(hidden[i])
            b.out.times(hidden, add)
            for (o in 0 until width) x[o] += add[o] + b.outBias[o]
        }
        return Node(parent, token, position, keys, values, x)
    }

    /** Causal attention, layer [l], of the query in [qkv] over [chain] and itself, into [out]. */
    @Suppress("LongParameterList")
    private fun attend(qkv: FloatArray, chain: Array<Node?>, l: Int, ownKeys: FloatArray, ownValues: FloatArray, att: FloatArray, out: FloatArray) {
        val width = config.width
        val d = width / config.heads
        val scale = 1f / sqrt(d.toFloat())
        val last = chain.size
        java.util.Arrays.fill(out, 0f)
        for (h in 0 until config.heads) {
            val off = h * d
            var max = Float.NEGATIVE_INFINITY
            for (s in 0..last) {
                val k = if (s == last) ownKeys else chain[s]!!.keys[l]
                var dot = 0f
                for (i in off until off + d) dot += qkv[i] * k[i]
                att[s] = dot * scale
                if (att[s] > max) max = att[s]
            }
            var sum = 0f
            for (s in 0..last) {
                att[s] = exp(att[s] - max)
                sum += att[s]
            }
            for (s in 0..last) {
                val v = if (s == last) ownValues else chain[s]!!.values[l]
                val a = att[s] / sum
                for (i in off until off + d) out[i] += a * v[i]
            }
        }
    }

    private fun normed(node: Node): FloatArray =
        node.normed ?: FloatArray(config.width).also {
            layerNorm(node.out, lnF, it)
            node.normed = it
        }

    /** The logit of [token] after [node]: its log-probability but for [logSum]. */
    fun logit(node: Node, token: Int): Float = tok.dot(normed(node), token)

    /**
     * The log of the softmax's denominator after [node]: a logit for every token in the
     * vocabulary, the costliest part of a position, done once per node.
     */
    fun logSum(node: Node): Float {
        if (node.summed) return node.logSum
        val normed = normed(node)
        val logits = FloatArray(config.vocab)
        tok.times(normed, logits)
        var max = Float.NEGATIVE_INFINITY
        for (v in 0 until config.vocab) if (logits[v] > max) max = logits[v]
        var sum = 0.0
        for (v in 0 until config.vocab) sum += exp((logits[v] - max).toDouble())
        node.logSum = max + ln(sum).toFloat()
        node.summed = true
        return node.logSum
    }

    /** log P([token] next, after [node]). */
    fun logProb(node: Node, token: Int): Float = logit(node, token) - logSum(node)

    // a node's keys and values for each layer, its output and that normed
    private fun nodeFloats() = 2 * config.layers * config.width + 2 * config.width

    /**
     * Scores readings after one context, a keystroke at a time: what was run for one reading is
     * kept, by the tokens before it, for the next reading and the next keystroke; what was run
     * for the context, for a context that goes on from it.
     */
    inner class Scorer(room: Int = ROOM, private val limit: Int = KEPT_FLOATS / nodeFloats()) {
        /** Context tokens the window has room for, before [room] positions of reading. */
        private val maxContext = maxOf(0, window - 1 - room)

        private val bos by lazy(LazyThreadSafetyMode.NONE) { next(null, BOS) }

        private var context: String? = null

        // the context read so far, a node a token after <bos>, and what it is to read
        private val chain = ArrayList<Node>()
        private var target = IntArray(0)

        /**
         * A token after [parent], [logits] the sum of the logits of the tokens up to it. Run into
         * a node only once something follows: the last of a reading needs its logit alone.
         */
        private inner class Step(val parent: Node, val token: Int, val logits: Float) {
            var node: Node? = null
        }

        // positions run and denominators taken in this call: the costly parts
        private var spent = 0
        private var budget = Int.MAX_VALUE

        private fun nodeOf(step: Step): Node? =
            step.node ?: if (spent++ >= budget) null else next(step.parent, step.token).also { step.node = it }

        private fun denominator(node: Node): Float? =
            if (node.summed) node.logSum else if (spent++ >= budget) null else logSum(node)

        private val kept = HashMap<String, Step>()

        /**
         * The log-probability of each of [readings] after [context], of as much of it as fits
         * the window; -infinity for an empty one. If [relative], leaving out what all of them
         * share, the same amount from each: what compares them, at a fraction of the cost.
         */
        fun score(context: String, readings: List<String>, relative: Boolean = false): FloatArray =
            within(context, readings, relative, Int.MAX_VALUE)!!

        /**
         * As [score], or null if that takes more than [budget] positions run or denominators
         * taken; what was done is kept, so asking again after the next keystroke goes on from
         * there. The readings are read a position of each at a time, so each gets its share.
         */
        fun within(context: String, readings: List<String>, relative: Boolean, budget: Int): FloatArray? {
            this.budget = budget
            spent = 0
            val first = start(context) ?: return null
            // the last token of each is not run, only read after the one before: one more fits
            val tokens = readings.map { r -> encode(r).let { it.copyOf(minOf(it.size, window - first.position)) } }
            val shared = if (relative) shared(tokens) else 0
            val n = readings.size
            // the tokens so far of each, as chars: token ids are below 2^16
            val keys = Array(n) { StringBuilder() }
            val parents = Array(n) { first }
            val logits = FloatArray(n)
            val denominators = FloatArray(n)
            for (t in 0 until (tokens.maxOfOrNull { it.size } ?: 0)) {
                for (i in 0 until n) {
                    val reading = tokens[i]
                    if (t >= reading.size) continue
                    keys[i].append(reading[t].toChar())
                    val parent = parents[i]
                    val sum = logits[i]
                    val step = kept.getOrPut(keys[i].toString()) { Step(parent, reading[t], sum + logit(parent, reading[t])) }
                    if (t >= shared) denominators[i] += denominator(step.parent) ?: return null
                    logits[i] = step.logits
                    if (t < reading.lastIndex) parents[i] = nodeOf(step) ?: return null
                }
            }
            return FloatArray(n) { if (tokens[it].isEmpty()) Float.NEGATIVE_INFINITY else logits[it] - denominators[it] }
        }

        /** The node the readings follow, [context] read up to it; null if that is past the budget. */
        private fun start(context: String): Node? {
            if (context != this.context) {
                this.context = context
                kept.clear()
                target = target(encode(context))
                var common = 0
                while (common < chain.size && common < target.size && chain[common].token == target[common]) common++
                while (chain.size > common) chain.removeAt(chain.size - 1)
            } else if (kept.size > limit) {
                kept.clear()
            }
            while (chain.size < target.size) {
                if (spent++ >= budget) return null
                chain += next(chain.lastOrNull() ?: bos, target[chain.size])
            }
            return chain.lastOrNull() ?: bos
        }

        /**
         * The end of [context] to read: from where what was read before is found in it, if that
         * fits, to go on from there (text committed after it, a piece picked); else all of it if
         * that fits, or the last half of what fits, to go on from for a while.
         */
        private fun target(context: IntArray): IntArray {
            if (chain.isNotEmpty()) {
                // the first place that fits: a later one, where the text committed repeats it, drops some
                var from = maxOf(0, context.size - maxContext)
                while (from + chain.size <= context.size && (0 until chain.size).any { context[from + it] != chain[it].token }) from++
                if (from + chain.size <= context.size) return context.copyOfRange(from, context.size)
            }
            val kept = if (context.size <= maxContext) context.size else maxContext / 2
            return context.copyOfRange(context.size - kept, context.size)
        }

        /**
         * How many positions from the start all of [tokens] have in common, after the same
         * tokens: each adds the same denominator to every reading.
         */
        private fun shared(tokens: List<IntArray>): Int {
            val shortest = tokens.minOfOrNull { it.size } ?: return 0
            var common = 0
            while (common < shortest && tokens.all { it[common] == tokens[0][common] }) common++
            // the token after the common ones follows the same text in each, if each has one
            return if (common < shortest) common + 1 else common
        }
    }

    companion object {
        const val PAD = 0
        const val UNK = 1
        const val BOS = 2
        /** Positions left for readings after the context: as long as most sentences typed at once. */
        const val ROOM = 24
        /**
         * What a [Scorer] keeps of its readings' nodes, in floats: 6 MB, some 550 steps of the
         * 4M model and 190 of the 25M one, whose node is three times the size.
         */
        const val KEPT_FLOATS = 1_500_000
        private const val EPS = 1e-5f
        private const val MAX_LAYERS = 64

        internal fun layerNorm(x: FloatArray, norm: Norm, out: FloatArray) {
            val n = norm.weight.size
            var mean = 0f
            for (i in 0 until n) mean += x[i]
            mean /= n
            var variance = 0f
            for (i in 0 until n) {
                val d = x[i] - mean
                variance += d * d
            }
            variance /= n
            val inv = 1f / sqrt(variance + EPS)
            for (i in 0 until n) out[i] = (x[i] - mean) * inv * norm.weight[i] + norm.bias[i]
        }

        /** The exact GELU, through erf: the tanh form is another function, not the one trained. */
        fun gelu(x: Float): Float = (0.5 * x * (1 + erf(x / Math.sqrt(2.0)))).toFloat()

        /** erf to about 1e-7 (Numerical Recipes' Chebyshev fit of erfc): plenty for float math. */
        @Suppress("MagicNumber")
        fun erf(x: Double): Double {
            val z = Math.abs(x)
            val t = 1 / (1 + 0.5 * z)
            val poly = -1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 +
                t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277))))))))
            val r = t * Math.exp(-z * z + poly)
            return if (x >= 0) 1 - r else r - 1
        }

        /**
         * Reads a model from a safetensors file's bytes, with its weights as stored or, if
         * [unpack], as floats; [IllegalArgumentException] if it is no chinese-ime-lm v1 model.
         * Weights as stored are multiplied by [kernel].
         */
        fun load(buffer: ByteBuffer, unpack: Boolean = false, kernel: MatrixKernel = MatrixKernel.JVM): SentenceModel {
            val file = Safetensors(buffer)
            val meta = file.metadata
            require(meta["format"] == "chinese-ime-lm" && meta["version"] == "1") { "not a chinese-ime-lm v1 model" }
            val config = config(requireNotNull(meta["config"]) { "no config" })
            val vocab = requireNotNull(Json.parse(requireNotNull(meta["vocab"]) { "no vocab" }) as? List<*>) { "bad vocab" }
                .map { requireNotNull(it as? String) { "bad vocab" } }
            require(vocab.size == config.vocab) { "vocab is ${vocab.size}, config ${config.vocab}" }
            val w = config.width
            fun matrix(name: String, rows: Int, columns: Int) = file.matrix(name, rows, columns, kernel).let { if (unpack) it.unpacked() else it }
            fun norm(name: String) = Norm(file.floats("$name.weight", w), file.floats("$name.bias", w))
            val blocks = (0 until config.layers).map { i ->
                val p = "blocks.$i."
                Block(
                    norm(p + "ln1"),
                    matrix(p + "qkv.weight", 3 * w, w), file.floats(p + "qkv.bias", 3 * w),
                    matrix(p + "proj.weight", w, w), file.floats(p + "proj.bias", w),
                    norm(p + "ln2"),
                    matrix(p + "fc.weight", 4 * w, w), file.floats(p + "fc.bias", 4 * w),
                    matrix(p + "out.weight", w, 4 * w), file.floats(p + "out.bias", w),
                )
            }
            return SentenceModel(
                config, vocab, matrix("tok.weight", config.vocab, w), file.floats("pos.weight", config.window, w),
                blocks, norm("ln_f"), meta["license"].orEmpty(), meta["attribution"].orEmpty(),
            )
        }

        private fun config(text: String): Config {
            val json = requireNotNull(Json.parse(text) as? Map<*, *>) { "bad config" }
            fun dim(key: String): Int {
                val v = requireNotNull(json[key] as? Double) { "no config $key" }
                require(v >= 1 && v <= MAX_DIM && v == Math.floor(v)) { "bad config $key" }
                return v.toInt()
            }
            val config = Config(dim("vocab"), dim("n_layer"), dim("n_head"), dim("n_embd"), dim("context"))
            require(config.width % config.heads == 0 && config.layers <= MAX_LAYERS && config.vocab > BOS) { "bad geometry" }
            return config
        }

        private const val MAX_DIM = 1 shl 16
    }
}
