/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

fun interface InkNet {
    fun run(x: FloatArray, shape: LongArray): FloatArray
}

/** Characters, best first; [scores] are log-probabilities the text before may re-weigh ([HandwritingSession]). */
class InkReading(val texts: List<String>, val scores: DoubleArray) {
    init {
        require(texts.size == scores.size) { "a score for each reading" }
    }

    companion object {
        val NONE = InkReading(emptyList(), DoubleArray(0))
    }
}

/** The recognizers run as the training code (`diag.py`) does: image and path log-softmaxes weighed [IMAGE] to 1 − [IMAGE], plus log frequency times [PRIOR]. */
class InkRecognizer(
    private val image: InkNet,
    private val path: InkNet,
    val chars: String,
    private val prior: FloatArray,
) {
    private val classes = classesOf(chars)

    init {
        require(prior.size == classes.size) { "a frequency for each character" }
    }

    fun character(strokes: List<FloatArray>, count: Int = CHARACTERS): InkReading {
        val n = classes.size
        val a = image.run(InkFeatures.charImage(strokes), longArrayOf(1, 1L + InkFeatures.DIRS, SIZE, SIZE))
        val p = InkFeatures.charPath(strokes)
        val b = path.run(p, longArrayOf(1, p.size.toLong() / InkFeatures.CHAR_PATH_DIM, InkFeatures.CHAR_PATH_DIM.toLong()))
        require(a.size == n && b.size == n) { "a score for each character" }
        logSoftmax(a, 0, n)
        logSoftmax(b, 0, n)
        val s = DoubleArray(n) { IMAGE * a[it] + (1 - IMAGE) * b[it] + PRIOR * prior[it] }
        val best = top(s, count)
        return InkReading(best.map { classes[it] }, DoubleArray(best.size) { s[best[it]] })
    }

    companion object {
        const val IMAGE = 0.7
        const val PRIOR = 0.2

        const val CHARACTERS = 20

        private const val SIZE = InkFeatures.SIZE.toLong()

        internal fun classesOf(chars: String): List<String> {
            val text = chars.trimEnd('\r', '\n')
            val out = ArrayList<String>(text.length)
            var i = 0
            while (i < text.length) {
                val n = Character.charCount(Character.codePointAt(text, i))
                out.add(text.substring(i, i + n))
                i += n
            }
            return out
        }

        /** Indices of [s]'s [count] largest, ties in index order, in one pass rather than sorting thousands per stroke. */
        internal fun top(s: DoubleArray, count: Int): IntArray {
            require(count >= 0) { "$count readings" }
            val best = IntArray(min(count, s.size))
            if (best.isEmpty()) return best
            var filled = 0
            for (i in s.indices) {
                // compareTo, not >: the order a sort of Doubles keeps, NaN above all
                if (filled == best.size && s[i].compareTo(s[best[filled - 1]]) <= 0) continue
                var j = if (filled < best.size) filled++ else filled - 1
                while (j > 0 && s[i].compareTo(s[best[j - 1]]) > 0) {
                    best[j] = best[j - 1]
                    j--
                }
                best[j] = i
            }
            return best
        }

        internal fun logSoftmax(v: FloatArray, at: Int, n: Int) {
            var max = Float.NEGATIVE_INFINITY
            for (i in at until at + n) if (v[i] > max) max = v[i]
            var sum = 0.0
            for (i in at until at + n) sum += exp((v[i] - max).toDouble())
            val log = max + ln(sum)
            for (i in at until at + n) v[i] = (v[i] - log).toFloat()
        }
    }
}
