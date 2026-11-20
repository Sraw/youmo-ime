/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * StrokeShape over made-up families of 撇, 横 (bowed, ticked, shaky) and the 折s, each share read printed
 * (`./gradlew :lib:ime-core:test --tests '*StrokeShapeRates*' -i`) and held to floors. No finger-drawn strokes:
 * the floors hold these families, not what users draw.
 */
class StrokeShapeRatesTest {

    private class Drawn(val bow: Float, val start: Pair<Double, Float>?, val end: Pair<Double, Float>?, val noise: Float, val path: FloatArray) {
        val likely: Boolean get() = abs(bow) <= LIKELY_BOW && (start?.second ?: 0f) <= LIKELY_TICK && (end?.second ?: 0f) <= LIKELY_TICK && noise <= LIKELY_NOISE
    }

    private class Path(val random: Random) {
        val xs = arrayListOf(0f)
        val ys = arrayListOf(0f)

        // [degrees] up positive
        fun line(degrees: Double, length: Float, steps: Int) {
            val r = Math.toRadians(degrees)
            val x = xs.last()
            val y = ys.last()
            for (i in 1..steps) {
                xs += x + (cos(r) * length * i / steps).toFloat()
                ys += y - (sin(r) * length * i / steps).toFloat()
            }
        }

        // [bow]: the middle's offset of [length] to the chord's right as drawn
        fun arc(degrees: Double, length: Float, bow: Float, steps: Int = 16) {
            val r = Math.toRadians(degrees)
            val ux = cos(r).toFloat()
            val uy = -sin(r).toFloat()
            val x = xs.last()
            val y = ys.last()
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val off = 4 * bow * length * t * (1 - t)
                xs += x + ux * length * t - uy * off
                ys += y + uy * length * t + ux * off
            }
        }

        fun done(noise: Float): FloatArray = FloatArray(xs.size * 2) {
            val v = if (it % 2 == 0) xs[it / 2] else ys[it / 2]
            if (noise > 0f) v + noise * random.nextGaussian() else v
        }

