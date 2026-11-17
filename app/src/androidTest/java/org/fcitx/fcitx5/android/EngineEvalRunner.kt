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
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.HostInstance
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.store.RecordFormat
import org.fcitx.fcitx5.android.host.HostEvent
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
 * It types on the app's own data on this device, which installDebug keeps: words and
 * frequencies learned there change the candidates. Its scores compare across devices, over time
 * or with `:lib:ime-eval`'s baselines only when run on an app with no data of its own: a fresh
 * emulator, or the app's data cleared first (`adb shell pm clear`). So it stops where the app has
 * words of its own (`userWordCounts`: added, made while typing or blocked) or where its logs hold
 * anything learned, the counts typing adds to the dictionary's own words among it (`learnedLogs`),
 * unless `-e evalLearned true` asks to score on them.
 */
class EngineEvalRunner {

    private var unrefined = 0

    // -e evalNine true: digits as pinyin's nine keys send them, codes of their own (Keyboard.pinyinNineKey); for
    // English as its nine keys do (Keyboard.nineKey)
    private var nine = false

    private var english = false

    // -e evalGrouped: a set's codes as a grouped keyboard sends them (GroupedKeyboard's wire, T9Keyboard's, ZhuyinKeyboard's):
    // 14 pinyin's 14 keys (the group's code itself), 18 its 18 keys (codes of their own, Keyboard.pinyin18Key); sp18 and
    // sp9 双拼's 18 and nine keys (the code itself), sp14 its 14 keys (Keyboard.shuangpin14Key); zy9 注音九键 and zy
    // 大千 (their keys' codes, read back by Keyboard.keyedChar as the set's)
    private var grouped: String? = null

