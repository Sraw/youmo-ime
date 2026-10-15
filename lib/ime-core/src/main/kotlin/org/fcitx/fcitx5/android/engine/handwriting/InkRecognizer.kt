/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import kotlin.math.exp
import kotlin.math.ln

/** A recognizer network: its output's values row-major, run on [x] of [shape]. */
fun interface InkNet {
    fun run(x: FloatArray, shape: LongArray): FloatArray
}

/**
 * What the pad's ink was read as: characters, best first, [scores] the recognizer's
 * log-probabilities, which the text before may weigh again ([HandwritingSession]).
 */
class InkReading(val texts: List<String>, val scores: DoubleArray) {
    init {
        require(texts.size == scores.size) { "a score for each reading" }
    }

    companion object {
        val NONE = InkReading(emptyList(), DoubleArray(0))
    }
}

/**
 * The handwriting recognizers, run as the training code runs them (the recognizers' training
 * sources: `diag.py`): one character by its image ([image]) and its pen path
 * ([path]), their log-softmaxes weighed [IMAGE] to 1 − [IMAGE] with each class's log frequency
 * ([prior]) times [PRIOR]. Strokes are x, y pairs in any one unit.
 */
class InkRecognizer(
    private val image: InkNet,
    private val path: InkNet,
    val chars: String,
    private val prior: FloatArray,
) {
    init {
        require(prior.size == chars.length) { "a frequency for each character" }
    }

    private val classes = chars.length

    /** The [count] likeliest characters [strokes] may be. */
    fun character(strokes: List<FloatArray>, count: Int = CHARACTERS): InkReading {
        val a = image.run(InkFeatures.charImage(strokes), longArrayOf(1, 1L + InkFeatures.DIRS, SIZE, SIZE))
        val p = InkFeatures.charPath(strokes)
        val b = path.run(p, longArrayOf(1, p.size.toLong() / InkFeatures.CHAR_PATH_DIM, InkFeatures.CHAR_PATH_DIM.toLong()))
        require(a.size == classes && b.size == classes) { "a score for each character" }
        logSoftmax(a, 0, classes)
        logSoftmax(b, 0, classes)
        val s = DoubleArray(classes) { IMAGE * a[it] + (1 - IMAGE) * b[it] + PRIOR * prior[it] }
        val best = s.indices.sortedByDescending { s[it] }.take(count)
        return InkReading(best.map { chars[it].toString() }, DoubleArray(best.size) { s[best[it]] })
    }

    companion object {
        const val IMAGE = 0.7
        const val PRIOR = 0.2

        /** A character is offered so many readings. */
        const val CHARACTERS = 20

        private const val SIZE = InkFeatures.SIZE.toLong()

        /** [v]'s [n] values from [at] made log-probabilities, in place. */
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