        private fun Random.nextGaussian(): Float {
            // Box–Muller: the same numbers on every JVM
            val u = nextDouble().coerceAtLeast(1e-12)
            val v = nextDouble()
            return (kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * cos(2 * Math.PI * v)).toFloat()
        }
    }

    // ticks at start and end as (degrees, share of its length)
    private fun family(
        degrees: List<Double>,
        bows: List<Float>,
        starts: List<Pair<Double, Float>?>,
        ends: List<Pair<Double, Float>?>,
    ): List<Drawn> {
        val random = Random(SEED)
        val out = ArrayList<Drawn>()
        for (d in degrees) for (length in LENGTHS) for (bow in bows) out += ticked(random, Chord(d, length, bow), starts, ends)
        return out
    }

    private class Chord(val degrees: Double, val length: Float, val bow: Float)

    private fun ticked(random: Random, chord: Chord, starts: List<Pair<Double, Float>?>, ends: List<Pair<Double, Float>?>): List<Drawn> {
        val out = ArrayList<Drawn>()
        for (s in starts) for (e in ends) for (noise in NOISES) out += drawn(random, chord, s, e, noise)
        return out
    }

    private fun drawn(random: Random, chord: Chord, s: Pair<Double, Float>?, e: Pair<Double, Float>?, noise: Float): Drawn {
        // right to left: the ticks and the bow mirrored
        val mirror = chord.degrees == 180.0
        val start = s?.let { (if (mirror) 180 - it.first else it.first) to it.second }
        val end = e?.let { (if (mirror) 180 - it.first else it.first) to it.second }
        val p = Path(random)
        start?.let { p.line(it.first, it.second * chord.length, 3) }
        p.arc(chord.degrees, chord.length, if (mirror) -chord.bow else chord.bow)
        end?.let { p.line(it.first, it.second * chord.length, 3) }
        return Drawn(chord.bow, s, e, noise, p.done(noise))
    }

    private val pie = family(
        degrees = listOf(-115.0, -125.0, -135.0, -145.0),
        bows = listOf(-0.15f, -0.08f, 0f, 0.08f, 0.15f, 0.22f, 0.3f),
        starts = listOf(null, 0.0 to 0.1f, -45.0 to 0.15f, 90.0 to 0.1f, -90.0 to 0.15f),
        ends = listOf(null, 90.0 to 0.12f, 135.0 to 0.15f, 0.0 to 0.12f, -180.0 to 0.15f, 45.0 to 0.2f),
    )

    private val heng = family(
        degrees = listOf(0.0, 10.0, -10.0, -20.0, 15.0, 180.0),
        bows = listOf(-0.15f, -0.08f, 0f, 0.08f, 0.15f, 0.22f),
        starts = listOf(null, -45.0 to 0.1f, -90.0 to 0.12f, 90.0 to 0.1f, -135.0 to 0.12f),
        ends = listOf(null, -90.0 to 0.12f, -45.0 to 0.15f, 90.0 to 0.12f, -135.0 to 0.2f, 45.0 to 0.15f),
    )

    // each leg's direction and length as a share of the stroke's
    private val zhe = mapOf(
        "横折" to listOf(0.0 to 1f, -90.0 to 1f),
        "横折 短竖" to listOf(0.0 to 1f, -90.0 to 0.4f),
        "横折 短横" to listOf(0.0 to 0.6f, -95.0 to 1f),
        "竖折" to listOf(-90.0 to 1f, 0.0 to 0.8f),
        "撇折" to listOf(-125.0 to 0.8f, 20.0 to 0.8f),
        "横撇" to listOf(0.0 to 0.8f, -130.0 to 0.9f),
        "横钩" to listOf(0.0 to 1f, -135.0 to 0.35f),
        "横折钩" to listOf(0.0 to 1f, -95.0 to 1f, 135.0 to 0.2f),
        "竖弯钩" to listOf(-90.0 to 1f, 0.0 to 0.9f, 90.0 to 0.2f),
        "竖折折钩" to listOf(-90.0 to 0.6f, 0.0 to 0.5f, -100.0 to 0.8f, 135.0 to 0.2f),
        "横折弯" to listOf(0.0 to 0.6f, -100.0 to 0.7f, 0.0 to 0.7f),
    )

    private fun zhes(): Map<String, List<Pair<Boolean, FloatArray>>> {
        val random = Random(SEED)
        return zhe.mapValues { (_, legs) ->
            val out = ArrayList<Pair<Boolean, FloatArray>>()
            for (length in ZHE_LENGTHS) for (noise in NOISES) for (rounded in listOf(false, true)) repeat(ZHE_REPEATS) {
                val p = Path(random)
                legs.forEachIndexed { i, (d, share) ->
                    // a corner drawn round: a piece halfway between the two ways first
                    if (rounded && i > 0) {
                        val before = legs[i - 1].first
                        val half = if (abs(before - d) <= 180) (before + d) / 2 else (before + d) / 2 + 180
                        p.line(half, ROUNDING * length, 2)
                    }
                    p.line(d, share * length, 6)
                }
                out += rounded to p.done(noise)
            }
            out
        }
    }

    private fun shares(paths: List<FloatArray>): Map<Char?, Double> =
        paths.groupingBy { StrokeShape.classify(it) }.eachCount().mapValues { it.value * 100.0 / paths.size }

    private fun said(shares: Map<Char?, Double>) = shares.entries.sortedBy { it.key ?: ' ' }.joinToString(" ") { "${it.key ?: '-'} %.1f%%".format(it.value) }

    @Test
    fun aPieOrAHengDrawnAsAFingerDrawsItIsSeldomZhe() {
        val pieLikely = pie.filter { it.likely }.map { it.path }
        val hengLikely = heng.filter { it.likely }.map { it.path }
        val p = shares(pieLikely)
        val h = shares(hengLikely)
        println("撇 likely (${pieLikely.size}): ${said(p)}")
        println("撇 all (${pie.size}): ${said(shares(pie.map { it.path }))}")
        println("横 likely (${hengLikely.size}): ${said(h)}")
        println("横 all (${heng.size}): ${said(shares(heng.map { it.path }))}")
        // each arm measured against its own length lost round 折 with a short arm
        assertTrue("撇 likely read as 折: ${p['z']}", (p['z'] ?: 0.0) < 4.0)
        assertTrue("横 likely read as 折: ${h['z']}", (h['z'] ?: 0.0) < 2.0)
    }

    @Test
    fun aZheIsStillZhe() {
        val drawn = zhes()
        val all = drawn.mapValues { (_, paths) -> paths.map { it.second } }
        val each = all.mapValues { (_, paths) -> shares(paths)['z'] ?: 0.0 }
        println("折: " + each.entries.joinToString(" ") { "${it.key} %.1f%%".format(it.value) })
        val round = drawn.mapValues { (_, paths) -> shares(paths.filter { it.first }.map { it.second })['z'] ?: 0.0 }
        println("折 drawn round: " + round.entries.joinToString(" ") { "${it.key} %.1f%%".format(it.value) })
        val total = all.values.sumOf { paths -> paths.count { StrokeShape.classify(it) == 'z' } } * 100.0 / all.values.sumOf { it.size }
        println("折 all: %.1f%%".format(total))
        // a single corner, a short arm too, its straightness measured against the long arm (a round corner's half is on it)
        for (name in listOf("横折", "横折 短竖", "横折 短横", "竖折", "撇折", "横撇", "横钩", "横折钩", "竖弯钩")) {
            assertTrue("$name read as 折: ${each[name]}", each.getValue(name) >= 95.0)
        }
        // more corners, rounded and shaky, are read less well: held where they are
        assertTrue("竖折折钩 read as 折: ${each["竖折折钩"]}", each.getValue("竖折折钩") >= 80.0)
        assertTrue("横折弯 read as 折: ${each["横折弯"]}", each.getValue("横折弯") >= 12.0)
    }

    private companion object {
        const val SEED = 7
        val LENGTHS = listOf(0.4f, 0.6f, 0.9f, 1.3f, 1.8f)
        val ZHE_LENGTHS = listOf(0.5f, 0.8f, 1.2f, 1.8f)
        val NOISES = listOf(0f, 0.02f, 0.04f)
        const val ZHE_REPEATS = 3
        const val ROUNDING = 0.12f
        const val LIKELY_BOW = 0.15f
        const val LIKELY_TICK = 0.15f
        const val LIKELY_NOISE = 0.02f
    }
}