    @Test
    fun run() {
        val args = InstrumentationRegistry.getArguments()
        val set = args.getString("evalSet")
        assumeTrue("pass -e evalSet <name> to run", set != null)
        checkThisInputMethodIsNotBound()
        val ime = args.getString("evalIme") ?: InputMethodCatalog.PINYIN
        nine = args.getString("evalNine") == "true"
        english = ime == InputMethodCatalog.ENGLISH
        grouped = args.getString("evalGrouped")?.also { require(it in GROUPED) { "evalGrouped $it: one of $GROUPED" } }
        // the two would measure the nine keys alone, the output named as if grouped were too
        require(!(nine && grouped != null)) { "evalNine and evalGrouped: one at a time" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val samples = testContext.assets.open("$set.tsv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                // input<TAB>expected<TAB>tag[<TAB>context[<TAB>touches[<TAB>places]]], as EvalSet reads it: each key's
                // touch a space apart, `-` none (pinyin --touch … --touched writes them; 061-neighbour-correction),
                // each key's place across it as the 14 and 18 keys send it, `-` none (061-neighbour-18key)
                val fields = line.split('\t')
                val places = fields.getOrNull(5)?.split(' ')?.map { it.toFloatOrNull() ?: Float.NaN }
                Sample(fields[0], fields.getOrElse(3) { "" }, fields.getOrNull(4)?.split(' ')?.map(Touch::parse), places)
            }.toList()
        }
        val dir = checkNotNull(context.getExternalFilesDir("eval")) { "external storage unavailable" }
        val out = File(dir, "$set-$ime.tsv")
        val fcitx: HostInstance = KotlinHost(context)
        val scope = MainScope()
        try {
            runBlocking {
                withTimeout(STARTUP_TIMEOUT_MS) {
                    // subscribe before start(): eventFlow has no replay (see HostTest.setup)
                    val ready = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        fcitx.eventFlow.filterIsInstance<HostEvent.Ready>().first()
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
     * it decodes and builds its candidate list, plus two hops to and from the host thread
     * (KotlinHost's `onHost`). Anything the host thread still has queued after the key (a
     * refine's events) the uncounted `isEmpty()`, a second turn on that thread, waits out so it
     * cannot land on the next key's clock. Compare engines through
     * this same runner; do not read the numbers as pure decoding time.
     */
    private suspend fun typeAndCollect(fcitx: HostInstance, sample: Sample): String = withTimeout(INPUT_TIMEOUT_MS) {
        fcitx.reset()
        // after the reset, as the service tells it
        if (sample.context.isNotEmpty()) fcitx.engineContext(sample.context)
        fcitx.isEmpty()
        val latencies = sample.input.mapIndexed { i, c ->
            val start = SystemClock.elapsedRealtimeNanos()
            val touch = sample.touches?.getOrNull(i)
            when {
                nine -> fcitx.sendKey(KeySym(UNICODE + if (english) Keyboard.nineKey(c, upper = false) else Keyboard.pinyinNineKey(c)), KeyStates.Virtual, touch = touch)
                grouped != null -> fcitx.sendKey(KeySym(UNICODE + wired(grouped!!, c, sample.places?.getOrNull(i) ?: Float.NaN)), KeyStates.Virtual, touch = touch)
                touch != null -> fcitx.sendKey(c.toString(), touch = touch)
                else -> fcitx.sendKey(c)
            }
            val micros = (SystemClock.elapsedRealtimeNanos() - start) / 1000
            fcitx.isEmpty()
            micros
        }
        tsvLine(sample.input, latencies, candidatesOncePaused(fcitx))
    }

    /**
     * The candidates once the user pauses, as PinyinRun scores them (`paused`): read once the engine
     * has no refining left for the last key ([EngineState.refining]). HostCore's refine snapshots and
     * this read take turns on the host thread, so the list read then is the refined one. One still
     * refining after [REFINE_TIMEOUT_MS] is read as it stands and counted in [unrefined].
     */
    private suspend fun candidatesOncePaused(fcitx: HostInstance): List<String> {
        val deadline = SystemClock.uptimeMillis() + REFINE_TIMEOUT_MS
        while (EngineState.refining && SystemClock.uptimeMillis() < deadline) delay(REFINE_POLL_MS)
        if (EngineState.refining) unrefined++
        return fcitx.getCandidates(0, CANDIDATES).map { it.text }
    }

    /** Same layout and cleaning as `RunResultFormat` in :lib:ime-eval, which this APK cannot depend on. */
    private fun tsvLine(input: String, latencies: List<Long>, candidates: List<String>) =
        (listOf(input, latencies.joinToString(",")) + candidates.map { it.replace(SEPARATORS, " ") })
            .joinToString("\t")

    /**
     * The logs under [dir], the engine's user directory, with a record in them: English's and each
     * table's, and the learning record (LearnLog), which keeps pinyin's (an older build's pinyin.user,
     * read once, is never written). One of a header alone learned nothing, as a first start leaves
     * it; the learning record, a snapshot of nothing (review-1a-app S6).
     */
    private fun learnedLogs(dir: File): List<File> = dir.walkTopDown().filter {
        it.isFile && when {
            it.name == LearnLog.FILE -> !LearnLog.replay(it.readBytes()).isEmpty()
            else -> it.name.endsWith(LOG_SUFFIX) && it.length() > RecordFormat.HEADER_SIZE
        }
    }.toList()

    // what the grouped keyboard [board] sends for the code [c] the engine reads, tapped [at] across its key (NaN: no
    // place), as GroupedKeyboard.tapped sends the 14 and 18 keys'
    private fun wired(board: String, c: Char, at: Float): Int = when (board) {
        "18" -> if (at.isNaN()) Keyboard.pinyin18Key(c) else Keyboard.pinyin18Tap(c, at)
        "14" -> if (at.isNaN()) c.code else Keyboard.tapKey(c, at)
        "sp14" -> Keyboard.shuangpin14Key(c)
        "zy9" -> Keyboard.ZHUYIN_NINE.first { Keyboard.keyedChar(it) == c }
        "zy" -> Keyboard.ZHUYIN_FULL.first { Keyboard.keyedChar(it) == c }
        else -> c.code
    }

    /** A line of an evaluation set as it is typed: [input] after [context], the text before the cursor ("" for none). */
    private data class Sample(val input: String, val context: String, val touches: List<Touch?>? = null, val places: List<Float>? = null)

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

        // -e evalGrouped's keyboards
        val GROUPED = setOf("14", "18", "sp18", "sp14", "sp9", "zy9", "zy")
    }
}
