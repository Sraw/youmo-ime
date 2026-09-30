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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs an evaluation set from lib/ime-eval/data through the engine the app ships and writes what
 * it offered, in the format `:lib:ime-eval` scores. Not a test: it asserts nothing about quality
 * and is skipped unless asked for, since one run takes minutes. lib/ime-eval/run-on-device.sh
 * drives it and pulls eval/<evalSet>-<evalIme>.tsv from the app's external files dir.
 */
class EngineEvalRunner {

    @Test
    fun run() {
        val args = InstrumentationRegistry.getArguments()
        val set = args.getString("evalSet")
        assumeTrue("pass -e evalSet <name> to run", set != null)
        val ime = args.getString("evalIme") ?: "pinyin"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val inputs = testContext.assets.open("$set.tsv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.toList()
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
                    inputs.take(WARM_UP_INPUTS).forEach { typeAndCollect(fcitx, it) }
                    out.bufferedWriter().use { writer ->
                        inputs.forEach { input ->
                            writer.write(typeAndCollect(fcitx, input))
                            writer.newLine()
                        }
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
     * Types [input] from a clean state and returns the result line.
     *
     * Each latency is the round trip of one `sendKey`: the engine's handling of the key, where
     * libime decodes and builds its candidate list, plus two thread hops. It excludes the UI
     * flush fcitx runs afterwards (marshalling candidates to Java), which the uncounted
     * `isEmpty()` waits out so it cannot land on the next key's clock. Compare engines through
     * this same runner; do not read the numbers as pure decoding time.
     */
    private suspend fun typeAndCollect(fcitx: Fcitx, input: String): String = withTimeout(INPUT_TIMEOUT_MS) {
        fcitx.reset()
        fcitx.isEmpty()
        val latencies = input.map { c ->
            val start = SystemClock.elapsedRealtimeNanos()
            fcitx.sendKey(c)
            val micros = (SystemClock.elapsedRealtimeNanos() - start) / 1000
            fcitx.isEmpty()
            micros
        }
        val candidates = fcitx.getCandidates(0, CANDIDATES).map { it.text }
        tsvLine(input, latencies, candidates)
    }

    /** Same layout and cleaning as `RunResultFormat` in :lib:ime-eval, which this APK cannot depend on. */
    private fun tsvLine(input: String, latencies: List<Long>, candidates: List<String>) =
        (listOf(input, latencies.joinToString(",")) + candidates.map { it.replace(SEPARATORS, " ") })
            .joinToString("\t")

    private companion object {
        const val UID = 0
        const val PKG = "org.fcitx.fcitx5.android.eval"
        /** `Metrics` scores top-1, top-3 and top-5 */
        const val CANDIDATES = 5
        const val WARM_UP_INPUTS = 10
        const val STARTUP_TIMEOUT_MS = 60_000L
        const val INPUT_TIMEOUT_MS = 10_000L
        val SEPARATORS = Regex("[\t\r\n]")
    }
}
