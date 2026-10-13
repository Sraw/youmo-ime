/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.store.RecordFormat
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs an evaluation set from lib/ime-eval/data through the engine the app ships and writes what
 * it offered, in the format `:lib:ime-eval` scores. Not a test: it asserts nothing about quality
 * and is skipped unless asked for, since one run takes minutes. lib/ime-eval/run-on-device.sh
 * drives it and pulls eval/<evalSet>-<evalIme>.tsv from the app's external files dir.
 *
 * It types on the app's own data on this device, which installTextDebug keeps: words and
 * frequencies learned there change the candidates. Its scores compare across devices, over time
 * or with `:lib:ime-eval`'s baselines only when run on an app with no data of its own: a fresh
 * emulator, or the app's data cleared first (`adb shell pm clear`). So it stops where the app has
 * words of its own (`userWordCounts`: added, made while typing or blocked) or where its logs hold
 * anything learned, the counts typing adds to the dictionary's own words among it (`learnedLogs`),
 * unless `-e evalLearned true` asks to score on them.
 */
class EngineEvalRunner {

    private var unrefined = 0

    @Test
    fun run() {
        val args = InstrumentationRegistry.getArguments()
        val set = args.getString("evalSet")
        assumeTrue("pass -e evalSet <name> to run", set != null)
        checkThisInputMethodIsNotBound()
        val ime = args.getString("evalIme") ?: Engines.PINYIN
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val samples = testContext.assets.open("$set.tsv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                // input<TAB>expected<TAB>tag[<TAB>context], as EvalSet reads it
                val fields = line.split('\t')
                Sample(fields[0], fields.getOrElse(3) { "" })
            }.toList()
        }
        val dir = checkNotNull(context.getExternalFilesDir("eval")) { "external storage unavailable" }
        val out = File(dir, "$set-$ime.tsv")
        val fcitx = Fcitx(context)
        val scope = MainScope()
        try {
            runBlocking {
                withTimeout(STARTUP_TIMEOUT_MS) {
                    // subscribe before start(): eventFlow has no replay (see FcitxTest.setup)
                    val ready = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        fcitx.eventFlow.filterIsInstance<FcitxEvent.ReadyEvent>().first()
                    }
                    fcitx.start()
                    ready.join()
                }
                // scores of an app that learned are not comparable: see the class's KDoc
                val ownWords = fcitx.userWordCounts().filterValues { it > 0 }
                // read after userWordCounts, which opens pinyin's log and fills a new one from libime's
                val logs = learnedLogs(File(context.filesDir, ENGINE_USER_DIR))
                val learned = ownWords.isNotEmpty() || logs.isNotEmpty()
                check(!learned || args.getString("evalLearned") == "true") {
                    "the app has learned (words $ownWords, logs ${logs.map { it.name }}): clear its data (adb shell pm clear), " +
                        "or pass -e evalLearned true to score on them"
                }
                fcitx.activate(UID, PKG)
                fcitx.focus(true)
                // setEnabledIme saves the profile at once; put the user's list back afterwards
                val enabledBefore = fcitx.enabledIme().map { it.uniqueName }.toTypedArray()
                try {
                    fcitx.setEnabledIme(arrayOf(ime))
                    // a misspelt name is not an error to fcitx: it would leave the previous input
                    // method active and score it as if it were [ime]
                    assertEquals("input method", ime, fcitx.currentIme().uniqueName)
                    // the first keystrokes pay for class loading and page faults in the model
                    samples.take(WARM_UP_INPUTS).forEach { typeAndCollect(fcitx, it) }
                    unrefined = 0
                    out.bufferedWriter().use { writer ->
                        samples.forEach { sample ->
                            writer.write(typeAndCollect(fcitx, sample))
                            writer.newLine()
                        }
                    }
                    check(unrefined == 0) {
                        "$unrefined of ${samples.size} samples still refining after $REFINE_TIMEOUT_MS ms: their lists in $out are unrefined"
                    }
                    // nothing was committed, so the engine learnt nothing
                    fcitx.reset()
                } finally {
                    fcitx.setEnabledIme(enabledBefore)
                    fcitx.focus(false)
                    fcitx.deactivate(UID)
                }
            }
        } finally {
            fcitx.stop()
            scope.cancel()
        }
    }

    /**
     * Types [sample] from a clean state, after its context as the JVM runners type it, and
     * returns the result line.
     *
     * Each latency is the round trip of one `sendKey`: the engine's handling of the key, where
     * it decodes and builds its candidate list, plus two thread hops. It excludes the UI
     * flush fcitx runs afterwards (marshalling candidates to Java), which the uncounted
     * `isEmpty()` waits out so it cannot land on the next key's clock. Compare engines through
     * this same runner; do not read the numbers as pure decoding time.
     */
    private suspend fun typeAndCollect(fcitx: Fcitx, sample: Sample): String = withTimeout(INPUT_TIMEOUT_MS) {
        fcitx.reset()
        // after the reset, as the service tells it
        if (sample.context.isNotEmpty()) fcitx.engineContext(sample.context)
        fcitx.isEmpty()
        val latencies = sample.input.map { c ->
            val start = SystemClock.elapsedRealtimeNanos()
            fcitx.sendKey(c)
            val micros = (SystemClock.elapsedRealtimeNanos() - start) / 1000
            fcitx.isEmpty()
            micros
        }
        tsvLine(sample.input, latencies, candidatesOncePaused(fcitx))
    }

    /**
     * The candidates once the user pauses, as PinyinRun scores them (`paused`): read once the engine
     * has no refining left for the last key ([EngineBridge.refining]). The addon's refine events and
     * this read take turns on the fcitx thread, so the list read then is the refined one. One still
     * refining after [REFINE_TIMEOUT_MS] is read as it stands and counted in [unrefined].
     */
    private suspend fun candidatesOncePaused(fcitx: Fcitx): List<String> {
        val deadline = SystemClock.uptimeMillis() + REFINE_TIMEOUT_MS
        while (EngineBridge.refining && SystemClock.uptimeMillis() < deadline) delay(REFINE_POLL_MS)
        if (EngineBridge.refining) unrefined++
        return fcitx.getCandidates(0, CANDIDATES).map { it.text }
    }

    /** Same layout and cleaning as `RunResultFormat` in :lib:ime-eval, which this APK cannot depend on. */
    private fun tsvLine(input: String, latencies: List<Long>, candidates: List<String>) =
        (listOf(input, latencies.joinToString(",")) + candidates.map { it.replace(SEPARATORS, " ") })
            .joinToString("\t")

    /**
     * The logs under [dir], the engine's user directory, with a record in them: pinyin's, which keeps
     * the counts of every word typed (the dictionary's too) and the layer prior (UserStore), English's
     * and each table's. One of a header alone learned nothing, as a first start leaves it.
     */
    private fun learnedLogs(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(LOG_SUFFIX) && it.length() > RecordFormat.HEADER_SIZE }.toList()

    /** A line of an evaluation set as it is typed: [input] after [context], the text before the cursor ("" for none). */
    private data class Sample(val input: String, val context: String)

    private companion object {
        const val UID = 0
        const val PKG = "org.fcitx.fcitx5.android.eval"
        /** `Metrics` scores top-1, top-3 and top-5 */
        const val CANDIDATES = 5
        const val WARM_UP_INPUTS = 10
        const val STARTUP_TIMEOUT_MS = 60_000L
        const val INPUT_TIMEOUT_MS = 10_000L
        /** Far past SentenceRefiner.SLICES slices on a slow emulator: reached only by an engine that does not stop refining. */
        const val REFINE_TIMEOUT_MS = 5_000L
        const val REFINE_POLL_MS = 10L
        /** Where EngineBridge keeps the engine's user data, under the app's files dir. */
        const val ENGINE_USER_DIR = "engine"
        /** As Engines names the logs: pinyin.user, english.user, wubi.user. */
        const val LOG_SUFFIX = ".user"
        val SEPARATORS = Regex("[\t\r\n]")
    }
}
