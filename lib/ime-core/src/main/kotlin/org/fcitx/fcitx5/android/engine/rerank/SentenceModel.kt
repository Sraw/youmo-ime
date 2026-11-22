/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import org.fcitx.fcitx5.android.core.CoreLog
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * A character-level transformer LM in the chinese-ime-lm safetensors format (pinyin-ime-reranker-4M). Text runs
 * a character at a time into a tree of [Node]s holding each position's keys and values, so readings sharing a
 * prefix run it once.
 */
class SentenceModel private constructor(
    private val config: Config,
    private val vocab: List<String>,
    private val tok: Matrix,
    private val pos: FloatArray,
    private val blocks: List<Block>,
    private val lnF: Norm,
    val license: String,
    /** Whose text the weights were trained on: travels with them. */
    val attribution: String,
    // gelu and the log-sums: float formulas every kernel computes to the same bits
    private val kernel: MatrixKernel,
) {
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

    /** [token] at [position] after [parent]: its per-layer keys and values, and its output predicting the next token. */
    class Node internal constructor(
        val parent: Node?,
        val token: Int,
        val position: Int,
        internal val keys: Array<FloatArray>,
        internal val values: Array<FloatArray>,
        internal val out: FloatArray,
    ) {
        // [out]'s final norm and its softmax's log-denominator: the costliest part, done once per node
        internal var normed: FloatArray? = null
        internal var logSum = 0f
        internal var summed = false
    }

    // [nextMany]'s and [logSums]'s working arrays, grown as needed, per thread (ime-eval shares a model)
    private class Work(config: Config) {
        val one = FloatArray(config.width)
        val oneQkv = FloatArray(3 * config.width)
        val att = FloatArray(config.window)
        var y = FloatArray(0)
        var qkv = FloatArray(0)
        var hidden = FloatArray(0)
        var add = FloatArray(0)
        var normed = FloatArray(0)
        var logits = FloatArray(0)
        var sums = FloatArray(0)
    }

    private val work = ThreadLocal.withInitial { Work(config) }

    private val index: Map<Char, Int> = HashMap<Char, Int>().apply {
        vocab.forEachIndexed { id, s -> if (s.length == 1 && id > BOS) put(s[0], id) }
    }

    val window: Int get() = config.window

    // `<bos>` run with its denominator: one per model (ime-eval makes a scorer per input), computed whole
    // before sharing, as its lazy parts would otherwise be written by whichever thread got there
    private val bos: Node by lazy { next(null, BOS).also { logSum(it) } }

    /** Unknown characters (and any beyond the BMP) as `<unk>`. */
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

    internal fun next(parent: Node?, token: Int): Node = nextMany(listOf(parent), intArrayOf(-1), intArrayOf(token))[0]

    /**
     * [tokens] after their parents at once, layer by layer, each weight matrix read once for all: the j-th after
     * `parents[j]`, or after the `pending[j]`-th new node where that is 0 or more. A node holds the same floats
     * whatever else runs with it, as every step works on one vector at a time.
     */
    @Suppress("LongMethod")
    internal fun nextMany(parents: List<Node?>, pending: IntArray, tokens: IntArray): List<Node> {
        val m = tokens.size
        if (m == 0) return emptyList()
        val width = config.width
        val nodes = ArrayList<Node>(m)
        val chains = ArrayList<Array<Node?>>(m)
        for (j in 0 until m) {
            val parent = if (pending[j] >= 0) nodes[pending[j]] else parents[j]
            val position = (parent?.position ?: -1) + 1
            require(position < config.window) { "past the model's window" }
            require(tokens[j] in 0 until config.vocab) { "no token ${tokens[j]}" }
            val chain = arrayOfNulls<Node>(position)
            var n = parent
            while (n != null) {
                chain[n.position] = n
                n = n.parent
            }
            val x = FloatArray(width)
            tok.row(tokens[j], x)
            for (i in 0 until width) x[i] += pos[position * width + i]
            nodes += Node(parent, tokens[j], position, Array(config.layers) { FloatArray(width) }, Array(config.layers) { FloatArray(width) }, x)
            chains += chain
        }
        val w = work.get()
        if (w.y.size < m * width) {
            w.y = FloatArray(m * width)
            w.qkv = FloatArray(m * 3 * width)
            w.hidden = FloatArray(m * 4 * width)
            w.add = FloatArray(m * width)
        }
        val y = w.y
        val qkv = w.qkv
        val hidden = w.hidden
        val add = w.add
        val one = w.one
        for ((l, b) in blocks.withIndex()) {
            for (j in 0 until m) {
                layerNorm(nodes[j].out, b.ln1, one)
                System.arraycopy(one, 0, y, j * width, width)
            }
            b.qkv.applyMany(y, m, b.qkvBias, qkv)
            // every new node's keys before any attends: a node may follow another of this batch
            for (j in 0 until m) {
                System.arraycopy(qkv, j * 3 * width + width, nodes[j].keys[l], 0, width)
                System.arraycopy(qkv, j * 3 * width + 2 * width, nodes[j].values[l], 0, width)
            }
            for (j in 0 until m) {
                System.arraycopy(qkv, j * 3 * width, w.oneQkv, 0, 3 * width)
                attend(w.oneQkv, chains[j], l, nodes[j].keys[l], nodes[j].values[l], w.att, one)
                System.arraycopy(one, 0, y, j * width, width)
            }
            b.proj.timesMany(y, m, add)
            for (j in 0 until m) {
                val x = nodes[j].out
                for (o in 0 until width) x[o] += add[j * width + o] + b.projBias[o]
                layerNorm(x, b.ln2, one)
                System.arraycopy(one, 0, y, j * width, width)
            }
            b.fc.applyMany(y, m, b.fcBias, hidden)
            kernel.geluF(hidden, m * 4 * width)
            b.out.timesMany(hidden, m, add)
            for (j in 0 until m) {
                val x = nodes[j].out
                for (o in 0 until width) x[o] += add[j * width + o] + b.outBias[o]
            }
        }
        return nodes
    }

    /** Causal attention of layer [l]: [qkv]'s query over [chain] and itself, into [out]. */
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
                att[s] = expF(att[s] - max)
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

    /** log-probability before subtracting [logSum]. */
    fun logit(node: Node, token: Int): Float = tok.dot(normed(node), token)

    /** The softmax's log-denominator after [node]: every token's logit, the costliest part, done once per node. */
    fun logSum(node: Node): Float {
        logSums(listOf(node))
        return node.logSum
    }

    /** [logSum] of each of [nodes] not yet summed, the vocabulary's matrix read once per [SUM_ROWS] of them. */
    internal fun logSums(nodes: List<Node>) {
        val todo = nodes.filter { !it.summed }.distinct()
        if (todo.isEmpty()) return
        val width = config.width
        val w = work.get()
        // bounded: the logits stay with the thread for good, and a row's sum is the same in any batch
        val rows = minOf(todo.size, SUM_ROWS)
        if (w.sums.size < rows) {
            w.sums = FloatArray(rows)
            w.normed = FloatArray(rows * width)
            w.logits = FloatArray(rows * config.vocab)
        }
        for (from in todo.indices step SUM_ROWS) {
            val m = minOf(SUM_ROWS, todo.size - from)
            for (j in 0 until m) System.arraycopy(normed(todo[from + j]), 0, w.normed, j * width, width)
            tok.timesMany(w.normed, m, w.logits)
            kernel.logSumsF(w.logits, m, config.vocab, w.sums)
            for (j in 0 until m) {
                todo[from + j].logSum = w.sums[j]
                todo[from + j].summed = true
            }
        }
    }

    fun logProb(node: Node, token: Int): Float = logit(node, token) - logSum(node)

    // a node's per-layer keys and values, its output and normed output
    private fun nodeFloats() = 2 * config.layers * config.width + 2 * config.width

    /** A chain of its own lock, so [Scorer.readNext] fills it on another thread while a key scores on the scorer's. */
    private class Spare {
        val lock = ReentrantLock()
        var chain = ArrayList<Node>()
    }

    /**
     * Scores readings after one context, a keystroke at a time: nodes run are kept by token prefix for the next
     * reading, keystroke and continued context. What is kept changes the time only, never a score.
     */
    inner class Scorer(room: Int = ROOM, private val limit: Int = KEPT_FLOATS / nodeFloats(), readsNext: Boolean = false) {
        private val maxContext = maxOf(0, window - 1 - room)

        // the chain from the start the context's tail moves to next, read ahead by [readNext]
        private val next = if (readsNext) Spare() else null

        private val bos get() = this@SentenceModel.bos

        // read by [readNext] off the scorer's thread
        @Volatile private var context: String? = null

        // moved on by each new context [start] takes: a [readNext] begun before gives the chain up at its next step
        private val started = AtomicInteger()

        // the context read so far, a node per token after <bos>, and what remains to read
        private var chain = ArrayList<Node>()
        private var target = IntArray(0)

        /** A token after [parent], [logits] summed up to it; run into a node only once something follows, as the last needs only its logit. */
        private inner class Step(val parent: Node, val token: Int, val logits: Float) {
            var node: Node? = null
        }

        private val kept = HashMap<String, Step>()

        // [kept]'s key: the reading's tokens up to [token] as chars (ids are below 2^16)
        private fun key(tokens: StringBuilder, token: Int): String = tokens.append(token.toChar()).toString()

        /**
         * Each reading's log-probability after [context] (as much as fits the window); -infinity for an empty one.
         * [relative] drops what all share, enough to compare them cheaply. The new positions of all readings run
         * together, as do their denominators: each weight matrix is read once per call.
         */
        fun score(context: String, readings: List<String>, relative: Boolean = false): FloatArray {
            val first = start(context)
            // the last token is only read, not run: one more fits
            val tokens = readings.map { r -> encode(r).let { it.copyOf(minOf(it.size, window - first.position)) } }
            if (kept.size > limit) keepOnly(tokens)
            val shared = if (relative) shared(tokens) else 0
            val fresh = runNew(first, tokens)
            logSums(parents(first, tokens, shared, fresh))
            val n = readings.size
            val keys = Array(n) { StringBuilder() }
            val at = Array(n) { first }
            val logits = FloatArray(n)
            val denominators = FloatArray(n)
            // a position at a time across the readings, each sum in the same order whatever was kept
            for (t in 0 until (tokens.maxOfOrNull { it.size } ?: 0)) {
                for (i in 0 until n) {
                    val reading = tokens[i]
                    if (t >= reading.size) continue
                    val parent = at[i]
                    val sum = logits[i]
                    val k = key(keys[i], reading[t])
                    val step = kept.getOrPut(k) { Step(parent, reading[t], sum + logit(parent, reading[t])) }
                    if (step.node == null) step.node = fresh[k]
                    if (t >= shared) denominators[i] += step.parent.logSum
                    logits[i] = step.logits
                    if (t < reading.lastIndex) at[i] = step.node!!
                }
            }
            return FloatArray(n) { if (tokens[it].isEmpty()) Float.NEGATIVE_INFINITY else logits[it] - denominators[it] }
        }

        /** Reads [context] ahead of the first key (after a commit, on entering a field): what [score] would read, so the same scores either way. */
        fun prewarm(context: String) {
            start(context)
        }

        /**
         * Once the start [context]'s tail is read from will move within a quarter of what fits, reads the chain from
         * where it moves to, [NEXT_STEP] tokens at a time on its own lock, so the commit that moves it reads only what it
         * added. Only [start] takes the chain, where it holds more of its context: it changes the time, never a score.
         * The one call that may run beside the others. It ends at the step after [start] takes another context or
         * [stop] says so, both looked at on the lock, so a key waits for one step at most; [pause] runs between steps.
         */
        fun readNext(context: String, stop: () -> Boolean = { false }, pause: () -> Unit = {}) {
            val next = next ?: return
            val at = started.get()
            if (context != this.context) return
            val tokens = encode(context)
            val from = nextStart(tokens, maxContext, within = (maxContext + 3) / 4)
            if (from < 0) return
            val target = tokens.copyOfRange(from, tokens.size)
            while (true) {
                pause()
                val done = next.lock.withLock {
                    if (started.get() != at || stop()) return
                    val chain = next.chain
                    val have = common(chain, target)
                    while (chain.size > have) chain.removeAt(chain.size - 1)
                    val count = minOf(NEXT_STEP, target.size - have)
                    if (count > 0) {
                        val parents = List(count) { if (it == 0) chain.lastOrNull() ?: bos else null }
                        chain += nextMany(parents, IntArray(count) { it - 1 }, target.copyOfRange(have, have + count))
                    }
                    have + count == target.size
                }
                if (done) return
            }
        }

        /** Runs together the nodes [tokens] go through that are not kept: each reading's positions before its last token, by key. */
        private fun runNew(first: Node, tokens: List<IntArray>): Map<String, Node> {
            val parents = ArrayList<Node?>()
            val pending = ArrayList<Int>()
            val run = ArrayList<Int>()
            val index = HashMap<String, Int>()
            for (reading in tokens) {
                var parent = first
                var pendingParent = -1
                val keys = StringBuilder()
                for (t in 0 until reading.lastIndex) {
                    val k = key(keys, reading[t])
                    val have = kept[k]?.node
                    if (have != null) {
                        parent = have
                        pendingParent = -1
                        continue
                    }
                    pendingParent = index.getOrPut(k) {
                        parents += if (pendingParent >= 0) null else parent
                        pending += pendingParent
                        run += reading[t]
                        run.size - 1
                    }
                }
            }
            val made = nextMany(parents, pending.toIntArray(), run.toIntArray())
            return index.mapValues { made[it.value] }
        }

        /** The nodes whose denominators the scores add: each reading's parents from position [shared] on. */
        private fun parents(first: Node, tokens: List<IntArray>, shared: Int, fresh: Map<String, Node>): List<Node> {
            val needed = ArrayList<Node>()
            for (reading in tokens) {
                var parent = first
                val keys = StringBuilder()
                for (t in reading.indices) {
                    if (t >= shared) needed += parent
                    if (t < reading.lastIndex) {
                        val k = key(keys, reading[t])
                        parent = kept[k]?.node ?: fresh.getValue(k)
                    }
                }
            }
            return needed
        }

        /** The node the readings follow, the context's new positions run together. */
        private fun start(context: String): Node {
            if (context != this.context) {
                this.context = context
                started.incrementAndGet()
                kept.clear()
                target = target(encode(context))
                var common = common(chain, target)
                // the step [readNext] is in, at most: it sees the context moved on before another
                next?.lock?.withLock {
                    val ahead = common(next.chain, target)
                    if (ahead > common) {
                        // the chain let go starts before any start to come: given up, not kept as a third
                        val taken = next.chain
                        next.chain = chain.apply { clear() }
                        chain = taken
                        common = ahead
                    }
                }
                while (chain.size > common) chain.removeAt(chain.size - 1)
            }
            if (chain.size < target.size) {
                val from = chain.size
                val count = target.size - from
                val parents = List(count) { if (it == 0) chain.lastOrNull() ?: bos else null }
                chain += nextMany(parents, IntArray(count) { it - 1 }, target.copyOfRange(from, target.size))
            }
            return chain.lastOrNull() ?: bos
        }

        private fun common(nodes: List<Node>, tokens: IntArray): Int {
            var common = 0
            while (common < nodes.size && common < tokens.size && nodes[common].token == tokens[common]) common++
            return common
        }

        /** Drops steps none of [tokens] goes through. */
        private fun keepOnly(tokens: List<IntArray>) {
            val needed = HashSet<String>()
            for (reading in tokens) {
                val before = StringBuilder()
                for (token in reading) needed += key(before, token)
            }
            kept.keys.retainAll(needed)
        }

        /**
         * The tail of [context] to read: all of it if it fits, else from [tailStart]. From the context alone, never
         * from what was read before, so a pick does not depend on what was typed or scored earlier.
         */
        private fun target(context: IntArray): IntArray =
            if (context.size <= maxContext) context else context.copyOfRange(tailStart(context, maxContext), context.size)

        /** Leading positions all [tokens] share: each adds the same denominator to every reading. */
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
        /** A [Scorer]'s node budget in floats (6 MB); past it only the steps of readings being scored are kept. */
        const val KEPT_FLOATS = 1_500_000
        /** Nodes whose denominators run at once: their logits are 32 × vocab floats (1.5 MB at 25M's). */
        internal const val SUM_ROWS = 32
        private const val EPS = 1e-5f
        private const val MAX_LAYERS = 64

        /** Tokens [Scorer.readNext] runs at a time on its lock: a key that takes the chain waits at most for these. */
        internal const val NEXT_STEP = 8

        /**
         * Where [tokens] past [limit] are read from: of the starts leaving 1/2 to 3/4 of [limit], the one whose pair of
         * tokens about it hashes highest, the last of equals. Chosen by the text, it stays as text is added until it
         * falls out or a higher one comes in, so most commits read only what they added; a move reads no more than
         * 3/4 of it, and a big commit, skipping a start, about the half a fixed cut read.
         */
        internal fun tailStart(tokens: IntArray, limit: Int): Int {
            if (tokens.size <= limit) return 0
            if (limit < 1) return tokens.size
            return highest(tokens, tokens.size - longest(limit), tokens.size - shortest(limit))
        }

        /**
         * The start [tailStart] moves to as tokens are added to [tokens], whichever they are, if it moves within
         * [within] of them; -1 if not, or if that start's pair is not yet in [tokens]. The range being narrower than
         * the shortest tail, it mostly is.
         */
        internal fun nextStart(tokens: IntArray, limit: Int, within: Int): Int {
            if (limit < 1) return -1
            val now = tailStart(tokens, limit)
            for (size in maxOf(tokens.size, limit) + 1..tokens.size + within) {
                if (size - shortest(limit) >= tokens.size) return -1
                val start = highest(tokens, size - longest(limit), size - shortest(limit))
                if (start != now) return start
            }
            return -1
        }

        private fun shortest(limit: Int) = (limit + 1) / 2

        private fun longest(limit: Int) = maxOf(shortest(limit), 3 * limit / 4)

        private fun highest(tokens: IntArray, from: Int, to: Int): Int {
            var best = -1
            var bestHash = 0
            for (p in from..to) {
                val h = cutHash(tokens[p - 1], tokens[p])
                if (best < 0 || Integer.compareUnsigned(h, bestHash) >= 0) {
                    best = p
                    bestHash = h
                }
            }
            return best
        }

        // murmur3's finaliser on the pair (ids are below 2^16): the same bits on every machine
        private fun cutHash(a: Int, b: Int): Int {
            var h = a shl 16 or b
            h = (h xor (h ushr 16)) * -0x7a143595
            h = (h xor (h ushr 13)) * -0x3d4d51cb
            return h xor (h ushr 16)
        }

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

        /**
         * Weights as stored, multiplied by [kernel] on int8-rounded activations: what the app computes, to the bit on
         * any kernel. With [unpack], as floats: quicker in Kotlin for the tools, but not the app's numbers.
         * [IllegalArgumentException] if not a chinese-ime-lm v1 model.
         */
        fun load(buffer: ByteBuffer, unpack: Boolean = false, kernel: MatrixKernel = MatrixKernel.JVM): SentenceModel {
            val start = System.nanoTime()
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
            val model = SentenceModel(
                config, vocab, matrix("tok.weight", config.vocab, w), file.floats("pos.weight", config.window, w),
                blocks, norm("ln_f"), meta["license"].orEmpty(), meta["attribution"].orEmpty(), kernel,
            )
            // timed on the asking thread (the fcitx one in the app): how long a key waited
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            CoreLog.d { "sentence model of ${config.layers} layers of $w read in $ms ms" }
            return model
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
