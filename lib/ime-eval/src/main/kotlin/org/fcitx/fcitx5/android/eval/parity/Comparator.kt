/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval.parity

import kotlinx.serialization.json.JsonArray
import org.fcitx.fcitx5.android.engine.host.Engines
import kotlin.math.abs

/**
 * Compares two runs of one sequence file (fcitx-replacement §7.2), [reference] a device's.
 *
 * Device against device (two hosts on one matrix kernel): every step's state the same, its
 * `InputConnection` calls included. The JVM against a device: the JVM's kernel adds in another
 * order than the device's ([org.fcitx.fcitx5.android.engine.rerank.MatrixKernel]), so a
 * sentence-model score's last bits differ and two candidates whose scores are within [epsilon] may
 * show in either order: one such pair of neighbours swapped is listed apart ([Result.swapped]), and
 * the sequence's later steps are not compared (they may follow from the other pick). Neither the
 * calls (the JVM has no test editor) are compared there, nor more candidates than the device's bar shows.
 * [score]: the sentence model's score difference of two candidates after a context, null if it has
 * none for them. A proxy (fcitx-replacement §7): the bar's order is the decoder's score with the
 * model's added, and at the Low/Medium 档位 the large model may not rank at all, so a pair within ε is
 * not proof the kernel swapped it, nor is a pair past ε proof it did not.
 */
class Comparator(
    private val jvm: Boolean,
    private val epsilon: Float = 0f,
    private val score: (context: String, a: String, b: String) -> Float? = { _, _, _ -> null },
) {
    /** What a step that is not the same is: [compare]'s `when` lists every kind, so a new one cannot be dropped there. */
    sealed interface Mismatch

    /** Where a sequence first differs: the step's [index] (0-based) and [step], and [what]. */
    data class Difference(val id: String, val index: Int, val step: Step?, val what: String) : Mismatch

    /** A swap allowed: the candidates [a] and [b] after [context], [delta] their score difference. */
    data class Swap(val id: String, val index: Int, val a: String, val b: String, val delta: Float, val context: String) : Mismatch

    /** A difference at a step read busy (the recorder's idle wait ran out): device timing, allowed (DECISIONS.md 2026-10-25). */
    data class Timing(val difference: Difference) : Mismatch

    data class Result(
        val same: Int,
        val differences: List<Difference>,
        val swapped: List<Swap>,
        val errors: List<Difference>,
        val missing: List<String>,
        val timing: List<Difference> = emptyList(),
    ) {
        val passed get() = differences.isEmpty() && errors.isEmpty() && missing.isEmpty()
    }

    fun compare(sequences: List<Sequence>, reference: List<Output>, other: List<Output>): Result {
        val refs = reference.associateBy { it.id }
        val others = other.associateBy { it.id }
        val differences = ArrayList<Difference>()
        val swapped = ArrayList<Swap>()
        val timing = ArrayList<Difference>()
        val errors = ArrayList<Difference>()
        val missing = ArrayList<String>()
        var same = 0
        // the reference's sequences: a device runs a sample of the file
        for (s in sequences.filter { it.id in refs }) {
            val r = refs.getValue(s.id)
            val o = others[s.id]
            if (o == null) {
                missing += s.id
                continue
            }
            when (val v = one(s, r, o)) {
                null -> if (r.error == null && o.error == null) same++ else errors += error(s, r, o)
                is Swap -> swapped += v
                is Timing -> timing += v.difference
                is Difference -> differences += v
            }
        }
        return Result(same, differences, swapped, errors, missing, timing)
    }

    private fun error(s: Sequence, r: Output, o: Output): Difference {
        val at = minOf(r.states.size, o.states.size)
        return Difference(s.id, at, s.steps.getOrNull(at), "stopped: ${r.error ?: "-"} / ${o.error ?: "-"}")
    }

    // the first difference, the swap allowed at it, or null if every step both ran is the same
    private fun one(s: Sequence, r: Output, o: Output): Mismatch? {
        val ran = minOf(r.states.size, o.states.size)
        // a state read before the engine went idle (the recorder's wait ran out): a difference there
        // is timing, not the host's; the next step's state shows what the step did (F4177). Its calls
        // though are each counted where they landed, so a late one is in the next step on one device and
        // not the other: the next step is held to the calls since the last idle state (F4295)
        fun busy(i: Int) = r.states[i].busy || o.states[i].busy
        fun calls(st: List<State>, i: Int): List<JsonArray>? {
            var from = i
            while (from > 0 && busy(from - 1)) from--
            return if ((from..i).any { st[it].ic == null }) null else (from..i).flatMap { st[it].ic!! }
        }
        for (i in 0 until ran) {
            if (busy(i) && i < ran - 1) continue
            val a = r.states[i].copy(ic = calls(r.states, i))
            val b = o.states[i].copy(ic = calls(o.states, i))
            // the last step busy: no later state shows what it did, so its difference is told, as timing
            // (listed apart, not failing: the device's timing is an allowed difference)
            step(s, i, a, b)?.let {
                return if (busy(i) && it is Difference && told(r, o, i, ::busy)) Timing(it) else it
            }
        }
        return if (r.states.size != o.states.size && r.error == null && o.error == null) {
            Difference(s.id, ran, null, "${r.states.size} vs ${o.states.size} states")
        } else {
            null
        }
    }

    // a busy last step's difference is timing only for a sequence both ran to its end, where some step
    // before was idle and compared: one that stopped short has later states to tell, and one busy
    // throughout compared nothing (F4322)
    private fun told(r: Output, o: Output, i: Int, busy: (Int) -> Boolean) =
        r.states.size == o.states.size && r.error == null && o.error == null && (0 until i).any { !busy(it) }

    // step [i]'s difference or swap, null if the same
    private fun step(s: Sequence, i: Int, a: State, b: State): Mismatch? {
        fun differs(what: String) = Difference(s.id, i, s.steps.getOrNull(i), what)
        return when {
            a.text != b.text -> differs("text 「${a.text}」 vs 「${b.text}」")
            a.sel != b.sel -> differs("selection ${a.sel} vs ${b.sel}")
            // the input method's name overlay (a second after a switch or a field focused) is on the
            // device's code row or not depending on timing; the JVM's pause has always run it out
            a.preedit != b.preedit && !(jvm && b.preedit.isEmpty() && a.preedit in OVERLAYS) -> differs("preedit 「${a.preedit}」 vs 「${b.preedit}」")
            !jvm && a.cands != b.cands -> differs("candidates ${a.cands} vs ${b.cands}")
            !jvm && a.ic != b.ic -> differs("calls ${a.ic} vs ${b.ic}")
            !jvm || b.cands.take(a.cands.size) == a.cands -> null
            else -> {
                val shown = b.cands.take(a.cands.size)
                swap(a.cands, b.cands)?.let { (x, y) ->
                    val context = a.text.substring(0, a.sel.first.coerceIn(0, a.text.length))
                    val delta = score(context, x, y)
                    if (delta != null && abs(delta) < epsilon) {
                        Swap(s.id, i, x, y, delta, context)
                    } else {
                        differs("candidates ${a.cands} vs $shown: 「$x」「$y」 swapped, score difference ${delta ?: "unknown"} ≥ ε $epsilon")
                    }
                } ?: differs("candidates ${a.cands} vs $shown")
            }
        }
    }

    /**
     * The neighbours [shown] has the other way round from the device's bar [bar], everything else the
     * same, in [shown]'s order; null for any other difference. The last on the bar may have swapped
     * with the first past it.
     */
    private fun swap(bar: List<String>, shown: List<String>): Pair<String, String>? {
        val n = bar.size
        if (shown.size < n) return null
        val diff = (0 until n).filter { bar[it] != shown[it] }
        return when {
            diff.size == 2 && diff[1] == diff[0] + 1 && bar[diff[0]] == shown[diff[1]] && bar[diff[1]] == shown[diff[0]] ->
                shown[diff[0]] to shown[diff[1]]
            diff == listOf(n - 1) && shown.size > n && bar[n - 1] == shown[n] -> shown[n - 1] to shown[n]
            else -> null
        }
    }

    companion object {
        // what the overlay shows: each listed input method's label
        private val OVERLAYS: Set<String> = Engines.LISTED.map { it.label }.toSet()

        fun report(r: Result, out: Appendable) {
            out.appendLine("same ${r.same}, different ${r.differences.size}, swaps allowed ${r.swapped.size}, timing ${r.timing.size}, stopped ${r.errors.size}, missing ${r.missing.size}")
            r.differences.forEach { out.appendLine("DIFF ${it.id} step ${it.index} (${it.step ?: "end"}): ${it.what}") }
            r.errors.forEach { out.appendLine("STOP ${it.id} step ${it.index} (${it.step ?: "end"}): ${it.what}") }
            r.swapped.forEach { out.appendLine("SWAP ${it.id} step ${it.index}: 「${it.a}」「${it.b}」 after 「${it.context}」 Δ ${it.delta}") }
            r.timing.forEach { out.appendLine("TIMING ${it.id} step ${it.index} (${it.step ?: "end"}) read busy: ${it.what}") }
            if (r.missing.isNotEmpty()) out.appendLine("MISSING ${r.missing.joinToString(" ")}")
        }
    }
}
